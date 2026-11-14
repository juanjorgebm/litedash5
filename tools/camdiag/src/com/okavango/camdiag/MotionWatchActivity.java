package com.okavango.camdiag;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

/**
 * Monitor de movimiento y reversa (v2.4). NO abre ninguna cámara.
 * - Escucha las difusiones que encontramos dentro de las apps del carro (REVERSE_ON/OFF/CHANGE,
 *   avm.overspeed, dvr.acc.overspeed, POWER_ON/OFF).
 * - Observa cambios en content://com.neusoft.optimus.mmi y en Settings.System.
 * - Muestra acelerómetro y giroscopio en vivo, con una estimación de "en movimiento" por vibración.
 * Guarda movimiento_FECHA.txt/.csv en la memoria interna y en cada USB que encuentre.
 */
public class MotionWatchActivity extends Activity implements SensorEventListener {

    private static final String[] ACTIONS = {
            "com.neusoft.c3alfus.commservice.action.REVERSE_ON",
            "com.neusoft.c3alfus.commservice.action.REVERSE_OFF",
            "com.neusoft.c3alfus.commservice.action.REVERSE_CHANGE",
            "com.neusoft.avm.overspeed",
            "com.neusoft.dvr.acc.overspeed",
            "android.intent.action.POWER_ON",
            "android.intent.action.POWER_OFF",
            "com.neusoft.ACTION_CANDIAGNOSIS",
    };
    private static final String MMI = "content://com.neusoft.optimus.mmi";

    private TextView live, log;
    private final StringBuilder text = new StringBuilder();
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SensorManager sm;

