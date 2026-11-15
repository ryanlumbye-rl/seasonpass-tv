package dk.seasonpass.tvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Feed version 2 (Sync til fjernsyn, 25-10-2026): telefonen sender hver aktiv serie med dens usete,
 * udkomne afsnit (ready) og de kommende (upcoming) med udgivelsestid. Her regnes rækkerne ud efter
 * uret, så et afsnit rykker fra "Kommer snart" til "Næste afsnit", når det udkommer, også uden at
 * telefonen er åben. Resultatet har samme form som version 1, så skærmen ikke skal kende forskel.
 */
final class Feed {

    private Feed() { }

    /** Tjeneste (SeasonPass' ServiceKey) → appen på fjernsynet, der viser den. */
    static final Map<String, String> APP = new HashMap<>();
    static {
        APP.put("tv2", "dk.tv2.tv2playtv");
        APP.put("netflix", "com.netflix.ninja");
        APP.put("apple", "com.apple.atve.androidtv.appletv");
        APP.put("prime", "com.amazon.amazonvideo.livingroom");
        APP.put("disney", "com.disney.disneyplus");
        APP.put("hbo", "com.wbd.stream");
        APP.put("viaplay", "com.viaplay.android");
        APP.put("drtv", "dk.dr.tvplayer");
    }

    /**
     * Billed-rettelser, kun på fjernsynet (25-10-2026). Telefonen sender seriens tekstfri
     * baggrundsbillede; for danske serier er det ofte et gammelt billede, mens appen viser årets
     * danske plakat. Her står den liggende udgave af den plakat. Nøglen er seriens id fra feeden.
     */
    static final Map<String, String> IMAGE = new HashMap<>();
    static {
        // Robinson Ekspeditionen: årets plakat (værten i solnedgang), ikke gruppebilledet fra en gammel sæson.
        IMAGE.put("tvmaze:24921", "https://image.tmdb.org/t/p/w1280/vLBXHFQNVZZK4gyikZsCmMDGJSD.jpg");
    }

    static final TimeZone CPH = TimeZone.getTimeZone("Europe/Copenhagen");
    static final Locale DA = new Locale("da", "DK");
    private static final long DAY = 86400000L;

