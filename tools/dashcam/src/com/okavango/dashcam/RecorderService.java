package com.okavango.dashcam;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.TextureView;
import android.view.WindowManager;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Corazón de la dashcam. Graba la cámara 0 (la salida del sistema 360) SOLO mientras el carro se mueve,
 * y la suelta en cuanto se detiene: así, cuando metes reversa (que siempre exige parar), la cámara ya
 * está libre para el sistema 360. Si alguien más tiene la cámara cuando el 360 se activa, el 360 queda
 * "sin señal" hasta reiniciar la pantalla; por eso nunca se graba con el carro quieto (salvo el clip manual).
 *
 * El movimiento se estima con la vibración del acelerómetro de la pantalla (desviación de |a| en 2 s).
 */
public class RecorderService extends Service implements SensorEventListener {

    static final String PREFS = "dashcam";
    static final String K_ARMED = "armed", K_START = "start_thr", K_STOP = "stop_thr";
    static final float DEF_START = 0.10f, DEF_STOP = 0.07f;
    /** Segundos seguidos por encima/debajo del umbral para decidir "en marcha" o "detenido". */
    static final long START_MS = 3000, STOP_MS = 2000;
    static final long SEGMENT_MS = 3 * 60 * 1000;
    static final long CLIP_MS = 60 * 1000;

    static final String ACTION_TOGGLE = "com.okavango.dashcam.TOGGLE";

    static volatile RecorderService instance;

    // --- estado que lee la pantalla principal
    volatile boolean recording, moving, accOn = true, armed, holdUntilStopped;
    volatile float vibration, startThr = DEF_START, stopThr = DEF_STOP, vibMin10 = 9, vibMax10;
    volatile long recordingSince, forcedUntil;
    volatile String lastError = "", lastEvent = "", folder = "";
    volatile File currentFile;

    private SharedPreferences prefs;
    private HandlerThread camThread;
    private Handler cam;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SensorManager sm;
    private PowerManager.WakeLock wake;
    private Camera camera;
    private MediaRecorder recorder;
    private long retryAfter;
    private FileWriter csv;

    // ventana de 2 s de la magnitud de la aceleración
    private final float[] mags = new float[400];
    private final long[] times = new long[400];
    private int magPos, magCount;
    private long aboveSince, belowSince;
    private final float[] hist10 = new float[20];
    private int histPos;

    // vista mínima (1×1, o un recuadro cuando la app está al frente) donde la cámara dibuja la vista previa
    private WindowManager wm;
    private TextureView preview;
    private WindowManager.LayoutParams previewLp;
    private volatile SurfaceTexture surface;
    private SurfaceTexture fallbackSurface;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        armed = prefs.getBoolean(K_ARMED, true);
        startThr = prefs.getFloat(K_START, DEF_START);
        stopThr = prefs.getFloat(K_STOP, DEF_STOP);

