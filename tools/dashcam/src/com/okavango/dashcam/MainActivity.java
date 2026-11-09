package com.okavango.dashcam;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.Locale;

/** Pantalla principal: estado, interruptor automático, clip manual, umbrales y acceso a los videos. */
public class MainActivity extends Activity {

    static final int AMBER = Color.rgb(0xFF, 0xB5, 0x47), BG = Color.rgb(0x17, 0x1B, 0x21),
            SLATE = Color.rgb(0x34, 0x3B, 0x45), RED = Color.rgb(0xFF, 0x4D, 0x4D);

    private TextView status, detail, folder, error, events, startVal, stopVal;
    private Switch autoSwitch;
    private Button updateButton;
    private TextView updateInfo;
    private String[] available;
    private boolean busy;
    private final Handler ui = new Handler();
    private boolean syncing;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RecorderService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(BG);
        // La barra del clima del carro tapa la parte de abajo.
        root.setPadding(dp(20), dp(16), dp(20), dp(90));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        root.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        TextView title = text("Dashcam Okavango", 18, AMBER);
        left.addView(title);
        status = text("Iniciando…", 30, Color.WHITE);
        status.setPadding(0, dp(4), 0, dp(4));
        left.addView(status);
        detail = text("", 16, Color.rgb(0xCC, 0xCC, 0xCC));
        left.addView(detail);

