package dk.seasonpass.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Selve pauseskærmen (bruges af SeasonDream og af "Prøv pauseskærmen"): serier som slideshow med blød overgang,
 * et stort ur og en lille "spiller nu"-boks, hvis noget spilles i fx Spotify. Indholdet og uret vælges i
 * Indstillinger (Prefs). Alt læses fra feed.json, der allerede ligger på tv'et, så pauseskærmen ikke bruger nettet.
 */
final class SaverView extends FrameLayout {

    private static final int FG = 0xFFF2F1EC;
    private static final int MUTED = 0xB3F2F1EC;
    private static final long SLIDE_MS = 12000;
    private static final long FADE_MS = 1400;
    private static final Locale DA = new Locale("da", "DK");

    private static final class Slide {
        String image, title, line;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Typeface light, regular, semibold, bold;
    private final FeatherImageView[] imgs = new FeatherImageView[2];
    private final android.animation.ValueAnimator[] zooms = new android.animation.ValueAnimator[2];
    private android.animation.ValueAnimator reveal;
    private int front = 0;
    private int transition = 0;
    /** Cirklen åbner sig langsomt og med en bred, blød kant; billedet zoomer hele tiden, så længe det er til stede. */
    private static final long REVEAL_MS = 3400;
    private static final long ZOOM_MS = 26000;
    private static final float ZOOM_TO = 1.16f;
    private View scrim;
    private final TextView clock, date, title, line, trackTitle, trackArtist;
    private final View track;
    private final ImageView trackArt;
    private final List<Slide> slides = new ArrayList<>();
    private int slideIndex = -1;
    private int generation = 0;
    private boolean running;
    private final String clockMode;
    private final boolean music;
    private String shownTrack = "";

    SaverView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        light = c.getResources().getFont(R.font.urbanist_light);
        regular = c.getResources().getFont(R.font.urbanist_regular);
        semibold = c.getResources().getFont(R.font.urbanist_semibold);
        bold = c.getResources().getFont(R.font.urbanist_bold);
        clockMode = Prefs.clock(c);
        music = Prefs.music(c);

