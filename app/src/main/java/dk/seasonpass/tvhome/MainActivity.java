package dk.seasonpass.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SeasonPass-startskærmen (25-10-2026): en stor serie øverst, "Næste afsnit",
 * "Dine apps" og "Kommer snart" på sort grund. Alt indhold kommer fra feed.json, skrevet af
 * telefonen (SyncServer) eller Mac'en (FeedProvider). Bygget i kode frem for XML, så skærmen er én fil.
 */
public class MainActivity extends Activity implements SyncServer.Listener {

    // SeasonPass' farver (src/theme.ts): neutral mørk grund, det man kan handle på er lyst.
    private static final int BG = Color.BLACK;
    private static final int SURFACE = 0xFF18181B;
    private static final int FG = 0xFFF2F1EC;
    private static final int MUTED = 0xADF2F1EC;   // 0.68
    private static final int FAINT = 0x80F2F1EC;   // 0.5

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Typeface light, regular, medium, semibold, bold;
    private ImageView heroImage;
    private TextView heroService, heroTitle, heroLine, heroOverview, clock;
    private LinearLayout dots, content;
    private ScrollView scroll;
    private View playButton;
    private final List<JSONObject> heroes = new ArrayList<>();
    private final Map<String, JSONObject> heroById = new HashMap<>();
    private int heroIndex = 0;
    private JSONObject currentHero;
    private long feedStamp = -1;
    /** Profilen, rækkerne er bygget for; skifter den, vises navnet kort. */
    private static String shownProfile;   // static: overlever, at skærmen bygges igen ved sprogskifte
    /** Fjernsynets tekster og sprog (fra den aktive profils feed); skærmen bygges igen, hvis de skifter. */
    private Strings S;
    /** Feed version 2 som modtaget; rækkerne regnes ud fra den igen, når uret flytter et afsnit. */
    private JSONObject v2;
    private String shownSignature = "";
    private TextView pairBanner, avatar, updatePill;
    private View infoPanel, infoPlay, infoReturn, infoSeen, infoNope;
    private TextView infoHelp;
    private JSONObject infoItem;
    private TextView infoService, infoTitle, infoLine, infoOverview;

    /** Hvert 5. minut: er et afsnit udkommet siden sidst, bygges rækkerne om. */
    private final Runnable rederive = new Runnable() {
        @Override public void run() {
            if (v2 != null) {
                try {
                    if (!signature(Feed.derive(v2, System.currentTimeMillis())).equals(shownSignature)) {
                        feedStamp = -1;
                        reloadIfChanged();
                    }
                } catch (Exception ignored) { }
            }
            main.postDelayed(this, 5 * 60 * 1000L);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            clock.setText(new SimpleDateFormat("HH.mm", new Locale("da", "DK")).format(new Date()));
            main.postDelayed(this, 15000);
        }
    };

    /** Mens fokus står på knapperne øverst, skifter den store serie hvert 9. sekund. */
    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (heroes.size() > 1 && playButton != null && playButton.getParent() instanceof View
                    && ((View) playButton.getParent()).hasFocus()) {
                heroIndex = (heroIndex + 1) % heroes.size();
                showHero(heroes.get(heroIndex));
            }
            main.postDelayed(this, 9000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S = Strings.of(this);
        light = getResources().getFont(R.font.urbanist_light);
        regular = getResources().getFont(R.font.urbanist_regular);
        medium = getResources().getFont(R.font.urbanist_medium);
        semibold = getResources().getFont(R.font.urbanist_semibold);
        bold = getResources().getFont(R.font.urbanist_bold);
        setContentView(buildScreen());
        SyncServer.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SyncServer.setListener(this);
        reloadIfChanged();
        main.post(tick);
        showName();
        checkUpdate();
        main.postDelayed(rotate, 9000);
        main.postDelayed(rederive, 5 * 60 * 1000L);
    }

    /** Avataren viser fornavnets første bogstav (fra telefonens profil); uden navn en tom ring. */
    private void showName() {
        if (avatar == null) return;
        String n = Prefs.name(this);
        avatar.setText(n.isEmpty() ? "" : new String(Character.toChars(Character.toUpperCase(n.codePointAt(0)))));
        avatar.setForeground(n.isEmpty() ? new PersonDrawable() : null);   // uden navn: et neutralt personikon i stedet for en tom ring
    }