        autoSwitch = new Switch(this);
        autoSwitch.setText("Grabar sola cuando el carro va en marcha  ");
        autoSwitch.setTextColor(Color.WHITE);
        autoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        autoSwitch.setPadding(0, dp(10), 0, dp(10));
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && !syncing) s.setArmed(on);
            }
        });
        left.addView(autoSwitch, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(button("Grabar clip 1 min", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.recordClip(); }
        }));
        buttons.addView(button("Detener", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.stopNow(); }
        }));
        buttons.addView(button("Ver videos", new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, FilesActivity.class)); }
        }));
        updateButton = button("Buscar actualización", new View.OnClickListener() {
            public void onClick(View v) { onUpdateClick(); }
        });
        left.addView(buttons);

        LinearLayout thr = new LinearLayout(this);
        thr.setGravity(Gravity.CENTER_VERTICAL);
        thr.setPadding(0, dp(8), 0, 0);
        thr.addView(text("Arranca si vibra más de ", 16, Color.WHITE));
        thr.addView(small("−", -0.01f, 0));
        startVal = text("", 18, AMBER);
        thr.addView(startVal);
        thr.addView(small("+", 0.01f, 0));
        thr.addView(text("     Para si baja de ", 16, Color.WHITE));
        thr.addView(small("−", 0, -0.01f));
        stopVal = text("", 18, AMBER);
        thr.addView(stopVal);
        thr.addView(small("+", 0, 0.01f));
        left.addView(thr);

        folder = text("", 14, Color.rgb(0xAA, 0xAA, 0xAA));
        folder.setPadding(0, dp(8), 0, 0);
        left.addView(folder);
        error = text("", 16, RED);
        left.addView(error);
        LinearLayout upd = new LinearLayout(this);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        upd.setPadding(0, dp(6), 0, 0);
        upd.addView(updateButton);
        updateInfo = text("Versión " + Updater.currentName(this), 14, Color.rgb(0xAA, 0xAA, 0xAA));
        upd.addView(updateInfo);
        left.addView(upd);

        // Columna derecha: hueco para la vista previa flotante (768×288 px) y el último evento.
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        root.addView(right, new LinearLayout.LayoutParams(dp(530), -1));
        TextView hole = text("La vista previa aparece aquí mientras graba", 15, Color.GRAY);
        hole.setGravity(Gravity.CENTER);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.BLACK);
        box.setStroke(dp(1), SLATE);
        hole.setBackground(box);
        right.addView(hole, new LinearLayout.LayoutParams(-1, dp(200)));
        events = text("", 15, Color.rgb(0xDD, 0xDD, 0xDD));
        events.setPadding(0, dp(10), 0, 0);
        right.addView(events);

        setContentView(root);
        checkUpdate(false);
    }

    // ---------------------------------------------------------------- actualizador

    private void onUpdateClick() {
        if (busy) return;
        if (available != null) install(); else checkUpdate(true);
    }

    private void checkUpdate(final boolean manual) {
        busy = true;
        if (manual) updateInfo.setText("Buscando actualización…");
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    String[] l = Updater.latest();
                    if (Integer.parseInt(l[0]) > Updater.currentCode(MainActivity.this)) {
                        available = l;
                        msg = "Hay una versión nueva: " + l[1] + " (tienes " + Updater.currentName(MainActivity.this) + "). Toca Actualizar.";
                    } else {
                        msg = "Versión " + Updater.currentName(MainActivity.this) + " · es la más reciente";
                    }
                } catch (Throwable t) {
                    msg = manual ? "No se pudo revisar (¿hay internet?): " + t.getMessage()
                            : "Versión " + Updater.currentName(MainActivity.this);
                }
                final String m = msg;
                ui.post(new Runnable() {
                    public void run() {
                        busy = false;
                        updateInfo.setText(m);
                        updateInfo.setTextColor(available != null ? AMBER : Color.rgb(0xAA, 0xAA, 0xAA));
                        if (available != null) updateButton.setText("Actualizar a " + available[1]);
                    }
                });
            }
        }).start();
    }

    private void install() {
        busy = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    Updater.downloadAndInstall(MainActivity.this, new Updater.Progress() {
                        public void update(final String text) {
                            ui.post(new Runnable() { public void run() { updateInfo.setText(text); } });
                        }
                    });
                } catch (final Throwable t) {
                    ui.post(new Runnable() { public void run() { updateInfo.setText("Error al descargar: " + t.getMessage()); } });
                }
                ui.post(new Runnable() { public void run() { busy = false; } });
            }
        }).start();
    }

    private final Runnable refresh = new Runnable() {
        public void run() {
            RecorderService s = RecorderService.instance;
            if (s == null) {
                status.setText("Iniciando…");
                RecorderService.start(MainActivity.this);
            } else {
                long now = SystemClock.elapsedRealtime();
                String st;
                if (s.recording) {
                    long sec = (now - s.recordingSince) / 1000;
                    st = String.format(Locale.US, "● GRABANDO  %d:%02d", sec / 60, sec % 60);
                    if (s.forcedUntil > now) st += "  (clip manual)";
                    status.setTextColor(RED);
                } else {
                    status.setTextColor(Color.WHITE);
                    st = !s.accOn ? "Carro apagado"
                            : !s.armed ? "Grabación automática apagada"
                            : s.moving ? "En marcha · preparando cámara…"
                            : "Lista · carro detenido · cámara libre";
                }
                status.setText(st);
                detail.setText(String.format(Locale.US,
                        "Vibración %.3f  (últimos 10 s: %.3f – %.3f)  ·  %s",
                        s.vibration, s.vibMin10 > 8 ? 0 : s.vibMin10, s.vibMax10,
                        s.moving ? "EN MARCHA" : "detenido"));
                startVal.setText(String.format(Locale.US, " %.2f ", s.startThr));
                stopVal.setText(String.format(Locale.US, " %.2f ", s.stopThr));
                syncing = true;
                autoSwitch.setChecked(s.armed);
                syncing = false;
                File cur = s.currentFile;
                File dir = cur != null ? cur.getParentFile() : Storage.recordDir();
                folder.setText((Storage.isUsb(dir) ? "Guardando en la USB: " : "Sin USB, guardando en memoria interna: ")
                        + dir.getAbsolutePath() + "  ·  libre " + Storage.human(dir.getUsableSpace()));
                error.setText(s.lastError);
                events.setText(s.lastEvent);
                s.showPreview(s.recording);
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
        RecorderService s = RecorderService.instance;
        if (s != null) s.showPreview(false);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(64));
        lp.rightMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private Button small(String label, final float dStart, final float dStop) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                RecorderService s = RecorderService.instance;
                if (s != null) s.setThresholds(s.startThr + dStart, s.stopThr + dStop);
            }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(56), dp(52)));
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