        camThread = new HandlerThread("dashcam");
        camThread.start();
        cam = new Handler(camThread.getLooper());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dashcam");
        wake.acquire();

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc != null) sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
        else lastError = "La pantalla no tiene acelerómetro: solo funciona el clip manual";

        readAcc();
        getContentResolver().registerContentObserver(Settings.System.getUriFor("ACC_OFF_ON_STATE"), false,
                new ContentObserver(main) {
                    @Override public void onChange(boolean self) { readAcc(); }
                });

        addPreviewWindow();
        startForeground(1, buildNotification("Dashcam lista"));
        openCsv();
        cam.postDelayed(tick, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_TOGGLE.equals(intent.getAction())) {
            toggle();
        }
        lastWidget = null;
        return START_STICKY;
    }

    // ------------------------------------------------------------------ controles desde la pantalla

    void setArmed(boolean a) {
        armed = a;
        prefs.edit().putBoolean(K_ARMED, a).apply();
        event(a ? "Dashcam activada" : "Dashcam desactivada");
    }

    void setThresholds(float start, float stop) {
        startThr = Math.max(0.02f, start);
        stopThr = Math.max(0.01f, Math.min(stop, startThr - 0.01f));
        prefs.edit().putFloat(K_START, startThr).putFloat(K_STOP, stopThr).apply();
    }

    /** Clip manual: graba 1 minuto aunque el carro esté quieto. No metas reversa mientras tanto. */
    void recordClip() {
        forcedUntil = SystemClock.elapsedRealtime() + CLIP_MS;
        retryAfter = 0;
        event("Clip manual de 1 minuto");
    }

    /** Botón grabar/detener (widget y pantalla): si graba, detiene; si no, graba ya (1 min, y sigue solo si el carro va en marcha). */
    void toggle() {
        if (recording || forcedUntil > SystemClock.elapsedRealtime()) stopNow();
        else {
            if (!armed) setArmed(true);
            recordClip();
        }
        lastWidget = null;
    }

    void stopNow() {
        forcedUntil = 0;
        // Que no vuelva a arrancar enseguida por movimiento: espera a que el carro pare.
        holdUntilStopped = moving;
        cam.post(new Runnable() { public void run() { stopRecording("detenido a mano"); } });
    }

    /** La pantalla principal muestra la vista previa en un recuadro mientras está al frente. */
    void showPreview(final boolean big) {
        main.post(new Runnable() {
            public void run() {
                if (preview == null) return;
                if (big) {
                    previewLp.width = 768;
                    previewLp.height = 288;
                    previewLp.x = 32;
                    previewLp.y = 40;
                } else {
                    previewLp.width = 1;
                    previewLp.height = 1;
                    previewLp.x = 0;
                    previewLp.y = 0;
                }
                try { wm.updateViewLayout(preview, previewLp); } catch (Throwable ignored) { }
            }
        });
    }

    // ------------------------------------------------------------------ lógica principal (cada 500 ms)

    private final Runnable tick = new Runnable() {
        public void run() {
            long now = SystemClock.elapsedRealtime();
            updateMotion(now);
            boolean forced = forcedUntil > now;
            if (!moving) holdUntilStopped = false;
            boolean want = accOn && (forced || (armed && moving && !holdUntilStopped));
            if (want && !recording && now >= retryAfter) startRecording();
            else if (!want && recording) {
                stopRecording(!accOn ? "carro apagado" : forced ? "" : !armed ? "dashcam desactivada"
                        : "carro detenido: cámara libre para el 360");
            }
            writeCsv(now);
            updateNotification();
            updateWidget();
            cam.postDelayed(this, 500);
        }
    };

    private void updateMotion(long now) {
        float v;
        synchronized (mags) {
            float mean = 0;
            int n = 0;
            for (int i = 0; i < magCount; i++) if (now - times[i] <= 2000) { mean += mags[i]; n++; }
            if (n < 10) { vibration = 0; return; }
            mean /= n;
            float var = 0;
            for (int i = 0; i < magCount; i++) if (now - times[i] <= 2000) var += (mags[i] - mean) * (mags[i] - mean);
            v = (float) Math.sqrt(var / n);
        }
        vibration = v;
        hist10[histPos] = v;
        histPos = (histPos + 1) % hist10.length;
        float mn = 9, mx = 0;
        for (float h : hist10) { if (h > 0) mn = Math.min(mn, h); mx = Math.max(mx, h); }
        vibMin10 = mn;
        vibMax10 = mx;

        if (v > startThr) { if (aboveSince == 0) aboveSince = now; belowSince = 0; }
        else if (v < stopThr) { if (belowSince == 0) belowSince = now; aboveSince = 0; }
        else { aboveSince = 0; belowSince = 0; }
        if (!moving && aboveSince != 0 && now - aboveSince >= START_MS) { moving = true; event("En movimiento"); }
        if (moving && belowSince != 0 && now - belowSince >= STOP_MS) { moving = false; event("Carro detenido"); }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        synchronized (mags) {
            mags[magPos] = m;
            times[magPos] = SystemClock.elapsedRealtime();
            magPos = (magPos + 1) % mags.length;
            if (magCount < mags.length) magCount++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    private void readAcc() {
        try {
            String v = Settings.System.getString(getContentResolver(), "ACC_OFF_ON_STATE");
            boolean on = v == null || !v.trim().equals("0");
            if (on != accOn) event(on ? "Carro encendido" : "Carro apagado");
            accOn = on;
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ grabación

    private void startRecording() {
        File dir = Storage.recordDir();
        folder = (Storage.isUsb(dir) ? "USB · " : "memoria interna · ") + dir.getAbsolutePath();
        int del = Storage.freeSpace(dir, null);
        if (del > 0) event("Borré " + del + " video(s) viejo(s) para hacer espacio");
        File f = new File(dir, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".mp4");
        try {
            camera = Camera.open(0);
        } catch (Throwable t) {
            camera = null;
            fail("Cámara ocupada (¿está abierto Vista 360?). Reintento en 10 s");
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            try {
                p.setPreviewSize(1280, 720);
                camera.setParameters(p);
            } catch (Throwable ignored) { }
            SurfaceTexture st = surface;
            if (st == null) {
                if (fallbackSurface == null) fallbackSurface = new SurfaceTexture(10);
                st = fallbackSurface;
            }
            camera.setPreviewTexture(st);
            camera.startPreview();
        } catch (Throwable t) {
            releaseCamera();
            fail("No se pudo iniciar la cámara: " + t.getMessage() + ". Reintento en 10 s");
            return;
        }
        int[] qualities = {CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_HIGH, -1};
        String err = "";
        for (int q : qualities) {
            try {
                camera.unlock();
                recorder = new MediaRecorder();
                recorder.setCamera(camera);
                recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
                if (q >= 0) {
                    if (!CamcorderProfile.hasProfile(0, q)) throw new RuntimeException("sin perfil " + q);
                    CamcorderProfile prof = CamcorderProfile.get(0, q);
                    recorder.setOutputFormat(prof.fileFormat);
                    recorder.setVideoFrameRate(prof.videoFrameRate);
                    int w = prof.videoFrameWidth, h = prof.videoFrameHeight;
                    if (w > 1280) { w = 1280; h = 720; }
                    recorder.setVideoSize(w, h);
                    recorder.setVideoEncodingBitRate(Math.min(prof.videoBitRate, 6000000));
                    recorder.setVideoEncoder(prof.videoCodec);
                } else {
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setVideoFrameRate(30);
                    recorder.setVideoSize(1280, 720);
                    recorder.setVideoEncodingBitRate(4000000);
                    recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                }
                recorder.setMaxDuration((int) SEGMENT_MS);
                recorder.setOutputFile(f.getAbsolutePath());
                recorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                    public void onInfo(MediaRecorder mr, int what, int extra) {
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                            cam.post(new Runnable() {
                                public void run() {
                                    // Corta en tramos de 3 min para que un archivo dañado no pierda todo.
                                    stopRecording("");
                                    retryAfter = 0;
                                }
                            });
                        }
                    }
                });
                recorder.setOnErrorListener(new MediaRecorder.OnErrorListener() {
                    public void onError(MediaRecorder mr, int what, int extra) {
                        cam.post(new Runnable() { public void run() { stopRecording("error del grabador"); } });
                    }
                });
                recorder.prepare();
                recorder.start();
                currentFile = f;
                recording = true;
                recordingSince = SystemClock.elapsedRealtime();
                lastError = "";
                event("Grabando " + f.getName());
                return;
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
                try { if (recorder != null) recorder.release(); } catch (Throwable ignored) { }
                recorder = null;
                f.delete();
                try { camera.reconnect(); } catch (Throwable e) { try { camera.lock(); } catch (Throwable ignored) { } }
            }
        }
        releaseCamera();
        fail("No se pudo grabar (" + err + "). Reintento en 10 s");
    }

    private void fail(String msg) {
        lastError = msg;
        event(msg);
        retryAfter = SystemClock.elapsedRealtime() + 10000;
    }

    private void stopRecording(String why) {
        if (recorder != null) {
            try { recorder.stop(); } catch (Throwable t) {
                if (currentFile != null && currentFile.length() < 1024) currentFile.delete();
            }
            try { recorder.release(); } catch (Throwable ignored) { }
            recorder = null;
        }
        releaseCamera();
        if (recording && why.length() > 0) event("Grabación detenida: " + why);
        recording = false;
        currentFile = null;
    }

    private void releaseCamera() {
        if (camera == null) return;
        try { camera.reconnect(); } catch (Throwable ignored) { }
        try { camera.stopPreview(); } catch (Throwable ignored) { }
        try { camera.release(); } catch (Throwable ignored) { }
        camera = null;
    }

    // ------------------------------------------------------------------ vista previa (ventana flotante)

    private void addPreviewWindow() {
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            preview = new TextureView(this);
            preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { surface = st; }
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { surface = null; return true; }
                public void onSurfaceTextureUpdated(SurfaceTexture st) { }
            });
            previewLp = new WindowManager.LayoutParams(1, 1,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            previewLp.gravity = Gravity.TOP | Gravity.END;
            wm.addView(preview, previewLp);
        } catch (Throwable t) {
            preview = null;
            event("Sin ventana de vista previa: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ registro y notificación

    private void event(String s) {
        lastEvent = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + s;
        csvLine("# " + lastEvent);
    }

    private void openCsv() {
        try {
            File dir = Storage.recordDir();
            File f = new File(dir, "registro_" + new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".csv");
            csv = new FileWriter(f, true);
            csv.write("hora,vibracion,en_movimiento,grabando,carro_encendido,umbral_arranque,umbral_parada\n");
            csv.flush();
        } catch (Throwable t) {
            csv = null;
        }
    }

    private long lastCsv;

    private void writeCsv(long now) {
        if (now - lastCsv < 1000) return;
        lastCsv = now;
        csvLine(String.format(Locale.US, "%s,%.4f,%d,%d,%d,%.3f,%.3f",
                new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()), vibration,
                moving ? 1 : 0, recording ? 1 : 0, accOn ? 1 : 0, startThr, stopThr));
    }

    private synchronized void csvLine(String s) {
        if (csv == null) return;
        try {
            csv.write(s);
            csv.write('\n');
            csv.flush();
        } catch (Throwable ignored) { }
    }

    private volatile String lastWidget;

    String widgetSubtitle() {
        if (recording) {
            long sec = (SystemClock.elapsedRealtime() - recordingSince) / 1000;
            return String.format(Locale.US, "grabando %d:%02d", sec / 60, sec % 60);
        }
        return !accOn ? "carro apagado" : !armed ? "automática apagada" : moving ? "en marcha" : "lista";
    }

    private void updateWidget() {
        String key = recording + "|" + widgetSubtitle();
        if (key.equals(lastWidget)) return;
        lastWidget = key;
        try { RecordWidget.render(this, recording, widgetSubtitle()); } catch (Throwable ignored) { }
    }

    private String lastNotif = "";

    private void updateNotification() {
        String s = recording ? "Grabando" : !accOn ? "Carro apagado" : !armed ? "Desactivada"
                : moving ? "En movimiento" : "Lista · carro detenido";
        if (s.equals(lastNotif)) return;
        lastNotif = s;
        try {
            ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, buildNotification(s));
        } catch (Throwable ignored) { }
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Dashcam Okavango")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        instance = null;
        cam.removeCallbacksAndMessages(null);
        cam.post(new Runnable() { public void run() { stopRecording("app cerrada"); } });
        camThread.quitSafely();
        try { sm.unregisterListener(this); } catch (Throwable ignored) { }
        try { if (preview != null) wm.removeView(preview); } catch (Throwable ignored) { }
        try { if (csv != null) csv.close(); } catch (Throwable ignored) { }
        try { wake.release(); } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    static void start(Context c) {
        c.startService(new Intent(c, RecorderService.class));
    }
}
