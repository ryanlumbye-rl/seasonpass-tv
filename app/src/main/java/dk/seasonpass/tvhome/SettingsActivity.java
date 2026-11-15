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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        regular = getResources().getFont(R.font.urbanist_regular);
        semibold = getResources().getFont(R.font.urbanist_semibold);
        bold = getResources().getFont(R.font.urbanist_bold);
        build(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        View f = getCurrentFocus();
        int idx = f == null ? -1 : col.indexOfChild(f);
        build(idx >= 0 ? idx : null);
    }

    private void build(Integer focusIndex) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(col);

        TextView title = text("Indstillinger", 30, bold, FG);
        title.setPadding(0, 0, 0, dp(16));
        col.addView(title);

        row("Pauseskærm", saverState(), x -> {
            if (!Prefs.setDream(this, !Prefs.dreamOn(this))) noRight();
            else rebuild();
        });
        row("Pauseskærmens indhold", Prefs.label(Prefs.CONTENT_KEYS, Prefs.CONTENT_LABELS, Prefs.content(this)), x -> {
            Prefs.sp(this).edit().putString("saver_content", Prefs.cycle(Prefs.CONTENT_KEYS, Prefs.content(this))).apply();
            rebuild();
        });
        row("Ur", Prefs.label(Prefs.CLOCK_KEYS, Prefs.CLOCK_LABELS, Prefs.clock(this)), x -> {
            Prefs.sp(this).edit().putString("saver_clock", Prefs.cycle(Prefs.CLOCK_KEYS, Prefs.clock(this))).apply();
            rebuild();
        });
        row("Musik i pauseskærmen", musicState(), x -> {
            if (!Prefs.music(this) || !Prefs.listenerOn(this)) {
                Prefs.sp(this).edit().putBoolean("saver_music", true).apply();
                if (!Prefs.listenerOn(this)) askListener();
            } else {
                Prefs.sp(this).edit().putBoolean("saver_music", false).apply();
            }
            rebuild();
        });
        row("Prøv pauseskærmen", "", x -> startActivity(new Intent(this, SaverPreviewActivity.class)));
        row("Opdatering", updateState(), x -> updateClicked());
        row("Tv'ets indstillinger", "", x -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "Indstillinger kunne ikke åbnes", Toast.LENGTH_SHORT).show();
            }
        });

        hint = text("", 12, regular, MUTED);
        hint.setPadding(dp(4), dp(14), dp(4), 0);
        hint.setText("SeasonPass " + Updater.installedVersion(this));
        col.addView(hint);

        setContentView(scroll);
        View first = col.getChildAt(1 + (focusIndex == null ? 0 : Math.max(0, focusIndex - 1)));
        if (first != null) first.requestFocus();
    }

    private void rebuild() {
        View f = getCurrentFocus();
        int idx = f == null ? 1 : col.indexOfChild(f);
        build(idx);
    }

    private String saverState() {
        if (Prefs.dreamOn(this)) return "SeasonPass";
        return Prefs.canWriteSecure(this) ? "Tv'ets egen · tryk for at bruge SeasonPass" : "Tv'ets egen";
    }

    private String musicState() {
        if (!Prefs.music(this)) return "Fra";
        return Prefs.listenerOn(this) ? "Til" : "Til · mangler adgang";
    }

    private String updateState() {
        if (!Updater.enabled()) return "Version " + Updater.installedVersion(this);
        if (checking) return "Søger …";
        if (update != null) return "Opdatér til " + update.version;
        return "Version " + Updater.installedVersion(this) + " · tryk for at søge";
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
            Toast.makeText(this, "Opdateringer er ikke slået til i denne version", Toast.LENGTH_SHORT).show();
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
                    msg = "Henter og installerer " + u.version + " …";
                } else {
                    found = Updater.check(this);
                    msg = found == null ? "Du har den nyeste version" : null;
                }
            } catch (Exception e) {
                msg = "Kunne ikke opdatere: " + e.getMessage();
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

    private void row(String label, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setFocusable(true);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(SURFACE);
        r.setBackground(bg);
        TextView l = text(label, 17, semibold, FG);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(text(value, 15, regular, MUTED));
        r.setOnFocusChangeListener((v, has) -> r.setForeground(has ? MainActivity.focusRing(dp(10), dp(3)) : null));
        r.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(700), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(5), 0, dp(5));
        col.addView(r, lp);
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
