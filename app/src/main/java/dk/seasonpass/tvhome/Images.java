package dk.seasonpass.tvhome;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Billeder i feed version 2 er adresser (TMDB, TVmaze). De hentes én gang til feed/img/<sha1>.jpg;
 * SyncServer rydder dem, som en ny feed ikke længere nævner. Feed version 1 (fra Mac'en) har
 * allerede filerne liggende som "img/…".
 */
final class Images {

    private Images() { }

    static boolean isUrl(String s) {
        return s.startsWith("https://") || s.startsWith("http://");
    }

    static String fileName(String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", h[i]));
            return b + ".jpg";
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode()) + ".jpg";
        }
    }

    /**
     * Egne billeder i stedet for feedets: en fil med samme navn som billedadressens (fx vLBX...jpg for
     * https://image.tmdb.org/t/p/w1280/vLBX...jpg) i covers/ i appens mappe på fjernsynet vinder over feedets billede.
     * Bruges fx til et billede uden logo. Gælder alle steder, billedet vises.
     */
    static volatile File covers;

    private static File override(String url) {
        File d = covers;
        if (d == null || !isUrl(url)) return null;
        String name = url.substring(url.lastIndexOf('/') + 1);
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        if (!name.matches("[A-Za-z0-9_-]{4,80}\\.jpg")) return null;
        File f = new File(d, name);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** Filen for et billede; henter det først, hvis det er en adresse, der ikke er hentet. Kaldes i baggrunden. */
    static File local(File feedDir, String ref) {
        if (!isUrl(ref)) return new File(feedDir, ref);
        File mine = override(ref);
        if (mine != null) return mine;
        File dir = new File(feedDir, "img");
        File f = new File(dir, fileName(ref));
        if (f.exists()) return f;
        if (!dir.isDirectory() && !dir.mkdirs()) return f;
        File tmp = new File(dir, f.getName() + ".part");
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(ref).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            if (c.getResponseCode() != 200) return f;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception e) {
            tmp.delete();
        } finally {
            if (c != null) c.disconnect();
        }
        return f;
    }
}