        for (int i = 0; i < 2; i++) {
            imgs[i] = new FeatherImageView(c);
            imgs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            imgs[i].setAlpha(0f);
            addView(imgs[i], new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        scrim = new View(c);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x66000000, 0x33000000});
        scrim.setBackground(g);
        addView(scrim, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Tegnerækkefølgen følger Z: gammelt billede 1, nyt billede 2, skygge 3, tekst og ur øverst.
        scrim.setTranslationZ(3);

        boolean big = clockMode.equals("big");
        boolean onlyClock = Prefs.content(c).equals("clock");

        // Venstre, nederst: seriens navn lille over uret.
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        title = text("", 22, bold, FG);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line = text("", 14, regular, MUTED);
        clock = text("", big ? 150 : 44, light, FG);
        clock.setLetterSpacing(-0.03f);
        date = text("", big ? 20 : 15, regular, MUTED);
        if (!onlyClock) {
            col.addView(title);
            col.addView(line, margins(0, 2, 0, 0));
        }
        if (!clockMode.equals("off") || onlyClock) {
            col.addView(clock, margins(0, big ? 6 : 14, 0, big ? -14 : 0));
            col.addView(date);
        }
        FrameLayout.LayoutParams cl = new LayoutParams(dp(onlyClock ? 840 : 560), ViewGroup.LayoutParams.WRAP_CONTENT,
                onlyClock ? Gravity.CENTER : Gravity.BOTTOM | Gravity.START);
        if (onlyClock) col.setGravity(Gravity.CENTER_HORIZONTAL);
        else cl.setMargins(dp(56), 0, 0, dp(46));
        if (onlyClock) { clock.setGravity(Gravity.CENTER); date.setGravity(Gravity.CENTER); }
        col.setTranslationZ(4);
        addView(col, cl);

        // Højre, nederst: spiller nu.
        LinearLayout t = new LinearLayout(c);
        t.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(14));
        tb.setColor(0x99000000);
        t.setBackground(tb);
        t.setPadding(dp(12), dp(12), dp(18), dp(12));
        trackArt = new ImageView(c);
        trackArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        trackArt.setClipToOutline(true);
        trackArt.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8)); }
        });
        t.addView(trackArt, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout tx = new LinearLayout(c);
        tx.setOrientation(LinearLayout.VERTICAL);
        trackTitle = text("", 15, semibold, FG);
        trackTitle.setSingleLine(true);
        trackTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        trackArtist = text("", 13, regular, MUTED);
        trackArtist.setSingleLine(true);
        trackArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tx.addView(trackTitle);
        tx.addView(trackArtist, margins(0, 2, 0, 0));
        trackTitle.setMaxWidth(dp(260));
        trackArtist.setMaxWidth(dp(260));
        LinearLayout.LayoutParams txp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        txp.leftMargin = dp(12);
        t.addView(tx, txp);
        track = t;
        track.setVisibility(GONE);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        tl.setMargins(0, 0, dp(56), dp(46));
        track.setTranslationZ(4);
        addView(track, tl);
    }

    void start() {
        if (running) return;
        running = true;
        generation++;
        tickClock.run();
        if (music) pollMusic.run();
        final int gen = generation;
        io.execute(() -> {
            List<Slide> s = loadSlides();
            main.post(() -> {
                if (!running || gen != generation) return;
                slides.clear();
                slides.addAll(s);
                slideIndex = -1;
                nextSlide.run();
            });
        });
    }

    void stop() {
        running = false;
        generation++;
        main.removeCallbacksAndMessages(null);
        if (reveal != null) reveal.cancel();
        for (int i = 0; i < 2; i++) if (zooms[i] != null) zooms[i].cancel();
        title.animate().cancel();
        line.animate().cancel();
    }

    private final Runnable tickClock = new Runnable() {
        @Override public void run() {
            if (!running) return;
            Date now = new Date();
            clock.setText(new SimpleDateFormat("HH.mm", DA).format(now));
            Strings tx = Strings.of(getContext());
            String d = tx.date("EEEEdMMMM", now.getTime(), java.util.TimeZone.getDefault());
            date.setText(d.isEmpty() ? d : d.substring(0, d.offsetByCodePoints(0, 1)).toUpperCase(tx.locale) + d.substring(d.offsetByCodePoints(0, 1)));
            main.postDelayed(this, 10000);
        }
    };

    private final Runnable nextSlide = new Runnable() {
        @Override public void run() {
            if (!running || slides.isEmpty()) return;
            slideIndex = (slideIndex + 1) % slides.size();
            final Slide s = slides.get(slideIndex);
            final int gen = generation;
            io.execute(() -> {
                Bitmap bmp = decode(s.image);
                main.post(() -> {
                    if (!running || gen != generation) return;
                    if (bmp == null && slides.size() > 1) { main.post(this); return; }
                    show(s, bmp);
                    main.postDelayed(this, SLIDE_MS);
                });
            });
        }
    };

    /**
     * Overgang til næste billede: det nye billede åbner sig som en cirkel fra et hjørne med en bred, blød kant, og
     * det gamle ligger under, til cirklen har dækket det. Begge billeder zoomer helt stille og uden at stoppe, så
     * længe de er på skærmen (zoomen er længere end tiden, billedet vises).
     */
    private void show(Slide s, Bitmap bmp) {
        final int back = 1 - front, old = front;
        final FeatherImageView in = imgs[back], out = imgs[old];
        stopZoom(back);
        in.endReveal();
        in.setImageBitmap(bmp);
        in.setTranslationZ(2);
        out.setTranslationZ(1);
        in.setAlpha(1f);
        startZoom(back);
        float w = getWidth(), h = getHeight();
        if (reveal != null) reveal.cancel();
        if (w > 0 && h > 0) {
            int corner = transition++ % 4;   // øverst venstre, nederst højre, øverst højre, nederst venstre
            float cx = (corner == 1 || corner == 2) ? w : 0, cy = (corner == 1 || corner == 3) ? h : 0;
            in.beginReveal(cx, cy, w * 0.7f, (float) Math.hypot(w, h));
            reveal = android.animation.ValueAnimator.ofFloat(0f, 1f);
            reveal.setDuration(REVEAL_MS);
            reveal.setInterpolator(new android.view.animation.PathInterpolator(0.45f, 0f, 0.25f, 1f));
            reveal.addUpdateListener(a -> in.setReveal((Float) a.getAnimatedValue()));
            reveal.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator x) {
                    in.endReveal();
                    if (front == back) {   // ikke afbrudt af et nyt skift
                        out.setAlpha(0f);
                        stopZoom(old);
                    }
                }
            });
            reveal.start();
        } else {
            out.setAlpha(0f);
            stopZoom(old);
        }
        front = back;
        changeCaption(s);
    }

    private void startZoom(int i) {
        final ImageView v = imgs[i];
        v.setScaleX(1f);
        v.setScaleY(1f);
        android.animation.ValueAnimator z = android.animation.ValueAnimator.ofFloat(1f, ZOOM_TO);
        z.setDuration(ZOOM_MS);
        z.setInterpolator(new LinearInterpolator());
        z.addUpdateListener(a -> {
            float f = (Float) a.getAnimatedValue();
            v.setScaleX(f);
            v.setScaleY(f);
        });
        zooms[i] = z;
        z.start();
    }

    private void stopZoom(int i) {
        if (zooms[i] != null) zooms[i].cancel();
        zooms[i] = null;
        imgs[i].setScaleX(1f);
        imgs[i].setScaleY(1f);
    }

    /** Et billede, der kan åbne sig som en cirkel med blød kant (alfa-maske, der tegnes ovenpå billedet). */
    private static final class FeatherImageView extends ImageView {
        private float progress = -1f, cx, cy, feather, total;
        private final android.graphics.Paint mask = new android.graphics.Paint();

        FeatherImageView(Context c) {
            super(c);
            mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }

        void beginReveal(float cx, float cy, float feather, float reach) {
            this.cx = cx;
            this.cy = cy;
            this.feather = feather;
            this.total = reach + feather;   // da er alt længere væk end hjørnet helt synligt
            progress = 0f;
            invalidate();
        }

        void setReveal(float p) { progress = p; invalidate(); }
        void endReveal() { progress = -1f; invalidate(); }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (progress < 0f) {
                super.onDraw(c);
                return;
            }
            int sc = c.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.onDraw(c);
            float outer = Math.max(1f, progress * total);
            int n = 12;
            int[] col = new int[n];
            float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                float t = i / (n - 1f);
                float a = Math.max(0f, Math.min(1f, (outer - t * outer) / feather));
                a = a * a * (3f - 2f * a);   // glat kant
                col[i] = ((int) (a * 255f)) << 24;
                pos[i] = t;
            }
            mask.setShader(new android.graphics.RadialGradient(cx, cy, outer, col, pos, android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(0, 0, getWidth(), getHeight(), mask);
            c.restoreToCount(sc);
        }
    }

    /** Teksten glider op og toner ind, når billedet skifter. */
    private void changeCaption(Slide s) {
        final float dy = dp(10);
        title.animate().cancel();
        line.animate().cancel();
        title.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            title.setText(s.title);
            title.setTranslationY(dy);
            title.animate().alpha(1f).translationY(0).setDuration(700).setStartDelay(500).start();
        }).start();
        line.animate().alpha(0f).setDuration(250).withEndAction(() -> {
            line.setText(s.line);
            line.setTranslationY(dy);
            line.animate().alpha(1f).translationY(0).setDuration(700).setStartDelay(650).start();
        }).start();
    }

    // --- Indhold ---

    private List<Slide> loadSlides() {
        List<Slide> out = new ArrayList<>();
        String mode = Prefs.content(getContext());
        if (mode.equals("clock")) return out;
        try {
            File f = new File(FeedProvider.dir(getContext()), "feed.json");
            JSONObject feed = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (feed.optInt("version") == 2) feed = Feed.derive(feed, System.currentTimeMillis());
            List<Slide> next = slides(feed.optJSONArray("next"), 'n');
            List<Slide> soon = slides(feed.optJSONArray("soon"), 's');
            List<Slide> disc = slides(feed.optJSONArray("discover"), 'd');
            switch (mode) {
                case "next": out.addAll(next); break;
                case "soon": out.addAll(soon); break;
                case "discover": out.addAll(disc); break;
                default:
                    // Blandet: skiftevis én fra hver, så ingen række fylder det hele.
                    for (int i = 0; out.size() < 30 && (i < next.size() || i < soon.size() || i < disc.size()); i++) {
                        if (i < next.size()) out.add(next.get(i));
                        if (i < soon.size()) out.add(soon.get(i));
                        if (i < disc.size()) out.add(disc.get(i));
                    }
            }
        } catch (Exception ignored) { }
        return out;
    }

    private List<Slide> slides(JSONArray a, char kind) {
        List<Slide> out = new ArrayList<>();
        Strings tx = Strings.of(getContext());
        Set<Long> hidden = kind == 'd' ? Actions.hidden(getContext()) : java.util.Collections.<Long>emptySet();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null || o.isNull("image") || o.optString("image").isEmpty()) continue;
            if (kind == 'd' && hidden.contains(Actions.tmdbId(o.optString("id")))) continue;
            Slide s = new Slide();
            s.image = o.optString("image");
            s.title = o.optString("title");
            String service = o.optString("service");
            if (kind == 'n') s.line = o.optString("episode") + (service.isEmpty() ? "" : "  ·  " + service);
            else if (kind == 's') s.line = o.optString("episode") + "  ·  " + o.optString("when");
            else {
                String because = o.optString("because");
                s.line = (because.isEmpty() ? tx.t("tvhome.suggestion") : tx.t("discover.because", "name", because)) + (service.isEmpty() ? "" : "  ·  " + service);
            }
            out.add(s);
        }
        return out;
    }

    private Bitmap decode(String ref) {
        try {
            File f = Images.local(FeedProvider.dir(getContext()), ref);
            if (!f.exists()) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            int w = getContext().getResources().getDisplayMetrics().widthPixels;
            o.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(1, o.outWidth / Math.max(1, w))));
            o.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(f.getPath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    // --- Spiller nu (Spotify m.fl.) ---

    private final Runnable pollMusic = new Runnable() {
        @Override public void run() {
            if (!running) return;
            update(findPlaying());
            main.postDelayed(this, 2500);
        }
    };

    /** Den session, der spiller lige nu; Spotify og YouTube Music først. Uden notifikationsadgang: ingen. */
    private MediaController findPlaying() {
        try {
            MediaSessionManager m = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = Prefs.listener(getContext());
            MediaController best = null;
            List<MediaController> all = m.getActiveSessions(cn);
            for (MediaController c : all) {
                PlaybackState st = c.getPlaybackState();
                if (st == null || st.getState() != PlaybackState.STATE_PLAYING) continue;
                if (c.getMetadata() == null || c.getMetadata().getString(MediaMetadata.METADATA_KEY_TITLE) == null) continue;
                String p = c.getPackageName();
                if (p.contains("spotify") || p.contains("tvmusic")) return c;
                if (best == null) best = c;
            }
            return best;
        } catch (SecurityException e) {
            android.util.Log.w("SaverView", "ingen adgang til sessioner: " + e.getMessage());
            return null;   // adgangen er ikke givet (Indstillinger → Musik)
        } catch (Exception e) {
            android.util.Log.w("SaverView", "findPlaying: " + e);
            return null;
        }
    }

    private void update(MediaController c) {
        if (c == null) {
            track.setVisibility(GONE);
            shownTrack = "";
            return;
        }
        MediaMetadata md = c.getMetadata();
        String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        if (a == null) a = md.getString(MediaMetadata.METADATA_KEY_ALBUM);
        String key = t + "|" + a;
        if (!key.equals(shownTrack)) {
            shownTrack = key;
            trackTitle.setText(t);
            trackArtist.setText(a == null ? "" : a);
            Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            trackArt.setImageBitmap(art);
            trackArt.setVisibility(art == null ? GONE : VISIBLE);
        }
        track.setVisibility(VISIBLE);
    }

    // --- Små hjælpere (samme enhed som forsiden: 960 enheder på tværs) ---

    private float unit() { return getResources().getDisplayMetrics().widthPixels / 960f; }
    private int dp(int v) { return Math.round(v * unit()); }

    private TextView text(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit());
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }
}
