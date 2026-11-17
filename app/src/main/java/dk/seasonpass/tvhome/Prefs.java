package dk.seasonpass.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/** Indstillinger på tv'et (valgt i SettingsActivity) og små hjælpere til systemindstillinger, appen selv må skrive. */
final class Prefs {

    private Prefs() { }

    static final String[] CONTENT_KEYS = {"mix", "next", "soon", "discover", "clock"};
    /** Tekstnøgler (se Strings), ikke tekster: sproget kommer fra telefonen. */
    static final String[] CONTENT_LABELS = {"tvhome.contentMix", "tvhome.contentNext", "home.soon", "tvhome.discover", "tvhome.contentClock"};
    static final String[] CLOCK_KEYS = {"big", "small", "off"};
    static final String[] CLOCK_LABELS = {"tvhome.clockBig", "tvhome.clockSmall", "tvhome.off"};

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    /** "Use without SeasonPass": the home screen shows only the date and your apps, the screensaver only the clock, and nothing listens for a phone. */
    static boolean standalone(Context c) { return sp(c).getBoolean("standalone", false); }
    static void setStandalone(Context c, boolean on) { sp(c).edit().putBoolean("standalone", on).apply(); }

    static String content(Context c) { return standalone(c) ? "clock" : sp(c).getString("saver_content", "mix"); }
    static String clock(Context c) { return sp(c).getString("saver_clock", "big"); }
    static boolean music(Context c) { return sp(c).getBoolean("saver_music", true); }

    /** Nyheder på pauseskærmen (News): slået fra som standard; kilden er en tjenestes id, tom = landets første. */
    static boolean news(Context c) { return sp(c).getBoolean("saver_news", false); }
    static String newsSource(Context c) { return sp(c).getString("news_source", ""); }

    /** Brugerens fornavn fra telefonen (feedets "name"), til avataren. Tom, hvis telefonen ikke har sendt et. */
    static String name(Context c) { return sp(c).getString("name", ""); }
    static void setName(Context c, String name) {
        if (!name.equals(name(c))) sp(c).edit().putString("name", name).apply();
    }

    /** Næste værdi i en liste (rundt). */
    static String cycle(String[] keys, String now) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(now)) return keys[(i + 1) % keys.length];
        return keys[0];
    }

    static String label(String[] keys, String[] labels, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return labels[i];
        return labels[0];
    }

    // --- Systemindstillinger. Appen må kun skrive dem, hvis adb har givet WRITE_SECURE_SETTINGS (install.sh gør det). ---

    static ComponentName dream(Context c) { return new ComponentName(c, SeasonDream.class); }
    static ComponentName listener(Context c) { return new ComponentName(c, MediaListener.class); }

    static boolean dreamOn(Context c) {
        String v = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        return has(v, dream(c));
    }

    /** Er appen godkendt som notifikationslytter (giver adgang til, hvad der spilles)? Systemets egen besked, ikke indstillingsteksten. */
    static boolean listenerOn(Context c) {
        return c.getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(listener(c));
    }

    /** Systemet gemmer en komponent både som "pakke/pakke.Klasse" og som "pakke/.Klasse"; begge tæller. */
    private static boolean has(String list, ComponentName c) {
        return list != null && (list.contains(c.flattenToString()) || list.contains(c.flattenToShortString()));
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Sætter SeasonPass som pauseskærm (til) eller Googles standard (fra). Returnerer false uden tilladelsen. */
    static boolean setDream(Context c, boolean on) {
        if (!canWriteSecure(c)) return false;
        android.content.ContentResolver r = c.getContentResolver();
        Settings.Secure.putString(r, "screensaver_components", on ? dream(c).flattenToString() : "com.android.dreams.basic/.Colors");
        Settings.Secure.putInt(r, "screensaver_enabled", 1);
        return true;
    }
}
