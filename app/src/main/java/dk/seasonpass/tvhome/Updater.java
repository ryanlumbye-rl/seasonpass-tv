package dk.seasonpass.tvhome;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Ny version: spørger GitHub Releases, om der er en nyere APK, og kan hente og installere den (efter tjek af SHA-256).
 * Opdateringen er signeret med samme nøgle, så Android lægger den oven på den gamle og beholder parringen.
 * Slået fra, hvis REPO er tom.
 */
final class Updater {

    private Updater() { }

    /** "bruger/repo" på GitHub, hvor Releases med SeasonPass-TV.apk ligger. Tom = ingen opdateringstjek. */
    static final String REPO = "ryanlumbye-rl/seasonpass-tv";
    static final String API_OVERRIDE = "";   // kun til prøver mod en lokal server

    static final String APK = "SeasonPass-TV.apk";
    static final long CHECK_EVERY_MS = 12 * 3600 * 1000L;

    static final class Info {
        String version, apkUrl, shaUrl, notes;
    }

    static boolean enabled() { return !REPO.isEmpty() || !API_OVERRIDE.isEmpty(); }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Nyeste udgivelse, hvis den er nyere end den installerede; ellers null. Kaldes i baggrunden. */
    static Info check(Context c) throws Exception {
        if (!enabled()) return null;
        String api = API_OVERRIDE.isEmpty() ? "https://api.github.com/repos/" + REPO + "/releases/latest" : API_OVERRIDE;
        HttpURLConnection h = (HttpURLConnection) new URL(api).openConnection();
        h.setConnectTimeout(10000);
        h.setReadTimeout(15000);
        h.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (h.getResponseCode() != 200) return null;
            JSONObject j = new JSONObject(new String(readAll(h.getInputStream()), StandardCharsets.UTF_8));
            Info i = new Info();
            i.version = j.optString("tag_name").replaceFirst("^v", "");
            i.notes = j.isNull("body") ? "" : j.optString("body");
            JSONArray as = j.optJSONArray("assets");
            for (int k = 0; as != null && k < as.length(); k++) {
                JSONObject a = as.getJSONObject(k);
                if (a.optString("name").equals(APK)) i.apkUrl = a.optString("browser_download_url");
                if (a.optString("name").equals(APK + ".sha256")) i.shaUrl = a.optString("browser_download_url");
            }
            if (i.apkUrl == null || i.shaUrl == null || !newer(i.version, installedVersion(c))) return null;
            return i;
        } finally {
            h.disconnect();
        }
    }

    /** "0.3.0" er nyere end "0.2.0"; tal sammenlignes ét ad gangen, ikke som tekst. */
    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-]"), y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return 0; }
    }

    /** Henter APK'en, tjekker SHA-256 og afleverer den til Android; en bekræftelse vises på skærmen. Kaldes i baggrunden. */
    static void install(Context c, Info info) throws Exception {
        File dir = new File(c.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("kunne ikke oprette mappe");
        File apk = new File(dir, APK);
        String want = new String(download(info.shaUrl, null), StandardCharsets.UTF_8).trim().split("\\s+")[0].toLowerCase();
        download(info.apkUrl, apk);
        if (!sha256(apk).equals(want)) {
            apk.delete();
            throw new IllegalStateException("SHA-256 passer ikke; opdateringen afvist");
        }
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(c.getPackageName());
        int id = pi.createSession(p);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("apk", 0, apk.length())) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent cb = new Intent(c, UpdateReceiver.class).setPackage(c.getPackageName());
            PendingIntent pend = PendingIntent.getBroadcast(c, id, cb, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pend.getIntentSender());
        }
    }

    private static byte[] download(String url, File to) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();   // GitHub omdirigerer til en anden vært
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setInstanceFollowRedirects(true);
        try {
            if (h.getResponseCode() != 200) throw new IllegalStateException("HTTP " + h.getResponseCode());
            if (to == null) return readAll(h.getInputStream());
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            return null;
        } finally {
            h.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static String sha256(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) d.update(buf, 0, n);
        }
        StringBuilder s = new StringBuilder();
        for (byte x : d.digest()) s.append(String.format("%02x", x));
        return s.toString();
    }
}
