package dk.seasonpass.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Vores egne valgmenuer og spørgsmål (0.11.6), i samme sprog som forsiden og Indstillinger: mørkt rundt kort,
 * rækker med hvid fokuskant og hvide pille-knapper. Erstatter Androids standarddialoger.
 */
final class Popup {

    private static final int SURFACE = 0xFF18181B;
    private static final int FG = 0xFFF2F1EC;
    private static final int MUTED = 0xADF2F1EC;

    private Popup() { }

    private static float unit(Activity a) { return a.getResources().getDisplayMetrics().widthPixels / 960f; }
    private static int dp(Activity a, int v) { return Math.round(v * unit(a)); }

    private static TextView text(Activity a, String s, float sp, Typeface tf, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit(a));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private static Dialog base(Activity a, LinearLayout card) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(card);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(dp(a, 520), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private static LinearLayout card(Activity a, String title) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(a, 14), dp(a, 18), dp(a, 14), dp(a, 14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 16));
        bg.setColor(SURFACE);
        card.setBackground(bg);
        TextView t = text(a, title, 22, a.getResources().getFont(R.font.urbanist_bold), FG);
        t.setPadding(dp(a, 12), 0, dp(a, 12), dp(a, 12));
        card.addView(t);
        return card;
    }

    /** En liste at vælge i; det nuværende (checked, eller -1) er mærket med en hvid pille. Vælger man, lukker den. */
    static void choose(Activity a, String title, List<String> items, int checked, IntConsumer pick) {
        Typeface semibold = a.getResources().getFont(R.font.urbanist_semibold);
        LinearLayout card = card(a, title);
        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(list);
        card.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialog d = base(a, card);
        View focus = null;
        for (int i = 0; i < items.size(); i++) {
            final int at = i;
            LinearLayout r = new LinearLayout(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setFocusable(true);
            r.setDefaultFocusHighlightEnabled(false);
            r.setPadding(dp(a, 14), dp(a, 11), dp(a, 14), dp(a, 11));
            r.addView(text(a, items.get(i), 17, semibold, FG), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i == checked) {
                TextView chip = text(a, "✓", 14, semibold, 0xFF0E0E10);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 4));
                GradientDrawable cb = new GradientDrawable();
                cb.setCornerRadius(dp(a, 99));
                cb.setColor(FG);
                chip.setBackground(cb);
                r.addView(chip);
                focus = r;
            }
            glow(a, r);
            r.setOnClickListener(x -> { d.dismiss(); pick.accept(at); });
            list.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (focus == null && i == 0) focus = r;
        }
        final View first = focus;
        d.setOnShowListener(x -> { if (first != null) first.post(first::requestFocus); });
        d.show();
    }

    /** Et spørgsmål med to pille-knapper; ja står først og har fokus. */
    static void ask(Activity a, String title, String yes, String no, Runnable onYes) {
        Typeface semibold = a.getResources().getFont(R.font.urbanist_semibold);
        LinearLayout card = card(a, title);
        LinearLayout buttons = new LinearLayout(a);
        buttons.setPadding(dp(a, 12), dp(a, 6), dp(a, 12), dp(a, 4));
        Dialog d = base(a, card);
        TextView y = button(a, yes, true, semibold);
        y.setOnClickListener(x -> { d.dismiss(); if (onYes != null) onYes.run(); });
        TextView n = button(a, no, false, semibold);
        n.setOnClickListener(x -> d.dismiss());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(a, 10), 0);
        buttons.addView(y, lp);
        buttons.addView(n);
        card.addView(buttons);
        d.setOnShowListener(x -> y.post(y::requestFocus));
        d.show();
    }

    private static TextView button(Activity a, String label, boolean primary, Typeface tf) {
        TextView p = text(a, label, 16, tf, FG);
        p.setFocusable(true);
        p.setDefaultFocusHighlightEnabled(false);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(a, 24), dp(a, 10), dp(a, 24), dp(a, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(a, 99));
        p.setBackground(bg);
        Runnable state = () -> {
            boolean on = p.hasFocus();
            bg.setColor(on ? FG : 0x40F2F1EC);
            p.setTextColor(on ? 0xFF0E0E10 : FG);
        };
        state.run();
        p.setOnFocusChangeListener((v, has) -> state.run());
        return p;
    }

    private static void glow(Activity a, View r) {
        r.setOnFocusChangeListener((v, has) -> {
            if (!has) { r.setBackground(null); r.setForeground(null); return; }
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(a, 10));
            g.setColor(0x14F2F1EC);
            r.setBackground(g);
            r.setForeground(MainActivity.focusRing(dp(a, 10), dp(a, 3)));
        });
    }
}
