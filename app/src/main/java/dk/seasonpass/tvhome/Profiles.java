package dk.seasonpass.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Flere i hustanden (0.4.0): hver parret telefon er sin egen profil. Feeden fra hver telefon gemmes for sig
 * (profile-&lt;id&gt;.json); den aktive profils feed ligger desuden som feed.json, så skærm og pauseskærm læser som før.
 *
 * Profilen skifter aldrig midt i en tv-session, hvor en anden telefon sender (fx åbnes SeasonPass på et værelse):
 * så gemmes feeden bare. Kun i et kort vindue efter en ny session (tv'et tændes, genstart, pauseskærm) overtager
 * den første telefon, der sender. Ellers vælges profilen i hånden (avataren øverst til højre).
 */
final class Profiles {

    private Profiles() { }

    /** Vinduet efter en ny session, hvor den første telefon, der sender, bliver den aktive profil. */
    static final long WINDOW_MS = 3 * 60 * 1000L;

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    /** Profil-id for en nøgle (en hash, så nøglen selv ikke ligger i filnavne). */
    static String id(String token) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 4; i++) s.append(String.format(Locale.ROOT, "%02x", h[i]));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Aktiv profil; tom før nogen telefon har sendt (eller når feeden kom fra Mac'en). */
    static String active(Context c) { return sp(c).getString("active", ""); }

    static File file(Context c, String id) { return new File(FeedProvider.dir(c), "profile-" + id + ".json"); }

    static String name(Context c, String id) { return sp(c).getString("name_" + id, ""); }

    /** Alle profiler med en gemt feed, i den rækkefølge de blev parret. */
    static List<String> all(Context c) {
        List<String> out = new ArrayList<>();
        Set<String> tokens = sp(c).getStringSet("tokens", java.util.Collections.<String>emptySet());
        List<String> ids = new ArrayList<>();
        for (String t : tokens) ids.add(id(t));
        java.util.Collections.sort(ids);
        for (String i : ids) if (file(c, i).isFile()) out.add(i);
        return out;
    }

    /** Ny session: tv'et tændt, genstartet eller pauseskærmen er startet. Åbner vinduet, hvor en telefon kan overtage. */
    static void sessionStart(Context c) {
        sp(c).edit().putLong("window_until", System.currentTimeMillis() + WINDOW_MS).apply();
    }

    private static boolean windowOpen(Context c) {
        return System.currentTimeMillis() < sp(c).getLong("window_until", 0);
    }

    /**
     * En parret telefon har sendt sin feed (allerede gemt som profilens fil). Returnerer true, hvis feed.json
     * skal opdateres, fordi profilen nu er den aktive.
     */
    static synchronized boolean onFeed(Context c, String id, String name) throws Exception {
        SharedPreferences.Editor e = sp(c).edit();
        e.putString("name_" + id, name);
        String now = active(c);
        boolean take;
        if (now.isEmpty()) {
            // Første profil: tag den, og lad den arve handlinger fra før profilerne fandtes.
            take = true;
            Actions.adopt(c, id);
        } else if (now.equals(id)) {
            take = true;
        } else if (windowOpen(c)) {
            take = true;
            e.remove("window_until");   // kun den første telefon i vinduet vinder
        } else {
            take = false;
            // En anden telefon åbnede SeasonPass, mens tv'et er i brug: spørg, i stedet for at skifte af sig selv.
            if (System.currentTimeMillis() >= sp(c).getLong("mute_" + id, 0)) {
                e.putString("pending", id).putLong("pending_at", System.currentTimeMillis());
            }
        }
        if (take) e.putString("active", id).remove("pending");
        e.apply();
        if (take) copyToFeed(c, id);
        return take;
    }

    /** Længe nok til at nå tilbage til forsiden, men ikke så længe, at en gammel forespørgsel dukker op. */
    static final long PENDING_MS = 2 * 60 * 1000L;
    /** Efter "Behold" (eller ingen svar) spørges der ikke igen om samme telefon så længe. */
    static final long MUTE_MS = 30 * 60 * 1000L;

    /** Profilen, hvis telefon netop har åbnet SeasonPass uden at blive den aktive; tom, hvis ingen venter. */
    static String pending(Context c) {
        String id = sp(c).getString("pending", "");
        if (id.isEmpty() || id.equals(active(c)) || !file(c, id).isFile()) return "";
        return System.currentTimeMillis() - sp(c).getLong("pending_at", 0) <= PENDING_MS ? id : "";
    }

    /** Spørgsmålet er besvaret med "Behold" eller timet ud. */
    static void dismiss(Context c, String id) {
        sp(c).edit().remove("pending").putLong("mute_" + id, System.currentTimeMillis() + MUTE_MS).apply();
    }

    /** Vælg profil i hånden. */
    static synchronized void select(Context c, String id) throws Exception {
        if (!file(c, id).isFile()) return;
        sp(c).edit().putString("active", id).remove("window_until").remove("pending").apply();
        copyToFeed(c, id);
    }

    private static void copyToFeed(Context c, String id) throws Exception {
        File dir = FeedProvider.dir(c);
        File tmp = new File(dir, "feed.json.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(Files.readAllBytes(file(c, id).toPath())); }
        if (!tmp.renameTo(new File(dir, "feed.json"))) throw new IllegalStateException("kunne ikke gemme feed");
    }

    /** Navnet på profilen til visning: fornavn, ellers "Profil n". */
    static String label(Context c, String id, int index) {
        String n = name(c, id);
        return n.isEmpty() ? Strings.of(c).t("tvhome.profileN", "n", index + 1) : n;
    }

    /** Til fejlsøgning. */
    static JSONObject describe(Context c) throws Exception {
        JSONObject o = new JSONObject().put("active", active(c));
        for (String i : all(c)) o.put(i, name(c, i));
        return o;
    }
}
