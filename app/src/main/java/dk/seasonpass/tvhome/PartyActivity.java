package dk.seasonpass.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Partytilstand (0.12.0): kun til eget brug, skjult bag GTA-mærket i Indstillinger. Fuld skærm med det, der spilles
 * (cover, titel), farver trukket fra coveret og en visualizer, der slår i takt med musikken. Lyden læses med Androids
 * Visualizer, hvis appen har fået lov (RECORD_AUDIO); ellers, eller hvis der ingen lyd kommer, tegnes en animation i
 * fast tempo. OK skifter visning (søjler, ring, bølge); Tilbage afslutter. Gæster: navne i party.txt (en pr. linje) i
 * appens mappe, og et billede party/Navn.jpg, hvis man har et. Intet hentes fra sociale medier.
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
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (view != null) view.startAudio();
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

        private static final int BANDS = 48;

        private final Handler main = new Handler(Looper.getMainLooper());
        private final Random rnd = new Random();
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Path path = new Path();
        private final float[] bands = new float[BANDS];
        private final float[] target = new float[BANDS];
        private final float[] hsv = new float[3];
        private final List<Guest> guests = new ArrayList<>();
        private final Typeface bold, semibold;

        private Visualizer viz;
        private boolean realAudio;
        private long lastReal, started;
        private float beat, bass, bassAvg, hue = 280;
        private int mode;
        private long modeAt;
        private String shownTrack = "";
        private String title = "", artist = "";
        private Bitmap cover;
        private float coverHue = -1;
        private boolean playing;
        private boolean running;

        PartyView(Context c) {
            super(c);
            bold = c.getResources().getFont(R.font.urbanist_bold);
            semibold = c.getResources().getFont(R.font.urbanist_semibold);
            setBackgroundColor(Color.BLACK);
            loadGuests();
        }

        void hideSystem() {
            setSystemUiVisibility(SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY | SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }

        private float u() { return getWidth() / 960f; }

        // --- Gæster -------------------------------------------------------------------------------------------

        private void loadGuests() {
            try {
                File dir = Console.dir(getContext());
                File list = new File(dir, "party.txt");
                if (!list.isFile()) return;
                int i = 0;
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
                    g.speed = 0.15f + rnd.nextFloat() * 0.2f;
                    g.ax = 0.3f + rnd.nextFloat() * 0.15f;
                    g.ay = 0.25f + rnd.nextFloat() * 0.15f;
                    guests.add(g);
                    i++;
                }
            } catch (Exception ignored) { }
        }

        // --- Livscyklus ---------------------------------------------------------------------------------------

        void start() {
            running = true;
            started = System.currentTimeMillis();
            startAudio();
            main.removeCallbacks(poll);
            main.post(poll);
            postInvalidateOnAnimation();
        }

        void stop() {
            running = false;
            main.removeCallbacks(poll);
            if (viz != null) {
                try { viz.setEnabled(false); viz.release(); } catch (Exception ignored) { }
                viz = null;
            }
        }

        void next(int d) {
            mode = (mode + d + 3) % 3;
            modeAt = System.currentTimeMillis();
        }

        void startAudio() {
            if (viz != null || !running) return;
            if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
            try {
                Visualizer v = new Visualizer(0);
                v.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
                v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                    @Override public void onWaveFormDataCapture(Visualizer x, byte[] w, int rate) { }
                    @Override public void onFftDataCapture(Visualizer x, byte[] fft, int rate) { fft(fft, rate); }
                }, Visualizer.getMaxCaptureRate() / 2, false, true);
                v.setEnabled(true);
                viz = v;
            } catch (Exception e) {
                android.util.Log.w("Party", "Visualizer: " + e);
            }
        }

        /** Lyden: 48 bånd på log-skala fra bas til diskant; "ægte", hvis der er noget i den. */
        private void fft(byte[] d, int rate) {
            int n = d.length / 2;
            float[] mag = new float[n];
            float sum = 0;
            for (int k = 1; k < n; k++) {
                float re = d[2 * k], im = d[2 * k + 1];
                mag[k] = (float) Math.sqrt(re * re + im * im);
                sum += mag[k];
            }
            if (sum < n * 0.5f) return;   // stilhed eller lyd, vi ikke må høre: lad animationen tage over
            lastReal = System.currentTimeMillis();
            float bs = 0;
            for (int b = 0; b < BANDS; b++) {
                int lo = (int) Math.pow(n, b / (float) BANDS), hi = Math.max(lo + 1, (int) Math.pow(n, (b + 1) / (float) BANDS));
                float m = 0;
                for (int k = Math.max(1, lo); k < Math.min(n, hi); k++) m = Math.max(m, mag[k]);
                float v = Math.min(1f, (float) Math.pow(m / 90f, 0.7f) * (0.75f + b * 0.012f));
                target[b] = v;
                if (b < 5) bs += v;
            }
            bass = bs / 5f;
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
                for (MediaController x : m.getActiveSessions(cn)) {
                    PlaybackState st = x.getPlaybackState();
                    if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                    if (x.getMetadata() == null || x.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
                    if (c == null || x.getPackageName().contains("spotify")) c = x;
                }
            } catch (Exception ignored) { }
            playing = c != null;
            if (c == null) return;
            MediaMetadata md = c.getMetadata();
            String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            String key = t + "|" + a;
            if (key.equals(shownTrack)) return;
            shownTrack = key;
            title = t == null ? "" : t;
            artist = a == null ? "" : a;
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            cover = art;
            coverHue = art == null ? -1 : hueOf(art);
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
            boolean real = now - lastReal < 1500;
            realAudio = real;
            if (!real) synth(t);

            // Slag: bassen ruller over sit eget gennemsnit; i animation et fast tempo (124 bpm).
            float b;
            if (real) {
                bassAvg += (bass - bassAvg) * 0.05f;
                b = Math.max(0f, (bass - bassAvg * 0.9f) * 3f);
            } else {
                float ph = (t * 124f / 60f) % 1f;
                b = (float) Math.pow(Math.max(0f, Math.cos(ph * 6.2832f) * 0.5f + 0.5f), 6);
            }
            beat = Math.max(b, beat * 0.88f);
            for (int i = 0; i < BANDS; i++) bands[i] += (target[i] - bands[i]) * (target[i] > bands[i] ? 0.6f : 0.18f);

            float want = coverHue >= 0 ? coverHue : (t * 12f) % 360f;
            float dh = ((want - hue + 540f) % 360f) - 180f;
            hue = (hue + dh * 0.03f + 360f) % 360f;
            if (now - modeAt > 40000 && now - started > 20000) next(1);

            int w = getWidth(), h = getHeight();
            float u = u();
            drawBackground(c, w, h);
            float cx = w / 2f, cy = h * 0.46f;
            float r = 130 * u * (1f + 0.06f * beat);

            if (mode == 0) bars(c, w, h, u);
            else if (mode == 1) radial(c, cx, cy, r, u);
            else wave(c, w, h, cy, u);

            drawGuests(c, w, h, t, u);
            drawCover(c, cx, cy, r, u);
            drawText(c, w, h, u, real);
            postInvalidateOnAnimation();
        }

        private int col(float hh, float s, float v, int a) {
            return (a << 24) | (Color.HSVToColor(new float[]{(hh % 360f + 360f) % 360f, s, v}) & 0xFFFFFF);
        }

        private void drawBackground(Canvas c, int w, int h) {
            c.drawColor(col(hue, 0.7f, 0.16f + 0.12f * beat, 255));
            p.setShader(new RadialGradient(w / 2f, h * 0.46f, h * (0.55f + 0.15f * beat),
                    col(hue + 20, 0.8f, 1f, (int) (70 + 90 * beat)), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
        }

        /** Uden lyd fra systemet: bølger i bevægelse, løftet af slaget. */
        private void synth(float t) {
            for (int i = 0; i < BANDS; i++) {
                float f = i / (float) BANDS;
                float v = 0.35f + 0.25f * (float) Math.sin(t * (1.3f + f * 3f) + i * 0.7f) + 0.2f * (float) Math.sin(t * 2.1f - i * 0.3f);
                v *= (1.1f - f * 0.5f);
                target[i] = Math.max(0.05f, Math.min(1f, v + beat * 0.35f * (1f - f)));
            }
        }

        private void bars(Canvas c, int w, int h, float u) {
            float gap = 6 * u, bw = (w - 80 * u - gap * (BANDS - 1)) / BANDS, x = 40 * u;
            for (int i = 0; i < BANDS; i++) {
                float bh = (40 + bands[i] * (h * 0.5f));
                p.setColor(col(hue + i * 4, 0.8f, 1f, 255));
                rect.set(x, h - bh, x + bw, h + bw);
                c.drawRoundRect(rect, bw / 2f, bw / 2f, p);
                x += bw + gap;
            }
        }

        private void radial(Canvas c, float cx, float cy, float r, float u) {
            int n = BANDS * 2;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(7 * u);
            for (int i = 0; i < n; i++) {
                float a = (float) (i / (float) n * Math.PI * 2 - Math.PI / 2);
                int bi = i < BANDS ? i : n - 1 - i;
                float len = 12 * u + bands[bi] * 85 * u;
                float r0 = r + 20 * u;
                p.setColor(col(hue + bi * 5, 0.85f, 1f, 255));
                c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                        cx + (float) Math.cos(a) * (r0 + len), cy + (float) Math.sin(a) * (r0 + len), p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        private void wave(Canvas c, int w, int h, float cy, float u) {
            for (int layer = 0; layer < 3; layer++) {
                path.reset();
                int pts = BANDS * 2;
                path.moveTo(0, cy);
                for (int i = 0; i <= pts; i++) {
                    float x = w * i / (float) pts;
                    int bi = Math.min(BANDS - 1, i < BANDS ? i : pts - i);
                    float amp = (20 + bands[bi] * 190 * (1f - layer * 0.25f)) * u;
                    float y = cy - amp * (float) (0.7 + 0.3 * Math.sin(i * 0.5 + layer * 2));
                    path.lineTo(x, y);
                }
                for (int i = pts; i >= 0; i--) {
                    float x = w * i / (float) pts;
                    int bi = Math.min(BANDS - 1, i < BANDS ? i : pts - i);
                    float amp = (20 + bands[bi] * 190 * (1f - layer * 0.25f)) * u;
                    path.lineTo(x, cy + amp * (float) (0.7 + 0.3 * Math.sin(i * 0.5 + layer * 2)));
                }
                path.close();
                p.setColor(col(hue + layer * 40, 0.85f, 1f, 150 - layer * 40));
                c.drawPath(path, p);
            }
        }

        private final Path clip = new Path();
        private final android.graphics.Rect src = new android.graphics.Rect();

        /** Billedet beskåret til en cirkel (midten af billedet), uden at lave nye shaders hver frame. */
        private void circleBitmap(Canvas c, Bitmap bmp, float cx, float cy, float r) {
            int bw = bmp.getWidth(), bh = bmp.getHeight(), side = Math.min(bw, bh);
            src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
            rect.set(cx - r, cy - r, cx + r, cy + r);
            clip.reset();
            clip.addCircle(cx, cy, r, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawBitmap(bmp, src, rect, p);
            c.restore();
        }

        private void drawCover(Canvas c, float cx, float cy, float r, float u) {
            p.setColor(col(hue, 0.5f, 1f, 255));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5 * u);
            c.drawCircle(cx, cy, r + 8 * u, p);
            p.setStyle(Paint.Style.FILL);
            if (cover != null) {
                circleBitmap(c, cover, cx, cy, r);
            } else {
                p.setColor(0x33FFFFFF);
                c.drawCircle(cx, cy, r, p);
                text.setColor(0xFFFFFFFF);
                text.setTypeface(bold);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(70 * u);
                c.drawText("PARTY", cx, cy + 24 * u, text);
            }
        }

        private void drawText(Canvas c, int w, int h, float u, boolean real) {
            float max = w / 2f - 150 * u - 90 * u;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTypeface(semibold);
            text.setTextSize(13 * u);
            text.setColor(0x99FFFFFF);
            c.drawText(real ? "LIVE" : "PARTY", 40 * u, 48 * u, text);
            text.setColor(0xFFFFFFFF);
            text.setTypeface(bold);
            text.setTextSize(30 * u);
            c.drawText(ellipsize(title.isEmpty() ? "Spil noget musik" : title, max), 40 * u, 92 * u, text);
            text.setTypeface(semibold);
            text.setTextSize(19 * u);
            text.setColor(0xCCFFFFFF);
            if (!artist.isEmpty()) c.drawText(ellipsize(artist, max), 40 * u, 124 * u, text);
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
                float r = (46 + 12 * beat) * u;
                p.setColor(col(hue + i * 37, 0.8f, 1f, 255));
                c.drawCircle(x, y, r + 4 * u, p);
                if (g.photo != null) {
                    circleBitmap(c, g.photo, x, y, r);
                } else {
                    p.setColor(col(hue + i * 37, 0.8f, 0.45f, 255));
                    c.drawCircle(x, y, r, p);
                    text.setTextAlign(Paint.Align.CENTER);
                    text.setTypeface(bold);
                    text.setColor(0xFFFFFFFF);
                    text.setTextSize(36 * u);
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
