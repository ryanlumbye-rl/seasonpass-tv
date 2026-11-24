package dk.seasonpass.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hvilke apps der står i rækken "Dine apps" på forsiden (0.5.0). Uden et valg: streaming-tjenesterne, der er installeret
 * (DEFAULT). Så snart man føjer en til eller fjerner en, gemmes hele listen i den rækkefølge, den står i; nye apps kommer sidst.
 */
final class HomeApps {

    private HomeApps() { }

    /** Dine tjenester først, i den rækkefølge mockuppen viser dem. */
    static final List<String> DEFAULT = Arrays.asList(
            "dk.tv2.tv2playtv", "com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom", "com.viaplay.android", "com.wbd.stream", "dk.dr.tvplayer",
            "com.apple.atve.androidtv.appletv");

    /** Apps til fjernsynet (leanback), undtagen SeasonPass selv. */
    static List<ResolveInfo> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> out = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (!r.activityInfo.packageName.equals(c.getPackageName())) out.add(r);
        }
        return out;
    }

    /** Pakkerne på forsiden, i rækkefølge (også dem, der ikke er installeret lige nu). */
    static List<String> list(Context c) {
        String saved = Prefs.sp(c).getString("home_apps", null);
        if (saved != null) {
            try {
                JSONArray a = new JSONArray(saved);
                List<String> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
                return out;
            } catch (Exception ignored) { }
        }
        List<String> out = new ArrayList<>();
        for (ResolveInfo r : installed(c)) if (DEFAULT.contains(r.activityInfo.packageName)) out.add(r.activityInfo.packageName);
        out.sort((a, b) -> DEFAULT.indexOf(a) - DEFAULT.indexOf(b));
        return out;
    }

    static boolean has(Context c, String pkg) { return list(c).contains(pkg); }

    /** Føjer til (sidst) eller fjerner. Returnerer true, hvis appen nu står på forsiden. */
    static boolean toggle(Context c, String pkg) {
        List<String> now = list(c);
        boolean on = !now.remove(pkg);
        if (on) now.add(pkg);
        Prefs.sp(c).edit().putString("home_apps", new JSONArray(now).toString()).apply();
        return on;
    }

    /** Flytter en app et sted til venstre (-1) eller højre (+1) i rækken, forbi det næste installerede felt. */
    static void move(Context c, String pkg, int delta) {
        List<String> now = list(c);
        java.util.Set<String> have = new java.util.HashSet<>();
        for (ResolveInfo r : installed(c)) have.add(r.activityInfo.packageName);
        int i = now.indexOf(pkg);
        if (i < 0) return;
        for (int j = i + delta; j >= 0 && j < now.size(); j += delta) {
            if (have.contains(now.get(j))) {
                java.util.Collections.swap(now, i, j);
                Prefs.sp(c).edit().putString("home_apps", new JSONArray(now).toString()).apply();
                return;
            }
        }
    }

    /** Antal apps på forsiden, der faktisk er installeret. */
    static int count(Context c) {
        int n = 0;
        List<String> l = list(c);
        for (ResolveInfo r : installed(c)) if (l.contains(r.activityInfo.packageName)) n++;
        return n;
    }
}
