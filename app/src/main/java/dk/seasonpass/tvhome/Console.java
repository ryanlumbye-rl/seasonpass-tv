package dk.seasonpass.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;

/**
 * GTA VI i pauseskærmen (0.11.0): et easter egg. Mærket ved siden af versionsnummeret i Indstillinger låser det op;
 * så kan det slås til og fra. Slidet viser spilletid og trofæer, hvis telefonen har sendt dem (feltet "psn" i feeden,
 * hentet fra PlayStation Network af SeasonPass-appen efter login dér); fjernsynet gemmer ingen login. Uden tal vises
 * kun navnet. Baggrunden er en tegnet solnedgang; ligger der en billedfil (console.jpg) i appens mappe, bruges den.
 * Intet billede fra spillet er med i appen.
 */
final class Console {

    private Console() { }

    static File dir(Context c) { return c.getApplicationContext().getExternalFilesDir(null); }

    /** Egne billeder på fjernsynet: console.jpg, console-2.jpg, console-3.jpg ... i appens mappe (console.jpg først). */
    static java.util.List<File> images(Context c) {
        java.util.List<File> out = new java.util.ArrayList<>();
        File d = dir(c);
        File[] all = d == null ? null : d.listFiles();
        if (all == null) return out;
        for (File f : all) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (f.isFile() && n.startsWith("console") && n.endsWith(".jpg")) out.add(f);
        }
        java.util.Collections.sort(out, (a, b) -> {
            boolean pa = a.getName().equalsIgnoreCase("console.jpg"), pb = b.getName().equalsIgnoreCase("console.jpg");
            if (pa != pb) return pa ? -1 : 1;
            String x = a.getName(), y = b.getName();
            return x.length() != y.length() ? x.length() - y.length() : x.compareTo(y);   // console-2 før console-10
        });
        return out;
    }

    /** Låst op med mærket i Indstillinger. */
    static boolean unlocked(Context c) { return Prefs.sp(c).getBoolean("gta_egg", false); }

    static void unlock(Context c) { Prefs.sp(c).edit().putBoolean("gta_egg", true).apply(); }

    static boolean enabled(Context c) { return unlocked(c) && Prefs.sp(c).getBoolean("console", false); }

    /** Valgt fast billede (0, 1, ...) eller -1 for "bland billederne". */
    static int fixedImage(Context c) {
        String v = Prefs.sp(c).getString("gta_image", "mix");
        try { return Math.max(0, Integer.parseInt(v) - 1); } catch (Exception e) { return -1; }
    }

    static String title(Context c) { return "GTA VI"; }

    /** Tallene for spillet fra telefonens feed (psn.gta6); null, hvis telefonen ikke har sendt nogen. */
    static JSONObject stats(Context c) {
        try {
            File f = new File(FeedProvider.dir(c), "feed.json");
            if (!f.isFile()) return null;
            JSONObject feed = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            JSONObject psn = feed.optJSONObject("psn");
            return psn == null ? null : psn.optJSONObject("gta6");
        } catch (Exception e) {
            return null;
        }
    }

    /** "PS5  ·  8 t 10 min spillet  ·  1 % trofæer  ·  sidst i dag"; uden tal fra telefonen bare "PS5". */
    static String line(Context c, Strings tx) {
        JSONObject g = stats(c);
        if (g == null) return "PS5";
        StringBuilder b = new StringBuilder("PS5");
        long played = g.optLong("playedMs");
        int pct = g.optInt("trophyPct", -1);
        long last = g.optLong("lastPlayedAt");
        if (played > 0) b.append("  ·  ").append(tx.t("tvhome.psnPlayed", "len", length(tx, played)));
        if (pct >= 0) b.append("  ·  ").append(tx.t("tvhome.psnTrophies", "pct", String.valueOf(pct)));
        if (last > 0) b.append("  ·  ").append(tx.t("tvhome.psnLast", "ago", ago(tx, last, System.currentTimeMillis())));
        return b.toString();
    }

    static String length(Strings tx, long ms) {
        long min = Math.max(1, ms / 60000);
        return min < 60 ? tx.t("tvhome.lenM", "m", String.valueOf(min))
                : tx.t("tvhome.lenHM", "h", String.valueOf(min / 60), "m", String.valueOf(min % 60));
    }

    private static String ago(Strings tx, long then, long now) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        a.setTimeInMillis(then);
        b.setTimeInMillis(now);
        for (Calendar k : new Calendar[]{a, b}) {
            k.set(Calendar.HOUR_OF_DAY, 0);
            k.set(Calendar.MINUTE, 0);
            k.set(Calendar.SECOND, 0);
            k.set(Calendar.MILLISECOND, 0);
        }
        long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
        if (days <= 0) return tx.t("tvhome.agoToday");
        if (days == 1) return tx.t("tvhome.agoYesterday");
        return tx.t("tvhome.agoDays", "n", String.valueOf(days));
    }
}
