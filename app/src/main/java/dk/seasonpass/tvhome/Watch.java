package dk.seasonpass.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hvor langt man er nået (0.10.0). Streaming-apps viser deres afspilning som en media session; den læser vi, mens
 * afspilningen kører (position, og hvor appen giver dem: titel, undertitel og længde). Når man kommer tilbage til
 * forsiden efter at have set det meste af noget, gætter vi afsnittet ud fra feedet og spørger "Så du hele afsnittet?".
 * Intet sendes til telefonen uden et ja. Apps uden session (DR, TV 2 Play) kan vi ikke aflæse på den måde.
 */
final class Watch {

    private Watch() { }

    /** Uden længde fra appen: så meget skal være set, før vi spørger. */
    static final long MIN_MS = 15 * 60 * 1000L;
    /** Med længde: så stor en del skal være set. */
    static final double SHARE = 0.8;
    private static final long LAUNCH_MS = 6 * 60 * 60 * 1000L;

    /** Det gættede afsnit: serien (feedets id og titel) og afsnittets sæson og nummer. */
    static final class Result {
        String show, title;
        int s, n;
    }

    private static final class Track {
        MediaController ctl;
        MediaController.Callback cb;
        String title = "", sub = "";
        long dur, max;
        boolean playing;
    }

    private static final class Done {
        String pkg, title, sub;
        long dur;
    }

    private static final Map<String, Track> TRACKS = new HashMap<>();
    private static final List<Done> DONE = new ArrayList<>();
    private static MediaSessionManager.OnActiveSessionsChangedListener keep;