    /** Version 2 → version 1-formen (hero, next, soon), regnet ud for tidspunktet `now`. */
    static JSONObject derive(JSONObject v2, long now) throws Exception {
        JSONArray shows = v2.optJSONArray("shows");
        List<JSONObject[]> ready = new ArrayList<>();   // {show, første klare afsnit}
        List<Long> readyKey = new ArrayList<>();
        List<Object[]> soon = new ArrayList<>();        // {show, afsnit, ms}
        Map<String, JSONObject> heroInfo = new HashMap<>();
        for (int i = 0; shows != null && i < shows.length(); i++) {
            JSONObject s = shows.getJSONObject(i);
            List<JSONObject> have = new ArrayList<>();
            JSONArray r = s.optJSONArray("ready");
            for (int k = 0; r != null && k < r.length(); k++) have.add(r.getJSONObject(k));
            JSONObject nextUp = null;
            JSONArray u = s.optJSONArray("upcoming");
            for (int k = 0; u != null && k < u.length(); k++) {
                JSONObject e = u.getJSONObject(k);
                if (e.optLong("ms") <= now) have.add(e);        // udkommet siden telefonen sendte
                else if (nextUp == null) nextUp = e;
            }
            if (!have.isEmpty()) {
                JSONObject card = card(s);
                JSONObject first = have.get(0);
                card.put("episode", ep(first));
                card.put("episodeName", first.optString("name", ""));
                card.put("count", have.size());
                ready.add(new JSONObject[]{card, first});
                readyKey.add(first.optLong("ms"));
                JSONObject h = card(s);
                h.put("season", "Sæson " + first.optInt("s"));
                h.put("line", ep(first) + " er klar");
                h.put("overview", s.optString("overview", ""));
                heroInfo.put(s.optString("id"), h);
            }
            if (nextUp != null) {
                JSONObject card = card(s);
                card.put("episode", ep(nextUp));
                card.put("when", whenText(nextUp.optLong("ms"), nextUp.optBoolean("timeKnown"), now));
                soon.add(new Object[]{card, nextUp, nextUp.optLong("ms")});
                if (!heroInfo.containsKey(s.optString("id"))) {
                    JSONObject h = card(s);
                    h.put("season", "Sæson " + nextUp.optInt("s"));
                    h.put("line", whenText(nextUp.optLong("ms"), nextUp.optBoolean("timeKnown"), now));
                    h.put("overview", s.optString("overview", ""));
                    heroInfo.put(s.optString("id"), h);
                }
            }
        }
        // Samme rækkefølge som telefonens forside (src/app/(tabs)/index.tsx): Næste afsnit efter det første
        // usete afsnits udgivelse, nyeste først; Kommer snart efter næste afsnit, tidligste først. Sorteringen
        // er stabil, så serier med samme tidspunkt står i telefonens rækkefølge, som de også gør der.
        Integer[] order = new Integer[ready.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(readyKey.get(b), readyKey.get(a)));
        JSONArray next = new JSONArray();
        JSONArray hero = new JSONArray();
        for (Integer i : order) {
            JSONObject c = ready.get(i)[0];
            next.put(c);
            if (hero.length() < 5) hero.put(heroInfo.get(c.optString("id")));
        }
        soon.sort((a, b) -> Long.compare((Long) a[2], (Long) b[2]));
        JSONArray soonArr = new JSONArray();
        for (Object[] o : soon) {
            soonArr.put(o[0]);
            if (hero.length() < 5 && ready.isEmpty()) hero.put(heroInfo.get(((JSONObject) o[0]).optString("id")));
        }
        JSONObject out = new JSONObject();
        out.put("version", 1);
        out.put("hero", hero);
        out.put("next", next);
        out.put("soon", soonArr);
        // Serier på pause (feed version 2 med "paused"): telefonen sender dem i sin egen rækkefølge.
        JSONArray pausedArr = new JSONArray();
        JSONArray p = v2.optJSONArray("paused");
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject s = p.getJSONObject(i);
            JSONObject c = card(s);
            JSONArray r = s.optJSONArray("ready");
            c.put("count", r == null ? 0 : r.length());
            pausedArr.put(c);
        }
        out.put("paused", pausedArr);
        // Opdag serier (valgfri): telefonen sender færdige forslag; tv'et viser dem, som de er.
        JSONArray discoverArr = new JSONArray();
        JSONArray d = v2.optJSONArray("discover");
        for (int i = 0; d != null && i < d.length(); i++) {
            JSONObject s = d.getJSONObject(i);
            JSONObject c = card(s);
            // Androids optString giver teksten "null" for JSON null; telefonen sender null uden beskrivelse.
            c.put("discover", true);
            c.put("overview", s.isNull("overview") ? "" : s.optString("overview", ""));
            c.put("because", s.isNull("because") ? "" : s.optString("because", ""));
            discoverArr.put(c);
        }
        out.put("discover", discoverArr);
        return out;
    }

    private static JSONObject card(JSONObject s) throws Exception {
        JSONObject c = new JSONObject();
        c.put("id", s.optString("id"));
        c.put("title", s.optString("title"));
        c.put("service", s.optString("service"));
        String app = APP.get(s.optString("host"));
        c.put("app", app == null ? JSONObject.NULL : app);
        c.put("image", image(s));
        return c;
    }

    /** Seriens billede: en rettelse fra IMAGE, ellers det, telefonen sendte. */
    static String image(JSONObject s) {
        String fix = IMAGE.get(s.optString("id"));
        return fix != null ? fix : s.optString("image", "");
    }

    private static String ep(JSONObject e) {
        return "S" + e.optInt("s") + " E" + (e.has("n") && !e.isNull("n") ? e.optInt("n") : "?");
    }

    /** Samme danske tekster som SeasonPass' whenText (src/time.ts). */
    static String whenText(long ms, boolean timeKnown, long now) {
        int d = dayDiff(now, ms);
        String t = timeKnown ? " kl. " + fmt("HH:mm", ms) : "";
        if (ms <= now && timeKnown) return "Klar nu";
        if (d == 0) return "I dag" + t;
        if (d == 1) return "I morgen" + t;
        if (d > 1 && d < 7) {
            String w = fmt("EEEE", ms);
            return Character.toUpperCase(w.charAt(0)) + w.substring(1) + t;
        }
        return fmt("d. MMM", ms) + t;
    }

    private static int dayDiff(long from, long to) {
        return Math.round((startOfDay(to) - startOfDay(from)) / (float) DAY);
    }

    private static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance(CPH);
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String fmt(String pattern, long ms) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, DA);
        f.setTimeZone(CPH);
        return f.format(new Date(ms));
    }
}
