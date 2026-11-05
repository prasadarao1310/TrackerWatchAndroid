package in.trackerwatch.app;

import java.util.Arrays;
import java.util.Locale;

/** Small, bounds-checked IPv4/UDP DNS codec. No Android dependencies. */
final class DnsPacket {
    final byte[] packet, dns;
    final String domain;
    final int ipHeader, questionEnd;
    private DnsPacket(byte[] packet, byte[] dns, String domain, int ipHeader, int end) {
        this.packet=packet; this.dns=dns; this.domain=domain; this.ipHeader=ipHeader; this.questionEnd=end;
    }
    static int u16(byte[] a,int p) { return ((a[p]&255)<<8)|(a[p+1]&255); }
    static void put16(byte[] a,int p,int v) { a[p]=(byte)(v>>>8); a[p+1]=(byte)v; }
    static DnsPacket parse(byte[] data,int length) {
        if(length<28 || length>data.length || (data[0]&240)!=64) return null;
        int ihl=(data[0]&15)*4;
        if(ihl<20 || ihl+8>length || (data[9]&255)!=17 || (u16(data,6)&0x3fff)!=0) return null;
        int total=u16(data,2), udp=u16(data,ihl+4);
        if(total>length || total<ihl+8 || udp<20 || ihl+udp!=total || u16(data,ihl+2)!=53) return null;
        if((data[16]&255)!=203 || (data[17]&255)!=0 || (data[18]&255)!=113 || (data[19]&255)!=53) return null;
        byte[] dns=Arrays.copyOfRange(data,ihl+8,total);
        if((dns[2]&0xf8)!=0 || u16(dns,4)!=1) return null;
        int p=12;
        StringBuilder name=new StringBuilder();
        while(true) {
            if(p>=dns.length) return null;
            int n=dns[p++]&255;
            if(n==0) break;
            if(n>63 || p+n>dns.length) return null;
            if(name.length()>0) name.append('.');
            for(int i=0;i<n;i++) {
                int c=dns[p++]&255;
                if(c<33 || c>126 || c=='.') return null;
                name.append((char)c);
            }
            if(name.length()>253) return null;
        }
        if(p+4>dns.length || name.length()==0) return null;
        return new DnsPacket(Arrays.copyOf(data,total),dns,name.toString().toLowerCase(Locale.ROOT),ihl,p+4);
    }
    byte[] error(int rcode) {
        byte[] reply=Arrays.copyOf(dns,questionEnd);
        reply[2]=(byte)(0x80|(dns[2]&1)); // QR, preserve RD; no false AA claim
        reply[3]=(byte)(0x80|(rcode&15)); // recursion available
        for(int p=6;p<12;p++) reply[p]=0;
        return reply;
    }
    boolean validResponse(byte[] reply) {
        if(reply.length<questionEnd || u16(reply,0)!=u16(dns,0) ||
           (reply[2]&0x80)==0 || u16(reply,4)!=1) return false;
        for(int i=12;i<questionEnd;i++) if(reply[i]!=dns[i]) return false;
        return true;
    }
    byte[] wrap(byte[] response) {
        if(response.length>32739) throw new IllegalArgumentException("DNS response too large");
        byte[] result=new byte[28+response.length];
        result[0]=0x45; put16(result,2,result.length);
        result[8]=64; result[9]=17;
        System.arraycopy(packet,16,result,12,4);
        System.arraycopy(packet,12,result,16,4);
        put16(result,20,53); put16(result,22,u16(packet,ipHeader));
        put16(result,24,response.length+8);
        System.arraycopy(response,0,result,28,response.length);
        int sum=0;
        for(int i=0;i<20;i+=2) sum+=u16(result,i);
        while((sum>>>16)>0) sum=(sum&65535)+(sum>>>16);
        put16(result,10,(~sum)&65535);
        // A zero UDP checksum is valid for IPv4.
        return result;
    }
}
