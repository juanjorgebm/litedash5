package com.okavango.camdiag;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Prueba de "dashcam segura": muestra la cámara 0 y analiza cada imagen buscando las guías de
 * colores (rojo + amarillo) que el sistema 360 dibuja en reversa. Si las ve, suelta la cámara al
 * instante y solo la vuelve a pedir cada 2 s; si al reabrir sigue viendo reversa, la suelta otra vez.
 */
public class SafeCamActivity extends Activity implements TextureView.SurfaceTextureListener {

    private static final int CAM_ID = 0;
    /** Porcentaje mínimo de píxeles rojos Y amarillos en la mitad derecha para decir "reversa". */
    private static final float RED_MIN = 0.15f, YELLOW_MIN = 0.15f;

    private TextView stats, log;
    private TextureView textureView;
    private FrameLayout frame;
    private SurfaceTexture surface;
    private HandlerThread thread;
    private Handler handler;
    private Camera camera;
    private int w, h;
    private byte[] buffer;
    private long lastAnalysis;
    private boolean released;
    private int reverseFramesAfterOpen;
    private float maxRed, maxYellow;
    private final StringBuilder logText = new StringBuilder();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        frame.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0xAA000000);
        overlay.setPadding(20, 12, 20, 12);
        stats = new TextView(this);
        stats.setTextColor(Color.rgb(0xFF, 0xB5, 0x47));
        stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        stats.setText("Dashcam segura · abriendo cámara 0…");
        log = new TextView(this);
        log.setTextColor(Color.WHITE);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        overlay.addView(stats);
        overlay.addView(log);
        frame.addView(overlay, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        setContentView(frame);

        thread = new HandlerThread("safecam");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surface = st;
        handler.post(new Runnable() { public void run() { open(); } });
    }

    private void open() {
        if (isFinishing() || paused) return;
        try {
            camera = Camera.open(CAM_ID);
        } catch (Throwable t) {
            camera = null;
            setStats("Cámara ocupada por el sistema (" + t.getMessage() + "). Reintento en 2 s");
            handler.postDelayed(new Runnable() { public void run() { open(); } }, 2000);
            return;
        }
        try {
            Camera.Parameters p = camera.getParameters();
            try {
                p.setPreviewSize(1920, 720);
                camera.setParameters(p);
            } catch (Throwable ignored) {
                p = camera.getParameters();
            }
            Camera.Size s = camera.getParameters().getPreviewSize();
            w = s.width;
            h = s.height;
            buffer = new byte[w * h * 3 / 2];
            camera.addCallbackBuffer(buffer);
            camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
                public void onPreviewFrame(byte[] data, Camera c) { analyze(data, c); }
            });
            camera.setPreviewTexture(surface);
            camera.startPreview();
            fit(w, h);
            reverseFramesAfterOpen = 0;
            if (released) add("Cámara recuperada");
            released = false;
        } catch (Throwable t) {
            add("Error al abrir: " + t);
            release();
            handler.postDelayed(new Runnable() { public void run() { open(); } }, 2000);
        }
    }

    private void analyze(byte[] d, Camera c) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAnalysis < 150) {
            c.addCallbackBuffer(d);
            return;
        }
        lastAnalysis = now;
        int red = 0, yellow = 0, total = 0;
        int uvBase = w * h;
        for (int y = 0; y < h; y += 4) {
            int row = y * w, uvRow = uvBase + (y >> 1) * w;
            for (int x = w / 2; x < w; x += 4) {
                int Y = d[row + x] & 0xFF;
                int idx = uvRow + (x & ~1);
                int V = d[idx] & 0xFF, U = d[idx + 1] & 0xFF;
                if (V > 195 && U < 115) red++;
                else if (Y > 150 && U < 70 && V > 125) yellow++;
                total++;
            }
        }
        float rp = 100f * red / total, yp = 100f * yellow / total;
        boolean reverse = rp >= RED_MIN && yp >= YELLOW_MIN;
        if (!reverse) { maxRed = Math.max(maxRed, rp); maxYellow = Math.max(maxYellow, yp); }
        setStats(String.format(Locale.US, "Dashcam segura · rojo %.2f%% · amarillo %.2f%% · %s · máx. normal: rojo %.2f%% amarillo %.2f%%",
                rp, yp, reverse ? "REVERSA" : "normal", maxRed, maxYellow));
        if (reverse) {
            add(String.format(Locale.US, "REVERSA detectada (rojo %.2f%%, amarillo %.2f%%) → suelto la cámara y abro Vista 360°", rp, yp));
            release();
            released = true;
            // El sistema no reintenta su pantalla de reversa: la abrimos nosotros.
            try {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
                i.setClassName("com.neusoft.avm", "com.neusoft.avm.MainActivity");
                i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Throwable t) {
                add("No se pudo abrir Vista 360°: " + t);
            }
            handler.postDelayed(new Runnable() { public void run() { open(); } }, 3000);
            return;
        }
        c.addCallbackBuffer(d);
    }

    private void release() {
        if (camera == null) return;
        try { camera.setPreviewCallbackWithBuffer(null); } catch (Throwable ignored) { }
        try { camera.stopPreview(); } catch (Throwable ignored) { }
        try { camera.release(); } catch (Throwable ignored) { }
        camera = null;
    }

    private void fit(final int vw0, final int vh0) {
        runOnUiThread(new Runnable() {
            public void run() {
                int rw = frame.getWidth(), rh = frame.getHeight();
                if (rw == 0 || rh == 0) return;
                int vw = rw, vh = rw * vh0 / vw0;
                if (vh > rh) { vh = rh; vw = rh * vw0 / vh0; }
                textureView.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
            }
        });
    }

    private void setStats(final String s) {
        runOnUiThread(new Runnable() { public void run() { stats.setText(s); } });
    }

    private void add(String s) {
        final String line = fmt.format(new Date()) + "  " + s + "\n";
        runOnUiThread(new Runnable() {
            public void run() {
                logText.append(line);
                // Solo las últimas líneas para no tapar la imagen.
                String[] lines = logText.toString().split("\n");
                StringBuilder tail = new StringBuilder();
                for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) tail.append(lines[i]).append('\n');
                log.setText(tail.toString());
            }
        });
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int a, int b) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        handler.post(new Runnable() { public void run() { release(); } });
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private boolean paused;

    @Override
    protected void onPause() {
        super.onPause();
        // En segundo plano no pedimos la cámara: así no le peleamos la cámara a Vista 360°.
        paused = true;
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() { public void run() { release(); } });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (paused) {
            paused = false;
            add("De vuelta en la app");
            if (surface != null) handler.post(new Runnable() { public void run() { open(); } });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() { public void run() { release(); } });
        thread.quitSafely();
    }
}
