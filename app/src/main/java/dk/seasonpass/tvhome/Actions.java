package dk.seasonpass.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Køen af handlinger, brugeren trykker på fjernsynet ("Har set den", "Ikke for mig"), til telefonen har hentet
 * dem (GET /actions) og kvitteret (POST /actions/ack). Gemmes i appens indstillinger og overlever genstart.
 * Indtil en handling er kvitteret, holdes serien skjult på fjernsynet, ellers kom den tilbage med næste feed.
 */
final class Actions {

    private static final int MAX = 200;
    static final String SEEN = "seen", DISMISS = "dismiss";

    private Actions() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    /** Alle ikke kvitterede handlinger, ældste først. */
    static synchronized JSONArray all(Context c) {
        try {
            return new JSONArray(prefs(c).getString("actions", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Tilføjer en handling; en ens handling for samme serie, der allerede venter, tilføjes ikke igen. */
    static synchronized void add(Context c, String kind, long tmdbId, String title) {
        try {
            JSONArray now = all(c);
            for (int i = 0; i < now.length(); i++) {
                JSONObject a = now.getJSONObject(i);
                if (a.optLong("tmdbId") == tmdbId && kind.equals(a.optString("kind"))) return;
            }
            JSONArray next = new JSONArray();
            int from = Math.max(0, now.length() - (MAX - 1));
            for (int i = from; i < now.length(); i++) next.put(now.get(i));
            next.put(new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("kind", kind)
                    .put("tmdbId", tmdbId)
                    .put("title", title)
                    .put("at", System.currentTimeMillis()));
            prefs(c).edit().putString("actions", next.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Fjerner de kvitterede handlinger. */
    static synchronized void ack(Context c, Set<String> ids) {
        try {
            JSONArray now = all(c), keep = new JSONArray();
            for (int i = 0; i < now.length(); i++) {
                if (!ids.contains(now.getJSONObject(i).optString("id"))) keep.put(now.get(i));
            }
            prefs(c).edit().putString("actions", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Tømmer køen (kun til vedligehold fra adb, se FeedProvider). */
    static synchronized void clear(Context c) {
        prefs(c).edit().remove("actions").apply();
    }

    /** TMDB-id'er, der venter på telefonen: skjules fra Opdag serier. */
    static synchronized Set<Long> hidden(Context c) {
        Set<Long> out = new HashSet<>();
        JSONArray now = all(c);
        for (int i = 0; i < now.length(); i++) out.add(now.optJSONObject(i).optLong("tmdbId"));
        return out;
    }

    /** "tmdb:123" → 123, ellers 0. */
    static long tmdbId(String id) {
        if (id == null || !id.startsWith("tmdb:")) return 0;
        try { return Long.parseLong(id.substring(5)); } catch (NumberFormatException e) { return 0; }
    }
}
