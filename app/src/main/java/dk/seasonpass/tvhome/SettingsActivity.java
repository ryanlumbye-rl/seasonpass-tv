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
        col.addView(hint, margins(0, 4, 0, 6));

        LinearLayout home = section(S.t("tvhome.sectionHome"));
        row(home, S.t("tvhome.homeApps"), S.t("tvhome.homeAppsValue", "n", HomeApps.count(this)), 0, x -> startActivity(new Intent(this, AppsActivity.class)));

        LinearLayout saver = section(S.t("tvhome.saver"));
        row(saver, S.t("tvhome.saver"), saverState(), 0, x -> {
            if (!Prefs.setDream(this, !Prefs.dreamOn(this))) noRight();
            else rebuild();
        });
        row(saver, S.t("tvhome.content"), S.t(Prefs.label(Prefs.CONTENT_KEYS, Prefs.CONTENT_LABELS, Prefs.content(this))), 0, x -> {
            Prefs.sp(this).edit().putString("saver_content", Prefs.cycle(Prefs.CONTENT_KEYS, Prefs.content(this))).apply();
            rebuild();
        });
        row(saver, S.t("tvhome.clock"), S.t(Prefs.label(Prefs.CLOCK_KEYS, Prefs.CLOCK_LABELS, Prefs.clock(this))), 0, x -> {
            Prefs.sp(this).edit().putString("saver_clock", Prefs.cycle(Prefs.CLOCK_KEYS, Prefs.clock(this))).apply();
            rebuild();
        });
        boolean musicOn = Prefs.music(this);
        row(saver, S.t("tvhome.music"), musicState(), !musicOn ? 2 : Prefs.listenerOn(this) ? 1 : 3, x -> {
            if (!Prefs.music(this) || !Prefs.listenerOn(this)) {
                Prefs.sp(this).edit().putBoolean("saver_music", true).apply();
                if (!Prefs.listenerOn(this)) askListener();
            } else {
                Prefs.sp(this).edit().putBoolean("saver_music", false).apply();
            }
            rebuild();
        });
        row(saver, S.t("tvhome.tryDream"), "›", 0, x -> startActivity(new Intent(this, SaverPreviewActivity.class)));

        LinearLayout system = section(S.t("tvhome.sectionSystem"));
        row(system, S.t("tvhome.update"), updateState(), update != null ? 1 : 0, x -> updateClicked());
        row(system, S.t("tvhome.tvSettings"), "›", 0, x -> {
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
    private void row(LinearLayout card, String label, String value, int kind, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(dp(18), dp(10), dp(14), dp(10));
        r.addView(text(label, 17, semibold, FG), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
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
