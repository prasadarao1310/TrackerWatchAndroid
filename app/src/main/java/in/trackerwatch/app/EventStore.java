package in.trackerwatch.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.io.*;
import java.util.*;

final class EventStore extends SQLiteOpenHelper {
    private static EventStore instance;
    static synchronized EventStore get(Context c) {
        if(instance==null) instance=new EventStore(c.getApplicationContext());
        return instance;
    }
    private EventStore(Context c) { super(c,"observations.db",null,1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (_id INTEGER PRIMARY KEY, time INTEGER NOT NULL, domain TEXT NOT NULL, company TEXT NOT NULL, locked INTEGER NOT NULL, blocked INTEGER NOT NULL, outcome TEXT NOT NULL)");
        db.execSQL("CREATE INDEX event_time ON events(time)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) { }
    synchronized void add(String domain,String company,boolean locked,boolean blocked,String outcome) {
        SQLiteDatabase db=getWritableDatabase();
        ContentValues v=new ContentValues();
        v.put("time",System.currentTimeMillis()); v.put("domain",domain); v.put("company",company);
        v.put("locked",locked?1:0); v.put("blocked",blocked?1:0); v.put("outcome",outcome);
        db.insertOrThrow("events",null,v);
        // Bound sensitive history by both age and count.
        db.delete("events","time < ?",new String[]{String.valueOf(System.currentTimeMillis()-7L*86400000)});
        db.execSQL("DELETE FROM events WHERE _id <= (SELECT _id FROM events ORDER BY _id DESC LIMIT 1 OFFSET 20000)");
    }
    synchronized void clear() { getWritableDatabase().delete("events",null,null); }
    static final class Snapshot {
        long total,matched,blocked,locked,companies;
        final int[] hourly=new int[24];
        final List<String[]> recent=new ArrayList<>();
        final List<String[]> leaders=new ArrayList<>();
    }
    Snapshot snapshot() {
        Snapshot s=new Snapshot(); SQLiteDatabase db=getReadableDatabase();
        long now=System.currentTimeMillis(), since=now-86400000L;
        String[] args={String.valueOf(since)};
        try(Cursor c=db.rawQuery("SELECT COUNT(*),SUM(company!=''),SUM(blocked),SUM(company!='' AND locked=1),COUNT(DISTINCT CASE WHEN company!='' THEN company END) FROM events WHERE time>=?",args)) {
            if(c.moveToFirst()) { s.total=c.getLong(0);s.matched=c.getLong(1);s.blocked=c.getLong(2);s.locked=c.getLong(3);s.companies=c.getLong(4); }
        }
        try(Cursor c=db.rawQuery("SELECT time FROM events WHERE time>=? AND company!=''",args)) {
            while(c.moveToNext()) { int h=(int)((c.getLong(0)-since)/3600000L); if(h>=0 && h<24) s.hourly[h]++; }
        }
        try(Cursor c=db.rawQuery("SELECT time,domain,company,locked,blocked,outcome FROM events ORDER BY _id DESC LIMIT 100",null)) {
            while(c.moveToNext()) { String[] row=new String[6]; for(int i=0;i<6;i++) row[i]=c.getString(i);s.recent.add(row); }
        }
        try(Cursor c=db.rawQuery("SELECT company,COUNT(*) FROM events WHERE time>=? AND company!='' GROUP BY company ORDER BY COUNT(*) DESC LIMIT 8",args)) {
            while(c.moveToNext()) s.leaders.add(new String[]{c.getString(0),c.getString(1)});
        }
        return s;
    }
    void export(OutputStream stream) throws IOException {
        try(Writer w=new OutputStreamWriter(stream,java.nio.charset.StandardCharsets.UTF_8);
            Cursor c=getReadableDatabase().rawQuery("SELECT time,domain,company,locked,blocked,outcome FROM events ORDER BY _id",null)) {
            w.write("unix_time_ms,domain,company,phone_locked,blocked,outcome\n");
            while(c.moveToNext()) {
                for(int i=0;i<6;i++) {
                    if(i>0) w.write(",");
                    String value=c.getString(i);
                    // Prevent spreadsheet formula interpretation in exported cells.
                    if(value.matches("^[=+@-].*")) value="'"+value;
                    w.write("\""+value.replace("\"","\"\"")+"\"");
                }
                w.write("\n");
            }
        }
    }
}
