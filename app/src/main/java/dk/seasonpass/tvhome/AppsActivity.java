package dk.seasonpass.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Undersiden "Apps": alle apps til fjernsynet i et gitter. Tilbage-knappen går til forsiden. */
public class AppsActivity extends Activity {

    private static final int SURFACE = 0xFF18181B;
    private static final int FG = 0xFFF2F1EC;
    private static final int COLS = 5;

    private Typeface semibold, bold;
    private Strings S;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        semibold = getResources().getFont(R.font.urbanist_semibold);
        bold = getResources().getFont(R.font.urbanist_bold);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(56), dp(48), dp(56), dp(40));
        scroll.addView(col);

        S = Strings.of(this);
        TextView title = text(S.t("tvhome.apps"), 40, bold);
        title.setLetterSpacing(-0.02f);
        col.addView(title);
        TextView hint = text(S.t("tvhome.appsHint"), 13, semibold);
        hint.setAlpha(0.68f);
        hint.setPadding(0, dp(6), 0, dp(14));
        col.addView(hint);

        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
        }
        apps.sort((a, c) -> String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(c.loadLabel(pm))));

        LinearLayout row = null;
        View first = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % COLS == 0) {
                row = new LinearLayout(this);
                col.addView(row);
            }
            View t = tile(apps.get(i), pm);
            if (first == null) first = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(90));
            lp.setMargins(0, dp(6), dp(14), dp(6));
            row.addView(t, lp);
        }
        setContentView(scroll);
        if (first != null) first.requestFocus();
    }

    private View tile(ResolveInfo r, PackageManager pm) {
        String pkg = r.activityInfo.packageName;
        FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setDefaultFocusHighlightEnabled(false);   // vi tegner selv fokus; systemets firkantede skær skinner ellers igennem de runde former
        tile.setBackgroundColor(SURFACE);
        int radius = dp(8);
        tile.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
        });
        tile.setClipToOutline(true);
        Drawable banner = r.activityInfo.loadBanner(pm);
        if (banner == null) banner = r.activityInfo.applicationInfo.loadBanner(pm);
        if (banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            iv.setImageDrawable(banner);
            tile.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            LinearLayout c = new LinearLayout(this);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(r.loadIcon(pm));
            c.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView l = text(String.valueOf(r.loadLabel(pm)), 11, semibold);
            l.setSingleLine(true);
            c.addView(l);
            tile.addView(c, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        tile.setContentDescription(r.loadLabel(pm));
        tile.setOnFocusChangeListener((x, has) -> tile.setForeground(has ? MainActivity.focusRing(radius, dp(3)) : null));
        tile.setOnClickListener(x -> {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, Strings.of(this).t("tvhome.notInstalled"), Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        // Mærket: appen står på forsiden. Hold OK nede for at føje den til eller fjerne den.
        TextView badge = text("✓", 13, bold);
        badge.setGravity(Gravity.CENTER);
        badge.setTextColor(0xFF0E0E10);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(FG);
        badge.setBackground(bb);
        badge.setVisibility(HomeApps.has(this, pkg) ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
        blp.setMargins(0, dp(6), dp(6), 0);
        tile.addView(badge, blp);
        tile.setOnLongClickListener(x -> {
            boolean on = HomeApps.toggle(this, pkg);
            badge.setVisibility(on ? View.VISIBLE : View.GONE);
            Toast.makeText(this, S.t(on ? "tvhome.homeAdded" : "tvhome.homeRemoved", "app", String.valueOf(r.loadLabel(pm))), Toast.LENGTH_SHORT).show();
            return true;
        });
        return tile;
    }

    private TextView text(String s, float sp, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * unit());
        t.setTypeface(tf);
        t.setTextColor(FG);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Samme enhed som forsiden: layoutet er tegnet til 960 enheders bredde. */
    private float unit() {
        return getResources().getDisplayMetrics().widthPixels / 960f;
    }

    private int dp(int v) {
        return Math.round(v * unit());
    }
}
