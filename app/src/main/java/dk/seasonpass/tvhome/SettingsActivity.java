package dk.seasonpass.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Indstillinger for SeasonPass på tv'et: pauseskærmens indhold og ur, musik, opdatering og vejen til tv'ets egne
 * indstillinger. Hver række er en knap, der skifter til næste valg (eller åbner noget).
 */
public class SettingsActivity extends Activity {

    private static final int SURFACE = 0xFF18181B;
    private static final int FG = 0xFFF2F1EC;
    private static final int MUTED = 0xADF2F1EC;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface regular, semibold, bold;
    private LinearLayout col;
    private TextView hint;
    private Updater.Info update;
    private boolean checking;
    private Strings S;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        S = Strings.of(this);
        regular = getResources().getFont(R.font.urbanist_regular);
        semibold = getResources().getFont(R.font.urbanist_semibold);
        bold = getResources().getFont(R.font.urbanist_bold);
        build(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        build(focusedRowIndex());
    }

    private void build(Integer focusIndex) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(col);

        // Samme sprog som forsiden: stor titel, dæmpet undertekst, mørke kort med tynde skillelinjer og hvide pille-valg.
        TextView title = text(S.t("tvhome.settings"), 36, bold, FG);
        title.setLetterSpacing(-0.02f);
        col.addView(title);
        hint = text("SeasonPass " + Updater.installedVersion(this), 13, regular, MUTED);
        LinearLayout versionRow = new LinearLayout(this);
        versionRow.setGravity(Gravity.CENTER_VERTICAL);
        versionRow.addView(hint);
        versionRow.addView(gtaBadge(), margins(12, 0, 0, 0));
        col.addView(versionRow, margins(0, 4, 0, 6));

        LinearLayout home = section(S.t("tvhome.sectionHome"));
        row(home, S.t("tvhome.homeApps"), S.t("tvhome.homeAppsValue", "n", HomeApps.count(this)) + ARROW, 0, S.t("tvhome.subHomeApps"), x -> startActivity(new Intent(this, AppsActivity.class)));
        row(home, S.t("tvhome.standalone"), S.t(Prefs.standalone(this) ? "tvhome.on" : "tvhome.off"), Prefs.standalone(this) ? 1 : 2, S.t("tvhome.subStandalone"), x -> {
            boolean on = !Prefs.standalone(this);
            Prefs.setStandalone(this, on);
            if (on) Toast.makeText(this, S.t("tvhome.standaloneHint"), Toast.LENGTH_LONG).show();
            rebuild();
        });

        LinearLayout saver = section(S.t("tvhome.saver"));
        row(saver, S.t("tvhome.saver"), saverState(), 0, S.t("tvhome.subSaver"), x -> {
            if (!Prefs.setDream(this, !Prefs.dreamOn(this))) noRight();
            else rebuild();
        });
        if (!Prefs.standalone(this)) row(saver, S.t("tvhome.content"), S.t(Prefs.label(Prefs.CONTENT_KEYS, Prefs.CONTENT_LABELS, Prefs.content(this))) + ARROW, 0, S.t("tvhome.subContent"), x -> {
            List<String> keys = new ArrayList<>(), names = new ArrayList<>();
            for (int i = 0; i < Prefs.CONTENT_KEYS.length; i++) {
                if (Prefs.CONTENT_KEYS[i].equals("gta") && !Console.unlocked(this)) continue;
                keys.add(Prefs.CONTENT_KEYS[i]);
                names.add(S.t(Prefs.CONTENT_LABELS[i]));
            }
            choose(S.t("tvhome.content"), names, keys.indexOf(Prefs.content(this)), i -> Prefs.sp(this).edit().putString("saver_content", keys.get(i)).apply());
        });
        row(saver, S.t("tvhome.clock"), S.t(Prefs.label(Prefs.CLOCK_KEYS, Prefs.CLOCK_LABELS, Prefs.clock(this))) + ARROW, 0, S.t("tvhome.subClock"), x -> {
            List<String> names = new ArrayList<>();
            for (String l : Prefs.CLOCK_LABELS) names.add(S.t(l));
            choose(S.t("tvhome.clock"), names, java.util.Arrays.asList(Prefs.CLOCK_KEYS).indexOf(Prefs.clock(this)),
                    i -> Prefs.sp(this).edit().putString("saver_clock", Prefs.CLOCK_KEYS[i]).apply());
        });
        boolean musicOn = Prefs.music(this);
        row(saver, S.t("tvhome.music"), musicState(), !musicOn ? 2 : Prefs.listenerOn(this) ? 1 : 3, S.t("tvhome.subMusic"), x -> {
            if (!Prefs.music(this) || !Prefs.listenerOn(this)) {
                Prefs.sp(this).edit().putBoolean("saver_music", true).apply();
                if (!Prefs.listenerOn(this)) askListener();
            } else {
                Prefs.sp(this).edit().putBoolean("saver_music", false).apply();
            }
            rebuild();
        });
        row(saver, S.t("tvhome.tryDream"), ARROW.trim(), 0, null, x -> startActivity(new Intent(this, SaverPreviewActivity.class)));

