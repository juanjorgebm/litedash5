package com.okavango.camdiag;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Monitor de reversa: SIN abrir ninguna cámara, vigila cada 300 ms las propiedades del sistema,
 * /sys/class/switch, los procesos en ejecución y algunas difusiones, y anota todo lo que cambia.
 * Sirve para encontrar la señal que avisa que se metió reversa.
 */
public class ReverseWatchActivity extends Activity {

    private static final String[] BROADCASTS = {
            "android.intent.action.REVERSE", "com.neusoft.action.REVERSE", "com.neusoft.reverse",
            "com.neusoft.avm.REVERSE", "com.neusoft.rvc.REVERSE", "android.intent.action.BACKCAR",
            "com.neusoft.action.BACKCAR", "com.ecarx.action.REVERSE", "ecarx.intent.action.REVERSE",
            "com.neusoft.action.GEAR_CHANGED", "com.ecarx.action.GEAR", "android.intent.action.CAMERA_BUTTON",
            "android.intent.action.SCREEN_OFF", "android.intent.action.SCREEN_ON",
    };

    private TextView log;
    private final StringBuilder text = new StringBuilder();
    private HandlerThread thread;
    private Handler handler;
    private Map<String, String> lastProps = new HashMap<>();
    private Map<String, String> lastSwitches = new HashMap<>();
    private Map<String, String> lastProcs = new HashMap<>();
    private Map<String, String> lastImportance = new HashMap<>();
    private boolean running = true;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            add("DIFUSIÓN " + i.getAction() + (i.getExtras() != null ? " " + i.getExtras() : ""));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x17, 0x1B, 0x21));
        root.setPadding(24, 24, 24, 24);
        TextView title = new TextView(this);
        title.setTextColor(Color.rgb(0xFF, 0xB5, 0x47));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setText("Monitor de reversa v1.6: mete reversa 5 s, quítala 5 s, y repite una vez más.");
        root.addView(title);
        ScrollView sv = new ScrollView(this);
        log = new TextView(this);
        log.setTextColor(Color.WHITE);
        log.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        log.setTextIsSelectable(true);
        sv.addView(log);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        IntentFilter f = new IntentFilter();
        for (String a : BROADCASTS) f.addAction(a);
        registerReceiver(receiver, f);

        thread = new HandlerThread("watch");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            public void run() {
                lastProps = readProps();
                lastSwitches = readSwitches();
                lastProcs = readProcs();
                lastImportance = readImportance();
                for (Map.Entry<String, String> e : lastImportance.entrySet()) add("app " + e.getKey() + " = " + e.getValue());
                add("Inicio: " + lastProps.size() + " propiedades, " + lastSwitches.size()
                        + " switches, " + lastProcs.size() + " procesos. Esperando cambios…");
                for (Map.Entry<String, String> e : lastSwitches.entrySet()) add("switch " + e.getKey() + " = " + e.getValue());
                handler.postDelayed(poll, 300);
            }
        });
    }

    private final Runnable poll = new Runnable() {
        public void run() {
            if (!running) return;
            try {
                diff("prop", lastProps, lastProps = readProps());
                diff("switch", lastSwitches, lastSwitches = readSwitches());
                diff("proceso", lastProcs, lastProcs = readProcs());
                diff("app", lastImportance, lastImportance = readImportance());
            } catch (Throwable t) {
                add("ERROR " + t);
            }
            handler.postDelayed(this, 300);
        }
    };

    private void diff(String kind, Map<String, String> before, Map<String, String> now) {
        for (Map.Entry<String, String> e : now.entrySet()) {
            String old = before.get(e.getKey());
            if (old == null) {
                add("+ " + kind + " " + e.getKey() + " = " + e.getValue());
            } else if (!old.equals(e.getValue())) {
                add("Δ " + kind + " " + e.getKey() + ": " + old + " → " + e.getValue());
            }
        }
        for (String k : before.keySet()) {
            if (!now.containsKey(k)) add("− " + kind + " " + k + " (era " + before.get(k) + ")");
        }
    }

    private Map<String, String> readProps() {
        Map<String, String> m = new HashMap<>();
        try {
            Process p = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s;
            while ((s = r.readLine()) != null) {
                int i = s.indexOf("]: [");
                if (i > 0 && s.length() > i + 4) {
                    String key = s.substring(1, i);
                    // Los relojes y contadores cambian solos y ensucian el registro.
                    if (key.contains("time") || key.contains("uptime") || key.contains("boottime")) continue;
                    m.put(key, s.substring(i + 4, s.length() - 1));
                }
            }
            r.close();
            p.waitFor();
        } catch (Throwable ignored) { }
        return m;
    }

    private Map<String, String> readSwitches() {
        Map<String, String> m = new HashMap<>();
        String[] dirs = {"/sys/class/switch", "/sys/class/gpio"};
        for (String d : dirs) {
            String[] names = new File(d).list();
            if (names == null) continue;
            for (String n : names) {
                String v = readLine(new File(d, n + "/state"));
                if (v == null) v = readLine(new File(d, n + "/value"));
                if (v != null) m.put(d + "/" + n, v);
            }
        }
        return m;
    }

    private Map<String, String> readProcs() {
        Map<String, String> m = new HashMap<>();
        String[] pids = new File("/proc").list();
        if (pids == null) return m;
        for (String pid : pids) {
            if (pid.isEmpty() || !Character.isDigit(pid.charAt(0))) continue;
            String cmd = readLine(new File("/proc/" + pid + "/cmdline"));
            if (cmd == null) continue;
            cmd = cmd.replace('\0', ' ').trim();
            if (cmd.isEmpty()) continue;
            m.put(cmd, "vivo");
        }
        return m;
    }

    /** Prioridad de las apps del fabricante (en Android 5.1.0 se ven todas): primer plano, visible, fondo… */
    private Map<String, String> readImportance() {
        Map<String, String> m = new HashMap<>();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses()) {
                String n = p.processName;
                if (!(n.contains("neusoft") || n.contains("ecarx") || n.contains("avm") || n.contains("rvc")
                        || n.contains("cam") || n.contains("launcher") || n.contains("systemui"))) continue;
                String imp = p.importance == 100 ? "PRIMER_PLANO" : p.importance == 200 ? "visible"
                        : p.importance == 300 ? "servicio" : p.importance == 400 ? "fondo" : String.valueOf(p.importance);
                String adj = readLine(new File("/proc/" + p.pid + "/oom_score_adj"));
                m.put(n, imp + (adj != null ? " adj " + adj : ""));
            }
        } catch (Throwable t) {
            m.put("error", String.valueOf(t));
        }
        return m;
    }

    private static String readLine(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private void add(String s) {
        final String line = fmt.format(new Date()) + "  " + s + "\n";
        synchronized (text) { text.append(line); }
        runOnUiThread(new Runnable() {
            public void run() { log.append(line); }
        });
    }

    @Override
    protected void onDestroy() {
        running = false;
        try { unregisterReceiver(receiver); } catch (Throwable ignored) { }
        if (thread != null) thread.quitSafely();
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "monitor_reversa.txt");
            FileOutputStream out = new FileOutputStream(f);
            synchronized (text) { out.write(text.toString().getBytes("UTF-8")); }
            out.close();
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Guardar también al salir a otra pantalla (p. ej. cuando el sistema muestra la reversa).
        try {
            File f = new File(Environment.getExternalStorageDirectory(), "monitor_reversa.txt");
            FileOutputStream out = new FileOutputStream(f);
            synchronized (text) { out.write(text.toString().getBytes("UTF-8")); }
            out.close();
        } catch (Throwable ignored) { }
    }
}
