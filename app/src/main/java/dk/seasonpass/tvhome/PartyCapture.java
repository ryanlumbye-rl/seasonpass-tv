package dk.seasonpass.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;

/**
 * Lyd til partytilstanden (0.12.1). Fjernsynets Visualizer virker ikke for apps (fejl -3), så lyden fra andre apps
 * fanges med Androids "audio playback capture" i stedet: man siger ja til en skærmoptagelses-dialog, én gang pr. start,
 * og tjenesten her lytter, regner et spektrum ud og lægger det i bands[]. Intet gemmes eller sendes nogen steder.
 * Giver en app ikke lov til at blive optaget, er der bare stilhed, og så tegner partytilstanden en animation i stedet.
 */
public class PartyCapture extends Service {

    static final int BANDS = 48;
    /** Seneste spektrum, 0..1 pr. bånd (log-skala, bas først), og hvornår der sidst var lyd i. */
    static final float[] bands = new float[BANDS];
    static volatile long lastSignal;
    static volatile boolean running;
    static volatile String error = "";

    private static final int N = 1024;
    private MediaProjection projection;
    private AudioRecord rec;
    private Thread thread;
    private volatile boolean stop;

    static void start(Context c, int resultCode, Intent data) {
        Intent i = new Intent(c, PartyCapture.class).putExtra("code", resultCode).putExtra("data", data);
        c.startForegroundService(i);
    }

    static void halt(Context c) {
        c.stopService(new Intent(c, PartyCapture.class));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("party", "Party", NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "party").setContentTitle("Party")
                    .setSmallIcon(android.R.drawable.ic_media_play).build();
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            if (in == null || running) return START_NOT_STICKY;
            MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(in.getIntExtra("code", 0), in.getParcelableExtra("data", Intent.class));
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stop = true; }
            }, null);
            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            rec = new AudioRecord.Builder().setAudioPlaybackCaptureConfig(cfg).setAudioFormat(fmt)
                    .setBufferSizeInBytes(N * 2 * 8).build();
            rec.startRecording();
            running = true;
            error = "";
            stop = false;
            thread = new Thread(this::loop, "party-capture");
            thread.start();
        } catch (Exception e) {
            error = String.valueOf(e);
            android.util.Log.w("PartyCapture", "kunne ikke starte: " + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        short[] buf = new short[N];
        float[] re = new float[N], im = new float[N], win = new float[N];
        for (int i = 0; i < N; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (N - 1)));
        float peak = 1f;
        while (!stop) {
            int got = 0;
            while (got < N && !stop) {
                int r = rec.read(buf, got, N - got);
                if (r <= 0) { stop = true; break; }
                got += r;
            }
            if (stop) break;
            double e = 0;
            for (int i = 0; i < N; i++) { re[i] = buf[i] / 32768f * win[i]; im[i] = 0; e += Math.abs(buf[i]); }
            if (e / N > 4) lastSignal = System.currentTimeMillis();   // der er faktisk lyd (ikke bare nuller)
            fft(re, im);
            float top = 0;
            float[] m = new float[BANDS];
            for (int b = 0; b < BANDS; b++) {
                int lo = Math.max(1, (int) Math.pow(300, b / (double) BANDS));
                int hi = Math.max(lo + 1, (int) Math.pow(300, (b + 1) / (double) BANDS));
                float mx = 0;
                for (int k = lo; k < hi && k < N / 2; k++) mx = Math.max(mx, (float) Math.hypot(re[k], im[k]));
                mx *= 1f + b * 0.04f;   // diskanten er svagere; løft den en smule
                m[b] = mx;
                top = Math.max(top, mx);
            }
            peak = Math.max(top, peak * 0.995f);   // auto-forstærkning: høj og lav musik fylder lige meget
            float ref = Math.max(peak, 0.02f);
            for (int b = 0; b < BANDS; b++) bands[b] = (float) Math.min(1.0, Math.pow(m[b] / ref, 0.8));
        }
        running = false;
    }

    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { float t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    float nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
        try { if (projection != null) projection.stop(); } catch (Exception ignored) { }
        running = false;
        super.onDestroy();
    }
}