        // Nyheder: slået fra som standard; kilderne følger landet (News.sources).
        LinearLayout news = section(S.t("tvhome.sectionNews"));
        boolean newsOn = Prefs.news(this);
        row(news, S.t("tvhome.news"), S.t(newsOn ? "tvhome.on" : "tvhome.off"), newsOn ? 1 : 2, S.t("tvhome.subNews"), x -> {
            Prefs.sp(this).edit().putBoolean("saver_news", !Prefs.news(this)).apply();
            rebuild();
        });
        if (newsOn) {
            java.util.List<News.Source> choices = News.sources(News.country(S));
            row(news, S.t("tvhome.newsSource"), News.chosen(this, S).name + ARROW, 0, null, x -> {
                List<String> names = new ArrayList<>();
                int at = 0;
                News.Source now = News.chosen(this, S);
                for (int i = 0; i < choices.size(); i++) {
                    names.add(choices.get(i).name);
                    if (choices.get(i).id.equals(now.id)) at = i;
                }
                choose(S.t("tvhome.newsSource"), names, at, i -> Prefs.sp(this).edit().putString("news_source", choices.get(i).id).apply());
            });
        }

        // GTA: skjult, til mærket ved versionsnummeret er trykket; slået fra som standard.
        gtaRow = null;
        if (Console.unlocked(this)) {
            LinearLayout ps = section("GTA");
            boolean psOn = Console.enabled(this);
            ps.addView(gtaBanner(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row(ps, S.t("tvhome.console"), S.t(psOn ? "tvhome.on" : "tvhome.off"), psOn ? 1 : 2, null, x -> {
                Prefs.sp(this).edit().putBoolean("console", !Console.enabled(this)).apply();
                rebuild();
            });
            gtaRow = lastRow;
            int imgs = Console.images(this).size();
            if ((psOn || "gta".equals(Prefs.content(this))) && imgs > 1) {
                int fx = Console.fixedImage(this);
                row(ps, S.t("tvhome.gtaImages"), (fx < 0 ? S.t("tvhome.gtaMix") : S.t("tvhome.gtaPicture", "n", String.valueOf(Math.min(fx, imgs - 1) + 1))) + ARROW,
                        0, S.t("tvhome.subGtaImages"), x -> {
                    List<String> names = new ArrayList<>();
                    names.add(S.t("tvhome.gtaMix"));
                    for (int i = 1; i <= imgs; i++) names.add(S.t("tvhome.gtaPicture", "n", String.valueOf(i)));
                    int cur = Console.fixedImage(this);
                    choose(S.t("tvhome.gtaImages"), names, cur < 0 ? 0 : Math.min(cur, imgs - 1) + 1,
                            i -> Prefs.sp(this).edit().putString("gta_image", i == 0 ? "mix" : String.valueOf(i)).apply());
                });
            }
            if (psOn) {
                row(ps, S.t("tvhome.psnLogin"), Console.stats(this) != null ? S.t("tvhome.on") : S.t("tvhome.psnInPhone"),
                        Console.stats(this) != null ? 1 : 0, null, x -> { });
            }
        }

        LinearLayout system = section(S.t("tvhome.sectionSystem"));
        row(system, S.t("tvhome.update"), updateState(), update != null ? 1 : 0, null, x -> updateClicked());
        row(system, S.t("tvhome.tvSettings"), ARROW.trim(), 0, null, x -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, S.t("tvhome.cantOpen"), Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(scroll);
        View first = firstRow(focusIndex);
        if (first != null) first.requestFocus();
    }

    /** Den række, fokus skal ligge på: den, der havde det før (efter et valg), ellers den første. */
    private View firstRow(Integer index) {
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            if (c instanceof LinearLayout && c.getTag() instanceof String && c.getTag().equals("card")) {
                LinearLayout card = (LinearLayout) c;
                for (int k = 0; k < card.getChildCount(); k++) if (card.getChildAt(k).isFocusable()) rows.add(card.getChildAt(k));
            }
        }
        if (rows.isEmpty()) return null;
        return rows.get(Math.max(0, Math.min(index == null ? 0 : index, rows.size() - 1)));
    }

    /** Overskrift og et mørkt kort til rækkerne under den. */
    /** Smalt banner øverst i GTA-sektionen: viser, at GTA VI og PlayStation er bygget ind i pauseskærmen, . */
    private View gtaBanner() {
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFF2B2160, 0xFF9A4A86, 0xFFF08A5D});
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.addView(text("GTA VI", 15, bold, 0xFFFFFFFF));
        b.addView(text("PlayStation", 13, semibold, 0xD9FFFFFF), margins(10, 0, 0, 0));
        View gap = new View(this);
        b.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
        b.addView(text(S.t("tvhome.gtaBanner"), 12, regular, 0xCCFFFFFF));
        return b;
    }

    /** Et lille "VI" i solnedgangsfarver; tegnet her, ikke spillets eget logo. */
    private View gtaBadge() {
        TextView b = text("VI", 11, bold, 0xFF1A0B2E);
        b.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF6FA5, 0xFFFFA45B});
        bg.setCornerRadius(dp(99));
        b.setBackground(bg);
        b.setFocusable(true);
        b.setDefaultFocusHighlightEnabled(false);
        b.setOnFocusChangeListener((v, has) -> v.setForeground(has ? MainActivity.focusRing(dp(99), dp(2)) : null));
        b.setOnClickListener(x -> gta());
        return b;
    }

    /** Mærket ved siden af versionsnummeret: låser GTA op første gang og går derefter til valget. */
    private void gta() {
        if (!Console.unlocked(this)) {
            Console.unlock(this);
            Toast.makeText(this, "Spiller du GTA?", Toast.LENGTH_LONG).show();
        }
        rebuild();
        if (gtaRow != null) gtaRow.post(() -> gtaRow.requestFocus());
    }

    private LinearLayout section(String label) {
        TextView h = text(label.toUpperCase(S.locale), 12, semibold, MUTED);
        h.setLetterSpacing(0.1f);
        col.addView(h, margins(4, 20, 0, 8));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setTag("card");
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(SURFACE);
        card.setBackground(bg);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        col.addView(card, new LinearLayout.LayoutParams(dp(720), ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    private LinearLayout.LayoutParams margins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private void rebuild() {
        build(focusedRowIndex());
    }

    private Integer focusedRowIndex() {
        View f = getCurrentFocus();
        if (f == null || col == null) return null;
        int n = 0;
        for (int i = 0; i < col.getChildCount(); i++) {
            View c = col.getChildAt(i);
            if (c instanceof LinearLayout && "card".equals(c.getTag())) {
                LinearLayout card = (LinearLayout) c;
                for (int k = 0; k < card.getChildCount(); k++) {
                    if (card.getChildAt(k) == f) return n;
                    if (card.getChildAt(k).isFocusable()) n++;
                }
            }
        }
        return null;
    }

    private String saverState() {
        if (Prefs.dreamOn(this)) return "SeasonPass";
        return S.t(Prefs.canWriteSecure(this) ? "tvhome.saverTvTap" : "tvhome.saverTv");
    }

    private String musicState() {
        if (!Prefs.music(this)) return S.t("tvhome.off");
        return Prefs.listenerOn(this) ? S.t("tvhome.on") : S.t("tvhome.musicMissing");
    }

    private String updateState() {
        if (!Updater.enabled()) return S.t("tvhome.version", "v", Updater.installedVersion(this));
        if (checking) return S.t("tvhome.searching");
        if (update != null) return S.t("tvhome.updateTo", "v", update.version);
        return S.t("tvhome.versionTap", "v", Updater.installedVersion(this));
    }

    /** Adgangen til at se, hvad der spilles, kan kun gives af systemet: tv'ets egen skærm, ellers adb. */
    private void askListener() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Giv adgang ved at køre install.sh igen (eller: adb shell cmd notification allow_listener " + getPackageName()
                    + "/" + getPackageName() + ".MediaListener)", Toast.LENGTH_LONG).show();
        }
    }

    private void noRight() {
        Toast.makeText(this, "Kræver én tilladelse via adb, se INSTALL.md (install.sh giver den)", Toast.LENGTH_LONG).show();
    }

    private void updateClicked() {
        if (!Updater.enabled()) {
            Toast.makeText(this, S.t("tvhome.updatesOff"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (checking) return;
        final Updater.Info u = update;
        checking = true;
        rebuild();
        new Thread(() -> {
            String msg = null;
            Updater.Info found = null;
            try {
                if (u != null) {
                    Updater.install(this, u);
                    msg = S.t("tvhome.installing", "v", u.version);
                } else {
                    found = Updater.check(this);
                    // Samme sted som startskærmen: pillen "Ny version" dér følger med et manuelt tjek.
                    Prefs.sp(this).edit().putLong("update_checked", System.currentTimeMillis())
                            .putString("update_version", found == null ? "" : found.version).apply();
                    msg = found == null ? S.t("tvhome.latest") : null;
                }
            } catch (Exception e) {
                msg = S.t("tvhome.updateFail", "e", String.valueOf(e.getMessage()));
            }
            final String m = msg;
            final Updater.Info f = found;
            main.post(() -> {
                checking = false;
                if (f != null) update = f;
                if (m != null) Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                rebuild();
            });
        }).start();
    }

    /**
     * En række i et kort: navnet til venstre, valget til højre. kind: 0 dæmpet tekst, 1 hvid pille (til/nyt), 2 dæmpet pille (fra),
     * 3 dæmpet pille med advarsel. Fokus: hvid kant og et lyst skær, som kortene på forsiden.
     */
    private View gtaRow, lastRow;

    private static final String ARROW = "  ›";

    /** Vælg ét af flere: en liste med det nuværende afkrydset (frem for at skifte rundt, så man kan se alle muligheder). */
    private void choose(String title, List<String> names, int current, java.util.function.IntConsumer pick) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(names.toArray(new String[0]), current, (d, which) -> {
                    pick.accept(which);
                    d.dismiss();
                    rebuild();
                })
                .show();
    }

    private void row(LinearLayout card, String label, String value, int kind, View.OnClickListener click) {
        row(card, label, value, kind, null, click);
    }

    private void row(LinearLayout card, String label, String value, int kind, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        lastRow = r;
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setDefaultFocusHighlightEnabled(false);   // vi tegner selv fokus; systemets firkantede skær skinner ellers igennem de runde former
        r.setPadding(dp(18), dp(10), dp(14), dp(10));
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(text(label, 17, semibold, FG));
        if (sub != null) names.addView(text(sub, 12, regular, MUTED), margins(0, 2, 0, 0));
        r.addView(names, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (kind == 0) {
            r.addView(text(value, 15, regular, MUTED));
        } else {
            TextView chip = text(value, 14, semibold, kind == 1 ? 0xFF0E0E10 : FG);
            chip.setPadding(dp(16), dp(6), dp(16), dp(6));
            GradientDrawable cb = new GradientDrawable();
            cb.setCornerRadius(dp(99));
            cb.setColor(kind == 1 ? FG : 0x33F2F1EC);
            chip.setBackground(cb);
            r.addView(chip);
        }
        r.setOnFocusChangeListener((v, has) -> {
            if (!has) { r.setBackground(null); r.setForeground(null); return; }
            GradientDrawable glow = new GradientDrawable();
            glow.setCornerRadius(dp(10));
            glow.setColor(0x14F2F1EC);
            r.setBackground(glow);
            r.setForeground(MainActivity.focusRing(dp(10), dp(3)));
        });
        r.setOnClickListener(click);
        card.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView text(String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit());
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private float unit() { return getResources().getDisplayMetrics().widthPixels / 960f; }
    private int dp(int v) { return Math.round(v * unit()); }
}
