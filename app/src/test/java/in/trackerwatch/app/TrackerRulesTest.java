package in.trackerwatch.app;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class TrackerRulesTest {
    @Test public void matchesOnlyDomainBoundaries(){
        assertTrue(TrackerRules.matches("a.doubleclick.net","doubleclick.net"));
        assertFalse(TrackerRules.matches("notdoubleclick.net","doubleclick.net"));
        assertFalse(TrackerRules.matches("doubleclick.net.evil.test","doubleclick.net"));
    }
    @Test public void normalizesAndValidates(){
        assertEquals("example.com",TrackerRules.normalize(" Example.COM. "));
        for(String bad:new String[]{"https://example.com","*.example.com","example..com","-bad.com","a-.com","localhost","x.com/path","a.com:53"})
            assertNull(bad,TrackerRules.normalize(bad));
    }
    @Test public void allowOverridesBlock(){
        assertFalse(TrackerRules.shouldBlock("a.doubleclick.net","Google",true,Set.of("doubleclick.net")));
        assertTrue(TrackerRules.shouldBlock("doubleclick.net","Google",true,Set.of("other.net")));
    }
    @Test public void monitoringDoesNotBlock(){assertFalse(TrackerRules.shouldBlock("doubleclick.net","Google",false,Set.of()));}
    @Test public void unmatchedNotBlocked(){assertFalse(TrackerRules.shouldBlock("example.com","",true,Set.of()));}
    @Test public void customRuleClassifiesSubdomains(){
        assertEquals("Custom rule",TrackerRules.company("api.example.com",Set.of("example.com")));
        assertEquals("",TrackerRules.company("otherexample.com",Set.of("example.com")));
    }
}
