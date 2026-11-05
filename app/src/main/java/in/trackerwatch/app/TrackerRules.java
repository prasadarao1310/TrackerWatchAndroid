package in.trackerwatch.app;

import java.util.*;

/** Deliberately small, transparent starter list, not a malware verdict. */
final class TrackerRules {
    static final Map<String,String> SEEDS=new LinkedHashMap<>();
    static {
        SEEDS.put("doubleclick.net","Google");
        SEEDS.put("google-analytics.com","Google");
        SEEDS.put("googlesyndication.com","Google");
        SEEDS.put("googleadservices.com","Google");
        SEEDS.put("app-measurement.com","Google");
        SEEDS.put("crashlytics.com","Google");
        SEEDS.put("appsflyer.com","AppsFlyer");
        SEEDS.put("adjust.com","Adjust");
        SEEDS.put("adjust.io","Adjust");
        SEEDS.put("branch.io","Branch");
        SEEDS.put("mixpanel.com","Mixpanel");
        SEEDS.put("amplitude.com","Amplitude");
        SEEDS.put("segment.io","Twilio Segment");
        SEEDS.put("segment.com","Twilio Segment");
        SEEDS.put("flurry.com","Flurry");
        SEEDS.put("unityads.unity3d.com","Unity");
        SEEDS.put("applovin.com","AppLovin");
        SEEDS.put("applvn.com","AppLovin");
        SEEDS.put("inmobi.com","InMobi");
        SEEDS.put("taboola.com","Taboola");
        SEEDS.put("outbrain.com","Outbrain");
        SEEDS.put("criteo.com","Criteo");
        SEEDS.put("scorecardresearch.com","Comscore");
        SEEDS.put("adsrvr.org","The Trade Desk");
        SEEDS.put("facebook.net","Meta");
    }
    static String normalize(String s) {
        s=s.trim().toLowerCase(Locale.ROOT);
        if(s.endsWith(".")) s=s.substring(0,s.length()-1);
        if(s.length()>253 || !s.contains(".")) return null;
        for(String part:s.split("\\.",-1))
            if(!part.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) return null;
        return s;
    }
    static boolean matches(String domain,String rule) { return domain.equals(rule)||domain.endsWith("."+rule); }
    static boolean any(String domain,Set<String> rules) {
        for(String r:rules) if(matches(domain,r)) return true;
        return false;
    }
    static String company(String domain,Set<String> custom) {
        for(Map.Entry<String,String> e:SEEDS.entrySet()) if(matches(domain,e.getKey())) return e.getValue();
        return any(domain,custom)?"Custom rule":"";
    }
    static boolean shouldBlock(String domain,String company,boolean enabled,Set<String> allow) {
        return enabled && !company.isEmpty() && !any(domain,allow);
    }
}
