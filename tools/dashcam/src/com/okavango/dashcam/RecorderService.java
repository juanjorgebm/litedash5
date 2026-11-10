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
import android.os.Bundle;
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
    static final String K_ARMED = "armed", K_START = "start_thr", K_STOP = "stop_thr",
            K_IMPACT_ON = "impact_on", K_IMPACT_THR = "impact_thr", K_CAM = "camera_id",
            K_SPEED = "speed_kmh", K_STOP_SPEED = "stop_kmh", K_MAX_INTERNAL = "max_internal_gb", K_FCW = "fcw_on", K_FCW_SENS = "fcw_sens", K_OPEN_ON_ACC = "open_on_acc";
    static final float DEF_START = 0.10f, DEF_STOP = 0.07f, DEF_IMPACT = 3.0f;
    /** Segundos seguidos por encima/debajo del umbral para decidir "en marcha" o "detenido". */
    static final long START_MS = 3000, STOP_MS = 2000;
    static final float DEF_STOP_KMH = 1f;
    static final long SEGMENT_MS = 3 * 60 * 1000;
    static final long CLIP_MS = 60 * 1000;

    static final String ACTION_TOGGLE = "com.okavango.dashcam.TOGGLE";

    static volatile RecorderService instance;

    // --- estado que lee la pantalla principal
    volatile boolean recording, moving, accOn = true, armed, holdUntilStopped;
    volatile float vibration, startThr = DEF_START, stopThr = DEF_STOP, vibMin10 = 9, vibMax10;
    // Golpes: desvío brusco de |a| respecto de su promedio lento (m/s²).
    volatile boolean impactOn = true;
    volatile float impactThr = DEF_IMPACT, jolt, joltMax10;
    volatile int cameraId;
    // GPS (si la pantalla lo tiene): ubicación en el video y velocidad para decidir cuándo grabar.
    volatile boolean hasGps, openOnAcc = true;
    volatile double lat, lon;
    volatile float speedKmh = -1, speedThr = 30, stopKmh = DEF_STOP_KMH;
    /** Límite de la carpeta Dashcam en la memoria interna (cuando no hay USB), en GB. */
    volatile float maxInternalGb = 2f;

    // ---- aviso de choque frontal (experimental)
    volatile boolean fcwOn;
    volatile float fcwSens = 1f, fcwFps;
    volatile Fcw.Result fcwResult;
    volatile String fcwSource = "", fcwError = "";
    private Fcw fcw;
    private HandlerThread fcwThread;
    private Handler fcwHandler;
    private final java.util.concurrent.atomic.AtomicBoolean fcwBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private final android.graphics.Bitmap fcwBitmap = android.graphics.Bitmap.createBitmap(Fcw.SIZE, Fcw.SIZE, android.graphics.Bitmap.Config.ARGB_8888);
    private final int[] fcwPixels = new int[Fcw.SIZE * Fcw.SIZE];
    private volatile long lastCallbackFrame, fcwFrames, fcwFpsSince;
    private int prevW, prevH;
    private android.media.ToneGenerator tone;
    volatile long fixAt;
    private android.location.LocationManager lm;
    /** Ubicación de cada segundo de la grabación en curso (para el .srt). */
    private final java.util.ArrayList<String> track = new java.util.ArrayList<>();
    private float slowMag = -1;
    private volatile long lastImpact;
    private volatile boolean impactPending, currentImpact;
    private final float[] joltHist = new float[20];
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
        impactOn = prefs.getBoolean(K_IMPACT_ON, true);
        impactThr = prefs.getFloat(K_IMPACT_THR, DEF_IMPACT);
        cameraId = prefs.getInt(K_CAM, 0);
        speedThr = prefs.getFloat(K_SPEED, 30);
        stopKmh = prefs.getFloat(K_STOP_SPEED, DEF_STOP_KMH);
        maxInternalGb = prefs.getFloat(K_MAX_INTERNAL, 2f);
        fcwOn = prefs.getBoolean(K_FCW, false);
        fcwSens = prefs.getFloat(K_FCW_SENS, 1f);
        fcwThread = new HandlerThread("fcw");
        fcwThread.start();
        fcwHandler = new Handler(fcwThread.getLooper());
        main.post(beeper);
        main.postDelayed(textureGrabber, 1000);
        openOnAcc = prefs.getBoolean(K_OPEN_ON_ACC, true);
        startGps();

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

    void setImpact(boolean on, float thr) {
        impactOn = on;
        impactThr = Math.max(0.5f, Math.min(thr, 15f));
        prefs.edit().putBoolean(K_IMPACT_ON, on).putFloat(K_IMPACT_THR, impactThr).apply();
    }

    void setSpeed(float kmh) {
        speedThr = Math.max(5, Math.min(kmh, 120));
        if (stopKmh >= speedThr) stopKmh = speedThr - 1;
        prefs.edit().putFloat(K_SPEED, speedThr).putFloat(K_STOP_SPEED, stopKmh).apply();
    }

    void setFcw(boolean on, float sens) {
        fcwOn = on;
        fcwSens = sens;
        prefs.edit().putBoolean(K_FCW, on).putFloat(K_FCW_SENS, sens).apply();
        if (!on) fcwResult = null;
    }

    /** Pitido de prueba (para saber si se oye y a qué volumen). */
    void testBeep() {
        main.post(new Runnable() { public void run() { beep(250); } });
    }

    private void beep(int ms) {
        try {
            if (tone == null) tone = new android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM, 100);
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, ms);
        } catch (Throwable t) {
            fcwError = "No se pudo pitar: " + t.getMessage();
        }
    }

    /** ¿Tiene sentido avisar? Solo con el carro avanzando. */
    private boolean fcwActive() {
        return fcwOn && recording && (gpsActive() ? speedKmh >= 8 : moving);
    }

    /** Pitidos: cada 0.9 s (atención), 0.4 s (peligro), 0.15 s (choque inminente). */
    private final Runnable beeper = new Runnable() {
        public void run() {
            Fcw.Result r = fcwResult;
            int level = r != null && fcwActive() && SystemClock.elapsedRealtime() - lastFcwAt < 1500 ? r.level : 0;
            long next = 200;
            if (level == 1) { beep(120); next = 900; }
            else if (level == 2) { beep(120); next = 400; }
            else if (level == 3) { beep(100); next = 150; }
            main.postDelayed(this, next);
        }
    };
    private volatile long lastFcwAt;

    /** Analiza una imagen ya reducida (fcwPixels) en el hilo del detector. */
    private void analyzePixels() {
        fcwHandler.post(new Runnable() {
            public void run() {
                try {
                    if (fcw == null) fcw = new Fcw(RecorderService.this);
                    fcwBitmap.setPixels(fcwPixels, 0, Fcw.SIZE, 0, 0, Fcw.SIZE, Fcw.SIZE);
                    long now = SystemClock.elapsedRealtime();
                    Fcw.Result r = fcw.analyze(fcwBitmap, fcwSens, now);
                    Fcw.Result prev = fcwResult;
                    fcwResult = r;
                    lastFcwAt = now;
                    if (r.level >= 2 && (prev == null || prev.level < r.level)) event("AVISO DE CHOQUE nivel " + r.level + ": " + r);
                    fcwFrames++;
                    if (now - fcwFpsSince >= 3000) {
                        fcwFps = fcwFrames * 1000f / (now - fcwFpsSince);
                        fcwFrames = 0;
                        fcwFpsSince = now;
                    }
                    fcwError = "";
                } catch (Throwable t) {
                    fcwError = "Detector: " + t;
                } finally {
                    fcwBusy.set(false);
                }
            }
        });
    }

    /** Dónde empieza la vista de frente en la imagen del 360 (a la izquierda está la vista de arriba). */
    static final float FRONT_X0 = 0.41f;

    /** Imagen de la cámara (NV21) → vista de frente (desde FRONT_X0 hasta el borde derecho) reducida a 300×300. */
    private final Camera.PreviewCallback frameCallback = new Camera.PreviewCallback() {
        public void onPreviewFrame(byte[] d, Camera c) {
            lastCallbackFrame = SystemClock.elapsedRealtime();
            if (fcwActive() && fcwSource.startsWith("cámara") && fcwBusy.compareAndSet(false, true)) {
                int w = prevW, h = prevH, uvBase = w * h, x0 = (int) (w * FRONT_X0), fw = w - x0;
                int n = Fcw.SIZE;
                for (int y = 0; y < n; y++) {
                    int sy = y * h / n, row = sy * w, uvRow = uvBase + (sy >> 1) * w;
                    for (int x = 0; x < n; x++) {
                        int sx = x0 + x * fw / n;
                        int Y = (d[row + sx] & 0xFF) - 16;
                        int idx = uvRow + (sx & ~1);
                        int V = (d[idx] & 0xFF) - 128, U = (d[idx + 1] & 0xFF) - 128;
                        if (Y < 0) Y = 0;
                        int r = (1192 * Y + 1634 * V) >> 10, g = (1192 * Y - 833 * V - 400 * U) >> 10, b = (1192 * Y + 2066 * U) >> 10;
                        r = r < 0 ? 0 : r > 255 ? 255 : r;
                        g = g < 0 ? 0 : g > 255 ? 255 : g;
                        b = b < 0 ? 0 : b > 255 ? 255 : b;
                        fcwPixels[y * n + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
                analyzePixels();
            }
            c.addCallbackBuffer(d);
        }
    };

    /**
     * Si la cámara no entrega imágenes por callback mientras graba, se copian de la vista previa
     * (TextureView) unas 4 veces por segundo.
     */
    private final Runnable textureGrabber = new Runnable() {
        public void run() {
            try {
                long now = SystemClock.elapsedRealtime();
                if (recording && now - recordingSince > 3000 && now - lastCallbackFrame > 2000) {
                    if (!fcwSource.startsWith("vista")) { fcwSource = "vista previa"; }
                } else if (recording && now - lastCallbackFrame < 2000) {
                    fcwSource = "cámara";
                }
                if (fcwActive() && fcwSource.startsWith("vista") && preview != null && surface != null
                        && fcwBusy.compareAndSet(false, true)) {
                    android.graphics.Bitmap full = preview.getBitmap(640, 360);
                    if (full != null) {
                        android.graphics.Bitmap right = android.graphics.Bitmap.createScaledBitmap(
                                android.graphics.Bitmap.createBitmap(full, (int) (640 * FRONT_X0), 0, 640 - (int) (640 * FRONT_X0), 360), Fcw.SIZE, Fcw.SIZE, true);
                        right.getPixels(fcwPixels, 0, Fcw.SIZE, 0, 0, Fcw.SIZE, Fcw.SIZE);
                        analyzePixels();
                    } else fcwBusy.set(false);
                }
            } catch (Throwable t) {
                fcwBusy.set(false);
                fcwError = "Vista previa: " + t.getMessage();
            }
            main.postDelayed(this, 250);
        }
    };

    void setMaxInternal(float gb) {
        maxInternalGb = Math.max(0.5f, Math.min(gb, 32f));
        prefs.edit().putFloat(K_MAX_INTERNAL, maxInternalGb).apply();
    }

    long maxInternalBytes() {
        return (long) (maxInternalGb * (1L << 30));
    }

    /** Velocidad a la que deja de grabar (con GPS), siempre por debajo de la de arranque. */
    void setStopSpeed(float kmh) {
        stopKmh = Math.max(0, Math.min(kmh, speedThr - 1));
        prefs.edit().putFloat(K_STOP_SPEED, stopKmh).apply();
    }

    void setOpenOnAcc(boolean on) {
        openOnAcc = on;
        prefs.edit().putBoolean(K_OPEN_ON_ACC, on).apply();
    }

    /** GPS con señal y dato de velocidad en los últimos 5 s. */
    boolean gpsActive() {
        return speedKmh >= 0 && SystemClock.elapsedRealtime() - fixAt < 5000;
    }

    private void startGps() {
        try {
            lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            hasGps = lm.getAllProviders().contains(android.location.LocationManager.GPS_PROVIDER);
            if (!hasGps) { event("La pantalla no tiene GPS: se usa la vibración"); return; }
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 1000, 0, gpsListener, Looper.getMainLooper());
        } catch (Throwable t) {
            hasGps = false;
            event("GPS no disponible: " + t.getMessage());
        }
    }

    private final android.location.LocationListener gpsListener = new android.location.LocationListener() {
        public void onLocationChanged(android.location.Location l) {
            lat = l.getLatitude();
            lon = l.getLongitude();
            speedKmh = l.hasSpeed() ? l.getSpeed() * 3.6f : -1;
            fixAt = SystemClock.elapsedRealtime();
        }
        public void onStatusChanged(String p, int st, Bundle b) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    /** Cámara a grabar; se aplica en la próxima grabación. */
    void setCamera(int id) {
        cameraId = id;
        prefs.edit().putInt(K_CAM, id).apply();
        event("Cámara elegida: " + id);
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
            // El clip manual o por golpe graba aunque el carro esté apagado (si la pantalla sigue encendida).
            boolean want = forced || (accOn && armed && moving && !holdUntilStopped);
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
        joltHist[histPos == 0 ? joltHist.length - 1 : histPos - 1] = jolt;
        jolt = 0;
        float jm = 0;
        for (float h : joltHist) jm = Math.max(jm, h);
        joltMax10 = jm;

        // Con GPS manda la velocidad (graba a partir de speedThr km/h); sin GPS, la vibración.
        boolean gps = gpsActive();
        boolean above = gps ? speedKmh >= speedThr : v > startThr;
        // Una vez grabando, sigue hasta que el carro casi se detiene (baja de stopKmh), no al bajar del límite de arranque.
        boolean below = gps ? speedKmh <= stopKmh : v < stopThr;  // <= para que 0 signifique "detenido"
        if (above) { if (aboveSince == 0) aboveSince = now; belowSince = 0; }
        else if (below) { if (belowSince == 0) belowSince = now; aboveSince = 0; }
        else { aboveSince = 0; belowSince = 0; }
        if (!moving && aboveSince != 0 && now - aboveSince >= START_MS) { moving = true; event("En movimiento"); }
        if (moving && belowSince != 0 && now - belowSince >= STOP_MS) { moving = false; event("Carro detenido"); }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float m = (float) Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
        if (slowMag < 0) slowMag = m;
        float j = Math.abs(m - slowMag);
        slowMag += 0.02f * (m - slowMag);
        if (j > jolt) jolt = j;
        if (impactOn && j > impactThr) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastImpact > 5000) {
                lastImpact = now;
                onImpact(j);
            }
        }
        synchronized (mags) {
            mags[magPos] = m;
            times[magPos] = SystemClock.elapsedRealtime();
            magPos = (magPos + 1) % mags.length;
            if (magCount < mags.length) magCount++;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    /** Golpe o frenada brusca: graba al menos 1 minuto y marca el video como "impacto" (no se borra solo). */
    private void onImpact(float j) {
        event(String.format(Locale.US, "GOLPE detectado (%.1f m/s²): grabando", j));
        if (recording) currentImpact = true;
        else impactPending = true;
        long until = SystemClock.elapsedRealtime() + CLIP_MS;
        if (forcedUntil < until) forcedUntil = until;
        retryAfter = 0;
        holdUntilStopped = false;
    }

    private void readAcc() {
        try {
            String v = Settings.System.getString(getContentResolver(), "ACC_OFF_ON_STATE");
            boolean on = v == null || !v.trim().equals("0");
            if (on != accOn) {
                event(on ? "Carro encendido" : "Carro apagado");
                if (on) openAppIfWanted(this);
            }
            accOn = on;
        } catch (Throwable ignored) { }
    }

    /** Al encender el carro abre la pantalla de la dashcam (si está activada esa opción). */
    static void openAppIfWanted(Context c) {
        if (!c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(K_OPEN_ON_ACC, true)) return;
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ grabación

    private void startRecording() {
        File dir = Storage.recordDir();
        folder = (Storage.isUsb(dir) ? "USB · " : "memoria interna · ") + dir.getAbsolutePath();
        int del = Storage.freeSpace(dir, null, maxInternalBytes());
        if (del > 0) event("Borré " + del + " video(s) viejo(s) para hacer espacio");
        currentImpact = impactPending;
        impactPending = false;
        File f = new File(dir, new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date()) + ".mp4");
        final int camId = cameraId;
        try {
            camera = Camera.open(camId);
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
            try {
                Camera.Size ps = camera.getParameters().getPreviewSize();
                prevW = ps.width;
                prevH = ps.height;
                for (int i = 0; i < 2; i++) camera.addCallbackBuffer(new byte[prevW * prevH * 3 / 2]);
                camera.setPreviewCallbackWithBuffer(frameCallback);
            } catch (Throwable ignored) { }
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
                    if (!CamcorderProfile.hasProfile(camId, q)) throw new RuntimeException("sin perfil " + q);
                    CamcorderProfile prof = CamcorderProfile.get(camId, q);
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
        File f = currentFile;
        if (recording && f != null && f.exists()) {
            if (currentImpact) {
                File marked = new File(f.getParentFile(), f.getName().replace(".mp4", "_impacto.mp4"));
                if (f.renameTo(marked)) f = marked;
            }
            java.util.ArrayList<String> t;
            synchronized (track) { t = new java.util.ArrayList<>(track); }
            writeSubtitles(f, recordingSince, t);
        }
        currentImpact = false;
        synchronized (track) { track.clear(); }
        recording = false;
        currentFile = null;
    }

    /**
     * Fecha y hora por segundo en un .srt con el mismo nombre del video: VLC y otros reproductores lo
     * muestran como subtítulo, y el reproductor de la app la dibuja en una esquina.
     */
    private static void writeSubtitles(File video, long startedElapsed, java.util.List<String> gps) {
        try {
            long durMs = SystemClock.elapsedRealtime() - startedElapsed;
            long startWall = System.currentTimeMillis() - durMs;
            SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
            StringBuilder sb = new StringBuilder();
            int n = (int) (durMs / 1000) + 1;
            for (int i = 0; i < n; i++) {
                sb.append(i + 1).append('\n').append(srtTime(i * 1000L)).append(" --> ").append(srtTime(i * 1000L + 1000))
                        .append('\n').append(df.format(new Date(startWall + i * 1000L))).append('\n');
                // Segunda línea: ubicación GPS de ese segundo (vacía si no hay señal).
                String g = i < gps.size() ? gps.get(i) : "";
                if (g.length() > 0) sb.append(g).append('\n');
                sb.append('\n');
            }
            FileWriter w = new FileWriter(new File(video.getParentFile(), video.getName().replace(".mp4", ".srt")));
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) { }
    }

    private static String srtTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }

    private void releaseCamera() {
        fcwResult = null;
        if (fcw != null) fcwHandler.post(new Runnable() { public void run() { if (fcw != null) fcw.reset(); } });
        if (camera == null) return;
        try { camera.setPreviewCallbackWithBuffer(null); } catch (Throwable ignored) { }
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
        if (recording) {
            synchronized (track) {
                track.add(gpsActive() ? String.format(Locale.US, "%.5f, %.5f  ·  %.0f km/h", lat, lon, speedKmh) : "");
            }
        }
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
        main.removeCallbacks(beeper);
        main.removeCallbacks(textureGrabber);
        fcwHandler.post(new Runnable() { public void run() { if (fcw != null) fcw.close(); } });
        fcwThread.quitSafely();
        try { if (tone != null) tone.release(); } catch (Throwable ignored) { }
        instance = null;
        cam.removeCallbacksAndMessages(null);
        cam.post(new Runnable() { public void run() { stopRecording("app cerrada"); } });
        camThread.quitSafely();
        try { sm.unregisterListener(this); } catch (Throwable ignored) { }
        try { if (lm != null) lm.removeUpdates(gpsListener); } catch (Throwable ignored) { }
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
