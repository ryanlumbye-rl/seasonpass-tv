package dk.seasonpass.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Philips-firmwaren (PH0M_EA_T32) sender selv Live TV i front, når fjernsynet tænder. Denne hjælper
 * sender den ene Live TV-skærm tilbage til Home, og ikke mere (25-10-2026; samme idé som
 * CleanYourTV's power_on_home):
 * - kun Live TV-appen (packageNames i res/xml/power_on_service.xml), ingen andre apps ses;
 * - kun i et vindue efter genstart eller efter skærmen tænder, og kun første gang i vinduet,
 *   så Live TV valgt med SOURCES bagefter får lov at blive;
 * - ingen tastefiltrering (den kan ødelægge gentagelse af holdte taster på nogle tv).
 *
 * Slås til over adb (tv-home/set-home.sh):
 *   settings put secure enabled_accessibility_services dk.seasonpass.tvhome/.PowerOnService
 *   settings put secure accessibility_enabled 1
 */
public class PowerOnService extends AccessibilityService {

    static final String LIVE_TV = "com.mediatek.wwtv.tvcenter";
    /** Efter genstart når firmwaren at åbne Live TV op til ca. 25 s efter opstart; med god margen. */
    static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;
    /** Efter standby kommer Live TV inden for få sekunder. */
    static final long SCREEN_ON_WINDOW_MS = 60 * 1000L;

    private long armedUntil;
    private BroadcastReceiver screenOn;

    @Override
    protected void onServiceConnected() {
        // Systemet holder denne tjenestes proces i live, så modtageren til telefonen svarer også,
        // mens en anden app er fremme (se SyncServer).
        SyncServer.start(this);
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) {
            armedUntil = BOOT_WINDOW_MS;
            Profiles.sessionStart(this);
        }
        screenOn = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                armedUntil = SystemClock.elapsedRealtime() + SCREEN_ON_WINDOW_MS;
                Profiles.sessionStart(c);   // tv'et tændt: ny session, første telefon der sender, overtager
            }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!shouldRedirect(e.getPackageName(), SystemClock.elapsedRealtime(), armedUntil)) return;
        armedUntil = 0;
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    static boolean shouldRedirect(CharSequence pkg, long now, long armedUntil) {
        return pkg != null && LIVE_TV.contentEquals(pkg) && now < armedUntil;
    }

    @Override public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (screenOn != null) unregisterReceiver(screenOn);
        super.onDestroy();
    }
}
