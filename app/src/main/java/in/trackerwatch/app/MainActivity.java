package in.trackerwatch.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff0c151a,CARD=0xff17252c,INK=0xffedf5f2,MUTED=0xffa5b7bc,MINT=0xff83e6c2;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout root,body,tabs;
    private ScrollView scroll;
    private int tab;
    private boolean foreground,loading;
    private EventStore.Snapshot data=new EventStore.Snapshot();
    private final Runnable tick=new Runnable(){ public void run(){ refresh();handler.postDelayed(this,2000); }};
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if(saved!=null) tab=saved.getInt("tab",0);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setPadding(dp(18),dp(12),dp(18),dp(8));
        root.setOnApplyWindowInsetsListener((v,insets)->{
            v.setPadding(dp(18),dp(12)+insets.getSystemWindowInsetTop(),dp(18),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        TextView brand=text("TRACKERWATCH",14,MINT);brand.setLetterSpacing(.16f);root.addView(brand);
        TextView sub=text("Your phone. A clearer picture.",23,INK);sub.setPadding(0,dp(8),0,dp(12));root.addView(sub);
        scroll=new ScrollView(this);scroll.setFillViewport(true);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        tabs=new LinearLayout(this);root.addView(tabs);
        String[] labels={"Overview","Activity","Rules","More"};
        for(int i=0;i<labels.length;i++) {
            final int selected=i;Button b=button(labels[i],()->{tab=selected;render();scroll.scrollTo(0,0);refresh();});
            b.setTextSize(12);b.setPadding(0,0,0,0);
            tabs.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));
        }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out);out.putInt("tab",tab); }
    @Override protected void onResume(){super.onResume();foreground=true;handler.post(tick);}
    @Override protected void onPause(){foreground=false;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);io.shutdown();super.onDestroy();}
    private void refresh() {
        if(loading || tab>1 || !foreground) return;
        loading=true;
        io.execute(()->{
            EventStore.Snapshot next=null;
            try { next=EventStore.get(this).snapshot(); } catch(Exception ignored) { }
            final EventStore.Snapshot snapshot=next;
            runOnUiThread(()->{
                loading=false;
                if(snapshot!=null) data=snapshot;
                if(foreground && tab<2) {int y=scroll.getScrollY();render();scroll.post(()->scroll.scrollTo(0,y));}
            });
        });
    }
    private int dp(float v){return (int)(v*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;
    }
    private Button button(String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(MINT);b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout card(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(16),dp(12),dp(16),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(CARD);bg.setCornerRadius(dp(18));box.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(12);body.addView(box,lp);return box;
    }
    private void title(String s){body.addView(text(s,25,INK));}
    private void note(LinearLayout box,String s){box.addView(text(s,14,MUTED));}
    private void render(){
        body.removeAllViews();
        for(int i=0;i<tabs.getChildCount();i++) ((Button)tabs.getChildAt(i)).setTextColor(i==tab?MINT:MUTED);
        if(tab==0) overview();else if(tab==1) activity();else if(tab==2) rules();else more();
    }
    private void overview(){
        LinearLayout status=card();
        status.addView(text(DnsVpnService.running?"●  Monitoring is active":"○  Monitoring is off",19,DnsVpnService.running?MINT:INK));
        note(status,"DNS-only local VPN • "+(DnsVpnService.prefs(this).getBoolean("blocking",false)?"blocking enabled":"observe mode"));
        status.addView(button(DnsVpnService.running?"Stop monitoring":"Start monitoring",()->{
            if(DnsVpnService.running){stopService(new Intent(this,DnsVpnService.class));handler.postDelayed(this::render,250);}
            else explainStart();
        }));
        String error=DnsVpnService.prefs(this).getString("last_error","");
        if(!error.isEmpty()) note(status,error);
        title("Last 24 hours");
        LinearLayout summary=card();
        summary.addView(text(NumberFormat.getIntegerInstance().format(data.total),38,INK));note(summary,"DNS requests observed");
        summary.addView(text(data.matched+" tracker-list matches  ·  "+data.companies+" labels",17,MINT));
        note(summary,data.blocked+" requests blocked • repeated DNS queries count separately");
        LinearLayout locked=card();
        String pct=data.matched==0?"—":Math.round(100.0*data.locked/data.matched)+"%";
        locked.addView(text(pct+" while locked",28,INK));
        note(locked,data.matched==0?"No tracker-list matches yet.":"Of matched DNS requests, "+data.locked+" arrived while Android reported the device locked.");
        LinearLayout graph=card();graph.addView(text("Matched requests · hourly",17,INK));
        graph.addView(new Bars(data.hourly),new LinearLayout.LayoutParams(-1,dp(120)));
        note(graph,"24 hours ago                                      Now");
        LinearLayout companies=card();companies.addView(text("Tracker-list labels",18,INK));
        if(data.leaders.isEmpty()) note(companies,"Start monitoring and use your phone normally. Results appear here when domains match the starter list.");
        for(String[] row:data.leaders) companies.addView(text(row[0]+"   "+row[1],16,INK));
        note(companies,"A domain match suggests analytics or advertising activity; it does not prove personal data was sent.");
    }
    private void activity(){
        title("Recent DNS activity");
        body.addView(text("Latest 100 observations · retained up to 7 days / 20,000 entries",13,MUTED));
        if(data.recent.isEmpty()){LinearLayout box=card();note(box,"No observations yet. Start monitoring from Overview, then open a website or use an app.");}
        DateFormat clock=new SimpleDateFormat("dd MMM, HH:mm:ss",Locale.getDefault());
        for(String[] row:data.recent){
            LinearLayout box=card();box.addView(text(row[1],17,INK));
            note(box,(row[2].isEmpty()?"Not on starter list":row[2])+" • "+clock.format(new Date(Long.parseLong(row[0]))));
            note(box,row[5]+("1".equals(row[3])?" • phone locked":" • phone unlocked"));
            if(!row[2].isEmpty()) box.addView(button("Allow this domain",()->saveRule("allow",row[1])));
        }
        body.addView(text("App attribution is unavailable in this DNS-only build. Unmatched does not mean tracking-free.",13,MUTED));
    }
    private void rules(){
        title("Your rules");
        LinearLayout mode=card();
        Switch toggle=new Switch(this);toggle.setText("Block matched domains");toggle.setTextColor(INK);
        toggle.setChecked(DnsVpnService.prefs(this).getBoolean("blocking",false));mode.addView(toggle);
        toggle.setOnCheckedChangeListener((v,enabled)->{
            if(enabled) new AlertDialog.Builder(this).setTitle("Enable blocking?")
                .setMessage("Some app features may stop working. Allowlist a domain or turn blocking off if needed. Only matching DNS queries observed by this VPN can be blocked.")
                .setPositiveButton("Enable",(d,w)->DnsVpnService.prefs(this).edit().putBoolean("blocking",true).apply())
                .setNegativeButton("Cancel",(d,w)->toggle.setChecked(false))
                .setOnCancelListener(d->toggle.setChecked(false)).show();
            else DnsVpnService.prefs(this).edit().putBoolean("blocking",false).apply();
        });
        note(mode,"Allow rules take priority. A domain rule includes its subdomains. Changes apply to new DNS queries; cached addresses can remain usable.");
        LinearLayout allow=card();allow.addView(text("Always allow",18,INK));
        allow.addView(button("Add allowed domain",()->promptRule("allow")));listRules(allow,"allow");
        LinearLayout custom=card();custom.addView(text("Custom blocked domains",18,INK));
        custom.addView(button("Add blocked domain",()->promptRule("custom")));listRules(custom,"custom");
        LinearLayout built=card();built.addView(text(TrackerRules.SEEDS.size()+" starter rules",18,INK));
        note(built,"Small bundled list, version 1. Not a comprehensive tracker database. Rules can also affect useful analytics or crash-reporting functions.");
        for(Map.Entry<String,String> e:TrackerRules.SEEDS.entrySet()) note(built,e.getKey()+"  ·  "+e.getValue());
    }
    private void promptRule(String key){
        EditText input=new EditText(this);input.setSingleLine();input.setHint("example.com");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this).setTitle("allow".equals(key)?"Allow domain":"Block domain").setView(input)
            .setPositiveButton("Add",(d,w)->saveRule(key,input.getText().toString())).setNegativeButton("Cancel",null).show();
    }
    private void saveRule(String key,String value){
        String normalized=TrackerRules.normalize(value);
        if(normalized==null){Toast.makeText(this,"Enter a domain only, such as example.com",Toast.LENGTH_LONG).show();return;}
        Set<String> set=new HashSet<>(DnsVpnService.prefs(this).getStringSet(key,Collections.emptySet()));
        set.add(normalized);DnsVpnService.prefs(this).edit().putStringSet(key,set).apply();
        Toast.makeText(this,"Rule saved",Toast.LENGTH_SHORT).show();render();
    }
    private void listRules(LinearLayout box,String key){
        Set<String> set=new TreeSet<>(DnsVpnService.prefs(this).getStringSet(key,Collections.emptySet()));
        if(set.isEmpty()) note(box,"No rules added.");
        for(String value:set) box.addView(button(value+"  ×",()->{
            Set<String> updated=new HashSet<>(DnsVpnService.prefs(this).getStringSet(key,Collections.emptySet()));
            updated.remove(value);DnsVpnService.prefs(this).edit().putStringSet(key,updated).apply();render();
        }));
    }
    private String privateDns(){
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        LinkProperties props=cm.getLinkProperties(cm.getActiveNetwork());
        if(Build.VERSION.SDK_INT>=28 && props!=null && props.isPrivateDnsActive())
            return props.getPrivateDnsServerName()==null?"Active (automatic)":"Active: "+props.getPrivateDnsServerName();
        return "Not reported active";
    }
    private void more(){
        title("Settings & scope");
        LinearLayout info=card();info.addView(text("TrackerWatch 0.1.0",20,INK));
        note(info,"Independent first version inspired by the supplied screenshot. No login, analytics SDK, ads or remote log upload.");
        note(info,"Regular DNS queries observed by this VPN are forwarded to Cloudflare 1.1.1.1 over UDP. Cloudflare and the network can see these queries. Blocked queries are answered locally.");
        note(info,"Local VPN does not hide your IP or encrypt your internet traffic. Another VPN cannot run alongside it.");
        LinearLayout checks=card();checks.addView(text("Device checks",18,INK));
        note(checks,"Private DNS: "+privateDns());
        note(checks,"Screen lock configured: "+(getSystemService(KeyguardManager.class).isDeviceSecure()?"Yes":"No"));
        note(checks,"USB debugging setting: "+(Settings.Global.getInt(getContentResolver(),Settings.Global.ADB_ENABLED,0)==1?"Enabled":"Disabled"));
        note(checks,"Android security patch: "+Build.VERSION.SECURITY_PATCH);
        note(checks,"These settings are informational, not a malware or security certification.");
        checks.addView(button("Open network settings",()->startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS))));
        LinearLayout scope=card();scope.addView(text("What this version cannot see",18,INK));
        note(scope,"Encrypted DNS (DoH/DoT), cached lookups, direct-IP traffic, custom DNS servers and traffic that bypasses the VPN can be missed. Only IPv4 UDP DNS addressed to the configured resolver is handled; TCP DNS is not supported.");
        note(scope,"It cannot read encrypted app contents, reliably attribute DNS to individual apps, scan APKs for embedded SDKs, or detect stalkerware. These features are not implemented.");
        note(scope,"Keep Android's 'Block connections without VPN' switched off: this app routes only DNS. Monitoring stops when you stop it or Android terminates it; no automatic boot start.");
        LinearLayout history=card();history.addView(text("Your history",18,INK));
        note(history,"Stored in this app's private database. Android backup is disabled. Exported files contain browsing-related domain names.");
        history.addView(button("Export CSV",()->{
            Intent export=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE,"TrackerWatch-history.csv");startActivityForResult(export,42);
        }));
        history.addView(button("Delete history",()->new AlertDialog.Builder(this).setTitle("Delete all observations?")
            .setMessage("This removes the local log. New observations continue if monitoring stays on.")
            .setPositiveButton("Delete",(d,w)->io.execute(()->{EventStore.get(this).clear();runOnUiThread(()->Toast.makeText(this,"History deleted",Toast.LENGTH_SHORT).show());}))
            .setNegativeButton("Cancel",null).show()));
    }
    private void explainStart(){
        new AlertDialog.Builder(this).setTitle("Start local DNS monitoring?")
            .setMessage("TrackerWatch will use Android's VPN slot to observe ordinary DNS queries and keep domain history on this phone. Allowed queries go to Cloudflare 1.1.1.1; this does not encrypt your traffic.\n\nAnother VPN will disconnect. Private DNS or browser Secure DNS can bypass monitoring; turn them off yourself if you want to test this build.\n\nPrivate DNS: "+privateDns()+"\n\nYou can stop anytime from the app or notification.")
            .setPositiveButton("Continue",(d,w)->{
                if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);
                Intent permission=VpnService.prepare(this);
                if(permission!=null) startActivityForResult(permission,41);else startVpn();
            }).setNegativeButton("Cancel",null).show();
    }
    private void startVpn(){startForegroundService(new Intent(this,DnsVpnService.class));handler.postDelayed(this::render,400);}
    @Override protected void onActivityResult(int request,int result,Intent intent){
        super.onActivityResult(request,result,intent);
        if(request==41 && result==RESULT_OK) startVpn();
        if(request==42 && result==RESULT_OK && intent!=null && intent.getData()!=null){
            Uri destination=intent.getData();
            io.execute(()->{
                String message;
                try {
                    OutputStream out=getContentResolver().openOutputStream(destination,"wt");
                    if(out==null) throw new IOException("No output stream");
                    EventStore.get(this).export(out);message="CSV saved";
                } catch(Exception e){message="Could not export CSV";}
                final String finalMessage=message;runOnUiThread(()->Toast.makeText(this,finalMessage,Toast.LENGTH_LONG).show());
            });
        }
    }
    private final class Bars extends View {
        private final int[] values;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Bars(int[] v){super(MainActivity.this);values=v.clone();setContentDescription("Hourly matched DNS requests over the last 24 hours: "+Arrays.toString(values));}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);int max=1;for(int n:values)max=Math.max(max,n);
            float width=getWidth()/24f,bottom=getHeight()-dp(6);
            paint.setColor(0xff30434b);canvas.drawLine(0,bottom,getWidth(),bottom,paint);
            for(int i=0;i<24;i++){
                paint.setColor(values[i]>0?MINT:0xff30434b);
                float height=values[i]==0?dp(2):(bottom-dp(8))*values[i]/max;
                canvas.drawRoundRect(i*width+dp(1),bottom-height,(i+1)*width-dp(2),bottom,dp(2),dp(2),paint);
            }
        }
    }
}
