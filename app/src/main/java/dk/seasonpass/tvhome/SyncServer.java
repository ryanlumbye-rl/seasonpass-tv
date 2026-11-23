package dk.seasonpass.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Sync til fjernsyn" (25-10-2026): SeasonPass på telefonen sender sit overblik hertil over Wi-Fi.
 *
 *   GET  /hello        {"app":"seasonpass-tv"}            telefonen leder efter fjernsynet
 *   POST /pair/start                                      fjernsynet viser en kode på fire cifre
 *   POST /pair         {"code":"4821"} → {"token":"…"}    telefonen husker nøglen
 *   POST /feed         Authorization: Bearer <nøgle>      feed version 2, se Feed
 *   GET  /actions      Authorization: Bearer <nøgle>      {"actions":[…]}: Har set den / Ikke for mig, se Actions
 *   POST /actions/ack  {"ids":["…"]}                       telefonen har udført dem; de fjernes fra køen
 *
 * Kun en parret telefon kan skrive. Koden gælder i to minutter og fem forsøg. Serveren kører i den
 * proces, PowerOnService holder i live, så den svarer også mens Netflix er fremme.
 */
final class SyncServer {

    static final int PORT = 8787;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final long CODE_TTL = 2 * 60 * 1000L;

    /** Skærmen lytter med, mens den er fremme. */
    interface Listener {
        void onFeed();
        void onProfileRequest(); // en anden telefon end den aktive har åbnet SeasonPass: spørg, om der skal skiftes
        void onPairCode(String code); // null: skjul koden igen
    }

    private static SyncServer instance;
    private static Listener listener;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final SecureRandom random = new SecureRandom();
    private String code;
    private long codeUntil;
    private int attempts;

    private SyncServer(Context c) {
        app = c.getApplicationContext();
    }

    static synchronized void start(Context c) {
        if (instance != null || Prefs.standalone(c)) return;
        instance = new SyncServer(c);
        Thread t = new Thread(instance::loop, "seasonpass-sync");
        t.setDaemon(true);
        t.start();
    }

    static void setListener(Listener l) {
        listener = l;
    }