    /** Kaldes, når notifikationslytteren er forbundet (MediaListener): følg afspilningerne i de kendte apps. */
    static synchronized void attach(Context c) {
        try {
            MediaSessionManager m = (MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(c, MediaListener.class);
            keep = Watch::sync;
            m.addOnActiveSessionsChangedListener(keep, cn);
            sync(m.getActiveSessions(cn));
        } catch (Exception ignored) { }   // uden lov til at se sessioner sker der ingenting, som før
    }

    private static synchronized void sync(List<MediaController> list) {
        java.util.Set<String> present = new java.util.HashSet<>();
        for (MediaController mc : list == null ? new ArrayList<MediaController>() : list) {
            String pkg = mc.getPackageName();
            if (!Feed.APP.containsValue(pkg)) continue;
            present.add(pkg);
            Track t = TRACKS.get(pkg);
            if (t == null) { t = new Track(); TRACKS.put(pkg, t); }
            if (t.ctl == null || !t.ctl.getSessionToken().equals(mc.getSessionToken())) {
                release(t);
                t.ctl = mc;
                final String p = pkg;
                t.cb = new MediaController.Callback() {
                    @Override public void onPlaybackStateChanged(PlaybackState s) { state(p, s); }
                    @Override public void onMetadataChanged(MediaMetadata md) { meta(p, md); }
                    @Override public void onSessionDestroyed() { close(p); }
                };
                mc.registerCallback(t.cb);
            }
            state(pkg, mc.getPlaybackState());
            meta(pkg, mc.getMetadata());
        }
        for (String pkg : new ArrayList<>(TRACKS.keySet())) if (!present.contains(pkg)) close(pkg);
    }

    private static void release(Track t) {
        if (t.ctl != null && t.cb != null) {
            try { t.ctl.unregisterCallback(t.cb); } catch (Exception ignored) { }
        }
        t.ctl = null;
        t.cb = null;
    }

    private static synchronized void state(String pkg, PlaybackState s) {
        Track t = TRACKS.get(pkg);
        if (t == null || s == null) return;
        int st = s.getState();
        boolean playing = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING;
        long p = s.getPosition();
        if (st == PlaybackState.STATE_PLAYING) {
            p += (long) (s.getPlaybackSpeed() * (SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()));
        }
        // Et nyt afsnit begynder (fx automatisk næste): det forrige gemmes som færdigt, før tælleren starter forfra.
        if (playing && p < 60 * 1000L && t.max >= MIN_MS) bank(pkg, t);
        if (st != PlaybackState.STATE_STOPPED && st != PlaybackState.STATE_NONE) t.max = Math.max(t.max, p);
        t.playing = playing;
    }

    private static synchronized void meta(String pkg, MediaMetadata md) {
        Track t = TRACKS.get(pkg);
        if (t == null || md == null) return;
        String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        String sub = text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        if (!title.equals(t.title) && t.max >= MIN_MS && !t.title.isEmpty()) bank(pkg, t);   // andet indhold: forrige er færdigt
        t.title = title;
        t.sub = sub;
        long d = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (d > 0) t.dur = d;
    }

    private static String text(MediaMetadata md, String a, String b) {
        String v = md.getString(a);
        if (v == null || v.isEmpty()) v = md.getString(b);
        return v == null ? "" : v;
    }

    /** Sessionen er væk: afspilningen er slut for nu. Det set bliver stående, til forsiden spørger. */
    private static synchronized void close(String pkg) {
        Track t = TRACKS.get(pkg);
        if (t == null) return;
        t.playing = false;
        release(t);
    }

    private static void bank(String pkg, Track t) {
        if (enough(t.max, t.dur)) {
            Done d = new Done();
            d.pkg = pkg;
            d.title = t.title;
            d.sub = t.sub;
            d.dur = t.dur;
            DONE.add(d);
        }
        t.max = 0;
    }

    static boolean enough(long max, long dur) {
        return dur > 0 ? max >= dur * SHARE : max >= MIN_MS;
    }

    /** Husk, hvilket afsnit der blev åbnet herfra (forsiden), så afspilninger uden titel kan kobles til en serie. */
    static void launched(Context c, String show, String app) {
        c.getApplicationContext().getSharedPreferences("watch", Context.MODE_PRIVATE).edit()
                .putString("show", show).putString("app", app).putLong("at", System.currentTimeMillis()).apply();
    }

    /**
     * Forsiden er fremme igen: har en afspilning, der ikke kører mere, fyldt nok til at spørge, gættes afsnittet.
     * Returnerer det næste, der skal spørges om, ellers null. Hver afspilning spørges kun om én gang.
     */
    static synchronized Result finished(Context c) {
        for (Map.Entry<String, Track> e : TRACKS.entrySet()) {
            Track t = e.getValue();
            if (!t.playing && enough(t.max, t.dur)) bank(e.getKey(), t);
        }
        while (!DONE.isEmpty()) {
            Result r = guess(c, DONE.remove(0));
            if (r != null) return r;
        }
        return null;
    }

    private static Result guess(Context c, Done d) {
        try {
            File f = new File(FeedProvider.dir(c), "feed.json");
            if (!f.isFile()) return null;
            JSONObject feed = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONArray shows = feed.optJSONArray("shows");
            if (shows == null) return null;
            String a = norm(d.title), b = norm(d.sub);
            // 1) Afsnittets navn står i afspilningen.
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < shows.length(); i++) {
                    JSONObject s = shows.getJSONObject(i);
                    if (pass == 0 && !d.pkg.equals(Feed.APP.get(s.optString("host")))) continue;
                    JSONArray r = s.optJSONArray("ready");
                    for (int k = 0; r != null && k < r.length(); k++) {
                        JSONObject e = r.getJSONObject(k);
                        String n = norm(e.optString("name"));
                        if (!n.isEmpty() && e.has("n") && !e.isNull("n") && (n.equals(a) || n.equals(b))) return result(s, e);
                    }
                }
            }
            // 2) Serien står i afspilningen: det ældste usete afsnit.
            for (int i = 0; i < shows.length(); i++) {
                JSONObject s = shows.getJSONObject(i);
                String t = norm(s.optString("title"));
                if (t.length() >= 3 && (t.equals(a) || t.equals(b))) {
                    JSONObject e = firstReady(s);
                    if (e != null) return result(s, e);
                }
            }
            // 3) Afsnittet blev åbnet fra forsiden.
            android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences("watch", Context.MODE_PRIVATE);
            if (d.pkg.equals(p.getString("app", "")) && System.currentTimeMillis() - p.getLong("at", 0) < LAUNCH_MS) {
                for (int i = 0; i < shows.length(); i++) {
                    JSONObject s = shows.getJSONObject(i);
                    if (!s.optString("id").equals(p.getString("show", ""))) continue;
                    JSONObject e = firstReady(s);
                    if (e != null) return result(s, e);
                }
            }
            // 4) Den serie i appen, man senest så noget af.
            JSONObject best = null;
            long bestAt = -1;
            for (int i = 0; i < shows.length(); i++) {
                JSONObject s = shows.getJSONObject(i);
                if (!d.pkg.equals(Feed.APP.get(s.optString("host"))) || firstReady(s) == null) continue;
                long at = s.optLong("lastWatchedAt", 0);
                if (at > bestAt) { bestAt = at; best = s; }
            }
            if (best != null) return result(best, firstReady(best));
        } catch (Exception ignored) { }
        return null;
    }

    private static JSONObject firstReady(JSONObject s) {
        JSONArray r = s.optJSONArray("ready");
        for (int k = 0; r != null && k < r.length(); k++) {
            JSONObject e = r.optJSONObject(k);
            if (e != null && e.has("n") && !e.isNull("n")) return e;
        }
        return null;
    }

    private static Result result(JSONObject s, JSONObject e) {
        Result r = new Result();
        r.show = s.optString("id");
        r.title = s.optString("title");
        r.s = e.optInt("s");
        r.n = e.optInt("n");
        return r;
    }

    private static String norm(String x) {
        return x == null ? "" : x.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