    // Acelerómetro: ventana de 2 s de la magnitud para medir la vibración.
    private final float[] win = new float[200];
    private int winN, winPos;
    private float ax, ay, az, gx, gy, gz, accStd, gyroMax;
    private long gyroMaxAt;
    private boolean moving;
    private long movingSince, lastUi;
    private long startedAt;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            StringBuilder s = new StringBuilder("★ DIFUSIÓN ").append(i.getAction());
            Bundle e = i.getExtras();
            if (e != null) {
                Set<String> keys = e.keySet();
                for (String k : keys) s.append("  ").append(k).append("=").append(e.get(k));
            }
            add(s.toString());
        }
    };

    private ContentObserver mmiObserver, settingsObserver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x17, 0x1B, 0x21));
        root.setPadding(24, 16, 24, 16);
        TextView title = new TextView(this);
        title.setTextColor(Color.rgb(0xFF, 0xB5, 0x47));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setText("Monitor de movimiento v2.6 (no usa la cámara): carro quieto 20 s con motor encendido, mete reversa 2 veces (5 s cada vez), maneja 5 min con paradas (semáforo), y apaga el carro al final.");
        root.addView(title);
        live = new TextView(this);
        live.setTextColor(Color.WHITE);
        live.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        live.setPadding(0, 8, 0, 8);
        root.addView(live);
        ScrollView sv = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(Color.rgb(0xDD, 0xDD, 0xDD));
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        log.setTextIsSelectable(true);
        sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        IntentFilter f = new IntentFilter();
        for (String a : ACTIONS) f.addAction(a);
        registerReceiver(receiver, f);
        add("Escuchando " + ACTIONS.length + " difusiones del carro.");

        mmiObserver = new ContentObserver(ui) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                add("◆ MMI cambió: " + uri + readRow(uri));
            }
        };
        try {
            getContentResolver().registerContentObserver(Uri.parse(MMI), true, mmiObserver);
            add("Observando " + MMI);
        } catch (Throwable t) {
            add("No se pudo observar MMI: " + t);
        }
        for (String p : new String[]{"CarDirectionNotify", "PowerStatusChangeNotify", "AlertNotify"}) {
            add("MMI " + p + ":" + readRow(Uri.parse(MMI + "/" + p)));
        }

        settingsObserver = new ContentObserver(ui) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                add("◇ Ajuste cambió: " + uri + readRow(uri));
            }
        };
        for (String t : new String[]{"system", "global", "secure"}) {
            try {
                getContentResolver().registerContentObserver(Uri.parse("content://settings/" + t), true, settingsObserver);
            } catch (Throwable ignored) { }
        }

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        add("Acelerómetro: " + (acc != null ? acc.getName() + " / " + acc.getVendor() : "NO HAY"));
        add("Giroscopio: " + (gyro != null ? gyro.getName() + " / " + gyro.getVendor() : "NO HAY"));
        if (acc != null) sm.registerListener(this, acc, 10000); // ~100 Hz
        if (gyro != null) sm.registerListener(this, gyro, 20000);
        startedAt = SystemClock.elapsedRealtime();
        ui.postDelayed(saver, 15000);
    }

    /** Intenta leer la fila que cambió; muchas veces el proveedor no deja, y eso también nos sirve saberlo. */
    private String readRow(Uri uri) {
        if (uri == null) return "";
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c == null) return " (sin cursor)";
            StringBuilder s = new StringBuilder();
            int rows = 0;
            while (c.moveToNext() && rows < 3) {
                s.append(" [");
                for (int i = 0; i < c.getColumnCount() && i < 12; i++) {
                    String v;
                    try { v = c.getString(i); } catch (Throwable t) { v = "?"; }
                    s.append(c.getColumnName(i)).append('=').append(v).append(i + 1 < c.getColumnCount() ? ", " : "");
                }
                s.append(']');
                rows++;
            }
            return rows == 0 ? " (vacío)" : s.toString();
        } catch (Throwable t) {
            return " (no se puede leer: " + t.getClass().getSimpleName() + ")";
        } finally {
            if (c != null) c.close();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        long now = SystemClock.elapsedRealtime();
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            ax = e.values[0]; ay = e.values[1]; az = e.values[2];
            float mag = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            win[winPos] = mag;
            winPos = (winPos + 1) % win.length;
            if (winN < win.length) winN++;
            float mean = 0;
            for (int i = 0; i < winN; i++) mean += win[i];
            mean /= winN;
            float var = 0;
            for (int i = 0; i < winN; i++) var += (win[i] - mean) * (win[i] - mean);
            accStd = (float) Math.sqrt(var / winN);
            // Los primeros 5 s (carro quieto) fijan la línea base de vibración.
            if (now - startedAt > 2000) evaluate(now);
        } else {
            gx = e.values[0]; gy = e.values[1]; gz = e.values[2];
            float g = Math.abs(gx) + Math.abs(gy) + Math.abs(gz);
            if (g > gyroMax || now - gyroMaxAt > 3000) { gyroMax = g; gyroMaxAt = now; }
        }
        if (now - lastUi > 250) {
            lastUi = now;
            live.setText(String.format(Locale.US,
                    "Acel x %.2f  y %.2f  z %.2f  ·  vibración %.3f (arranca > %.2f, para < %.2f)\nGiro x %.2f  y %.2f  z %.2f  ·  máx. 3 s %.2f\nEstimación: %s",
                    ax, ay, az, accStd, START_THR, STOP_THR, gx, gy, gz, gyroMax, moving ? "EN MOVIMIENTO" : "quieto"));
        }
    }

    // Umbrales fijos con histéresis (quieto ≈ 0.01; en v2.4 la línea base se calibró mal y parpadeaba).
    private static final float START_THR = 0.08f, STOP_THR = 0.04f;
    private long aboveSince, belowSince;

    private void evaluate(long now) {
        if (accStd > START_THR) { if (aboveSince == 0) aboveSince = now; belowSince = 0; }
        else if (accStd < STOP_THR) { if (belowSince == 0) belowSince = now; aboveSince = 0; }
        else { aboveSince = 0; belowSince = 0; }
        boolean m = moving;
        if (!moving && aboveSince != 0 && now - aboveSince > 3000) m = true;
        if (moving && belowSince != 0 && now - belowSince > 8000) m = false;
        if (m != moving) {
            moving = m;
            add(String.format(Locale.US, "%s (vibración %.3f, giro %.2f)%s",
                    m ? "▶ Empieza movimiento" : "■ Se detuvo", accStd, gyroMax,
                    m ? "" : String.format(Locale.US, " · duró %d s", (now - movingSince) / 1000)));
            movingSince = now;
        }
        if (now - lastCsv >= 500) {
            lastCsv = now;
            synchronized (csv) {
                csv.append(fmt.format(new Date())).append(String.format(Locale.US, ",%.4f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d\n",
                        accStd, ax, ay, az, gx, gy, gz, gyroMax, moving ? 1 : 0));
            }
        }
    }

    private long lastCsv;
    private final StringBuilder csv = new StringBuilder("hora,vibracion,ax,ay,az,gx,gy,gz,giro_max3s,movimiento\n");

    @Override
    public void onAccuracyChanged(Sensor s, int a) { }

    private final Runnable saver = new Runnable() {
        public void run() { save(); ui.postDelayed(this, 15000); }
    };

    /** Nombre con fecha y hora de inicio: cada vez que se abre el monitor crea archivos nuevos (no borra el viaje anterior). */
    private final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());

    private void save() {
        String data;
        synchronized (text) { data = text.toString(); }
        String csvData;
        synchronized (csv) { csvData = csv.toString(); }
        java.util.List<File> dirs = new java.util.ArrayList<>();
        dirs.add(Environment.getExternalStorageDirectory());
        // La USB cambia de nombre al reconectarla (usbotg-sda1, usbotg-sda2…): se buscan todas.
        for (String base : new String[]{"/storage/usbotg", "/mnt/media_rw"}) {
            File[] list = new File(base).listFiles();
            if (list == null) continue;
            for (File d : list) if (d.isDirectory() && d.canWrite()) dirs.add(d);
        }
        for (File d : dirs) {
            write(new File(d, "movimiento_" + stamp + ".txt"), data);
            write(new File(d, "movimiento_" + stamp + ".csv"), csvData);
        }
    }

    private static void write(File f, String data) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            out.write(data.getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) { }
    }

    private void add(String s) {
        final String line = fmt.format(new Date()) + "  " + s + "\n";
        synchronized (text) { text.append(line); }
        ui.post(new Runnable() { public void run() { log.append(line); } });
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(saver);
        try { unregisterReceiver(receiver); } catch (Throwable ignored) { }
        try { getContentResolver().unregisterContentObserver(mmiObserver); } catch (Throwable ignored) { }
        try { getContentResolver().unregisterContentObserver(settingsObserver); } catch (Throwable ignored) { }
        if (sm != null) sm.unregisterListener(this);
        save();
        super.onDestroy();
    }
}
