package in.trackerwatch.app;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.*;
import android.system.OsConstants;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class DnsVpnService extends VpnService {
    static final String STOP="in.trackerwatch.app.STOP";
    static volatile String state="Stopped";
    static volatile boolean running=false;
    private volatile Session session;
    private final Handler main=new Handler(Looper.getMainLooper());
    private static final class Session {
        volatile boolean active=true;
        ParcelFileDescriptor tunnel;
        FileInputStream input;
        FileOutputStream output;
        Thread reader;
        final ThreadPoolExecutor tasks=new ThreadPoolExecutor(4,4,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),new ThreadPoolExecutor.AbortPolicy());
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings",MODE_PRIVATE); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || STOP.equals(intent.getAction())) { stopCurrent();stopSelf();return START_NOT_STICKY; }
        if(session!=null) return START_NOT_STICKY;
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("monitor","DNS monitoring",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,DnsVpnService.class).setAction(STOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification note=new Notification.Builder(this,"monitor").setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("TrackerWatch is monitoring DNS").setContentText("Local DNS observations • tap to view")
                .setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop",stop).build()).build();
            if(Build.VERSION.SDK_INT>=34) startForeground(1,note,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(1,note);
            Session s=new Session();
            try {
                s.tunnel=new Builder().setSession("TrackerWatch DNS")
                    .addAddress("10.111.0.2",32).addDnsServer("203.0.113.53")
                    .addRoute("203.0.113.53",32).allowFamily(OsConstants.AF_INET6)
                    .addDisallowedApplication(getPackageName()).setMtu(32767).setBlocking(true)
                    .setConfigureIntent(open).establish();
                if(s.tunnel==null) throw new IOException("VPN permission was not granted");
                s.input=new FileInputStream(s.tunnel.getFileDescriptor());
                s.output=new FileOutputStream(s.tunnel.getFileDescriptor());
                session=s; running=true;state="Monitoring";
                prefs(this).edit().remove("last_error").apply();
                s.reader=new Thread(()->readLoop(s),"TrackerWatch-DNS");s.reader.start();
            } catch(Exception e) {
                s.active=false;s.tasks.shutdownNow();
                if(s.tunnel!=null) try { s.tunnel.close(); } catch(IOException ignored) { }
                throw e;
            }
        } catch(Exception e) {
            stopCurrent();
            state="Could not start";
            prefs(this).edit().putString("last_error",e.getClass().getSimpleName()+": "+e.getMessage()).apply();
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    private void readLoop(Session s) {
        byte[] buffer=new byte[32767];
        try {
            while(s.active) {
                int n=s.input.read(buffer);
                if(n<0) break;
                DnsPacket query=DnsPacket.parse(buffer,n);
                if(query==null) continue;
                boolean locked=getSystemService(KeyguardManager.class).isDeviceLocked();
                try { s.tasks.execute(()->resolve(s,query,locked)); }
                catch(RejectedExecutionException e) { if(s.active) write(s,query.wrap(query.error(2))); }
            }
            if(s.active) throw new IOException("VPN interface closed");
        } catch(Exception e) {
            if(s.active) main.post(()-> {
                if(session==s) {
                    stopCurrent();state="Monitoring stopped";
                    prefs(this).edit().putString("last_error","VPN read failed. Start monitoring again.").apply();
                    stopSelf();
                }
            });
        }
    }
    private void resolve(Session s,DnsPacket query,boolean locked) {
        if(!s.active) return;
        SharedPreferences p=prefs(this);
        Set<String> custom=new HashSet<>(p.getStringSet("custom",Collections.emptySet()));
        Set<String> allow=new HashSet<>(p.getStringSet("allow",Collections.emptySet()));
        String company=TrackerRules.company(query.domain,custom);
        boolean blocked=TrackerRules.shouldBlock(query.domain,company,p.getBoolean("blocking",false),allow);
        byte[] answer;String outcome;
        if(blocked) { answer=query.error(3);outcome="Blocked (NXDOMAIN)"; }
        else {
            try(DatagramSocket socket=new DatagramSocket()) {
                if(!protect(socket)) throw new IOException("Could not protect resolver socket");
                socket.connect(InetAddress.getByName("1.1.1.1"),53);
                socket.setSoTimeout(2500);
                socket.send(new DatagramPacket(query.dns,query.dns.length));
                byte[] reply=new byte[16384];DatagramPacket received=new DatagramPacket(reply,reply.length);
                socket.receive(received);
                answer=Arrays.copyOf(reply,received.getLength());
                if(!query.validResponse(answer)) throw new IOException("Unexpected DNS response");
                outcome=(answer[2]&2)!=0?"Truncated response (TCP unsupported)":
                    (answer[3]&15)==0?"Resolved":"DNS response "+(answer[3]&15);
            } catch(Exception e) {
                answer=query.error(2);outcome="Resolver error (SERVFAIL)";
            }
        }
        if(!s.active) return;
        try {
            write(s,query.wrap(answer));
            EventStore.get(this).add(query.domain,company,locked,blocked,outcome);
        } catch(Exception e) {
            main.post(()-> {
                if(session==s) {
                    stopCurrent(); state="Monitoring stopped";
                    prefs(this).edit().putString("last_error","Could not write DNS response or local history.").apply();
                    stopSelf();
                }
            });
        }
    }
    private void write(Session s,byte[] bytes) throws IOException {
        synchronized(s) { if(s.active) s.output.write(bytes); }
    }
    private void stopCurrent() {
        Session s=session;session=null;running=false;state="Stopped";
        if(s!=null) {
            s.active=false;s.tasks.shutdownNow();
            if(s.tunnel!=null) try { s.tunnel.close(); } catch(IOException ignored) { }
            if(s.reader!=null) s.reader.interrupt();
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onRevoke() { stopCurrent();stopSelf(); }
    @Override public void onDestroy() { stopCurrent();super.onDestroy(); }
}