    /** Vælg profil i hånden (flere telefoner parret). Valget gælder, til tv'et får en ny session med en anden telefon. */
    private void pickProfile() {
        List<String> ids = Profiles.all(this);
        if (ids.size() < 2) {
            Toast.makeText(this, S.t("tvhome.oneProfile"), Toast.LENGTH_LONG).show();
            return;
        }
        String now = Profiles.active(this);
        String[] labels = new String[ids.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = (ids.get(i).equals(now) ? "\u2713  " : "     ") + Profiles.label(this, ids.get(i), i);
        }
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(S.t("tvhome.who"))
                .setItems(labels, (d, which) -> {
                    try { Profiles.select(this, ids.get(which)); } catch (Exception ignored) { }
                    feedStamp = -1;
                    reloadIfChanged();
                })
                .show();
    }

    /** Hoved og skuldre inde i avatarens ring, til når telefonen ikke har sendt et navn. */
    private static final class PersonDrawable extends Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        PersonDrawable() { p.setColor(0x8CF2F1EC); }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float w = b.width(), h = b.height(), cx = b.exactCenterX();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, b.exactCenterY(), w / 2f - 2f, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            c.drawCircle(cx, b.top + h * 0.38f, w * 0.17f, p);
            c.drawOval(new android.graphics.RectF(b.left + w * 0.2f, b.top + h * 0.62f, b.right - w * 0.2f, b.top + h * 1.15f), p);
            c.restore();
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Højst hver 12. time: er der en nyere udgivelse, kommer en pille "Ny version" øverst. */
    private void checkUpdate() {
        android.content.SharedPreferences sp = Prefs.sp(this);
        showUpdatePill(sp.getString("update_version", ""));
        if (!Updater.enabled() || System.currentTimeMillis() - sp.getLong("update_checked", 0) < Updater.CHECK_EVERY_MS) return;
        io.execute(() -> {
            try {
                Updater.Info i = Updater.check(this);
                sp.edit().putLong("update_checked", System.currentTimeMillis()).putString("update_version", i == null ? "" : i.version).apply();
                main.post(() -> showUpdatePill(i == null ? "" : i.version));
            } catch (Exception ignored) { }   // intet net lige nu: prøv igen næste gang
        });
    }

