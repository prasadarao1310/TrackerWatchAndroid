package in.trackerwatch.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.util.*;

public class DnsPacketTest {
    static byte[] request(String name) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x12,0x34,1,0,0,1,0,0,0,0,0,0});
        for(String label:name.split("\\.")){out.write(label.length());out.writeBytes(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        out.writeBytes(new byte[]{0,0,1,0,1});
        byte[] dns=out.toByteArray(),packet=new byte[28+dns.length];
        packet[0]=0x45;DnsPacket.put16(packet,2,packet.length);packet[8]=64;packet[9]=17;
        packet[12]=10;packet[15]=2;packet[16]=(byte)203;packet[18]=113;packet[19]=53;
        DnsPacket.put16(packet,20,40222);DnsPacket.put16(packet,22,53);DnsPacket.put16(packet,24,dns.length+8);
        System.arraycopy(dns,0,packet,28,dns.length);return packet;
    }
    static DnsPacket parsed(String domain){byte[] p=request(domain);return DnsPacket.parse(p,p.length);}
    @Test public void parsesAndNormalizesDomain(){assertEquals("www.example.com",parsed("WWW.Example.com").domain);}
    @Test public void rejectsEveryTruncatedPrefix(){
        byte[] p=request("example.com");
        for(int i=0;i<p.length;i++)assertNull("length "+i,DnsPacket.parse(p,i));
    }
    @Test public void rejectsFragments(){
        byte[] p=request("example.com");p[6]=0x20;assertNull(DnsPacket.parse(p,p.length));
        p[6]=0;p[7]=1;assertNull(DnsPacket.parse(p,p.length));
    }
    @Test public void rejectsWrongPortAndAddress(){
        byte[] p=request("example.com");p[23]=54;assertNull(DnsPacket.parse(p,p.length));
        p=request("example.com");p[19]=54;assertNull(DnsPacket.parse(p,p.length));
    }
    @Test public void rejectsTcp(){byte[] p=request("example.com");p[9]=6;assertNull(DnsPacket.parse(p,p.length));}
    @Test public void rejectsCompressionPointerInQuery(){byte[] p=request("example.com");p[40]=(byte)0xc0;assertNull(DnsPacket.parse(p,p.length));}
    @Test public void rejectsMultipleQuestions(){byte[] p=request("example.com");p[33]=2;assertNull(DnsPacket.parse(p,p.length));}
    @Test public void rejectsResponsesAsQueries(){byte[] p=request("example.com");p[30]=(byte)0x81;assertNull(DnsPacket.parse(p,p.length));}
    @Test public void rejectsBadUdpLength(){byte[] p=request("example.com");DnsPacket.put16(p,24,12);assertNull(DnsPacket.parse(p,p.length));}
    @Test public void errorRetainsQuestionAndId(){
        DnsPacket q=parsed("example.com");byte[] r=q.error(3);
        assertEquals(0x1234,DnsPacket.u16(r,0));assertEquals(3,r[3]&15);assertTrue((r[2]&0x80)!=0);
        assertEquals(1,DnsPacket.u16(r,4));assertEquals(0,DnsPacket.u16(r,6));assertTrue(q.validResponse(r));
    }
    @Test public void rejectsUnrelatedResponse(){
        DnsPacket q=parsed("example.com");byte[] r=q.error(0);r[0]^=1;assertFalse(q.validResponse(r));
        r=q.error(0);r[13]^=1;assertFalse(q.validResponse(r));
        assertFalse(q.validResponse(new byte[2]));
    }
    @Test public void wrapsSwappedAddressesAndValidChecksum(){
        DnsPacket q=parsed("example.com");byte[] p=q.wrap(q.error(3));
        assertEquals(p.length,DnsPacket.u16(p,2));assertEquals(203,p[12]&255);assertEquals(10,p[16]&255);
        assertEquals(53,DnsPacket.u16(p,20));assertEquals(40222,DnsPacket.u16(p,22));
        int sum=0;for(int i=0;i<20;i+=2)sum+=DnsPacket.u16(p,i);
        while((sum>>>16)>0)sum=(sum&65535)+(sum>>>16);
        assertEquals(65535,sum);
    }
    @Test public void fuzzDoesNotReadOutsidePacket(){
        Random random=new Random(42);
        for(int i=0;i<5000;i++){byte[] p=new byte[random.nextInt(400)];random.nextBytes(p);DnsPacket.parse(p,p.length);}
    }
}
