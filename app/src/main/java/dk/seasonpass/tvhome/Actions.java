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

    /** Hver profil har sin egen kø ("actions_<id>"); før profiler og fra Mac'en: "actions". */
    private static String key(String profile) {
        return profile == null || profile.isEmpty() ? "actions" : "actions_" + profile;
    }

    /** Den første profil arver køen fra før profilerne fandtes. */
    static synchronized void adopt(Context c, String profile) {
        SharedPreferences p = prefs(c);
        String old = p.getString("actions", null);
        if (old != null && !p.contains(key(profile))) {
            p.edit().putString(key(profile), old).remove("actions").apply();
        }
    }

    /** Alle ikke kvitterede handlinger for den aktive profil, ældste først. */
    static JSONArray all(Context c) { return all(c, Profiles.active(c)); }

    static synchronized JSONArray all(Context c, String profile) {
        try {
            return new JSONArray(prefs(c).getString(key(profile), "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** Tilføjer en handling; en ens handling for samme serie, der allerede venter, tilføjes ikke igen. */
    static synchronized void add(Context c, String kind, long tmdbId, String title) {
        String profile = Profiles.active(c);
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
            prefs(c).edit().putString(key(profile), next.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Et afsnit er set (efter et ja på forsiden). Samme afsnit, der allerede venter, tilføjes ikke igen. */
    static synchronized void episode(Context c, String show, int season, int number) {
        if (show == null || show.isEmpty() || season < 1 || number < 1) return;   // specials kan telefonen ikke finde
        String profile = Profiles.active(c);
        try {
            JSONArray now = all(c);
            for (int i = 0; i < now.length(); i++) {
                JSONObject a = now.getJSONObject(i);
                if ("episodeSeen".equals(a.optString("kind")) && show.equals(a.optString("show"))
                        && a.optInt("s") == season && a.optInt("n") == number) return;
            }
            JSONArray next = new JSONArray();
            int from = Math.max(0, now.length() - (MAX - 1));
            for (int i = from; i < now.length(); i++) next.put(now.get(i));
            next.put(new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("kind", "episodeSeen")
                    .put("show", show)
                    .put("s", season)
                    .put("n", number)
                    .put("at", System.currentTimeMillis()));
            prefs(c).edit().putString(key(profile), next.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Fjerner de kvitterede handlinger fra profilens kø. */
    static synchronized void ack(Context c, String profile, Set<String> ids) {
        try {
            JSONArray now = all(c, profile), keep = new JSONArray();
            for (int i = 0; i < now.length(); i++) {
                if (!ids.contains(now.getJSONObject(i).optString("id"))) keep.put(now.get(i));
            }
            prefs(c).edit().putString(key(profile), keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Tømmer køen (kun til vedligehold fra adb, se FeedProvider). */
    static synchronized void clear(Context c) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (String k : new java.util.ArrayList<>(prefs(c).getAll().keySet())) if (k.startsWith("actions")) e.remove(k);
        e.apply();
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
