package dk.seasonpass.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Partytilstand (0.12.x): kun til eget brug, skjult bag GTA-mærket i Indstillinger. Fuld skærm med det, der spilles
 * (cover, titel, tid), en levende farveflade trukket fra coveret og en visualizer, der reagerer på lyden, hvis
 * fjernsynet selv spiller den (lydfangst efter ja til en skærmoptagelses-dialog); ellers bevæger den sig i fast tempo.
 * OK skifter visning (glas, orb, kromatisk linje), Tilbage afslutter. Gæster: navne i party.txt (en pr. linje) i
 * appens mappe og eventuelt party/Navn.jpg. Intet hentes fra sociale medier.
 */
public class PartyActivity extends Activity {

    private PartyView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new PartyView(this);
        setContentView(view);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        } else {
            askCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) askCapture();
    }

    /** Lydfangst kræver et ja til systemets skærmoptagelses-dialog; siger man nej, tegnes animationen i stedet. */
    private void askCapture() {
        if (PartyCapture.running) return;
        try {
            MediaProjectionManager m = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            startActivityForResult(m.createScreenCaptureIntent(), 7);
        } catch (Exception e) {
            android.util.Log.w("Party", "ingen lydfangst: " + e);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 7 && res == RESULT_OK && data != null) PartyCapture.start(this, res, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.hideSystem();
        view.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PartyCapture.halt(this);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            view.next(1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            view.next(-1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    // ------------------------------------------------------------------------------------------------------------

    private static final class Guest {
        String name;
        Bitmap photo;
        float phase, speed, ax, ay;
    }

    private static final class PartyView extends View {

        private static final int B = PartyCapture.BANDS;

        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService io = Executors.newSingleThreadExecutor();
        private final Random rnd = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path(), clip = new Path();
        private final android.graphics.Rect src = new android.graphics.Rect();
        private final float[] bands = new float[B];
        private final float[] target = new float[B];
        private final float[] hsv = new float[3];
        private final List<Guest> guests = new ArrayList<>();
        private final Typeface bold, semibold;
        private final PorterDuffXfermode screen = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);
        private final PorterDuffXfermode add = new PorterDuffXfermode(PorterDuff.Mode.ADD);
        private Bitmap grain;
        private Paint grainPaint;

        private long started, modeAt;
        private float beat, bass, bassAvg, hue = 280;
        private int mode;
        private String shownTrack = "";
        private String title = "", artist = "";
        private Bitmap cover;
        private float coverHue = -1;
        private MediaController ctl;
        private long duration;
        private boolean running;
        private long coverToken, coverTry;

        PartyView(Context c) {
            super(c);
            bold = c.getResources().getFont(R.font.urbanist_bold);
            semibold = c.getResources().getFont(R.font.urbanist_semibold);
            setBackgroundColor(Color.BLACK);
            loadGuests();
            makeGrain();
        }

        void hideSystem() {
            setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY | SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        private float u() { return getWidth() / 960f; }

        /** Filmkorn: en lille støjflade, der gentages og rystes en smule hver frame. Giver farveflader et trykt udtryk. */
        private void makeGrain() {
            int n = 128;
            int[] px = new int[n * n];
            for (int i = 0; i < px.length; i++) {
                int g = rnd.nextInt(256);
                px[i] = Color.argb(255, g, g, g);
            }
            grain = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            grainPaint = new Paint();
            grainPaint.setShader(new android.graphics.BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
            grainPaint.setAlpha(22);
            grainPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.OVERLAY));
        }

        // --- Gæster -------------------------------------------------------------------------------------------

        private void loadGuests() {
            try {
                File dir = Console.dir(getContext());
                File list = new File(dir, "party.txt");
                if (!list.isFile()) return;
                for (String line : new String(Files.readAllBytes(list.toPath()), StandardCharsets.UTF_8).split("\\r?\\n")) {
                    String n = line.trim();
                    if (n.isEmpty() || n.startsWith("#") || guests.size() >= 12) continue;
                    Guest g = new Guest();
                    g.name = n;
                    File f = new File(new File(dir, "party"), n + ".jpg");
                    if (f.isFile()) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 4;
                        g.photo = BitmapFactory.decodeFile(f.getPath(), o);
                    }
                    g.phase = rnd.nextFloat() * 6.28f;
                    g.speed = 0.12f + rnd.nextFloat() * 0.18f;
                    g.ax = 0.3f + rnd.nextFloat() * 0.15f;
                    g.ay = 0.25f + rnd.nextFloat() * 0.15f;
                    guests.add(g);
                }
            } catch (Exception ignored) { }
        }

        // --- Livscyklus ---------------------------------------------------------------------------------------

        void start() {
            running = true;
            started = System.currentTimeMillis();
            main.removeCallbacks(poll);
            main.post(poll);
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
            main.removeCallbacks(poll);
        }

        void next(int d) {
            mode = (mode + d + 3) % 3;
            modeAt = System.currentTimeMillis();
        }

        // --- Det, der spilles ---------------------------------------------------------------------------------

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                update();
                main.postDelayed(this, 800);
            }
        };

        private void update() {
            MediaController c = null;
            try {
                MediaSessionManager m = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
                ComponentName cn = Prefs.listener(getContext());
                MediaController paused = null;
                for (MediaController x : m.getActiveSessions(cn)) {
                    MediaMetadata md = x.getMetadata();
                    if (md == null || title(md) == null) continue;
                    PlaybackState st = x.getPlaybackState();
                    if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                        if (c == null || x.getPackageName().contains("spotify")) c = x;
                    } else if (paused == null) {
                        paused = x;
                    }
                }
                if (c == null) c = paused;   // sat på pause: vis stadig, hvad det var
            } catch (Exception ignored) { }
            ctl = c;
            if (c == null) return;
            MediaMetadata md = c.getMetadata();
            String t = title(md);
            String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
            String key = t + "|" + a;
            if (key.equals(shownTrack)) {
                if (cover == null && System.currentTimeMillis() - coverTry > 5000) loadCover(md);   // coveret kommer ofte lidt senere
                return;
            }
            shownTrack = key;
            title = t == null ? "" : t;
            artist = a == null ? "" : a;
            cover = null;
            coverHue = -1;
            loadCover(md);
        }

        private String title(MediaMetadata md) {
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null || t.isEmpty()) t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            return t == null || t.isEmpty() ? null : t;
        }

        /** Coveret: direkte som billede, ellers fra adressen i metadata (content:// eller https://, som Spotify bruger). */
        private void loadCover(MediaMetadata md) {
            coverTry = System.currentTimeMillis();
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != null) { setCover(art); return; }
            String uri = md.getString(MediaMetadata.METADATA_KEY_ART_URI);
            if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
            if (uri == null) uri = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
            if (uri == null) return;
            final String u = uri;
            final long token = ++coverToken;
            io.execute(() -> {
                Bitmap b = fetch(u);
                if (b != null && token == coverToken) main.post(() -> setCover(b));
            });
        }

        private Bitmap fetch(String uri) {
            try {
                InputStream in;
                if (uri.startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    in = c.getInputStream();
                } else {
                    in = getContext().getContentResolver().openInputStream(Uri.parse(uri));
                }
                if (in == null) return null;
                try (InputStream s = in) {
                    return BitmapFactory.decodeStream(s);
                }
            } catch (Exception e) {
                android.util.Log.w("Party", "cover: " + e);
                return null;
            }
        }

        private void setCover(Bitmap b) {
            cover = b;
            coverHue = hueOf(b);
        }

        /** Den farve i coveret, der fylder mest og er mættet nok; bruges som partyets grundfarve. */
        private float hueOf(Bitmap b) {
            try {
                Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
                float[] bin = new float[12];
                for (int y = 0; y < 24; y++) {
                    for (int x = 0; x < 24; x++) {
                        Color.colorToHSV(s.getPixel(x, y), hsv);
                        if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue;
                        bin[(int) (hsv[0] / 30f) % 12] += hsv[1] * hsv[2];
                    }
                }
                int best = 0;
                for (int i = 1; i < 12; i++) if (bin[i] > bin[best]) best = i;
                return bin[best] == 0 ? -1 : best * 30f + 15f;
            } catch (Exception e) {
                return -1;
            }
        }

        // --- Tegning ------------------------------------------------------------------------------------------

        @Override
        protected void onDraw(Canvas c) {
            long now = System.currentTimeMillis();
            float t = (now - started) / 1000f;
            boolean real = PartyCapture.running && now - PartyCapture.lastSignal < 1500;
            if (real) {
                for (int i = 0; i < B; i++) target[i] = PartyCapture.bands[i];
                float bs = 0;
                for (int i = 0; i < 5; i++) bs += PartyCapture.bands[i];
                bass = bs / 5f;
            } else {
                synth(t);
            }

            // Slag: bassen over sit eget gennemsnit; uden lyd et fast tempo (124 bpm).
            float b;
            if (real) {
                bassAvg += (bass - bassAvg) * 0.06f;
                b = Math.max(0f, (bass - bassAvg) * 3.2f);
            } else {
                float ph = (t * 124f / 60f) % 1f;
                b = (float) Math.pow(Math.max(0f, Math.cos(ph * 6.2832f) * 0.5f + 0.5f), 6);
            }
            beat = Math.min(1f, Math.max(b, beat * 0.86f));
            for (int i = 0; i < B; i++) bands[i] += (target[i] - bands[i]) * (target[i] > bands[i] ? 0.65f : 0.2f);

            float want = coverHue >= 0 ? coverHue : (t * 10f) % 360f;
            float dh = ((want - hue + 540f) % 360f) - 180f;
            hue = (hue + dh * 0.03f + 360f) % 360f;
            if (now - modeAt > 40000 && now - started > 20000) next(1);

            int w = getWidth(), h = getHeight();
            float u = u();
            aurora(c, w, h, t);
            if (mode == 0) { marquee(c, w, h, t, u); capsules(c, w, h, u); }
            else if (mode == 1) orb(c, w, h, t, u);
            else scope(c, w, h, u);
            drawGuests(c, w, h, t, u);
            drawCover(c, w, h, u);
            drawText(c, w, h, u, real);
            drawTime(c, w, h, u);

            // Korn over det hele.
            c.save();
            c.translate(-rnd.nextInt(128), -rnd.nextInt(128));
            c.drawRect(0, 0, w + 128, h + 128, grainPaint);
            c.restore();
            postInvalidateOnAnimation();
        }

        private int col(float hh, float s, float v, int a) {
            hsv[0] = (hh % 360f + 360f) % 360f;
            hsv[1] = s;
            hsv[2] = v;
            return (a << 24) | (Color.HSVToColor(hsv) & 0xFFFFFF);
        }

        /** Aurora: store, bløde farveblobs i nabofarver, der driver rundt og lyser op på slaget. */
        private void aurora(Canvas c, int w, int h, float t) {
            c.drawColor(col(hue + 200, 0.6f, 0.07f, 255));
            p.setXfermode(screen);
            for (int i = 0; i < 4; i++) {
                float x = w * (0.5f + 0.38f * (float) Math.sin(t * (0.13f + i * 0.05f) + i * 1.9f));
                float y = h * (0.5f + 0.34f * (float) Math.cos(t * (0.11f + i * 0.04f) + i * 2.7f));
                float r = h * (0.55f + 0.12f * beat + 0.08f * i);
                int col = col(hue + i * 38 - 40, 0.8f, 0.9f, (int) (110 + 70 * beat));
                p.setShader(new RadialGradient(x, y, r, col, col & 0xFFFFFF, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, p);
            }
            p.setShader(null);
            p.setXfermode(null);
        }

        /** Uden lyd: bølger i bevægelse, løftet af slaget. */
        private void synth(float t) {
            for (int i = 0; i < B; i++) {
                float f = i / (float) B;
                float v = 0.35f + 0.25f * (float) Math.sin(t * (1.3f + f * 3f) + i * 0.7f) + 0.2f * (float) Math.sin(t * 2.1f - i * 0.3f);
                v *= (1.1f - f * 0.5f);
                target[i] = Math.max(0.05f, Math.min(1f, v + beat * 0.35f * (1f - f)));
            }
        }

        /** Kinetisk typografi: titlen i kæmpe omrids, der glider langsomt hen over skærmen bag alt andet. */
        private void marquee(Canvas c, int w, int h, float t, float u) {
            String s = (title.isEmpty() ? "PARTY" : title.toUpperCase(Locale.ROOT)) + "   ";
            text.setTypeface(bold);
            text.setTextSize(260 * u);
            text.setTextAlign(Paint.Align.LEFT);
            text.setStyle(Paint.Style.STROKE);
            text.setStrokeWidth(2.5f * u);
            text.setColor(0x55FFFFFF);
            float tw = Math.max(1f, text.measureText(s));
            float x = -((t * 55f * u) % tw);
            while (x < w) {
                c.drawText(s, x, h * 0.36f + 90 * u, text);
                x += tw;
            }
            text.setStyle(Paint.Style.FILL);
        }

        /** Glaskapsler: halvgennemsigtige piller med lys kant, som liquid glass. */
        private void capsules(Canvas c, int w, int h, float u) {
            float gap = 7 * u, bw = (w - 120 * u - gap * (B - 1)) / B, x = 60 * u, base = h - 60 * u;
            for (int i = 0; i < B; i++) {
                float bh = 26 * u + bands[i] * (h * 0.38f);
                rect.set(x, base - bh, x + bw, base);
                p.setStyle(Paint.Style.FILL);
                p.setColor(col(hue + i * 3, 0.35f, 1f, 70 + (int) (60 * bands[i])));
                c.drawRoundRect(rect, bw / 2f, bw / 2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.6f * u);
                p.setColor(0xBBFFFFFF);
                c.drawRoundRect(rect, bw / 2f, bw / 2f, p);
                x += bw + gap;
            }
            p.setStyle(Paint.Style.FILL);
        }

        /** Orb: en organisk, flydende blob, der former sig efter spektret, med lysende ringe, der løber ud. */
        private void orb(Canvas c, int w, int h, float t, float u) {
            float cx = w / 2f, cy = h * 0.46f, base = 170 * u * (1f + 0.05f * beat);
            int n = B * 2;
            float[] xs = new float[n], ys = new float[n];
            for (int i = 0; i < n; i++) {
                int bi = i < B ? i : n - 1 - i;
                float a = (float) (i / (float) n * Math.PI * 2 - Math.PI / 2);
                float rr = base + bands[bi] * 85 * u + 8 * u * (float) Math.sin(t * 1.4f + i * 0.6f);
                xs[i] = cx + (float) Math.cos(a) * rr;
                ys[i] = cy + (float) Math.sin(a) * rr;
            }
            path.reset();
            path.moveTo((xs[n - 1] + xs[0]) / 2f, (ys[n - 1] + ys[0]) / 2f);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                path.quadTo(xs[i], ys[i], (xs[i] + xs[j]) / 2f, (ys[i] + ys[j]) / 2f);
            }
            path.close();
            p.setXfermode(add);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, base + 90 * u, col(hue + 40, 0.7f, 1f, 200), col(hue - 30, 0.9f, 0.6f, 90), Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setXfermode(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * u);
            p.setColor(0xCCFFFFFF);
            c.drawPath(path, p);
            for (int k = 0; k < 3; k++) {
                float ph = ((t * 0.5f + k / 3f) % 1f);
                p.setColor(col(hue, 0.4f, 1f, (int) (120 * (1f - ph))));
                p.setStrokeWidth(2f * u);
                c.drawCircle(cx, cy, base + 90 * u + ph * 260 * u * (0.7f + beat * 0.6f), p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        /** Kromatisk oscilloskop: tre forskudte linjer i rødt, grønt og blåt, der lægges oven på hinanden som en glitch. */
        private void scope(Canvas c, int w, int h, float u) {
            float cy = h * 0.5f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4.5f * u);
            p.setXfermode(add);
            int[] colors = {0xFFFF2A55, 0xFF22FF99, 0xFF3A6BFF};
            for (int k = 0; k < 3; k++) {
                path.reset();
                float off = (k - 1) * (3f + 14f * beat) * u;
                for (int i = 0; i <= B * 2; i++) {
                    int bi = Math.min(B - 1, i < B ? i : B * 2 - i);
                    float x = w * i / (float) (B * 2);
                    float amp = (10 + bands[bi] * 210) * u * (i % 2 == 0 ? 1f : -1f);
                    float y = cy + amp + off;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                p.setColor(colors[k]);
                c.drawPath(path, p);
            }
            p.setXfermode(null);
            p.setStyle(Paint.Style.FILL);
        }

        private void roundBitmap(Canvas c, Bitmap bmp, float l, float tp, float r, float b, float rad) {
            int bw = bmp.getWidth(), bh = bmp.getHeight(), side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            rect.set(l, tp, r, b);
            clip.reset();
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(bmp, src, rect, p);
            c.restore();
        }

        /** Cover som et glaskort: afrundet, med lys kant og et skær i coverets farve. */
        private void drawCover(Canvas c, int w, int h, float u) {
            float cx = w / 2f, cy = h * 0.46f, half = (mode == 1 ? 112 : 140) * u * (1f + 0.045f * beat);
            p.setXfermode(screen);
            p.setShader(new RadialGradient(cx, cy, half * 1.9f, col(hue, 0.7f, 1f, (int) (90 + 90 * beat)), 0, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, half * 1.9f, p);
            p.setShader(null);
            p.setXfermode(null);
            float rad = 34 * u;
            if (cover != null) {
                roundBitmap(c, cover, cx - half, cy - half, cx + half, cy + half, rad);
            } else {
                rect.set(cx - half, cy - half, cx + half, cy + half);
                p.setColor(0x33FFFFFF);
                c.drawRoundRect(rect, rad, rad, p);
                text.setColor(0xFFFFFFFF);
                text.setTypeface(bold);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(52 * u);
                c.drawText("PARTY", cx, cy + 18 * u, text);
            }
            rect.set(cx - half, cy - half, cx + half, cy + half);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.5f * u);
            p.setColor(0xCCFFFFFF);
            c.drawRoundRect(rect, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        private void drawText(Canvas c, int w, int h, float u, boolean real) {
            float max = w / 2f - 150 * u - 40 * u;
            text.setTextAlign(Paint.Align.LEFT);
            String tag = real ? "LIVE" : "NOW PLAYING";
            text.setTypeface(semibold);
            text.setTextSize(12 * u);
            float tw = text.measureText(tag) + 28 * u;
            rect.set(40 * u, 34 * u, 40 * u + tw, 62 * u);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(rect, 14 * u, 14 * u, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.2f * u);
            p.setColor(0x88FFFFFF);
            c.drawRoundRect(rect, 14 * u, 14 * u, p);
            p.setStyle(Paint.Style.FILL);
            text.setColor(0xFFFFFFFF);
            c.drawText(tag, 54 * u, 53 * u, text);
            text.setTypeface(bold);
            text.setTextSize(34 * u);
            c.drawText(ellipsize(title.isEmpty() ? "Spil noget musik" : title, max), 40 * u, 118 * u, text);
            text.setTypeface(semibold);
            text.setTextSize(21 * u);
            text.setColor(0xCCFFFFFF);
            if (!artist.isEmpty()) c.drawText(ellipsize(artist, max), 40 * u, 152 * u, text);
        }

        /** Spilletid og en tynd linje under titlen, hvis afspilleren oplyser den. */
        private void drawTime(Canvas c, int w, int h, float u) {
            MediaController m = ctl;
            if (m == null) return;
            PlaybackState st = m.getPlaybackState();
            if (st == null || st.getPosition() < 0) return;
            long pos = st.getPosition();
            if (st.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((android.os.SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
            }
            if (duration > 0) pos = Math.min(pos, duration);
            String s = clock(pos) + (duration > 0 ? "  /  " + clock(duration) : "");
            float x = 40 * u, y = 186 * u;
            text.setTypeface(semibold);
            text.setTextSize(17 * u);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(s, x, y, text);
            if (duration > 0) {
                float lw = 260 * u;
                rect.set(x, y + 14 * u, x + lw, y + 18 * u);
                p.setColor(0x44FFFFFF);
                c.drawRoundRect(rect, 2 * u, 2 * u, p);
                rect.set(x, y + 14 * u, x + lw * Math.max(0f, Math.min(1f, pos / (float) duration)), y + 18 * u);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(rect, 2 * u, 2 * u, p);
            }
        }

        private String clock(long ms) {
            long s = Math.max(0, ms / 1000);
            return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        }

        private String ellipsize(String s, float max) {
            if (text.measureText(s) <= max) return s;
            int n = s.length();
            while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > max) n--;
            return s.substring(0, n) + "…";
        }

        /** Gæsterne svæver rundt om cover og slår med i takt; billede, hvis der er et, ellers et farvet initial. */
        private void drawGuests(Canvas c, int w, int h, float t, float u) {
            for (int i = 0; i < guests.size(); i++) {
                Guest g = guests.get(i);
                float x = w / 2f + (float) Math.sin(t * g.speed + g.phase) * w * g.ax * 1.4f;
                float y = h * 0.42f + (float) Math.cos(t * g.speed * 0.8f + g.phase * 1.7f) * h * g.ay;
                float r = (44 + 12 * beat) * u;
                p.setColor(col(hue + i * 37, 0.5f, 1f, 255));
                c.drawCircle(x, y, r + 3 * u, p);
                if (g.photo != null) {
                    roundBitmap(c, g.photo, x - r, y - r, x + r, y + r, r);
                } else {
                    p.setColor(col(hue + i * 37, 0.8f, 0.45f, 255));
                    c.drawCircle(x, y, r, p);
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setTypeface(bold);
                    text.setColor(0xFFFFFFFF);
                    text.setTextSize(34 * u);
                    c.drawText(g.name.substring(0, 1).toUpperCase(Locale.ROOT), x, y + 12 * u, text);
                }
                text.setTextAlign(Paint.Align.CENTER);
                text.setTypeface(semibold);
                text.setColor(0xFFFFFFFF);
                text.setTextSize(17 * u);
                c.drawText(g.name, x, y + r + 24 * u, text);
            }
        }
    }
}