    private void showUpdatePill(String version) {
        boolean show = !version.isEmpty() && Updater.newer(version, Updater.installedVersion(this));
        updatePill.setText(S.t("tvhome.newVersion", "v", version));
        updatePill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void reloadIfChanged() {
        File f = new File(FeedProvider.dir(this), "feed.json");
        long stamp = f.exists() ? f.lastModified() : 0;
        if (stamp != feedStamp) {
            feedStamp = stamp;
            Strings now = Strings.of(this);
            if (!now.same(S)) { recreate(); return; }   // en anden profil eller et nyt sprog: byg skærmen med de nye tekster
            loadFeed(f);
        }
    }

    /** Telefonen har sendt en ny feed (SyncServer). */
    @Override
    public void onFeed() {
        feedStamp = -1;
        reloadIfChanged();
    }

    /** Parring: vis koden stort, til telefonen har den (null skjuler den). */
    @Override
    public void onPairCode(String code) {
        if (code == null) {
            pairBanner.setVisibility(View.GONE);
            Toast.makeText(this, S.t("tvhome.paired"), Toast.LENGTH_SHORT).show();
            return;
        }
        pairBanner.setText(S.t("tvhome.pairCode", "code", code));
        pairBanner.setVisibility(View.VISIBLE);
        main.postDelayed(() -> pairBanner.setVisibility(View.GONE), 2 * 60 * 1000L);
    }

    private static String signature(JSONObject d) {
        return String.valueOf(d.optJSONArray("next")) + d.optJSONArray("soon") + d.optJSONArray("paused") + d.optJSONArray("discover");
    }

    /** Home-knappen mens skærmen allerede er fremme: tilbage til toppen og Afspil, som Google TV. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (infoPanel != null && infoPanel.getVisibility() == View.VISIBLE) closeInfo();
        toTop();
    }

    /** Tilbage-knappen på startskærmen: til toppen, ellers ingenting (Home har intet at gå tilbage til). */
    @Override
    public void onBackPressed() {
        if (infoPanel != null && infoPanel.getVisibility() == View.VISIBLE) { closeInfo(); return; }
        toTop();
    }

    private void toTop() {
        if (scroll == null || playButton == null) return;
        for (int i = 0; i < content.getChildCount(); i++) {
            View h = findScroller(content.getChildAt(i));
            if (h != null) h.scrollTo(0, 0);
        }
        scroll.smoothScrollTo(0, 0);
        playButton.requestFocus();
    }

    private static View findScroller(View v) {
        if (v instanceof HorizontalScrollView) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findScroller(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
        main.removeCallbacks(rotate);
        main.removeCallbacks(rederive);
        SyncServer.setListener(null);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) cache.evictAll();
    }

    // ---- Skærmen ---------------------------------------------------------------------------

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // Billedet fylder hele skærmen og skaleres fra toppen, så ansigter ikke skæres af (det
        // liggende baggrundsbillede er 16:9 som skærmen; et stående falder tilbage til toppen).
        heroImage = new ImageView(this);
        heroImage.setScaleType(ImageView.ScaleType.MATRIX);
        heroImage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitTop(heroImage));
        root.addView(heroImage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // To tonede lag: mørkt fra venstre, så teksten kan læses, og ned mod sort bag rækkerne.
        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xB3000000, 0x00000000}));
        root.addView(left, new FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START));
        View bottom = new View(this);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xB3000000, 0xF2000000}));
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(330), Gravity.BOTTOM);
        root.addView(bottom, blp);
        // Svagt mørke i toppen, så ur og profil kan læses på lyse billeder (Slow Horses' himmel).
        View topShade = new View(this);
        topShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x8C000000, 0x00000000}));
        root.addView(topShade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110), Gravity.TOP));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(40));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Øverst til højre: profil og ur, som i mockuppen.
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // Vores egen indstillingsside (pauseskærm, musik, opdatering); den fører videre til tv'ets egne indstillinger
        // og dermed udviklerindstillingerne. Nås med Op fra knapperne øverst.
        TextView settings = pill(S.t("tvhome.settings"), false);
        settings.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * unit());
        settings.setPadding(dp(16), dp(7), dp(16), dp(7));
        settings.setOnClickListener(x -> startActivity(new Intent(this, SettingsActivity.class)));
        settings.setOnFocusChangeListener(chain(settings, null));
        TextView appsPill = pill(S.t("tvhome.apps"), false);
        appsPill.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * unit());
        appsPill.setPadding(dp(16), dp(7), dp(16), dp(7));
        appsPill.setOnClickListener(x -> openApps());
        appsPill.setOnFocusChangeListener(chain(appsPill, null));
        updatePill = pill("", true);
        updatePill.setTextSize(TypedValue.COMPLEX_UNIT_PX, 13 * unit());
        updatePill.setPadding(dp(16), dp(7), dp(16), dp(7));
        updatePill.setVisibility(View.GONE);
        updatePill.setOnClickListener(x -> startActivity(new Intent(this, SettingsActivity.class)));
        updatePill.setOnFocusChangeListener(chain(updatePill, null));
        top.addView(updatePill, margins(0, 0, 10, 0));
        top.addView(appsPill, margins(0, 0, 10, 0));
        LinearLayout.LayoutParams slp = margins(0, 0, 14, 0);
        top.addView(settings, slp);
        avatar = text("", 15, medium, FG);
        showName();
        avatar.setGravity(Gravity.CENTER);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setStroke(dp(1), 0x47F2F1EC);
        avatar.setBackground(ring);
        avatar.setFocusable(true);
        avatar.setOnClickListener(x -> pickProfile());
        avatar.setOnFocusChangeListener((x, has) -> avatar.setForeground(has ? focusRing(dp(17), dp(3)) : null));
        top.addView(avatar, new LinearLayout.LayoutParams(dp(34), dp(34)));
        clock = text("", 17, regular, FG);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(14);
        top.addView(clock, clp);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tlp.setMargins(0, dp(22), dp(36), 0);
        root.addView(top, tlp);

        // Parring med telefonen: koden står stort midt for, til telefonen har den.
        pairBanner = text("", 24, semibold, 0xFF0E0E10);
        pairBanner.setPadding(dp(28), dp(16), dp(28), dp(16));
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(dp(99));
        pb.setColor(FG);
        pairBanner.setBackground(pb);
        pairBanner.setElevation(dp(12));
        pairBanner.setVisibility(View.GONE);
        root.addView(pairBanner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        infoPanel = buildInfoPanel();
        root.addView(infoPanel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** "Mere info": hele beskrivelsen af den store serie oven på forsiden. Tilbage lukker den. */
    private View buildInfoPanel() {
        FrameLayout p = new FrameLayout(this);
        p.setBackgroundColor(0xB3000000);
        p.setClickable(true);
        p.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(110), dp(56), 0);
        infoService = text("", 14, regular, MUTED);
        infoTitle = text("", 40, bold, FG);
        infoTitle.setLetterSpacing(-0.02f);
        infoLine = text("", 16, regular, FG);
        infoOverview = text("", 16, light, FG);
        infoOverview.setLineSpacing(0, 1.25f);
        col.addView(infoService);
        col.addView(infoTitle, margins(0, 4, 0, 0));
        col.addView(infoLine, margins(0, 8, 0, 0));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(18);
        col.addView(infoOverview, olp);
        LinearLayout buttons = new LinearLayout(this);
        infoPlay = pill("▶  " + S.t("tvhome.play"), true);
        infoPlay.setOnClickListener(x -> { JSONObject h = infoItem; closeInfo(); launch(h); });
        infoPlay.setOnFocusChangeListener(chain(infoPlay, null));
        infoSeen = pill(S.t("tvhome.seen"), false);
        infoSeen.setOnClickListener(x -> discoverAction(Actions.SEEN));
        infoSeen.setOnFocusChangeListener(chain(infoSeen, null));
        infoNope = pill(S.t("tvhome.nope"), false);
        infoNope.setOnClickListener(x -> discoverAction(Actions.DISMISS));
        infoNope.setOnFocusChangeListener(chain(infoNope, null));
        View close = pill(S.t("common.close"), false);
        close.setOnClickListener(x -> closeInfo());
        close.setOnFocusChangeListener(chain(close, null));
        buttons.addView(infoPlay);
        buttons.addView(infoSeen, margins(10, 0, 0, 0));
        buttons.addView(infoNope, margins(10, 0, 0, 0));
        buttons.addView(close, margins(10, 0, 0, 0));
        col.addView(buttons, margins(0, 26, 0, 0));
        infoHelp = text(S.t("tvhome.help"), 12, light, MUTED);
        infoHelp.setLineSpacing(0, 1.3f);
        col.addView(infoHelp, margins(0, 16, 0, 0));
        p.addView(col);
        return p;
    }

    private void showInfo() {
        showInfo(currentHero, null);
    }

    /** `from`: det kort, fokus skal tilbage til, når panelet lukkes (null: knapperne øverst). */
    private void showInfo(JSONObject o, View from) {
        if (o == null) return;
        infoItem = o;
        infoReturn = from;
        infoService.setText(o.optString("service"));
        infoTitle.setText(o.optString("title"));
        String because = o.optString("because", "");
        String season = o.optString("season", ""), line = o.optString("line", "");
        boolean disc = o.optBoolean("discover");
        int vis = disc ? View.VISIBLE : View.GONE;
        infoSeen.setVisibility(vis);
        infoNope.setVisibility(vis);
        infoHelp.setVisibility(vis);
        if (!because.isEmpty()) {
            infoLine.setText(S.t("discover.because", "name", because));
            ((TextView) infoPlay).setText("▶  Åbn " + o.optString("service"));
        } else {
            infoLine.setText(season.isEmpty() ? line : season + "  ·  " + line);
            ((TextView) infoPlay).setText("▶  " + S.t("tvhome.play"));
        }
        String ov = o.optString("overview", "");
        infoOverview.setText(ov.isEmpty() ? S.t("show.noDesc") : ov);
        infoPanel.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.INVISIBLE);   // ellers skinner rækkerne og den lille tekst igennem
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        infoPlay.requestFocus();
    }

    /** "Har set den" / "Ikke for mig": ind i køen til telefonen, og kortet forsvinder med det samme. */
    private void discoverAction(String kind) {
        JSONObject o = infoItem;
        long tmdb = o == null ? 0 : Actions.tmdbId(o.optString("id"));
        if (tmdb <= 0) {
            Toast.makeText(this, S.t("tvhome.saveFail"), Toast.LENGTH_SHORT).show();
            return;
        }
        String title = o.optString("title");
        Actions.add(this, kind, tmdb, title);
        infoReturn = dropDiscoverCard(infoReturn);
        closeInfo();
        Toast.makeText(this, Actions.SEEN.equals(kind)
                ? S.t("tvhome.followed", "title", title)
                : S.t("tvhome.dismissed", "title", title), Toast.LENGTH_LONG).show();
    }

    /** Fjerner kortet fra rækken; giver kortet, fokus skal over på, eller null (så skjules rækken). */
    private View dropDiscoverCard(View card) {
        if (card == null || !(card.getParent() instanceof ViewGroup)) return null;
        ViewGroup items = (ViewGroup) card.getParent();
        int i = items.indexOfChild(card);
        items.removeView(card);
        if (items.getChildCount() > 0) return items.getChildAt(Math.min(i, items.getChildCount() - 1));
        if (items.getParent() != null && items.getParent().getParent() instanceof View) {
            ((View) items.getParent().getParent()).setVisibility(View.GONE);   // hele rækken med overskrift
        }
        return null;
    }

    /** Opdag serier uden dem, der venter på telefonen (set eller afvist). */
    private JSONArray visibleDiscover(JSONArray all) {
        JSONArray out = new JSONArray();
        java.util.Set<Long> hidden = Actions.hidden(this);
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o != null && !hidden.contains(Actions.tmdbId(o.optString("id")))) out.put(o);
        }
        return out;
    }

    private void closeInfo() {
        infoPanel.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        View back = infoReturn != null && infoReturn.isAttachedToWindow() ? infoReturn : playButton;
        infoReturn = null;
        if (back != null) back.requestFocus();
    }

    private void loadFeed(File f) {
        String profile = Profiles.active(this);
        if (shownProfile != null && !profile.equals(shownProfile) && !profile.isEmpty()) {
            List<String> ids = Profiles.all(this);
            Toast.makeText(this, Profiles.label(this, profile, Math.max(0, ids.indexOf(profile))), Toast.LENGTH_SHORT).show();
        }
        shownProfile = profile;
        content.removeAllViews();
        heroes.clear();
        heroById.clear();
        JSONObject feed = null;
        v2 = null;
        try {
            feed = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            if (feed.optInt("version") == 2) {
                v2 = feed;
                Prefs.setName(this, feed.isNull("name") ? "" : feed.optString("name", "").trim());
                showName();
                feed = Feed.derive(v2, System.currentTimeMillis());
            }
            shownSignature = signature(feed);
        } catch (Exception ignored) {
            // Ingen feed endnu: vis apps og en kort forklaring.
            feed = null;
        }
        content.addView(heroBlock());
        if (feed == null) {
            heroTitle.setText("SeasonPass");
            heroLine.setText(S.t("tvhome.emptyTitle"));
            heroOverview.setText(S.t("tvhome.emptyText"));
            content.addView(section(S.t("tvhome.yourApps"), appsRow()));
            return;
        }
        JSONArray h = feed.optJSONArray("hero");
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            heroes.add(o);
            heroById.put(o.optString("id"), o);
        }
        JSONArray next = feed.optJSONArray("next");
        JSONArray soon = feed.optJSONArray("soon");
        JSONArray paused = feed.optJSONArray("paused");
        if (next != null && next.length() > 0) content.addView(section(S.t("tvhome.next"), cardsRow(next, true)));
        content.addView(section(S.t("tvhome.yourApps"), appsRow()));
        if (paused != null && paused.length() > 0) content.addView(section(S.t("mode.paused"), pausedRow(paused)));
        JSONArray discover = visibleDiscover(feed.optJSONArray("discover"));
        if (discover != null && discover.length() > 0) content.addView(section(S.t("tvhome.discover"), discoverRow(discover)));
        if (soon != null && soon.length() > 0) content.addView(section(S.t("home.soon"), cardsRow(soon, false)));
        buildDots();
        heroIndex = 0;
        if (!heroes.isEmpty()) showHero(heroes.get(0));
        playButton.requestFocus();
    }

    private View heroBlock() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(56), dp(66), dp(56), 0);
        heroService = text("", 13, regular, MUTED);
        heroTitle = text("", 40, bold, FG);
        heroTitle.setLetterSpacing(-0.02f);
        heroTitle.setMaxLines(1);
        heroTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroLine = text("", 14, regular, FG);
        heroOverview = text("", 13, light, MUTED);
        heroOverview.setMaxLines(2);
        heroOverview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroOverview.setLineSpacing(0, 1.15f);
        v.addView(heroService);
        v.addView(heroTitle, margins(0, 2, 0, 0));
        v.addView(heroLine, margins(0, 6, 0, 0));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(400), ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(10);
        v.addView(heroOverview, olp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        playButton = pill("▶  " + S.t("tvhome.play"), true);
        playButton.setOnClickListener(x -> launch(currentHero));
        View info = pill(S.t("tvhome.moreInfo"), false);
        info.setOnClickListener(x -> showInfo());
        buttons.addView(playButton);
        LinearLayout.LayoutParams ilp = margins(10, 0, 0, 0);
        buttons.addView(info, ilp);
        View.OnFocusChangeListener toTop = (x, has) -> { if (has) { scroll.smoothScrollTo(0, 0); restoreHero(); } };
        playButton.setOnFocusChangeListener(chain(playButton, toTop));
        info.setOnFocusChangeListener(chain(info, toTop));
        v.addView(buttons, margins(0, 18, 0, 0));

        dots = new LinearLayout(this);
        dots.setGravity(Gravity.END);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        dlp.topMargin = dp(4);
        v.addView(dots, dlp);
        return v;
    }

    private void buildDots() {
        dots.removeAllViews();
        if (heroes.size() < 2) return;
        for (int i = 0; i < heroes.size(); i++) {
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(FG);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.leftMargin = dp(6);
            dots.addView(d, lp);
        }
    }

    /** Fokus er forladt Opdag serier: den store serie er igen en af dine egne. */
    private void restoreHero() {
        if (currentHero != null && !heroes.isEmpty() && !heroes.contains(currentHero)) {
            showHero(heroes.get(Math.min(heroIndex, heroes.size() - 1)));
        }
    }

    private void showHero(JSONObject o) {
        if (o == null || o == currentHero) return;
        currentHero = o;
        heroService.setText(o.optString("service"));
        heroTitle.setText(o.optString("title"));
        String season = o.optString("season", "");
        String line = o.optString("line", "");
        String because = o.optString("because", "");
        heroLine.setText(!because.isEmpty() ? S.t("discover.because", "name", because) : season.isEmpty() ? line : season + "  ·  " + line);
        heroOverview.setText(o.optString("overview", ""));
        int i = heroes.indexOf(o);
        for (int k = 0; k < dots.getChildCount(); k++) dots.getChildAt(k).setAlpha(k == i ? 1f : 0.3f);
        String img = o.optString("image", null);
        heroImage.animate().alpha(0.25f).setDuration(120).withEndAction(() ->
                loadImage(heroImage, img, getResources().getDisplayMetrics().widthPixels, () ->
                        heroImage.animate().alpha(1f).setDuration(220).start())).start();
    }

    private View section(String title, View row) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(title, 17, medium, FG);
        t.setPadding(dp(48), dp(18), 0, dp(8));
        s.addView(t);
        s.addView(row);
        return s;
    }

    private HorizontalScrollView hrow(LinearLayout items) {
        // Det valgte kort står altid ved rækkens venstre margen, flugtende med overskriften (som Google TV).
        // Standarden ruller kun lige nok, så det sidste kort endte ude ved skærmkanten og rækken stod skævt.
        HorizontalScrollView h = new HorizontalScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
                int max = Math.max(0, items.getWidth() - getWidth());
                int target = Math.max(0, Math.min(max, r.left - items.getPaddingLeft()));
                return target - getScrollX();
            }
        };
        h.setHorizontalScrollBarEnabled(false);
        h.setClipToPadding(false);
        // Plads til det fokuserede korts forstørrelse og hvide kant.
        items.setPadding(dp(48), dp(6), dp(48), dp(6));
        items.setClipChildren(false);
        h.setClipChildren(false);
        h.addView(items);
        return h;
    }

    private View cardsRow(JSONArray arr, boolean ready) {
        LinearLayout items = new LinearLayout(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            String sub = ready
                    ? o.optString("episode") + (o.optInt("count", 1) > 1 ? "  ·  " + S.t("tvhome.newCount", "n", o.optInt("count")) : "") + "  ·  " + o.optString("service")
                    : o.optString("when") + "  ·  " + o.optString("episode");
            View c = card(o.optString("title"), sub, o.optString("image", null));
            c.setOnClickListener(x -> launch(o));
            JSONObject hero = heroById.get(o.optString("id"));
            c.setOnFocusChangeListener(chain(c, (x, has) -> { if (has && hero != null) showHero(hero); }));
            items.addView(c, sized(212, 119, 14));
        }
        return hrow(items);
    }

    /** Serier på pause: samme kort, men uden at skifte den store serie øverst. */
    private View pausedRow(JSONArray arr) {
        LinearLayout items = new LinearLayout(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            int n = o.optInt("count", 0);
            String sub = (n > 0 ? S.t("tvhome.toWatch", "n", n) + "  ·  " : "") + o.optString("service");
            View c = card(o.optString("title"), sub, o.optString("image", null));
            c.setOnClickListener(x -> launch(o));
            c.setOnFocusChangeListener(chain(c, (x, has) -> { if (has) restoreHero(); }));
            items.addView(c, sized(212, 119, 14));
        }
        return hrow(items);
    }

    /** Opdag serier: store kort med billede, "Fordi du ser X" og de første linjer af beskrivelsen. */
    private View discoverRow(JSONArray arr) {
        LinearLayout items = new LinearLayout(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            FrameLayout c = new FrameLayout(this);
            c.setFocusable(true);
            c.setBackgroundColor(SURFACE);
            rounded(c, 12);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            c.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View shade = new View(this);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x99000000, 0xF2000000}));
            c.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            txt.setPadding(dp(14), 0, dp(14), dp(12));
            TextView t = text(o.optString("title"), 16, bold, FG);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String because = o.optString("because", "");
            String sub = (because.isEmpty() ? "" : "Fordi du ser " + because + "  ·  ") + o.optString("service");
            TextView s = text(sub, 12, medium, MUTED);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView ov = text(o.optString("overview", ""), 11, light, MUTED);
            ov.setMaxLines(2);
            ov.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(t);
            txt.addView(s, margins(0, 2, 0, 0));
            txt.addView(ov, margins(0, 6, 0, 0));
            c.addView(txt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            loadImage(iv, o.optString("image", null), dp(340), null);
            c.setOnFocusChangeListener(chain(c, (x, has) -> { if (has) showHero(o); }));
            c.setOnClickListener(x -> showInfo(o, c));
            items.addView(c, sized(340, 191, 14));
        }
        return hrow(items);
    }

    private View card(String title, String sub, String image) {
        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setBackgroundColor(SURFACE);
        rounded(c, 10);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x33000000, 0xE6000000}));
        c.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setPadding(dp(11), 0, dp(11), dp(9));
        TextView t = text(title, 14, semibold, FG);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView s = text(sub, 11, regular, MUTED);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(t);
        txt.addView(s);
        c.addView(txt, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        loadImage(iv, image, dp(212), null);
        c.setOnFocusChangeListener(chain(c, null));
        return c;
    }

    private View appsRow() {
        LinearLayout items = new LinearLayout(this);
        PackageManager pm = getPackageManager();
        // Rækken er brugerens valg (HomeApps); lange tryk på OK fjerner en app, og "Alle apps" føjer til.
        List<String> chosen = HomeApps.list(this);
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : HomeApps.installed(this)) if (chosen.contains(r.activityInfo.packageName)) apps.add(r);
        apps.sort((x, y) -> chosen.indexOf(x.activityInfo.packageName) - chosen.indexOf(y.activityInfo.packageName));
        for (ResolveInfo r : apps) {
            String pkg = r.activityInfo.packageName;
            FrameLayout tile = new FrameLayout(this);
            tile.setFocusable(true);
            tile.setBackgroundColor(SURFACE);
            rounded(tile, 8);
            Drawable banner = r.activityInfo.loadBanner(pm);
            if (banner == null) banner = r.activityInfo.applicationInfo.loadBanner(pm);
            if (banner != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setImageDrawable(banner);
                tile.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER);
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(r.loadIcon(pm));
                col.addView(iv, new LinearLayout.LayoutParams(dp(30), dp(30)));
                TextView l = text(String.valueOf(r.loadLabel(pm)), 10, medium, MUTED);
                l.setSingleLine(true);
                col.addView(l);
                tile.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            tile.setContentDescription(r.loadLabel(pm));
            tile.setOnClickListener(x -> launchPackage(pkg));
            tile.setOnLongClickListener(x -> {
                HomeApps.toggle(this, pkg);
                int at = items.indexOfChild(tile);
                items.removeView(tile);
                View next = items.getChildAt(Math.min(at, items.getChildCount() - 1));
                if (next != null) next.requestFocus();
                Toast.makeText(this, S.t("tvhome.homeRemoved", "app", String.valueOf(r.loadLabel(pm))), Toast.LENGTH_SHORT).show();
                return true;
            });
            tile.setOnFocusChangeListener(chain(tile, (x, has) -> { if (has) restoreHero(); }));
            items.addView(tile, sized(128, 72, 12));
        }
        // Sidste felt: undersiden med alle apps.
        TextView all = text(S.t("tvhome.allApps"), 13, semibold, FG);
        all.setGravity(Gravity.CENTER);
        all.setFocusable(true);
        all.setBackgroundColor(SURFACE);
        rounded(all, 8);
        all.setOnClickListener(x -> openApps());
        all.setOnFocusChangeListener(chain(all, null));
        items.addView(all, sized(128, 72, 12));
        return hrow(items);
    }

    // ---- Fokus, billeder og start af apps ---------------------------------------------------

    /**
     * Fokus: en hvid kant inden i kortet, og de andre kort dæmpes en anelse (det fokuserede er det
     * klareste). Ingen forstørrelse: den blev skåret af ved kanten (Afspil, første kort) og koster på et
     * langsomt tv. Knapperne øverst skifter i stedet fyld: den valgte er hvid, som i mockuppen.
     */
    private View.OnFocusChangeListener chain(View v, View.OnFocusChangeListener then) {
        return (x, has) -> {
            if (v.getTag() instanceof String) {
                pillState((TextView) v, has);
            } else {
                v.setForeground(has ? focusRing(dp(10), dp(3)) : null);
            }
            if (then != null) then.onFocusChange(x, has);
        };
    }

    /** Hvid kant med en mørk streg inden i, så rammen også kan ses på lyse logoer (Netflix, YouTube). */
    static android.graphics.drawable.Drawable focusRing(int radius, int w) {
        GradientDrawable outer = new GradientDrawable();
        outer.setCornerRadius(radius);
        outer.setStroke(w, FG);
        GradientDrawable inner = new GradientDrawable();
        inner.setCornerRadius(Math.max(0, radius - w));
        inner.setStroke(Math.max(1, w / 2), 0xFF0E0E10);
        android.graphics.drawable.LayerDrawable l = new android.graphics.drawable.LayerDrawable(new Drawable[]{outer, inner});
        l.setLayerInset(1, w, w, w, w);
        return l;
    }

    private TextView pill(String label, boolean primary) {
        TextView p = text(label, 15, semibold, FG);
        p.setTag("pill");
        p.setFocusable(true);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(24), dp(10), dp(24), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(99));
        p.setBackground(bg);
        pillState(p, primary);
        return p;
    }

    /** Valgt: fyldt hvid med mørk tekst. Ellers: mørk, gennemskinnelig med lys tekst. */
    private void pillState(TextView p, boolean on) {
        ((GradientDrawable) p.getBackground()).setColor(on ? FG : 0x40F2F1EC);
        p.setTextColor(on ? 0xFF0E0E10 : FG);
    }

    private void launch(JSONObject o) {
        if (o == null) return;
        String app = o.optString("app", "");
        if (app.isEmpty() || "null".equals(app)) {
            Toast.makeText(this, S.t("tvhome.unknownApp"), Toast.LENGTH_SHORT).show();
            return;
        }
        launchPackage(app);
    }

    private void openApps() {
        startActivity(new Intent(this, AppsActivity.class));
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, S.t("tvhome.notInstalled"), Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Læser et billede fra feed-mappen i baggrunden, nedskaleret til omtrent `width` px. */
    private void loadImage(ImageView iv, String rel, int width, Runnable done) {
        if (rel == null || rel.isEmpty() || "null".equals(rel)) {
            iv.setImageDrawable(null);
            if (done != null) done.run();
            return;
        }
        Bitmap hit = cache.get(rel + "@" + width);
        if (hit != null) {
            iv.setImageBitmap(hit);
            if (iv == heroImage) fitTop(iv);
            if (done != null) done.run();
            return;
        }
        iv.setTag(rel);
        File feedDir = FeedProvider.dir(this);
        io.execute(() -> {
            Bitmap bm = null;
            File f = Images.local(feedDir, rel);   // henter adresser første gang (feed version 2)
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int s = 1;
                while (o.outWidth / (s * 2) >= width) s *= 2;
                BitmapFactory.Options d = new BitmapFactory.Options();
                d.inSampleSize = s;
                d.inPreferredConfig = Bitmap.Config.RGB_565;
                bm = BitmapFactory.decodeFile(f.getPath(), d);
            } catch (OutOfMemoryError ignored) {
                cache.evictAll();
            }
            Bitmap result = bm;
            main.post(() -> {
                if (result != null) cache.put(rel + "@" + width, result);
                if (rel.equals(iv.getTag())) {
                    iv.setImageBitmap(result);
                    if (iv == heroImage) fitTop(iv);
                }
                if (done != null) done.run();
            });
        });
    }

    /** Dækker hele billedfeltet, centreret vandret og forankret i toppen (CENTER_CROP skar hoveder af). */
    private void fitTop(ImageView iv) {
        Drawable d = iv.getDrawable();
        int vw = iv.getWidth(), vh = iv.getHeight();
        if (d == null || vw == 0 || vh == 0 || d.getIntrinsicWidth() <= 0) return;
        float bw = d.getIntrinsicWidth(), bh = d.getIntrinsicHeight();
        float s = Math.max(vw / bw, vh / bh);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s);
        m.postTranslate((vw - bw * s) / 2f, 0);
        iv.setImageMatrix(m);
    }

    // ---- Små hjælpere ------------------------------------------------------------------------

    private TextView text(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit());
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private void rounded(View v, int radiusDp) {
        int r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) { o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r); }
        });
        v.setClipToOutline(true);
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams sized(int w, int h, int right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.rightMargin = dp(right);
        return lp;
    }

    /**
     * Layoutet er tegnet til en skærm, der er 960 enheder bred (mockuppen). Philips-fjernsynet melder en
     * tæthed, der ikke passer til dets 1920 px brede skærm, så enheden regnes ud fra bredden i stedet.
     */
    private float unit() {
        return getResources().getDisplayMetrics().widthPixels / 960f;
    }

    private int dp(int v) {
        return Math.round(v * unit());
    }
}
