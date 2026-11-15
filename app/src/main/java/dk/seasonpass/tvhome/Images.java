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

    /** Filen for et billede; henter det først, hvis det er en adresse, der ikke er hentet. Kaldes i baggrunden. */
    static File local(File feedDir, String ref) {
        if (!isUrl(ref)) return new File(feedDir, ref);
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