    private void loop() {
        try (ServerSocket server = new ServerSocket(PORT)) {
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            synchronized (SyncServer.class) { instance = null; } // næste start() prøver igen
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            if (Prefs.standalone(app)) return;   // switched to "Use without SeasonPass" while running: refuse everything
            sock.setSoTimeout(10000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String request = line(in);
            if (request == null) return;
            String[] parts = request.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], path = parts[1];
            int length = 0;
            String auth = "";
            for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
                int c = h.indexOf(':');
                if (c < 0) continue;
                String name = h.substring(0, c).trim().toLowerCase(Locale.ROOT), value = h.substring(c + 1).trim();
                if (name.equals("content-length")) length = Integer.parseInt(value);
                if (name.equals("authorization")) auth = value;
            }
            if (length > MAX_BODY) { reply(sock, 413, "{}"); return; }
            byte[] body = new byte[length];
            for (int off = 0; off < length; ) {
                int n = in.read(body, off, length - off);
                if (n < 0) break;
                off += n;
            }
            String text = new String(body, StandardCharsets.UTF_8);

            if (method.equals("GET") && path.equals("/hello")) {
                reply(sock, 200, "{\"app\":\"seasonpass-tv\",\"version\":1}");
            } else if (method.equals("POST") && path.equals("/pair/start")) {
                startPairing();
                reply(sock, 200, "{\"ok\":true}");
            } else if (method.equals("POST") && path.equals("/pair")) {
                String token = pair(new JSONObject(text).optString("code"));
                reply(sock, token == null ? 403 : 200, token == null ? "{}" : new JSONObject().put("token", token).toString());
            } else if (method.equals("POST") && path.equals("/feed")) {
                if (!auth.startsWith("Bearer ") || !tokens().contains(auth.substring(7))) { reply(sock, 401, "{}"); return; }
                JSONObject feed = new JSONObject(text);
                if (feed.optInt("version") != 2) { reply(sock, 400, "{}"); return; }
                boolean active = write(feed, Profiles.id(auth.substring(7)));
                reply(sock, 200, "{\"ok\":true}");
                if (active) main.post(() -> { if (listener != null) listener.onFeed(); });
                else if (!Profiles.pending(app).isEmpty()) main.post(() -> { if (listener != null) listener.onProfileRequest(); });
            } else if (method.equals("GET") && path.equals("/actions")) {
                if (!authorized(auth)) { reply(sock, 401, "{}"); return; }
                reply(sock, 200, new JSONObject().put("actions", Actions.all(app, Profiles.id(auth.substring(7)))).toString());
            } else if (method.equals("POST") && path.equals("/actions/ack")) {
                if (!authorized(auth)) { reply(sock, 401, "{}"); return; }
                JSONArray ids = new JSONObject(text).optJSONArray("ids");
                Set<String> done = new HashSet<>();
                for (int i = 0; ids != null && i < ids.length(); i++) done.add(ids.optString(i));
                Actions.ack(app, Profiles.id(auth.substring(7)), done);
                reply(sock, 200, "{\"ok\":true}");
            } else {
                reply(sock, 404, "{}");
            }
        } catch (Exception ignored) {
            // En afbrudt eller forkert forespørgsel må aldrig vælte startskærmen.
        }
    }

    private boolean authorized(String auth) {
        return auth.startsWith("Bearer ") && tokens().contains(auth.substring(7));
    }

    private synchronized void startPairing() {
        code = String.format(Locale.ROOT, "%04d", random.nextInt(10000));
        codeUntil = System.currentTimeMillis() + CODE_TTL;
        attempts = 0;
        String shown = code;
        main.post(() -> {
            if (listener != null) listener.onPairCode(shown);
            else Toast.makeText(app, Strings.of(app).t("tvhome.pairCode", "code", shown), Toast.LENGTH_LONG).show();
        });
    }

    private synchronized String pair(String given) {
        if (code == null || System.currentTimeMillis() > codeUntil || attempts >= 5) return null;
        if (!code.equals(given)) { attempts++; return null; }
        code = null;
        byte[] b = new byte[16];
        random.nextBytes(b);
        StringBuilder t = new StringBuilder();
        for (byte x : b) t.append(String.format(Locale.ROOT, "%02x", x));
        Set<String> all = new HashSet<>(tokens());
        all.add(t.toString());
        prefs().edit().putStringSet("tokens", all).apply();
        main.post(() -> { if (listener != null) listener.onPairCode(null); });
        return t.toString();
    }

    private Set<String> tokens() {
        return prefs().getStringSet("tokens", new HashSet<>());
    }

    private SharedPreferences prefs() {
        return app.getSharedPreferences("sync", Context.MODE_PRIVATE);
    }

    /**
     * Gemmer telefonens feed som dens profils fil, og som feed.json, hvis profilen er (eller nu bliver) den aktive.
     * Rydder billeder, ingen profil længere bruger. Returnerer true, hvis skærmen skal genindlæse.
     */
    private boolean write(JSONObject feed, String profile) throws Exception {
        File dir = FeedProvider.dir(app);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("ingen feed-mappe");
        File tmp = new File(dir, "profile-" + profile + ".json.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(feed.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(Profiles.file(app, profile))) throw new IllegalStateException("kunne ikke gemme feed");
        boolean active = Profiles.onFeed(app, profile, feed.isNull("name") ? "" : feed.optString("name", "").trim());
        Set<String> keep = new HashSet<>();
        for (String id : Profiles.all(app)) {
            try {
                keepImages(new JSONObject(new String(java.nio.file.Files.readAllBytes(Profiles.file(app, id).toPath()), StandardCharsets.UTF_8)), keep);
            } catch (Exception ignored) { }   // en ulæselig profil må ikke stoppe oprydningen; billederne tages ikke med
        }
        File[] imgs = new File(dir, "img").listFiles();
        if (imgs != null) for (File f : imgs) if (!keep.contains(f.getName())) f.delete();
        return active;
    }

    private static void keepImages(JSONObject feed, Set<String> keep) throws Exception {
        for (String list : new String[]{"shows", "paused", "discover"}) {
            JSONArray items = feed.optJSONArray(list);
            for (int i = 0; items != null && i < items.length(); i++) {
                String url = Feed.image(items.getJSONObject(i));   // med rettelserne, så de ikke slettes
                if (!url.isEmpty()) keep.add(Images.fileName(url));
            }
        }
    }

    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c = in.read(); c >= 0; c = in.read()) {
            if (c == '\n') return b.toString("UTF-8").replace("\r", "");
            if (b.size() > 8192) return null;
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static void reply(Socket s, int status, String json) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.US_ASCII));
        o.write(body);
        o.flush();
    }
}
