package com.okavango.dashcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla única con tres pestañas a la izquierda (como LiteDash): Grabar, Videos y Ajustes.
 * Colores: grafito (#171B21), pizarra (#343B45), ámbar (#FFB547).
 */
public class MainActivity extends Activity {

    static final int AMBER = Color.rgb(0xFF, 0xB5, 0x47), BG = Color.rgb(0x17, 0x1B, 0x21),
            SLATE = Color.rgb(0x34, 0x3B, 0x45), PANEL = Color.rgb(0x22, 0x27, 0x2F),
            RED = Color.rgb(0xE5, 0x39, 0x35), GREY = Color.rgb(0xA8, 0xB0, 0xBA);

    private final Handler ui = new Handler();
    private FrameLayout content;
    private View recordTab, videosTab, settingsTab;
    private TextView[] tabButtons;
    private int tab;

    // Grabar
    private TextView status, detail, folder, error, events, recButton;
    // Videos
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<File> adapter;
    private TextView videosHeader;
    // Ajustes
    private Switch autoSwitch;
    private TextView startVal, stopVal, liveVib, updateInfo, updateButton;
    private boolean syncing, busy;
    private String[] available;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RecorderService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        // La barra del clima del carro tapa la parte de abajo de la pantalla.
        root.setPadding(0, 0, 0, dp(80));

        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setBackgroundColor(PANEL);
        rail.setPadding(dp(12), dp(16), dp(12), dp(16));
        TextView brand = text("Dashcam", 20, AMBER);
        brand.setPadding(dp(8), 0, 0, dp(16));
        rail.addView(brand);
        String[] names = {"Grabar", "Videos", "Ajustes"};
        tabButtons = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 20, Color.WHITE);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { showTab(idx); } });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(64));
            lp.bottomMargin = dp(10);
            rail.addView(t, lp);
            tabButtons[i] = t;
        }
        root.addView(rail, new LinearLayout.LayoutParams(dp(190), -1));

        content = new FrameLayout(this);
        content.setPadding(dp(24), dp(18), dp(24), dp(8));
        root.addView(content, new LinearLayout.LayoutParams(0, -1, 1f));

        recordTab = buildRecordTab();
        videosTab = buildVideosTab();
        settingsTab = buildSettingsTab();
        content.addView(recordTab);
        content.addView(videosTab);
        content.addView(settingsTab);
        setContentView(root);
        showTab(0);
        checkUpdate(false);
    }

    private void showTab(int i) {
        tab = i;
        recordTab.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        videosTab.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        settingsTab.setVisibility(i == 2 ? View.VISIBLE : View.GONE);
        for (int k = 0; k < tabButtons.length; k++) {
            boolean on = k == i;
            tabButtons[k].setBackground(box(on ? SLATE : PANEL, on ? AMBER : PANEL, 14));
            tabButtons[k].setTextColor(on ? AMBER : Color.WHITE);
        }
        if (i == 1) reloadVideos();
        RecorderService s = RecorderService.instance;
        if (s != null && i != 0) s.showPreview(false);
    }

    // ---------------------------------------------------------------- pestaña Grabar

    private View buildRecordTab() {
        LinearLayout row = new LinearLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, -1, 1f));

        status = text("Iniciando…", 30, Color.WHITE);
        left.addView(status);
        detail = text("", 17, GREY);
        detail.setPadding(0, dp(4), 0, dp(16));
        left.addView(detail);

        recButton = text("GRABAR", 28, Color.WHITE);
        recButton.setCompoundDrawablePadding(dp(16));
        setRecIcon(false);
        recButton.setGravity(Gravity.CENTER);
        recButton.setTypeface(Typeface.DEFAULT_BOLD);
        recButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.toggle(); }
        });
        left.addView(recButton, new LinearLayout.LayoutParams(dp(360), dp(96)));

        TextView hint = text("Graba al tocar. Con la grabación automática encendida, también graba sola mientras el carro va en marcha y se detiene al parar.", 15, GREY);
        hint.setPadding(0, dp(12), dp(16), 0);
        left.addView(hint);

        folder = text("", 15, GREY);
        folder.setPadding(0, dp(12), 0, 0);
        left.addView(folder);
        error = text("", 16, RED);
        left.addView(error);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        row.addView(right, new LinearLayout.LayoutParams(dp(530), -1));
        TextView hole = text("La vista previa aparece aquí mientras graba", 15, GREY);
        hole.setGravity(Gravity.CENTER);
        hole.setBackground(box(Color.BLACK, SLATE, 10));
        right.addView(hole, new LinearLayout.LayoutParams(-1, dp(200)));
        events = text("", 15, Color.rgb(0xDD, 0xDD, 0xDD));
        events.setPadding(0, dp(10), 0, 0);
        right.addView(events);
        return row;
    }

    // ---------------------------------------------------------------- pestaña Videos

    private View buildVideosTab() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        videosHeader = text("", 20, AMBER);
        videosHeader.setPadding(0, 0, 0, dp(10));
        col.addView(videosHeader);
        ListView list = new ListView(this);
        list.setDividerHeight(dp(8));
        list.setDivider(null);
        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, files) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                File f = getItem(pos);
                RecorderService s = RecorderService.instance;
                boolean live = s != null && f.equals(s.currentFile);
                t.setText(Storage.label(f) + (live ? "   · grabando" : ""));
                t.setPadding(dp(20), 0, dp(20), 0);
                t.setTextColor(live ? RED : Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                t.setMinHeight(dp(60));
                t.setBackground(pressable(SLATE, SLATE));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ArrayList<String> paths = new ArrayList<>();
                for (File f : files) paths.add(f.getAbsolutePath());
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putStringArrayListExtra(PlayerActivity.EXTRA_LIST, paths);
                i.putExtra(PlayerActivity.EXTRA_POS, pos);
                startActivity(i);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final File f = files.get(pos);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("¿Borrar este video?")
                        .setMessage(Storage.label(f))
                        .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { f.delete(); reloadVideos(); }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        return col;
    }

    private void reloadVideos() {
        files.clear();
        files.addAll(Storage.allVideos());
        long total = 0;
        for (File f : files) total += f.length();
        videosHeader.setText(files.size() + " videos · " + Storage.human(total)
                + "      toca para ver · mantén presionado para borrar");
        adapter.notifyDataSetChanged();
    }

    // ---------------------------------------------------------------- pestaña Ajustes

    private View buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        sv.addView(col);

        LinearLayout autoCard = card(col, "Grabación automática");
        autoSwitch = new Switch(this);
        autoSwitch.setText("Grabar sola cuando el carro va en marcha   ");
        autoSwitch.setTextColor(Color.WHITE);
        autoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && !syncing) s.setArmed(on);
            }
        });
        autoCard.addView(autoSwitch);

        LinearLayout thrCard = card(col, "Sensibilidad del movimiento");
        liveVib = text("", 17, GREY);
        liveVib.setPadding(0, 0, 0, dp(8));
        thrCard.addView(liveVib);
        startVal = text("", 22, AMBER);
        stopVal = text("", 22, AMBER);
        thrCard.addView(stepper("Empieza a grabar si vibra más de", startVal, 0.01f, 0));
        thrCard.addView(stepper("Deja de grabar si baja de", stopVal, 0, 0.01f));
        TextView tip = text("Mira la vibración en un semáforo con el motor encendido: el número de \"Deja de grabar\" debe quedar por encima de ese valor.", 15, GREY);
        tip.setPadding(0, dp(6), 0, 0);
        thrCard.addView(tip);

        LinearLayout updCard = card(col, "Actualización");
        LinearLayout upd = new LinearLayout(this);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        updateButton = button("Buscar actualización", new View.OnClickListener() {
            public void onClick(View v) { onUpdateClick(); }
        });
        upd.addView(updateButton);
        updateInfo = text("Versión " + Updater.currentName(this), 17, GREY);
        updateInfo.setPadding(dp(16), 0, 0, 0);
        upd.addView(updateInfo);
        updCard.addView(upd);
        return sv;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(box(PANEL, PANEL, 16));
        c.setPadding(dp(20), dp(14), dp(20), dp(16));
        TextView t = text(title, 18, AMBER);
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private View stepper(String label, TextView value, final float dStart, final float dStop) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(4), 0, dp(4));
        r.addView(text(label, 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        r.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { bump(-dStart, -dStop); }
        }));
        value.setGravity(Gravity.CENTER);
        r.addView(value, new LinearLayout.LayoutParams(dp(90), -2));
        r.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { bump(dStart, dStop); }
        }));
        return r;
    }

    private void bump(float dStart, float dStop) {
        RecorderService s = RecorderService.instance;
        if (s != null) s.setThresholds(s.startThr + dStart, s.stopThr + dStop);
    }

    // ---------------------------------------------------------------- refresco cada 0.5 s

    private final Runnable refresh = new Runnable() {
        public void run() {
            RecorderService s = RecorderService.instance;
            if (s == null) {
                status.setText("Iniciando…");
                RecorderService.start(MainActivity.this);
            } else {
                long now = SystemClock.elapsedRealtime();
                if (s.recording) {
                    long sec = (now - s.recordingSince) / 1000;
                    String st = String.format(Locale.US, "Grabando  %d:%02d", sec / 60, sec % 60);
                    if (s.forcedUntil > now) st += "  (clip)";
                    status.setText(st);
                    status.setTextColor(RED);
                } else {
                    status.setTextColor(Color.WHITE);
                    status.setText(!s.accOn ? "Carro apagado"
                            : !s.armed ? "Lista · automática apagada"
                            : s.moving ? "En marcha · preparando cámara…"
                            : "Lista · carro detenido");
                }
                boolean active = s.recording || s.forcedUntil > now;
                recButton.setText(active ? "DETENER" : "GRABAR");
                setRecIcon(active);
                recButton.setBackground(active ? pressable(RED, Color.WHITE) : pressable(SLATE, AMBER));
                String vib = String.format(Locale.US, "Vibración %.3f  (últimos 10 s: %.3f – %.3f)  ·  %s",
                        s.vibration, s.vibMin10 > 8 ? 0 : s.vibMin10, s.vibMax10, s.moving ? "en marcha" : "detenido");
                detail.setText(vib + (s.armed ? "  ·  automática encendida" : "  ·  automática apagada"));
                liveVib.setText(vib);
                startVal.setText(String.format(Locale.US, "%.2f", s.startThr));
                stopVal.setText(String.format(Locale.US, "%.2f", s.stopThr));
                syncing = true;
                autoSwitch.setChecked(s.armed);
                syncing = false;
                File cur = s.currentFile;
                File dir = cur != null ? cur.getParentFile() : Storage.recordDir();
                folder.setText((Storage.isUsb(dir) ? "Guarda en la USB · libre " : "Sin USB: guarda en la memoria interna · libre ")
                        + Storage.human(dir.getUsableSpace()));
                error.setText(s.lastError);
                events.setText(s.lastEvent);
                s.showPreview(s.recording && tab == 0);
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(refresh);
        if (tab == 1) reloadVideos();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(refresh);
        RecorderService s = RecorderService.instance;
        if (s != null) s.showPreview(false);
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
                    String[] l = Updater.latest(MainActivity.this);
                    if (Integer.parseInt(l[0]) > Updater.currentCode(MainActivity.this)) {
                        available = l;
                        msg = "Hay una versión nueva: " + l[1] + " (tienes " + Updater.currentName(MainActivity.this) + ")";
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
                        updateInfo.setTextColor(available != null ? AMBER : GREY);
                        if (available != null) {
                            updateButton.setText("Actualizar a " + available[1]);
                            // Aviso visible en la pestaña Ajustes.
                            tabButtons[2].setText("Ajustes  (nueva versión)");
                            tabButtons[2].setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                        }
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

    // ---------------------------------------------------------------- estilo

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    /** Botón propio (los del sistema salen blancos con letra blanca en esta pantalla). */
    private TextView button(String label, View.OnClickListener l) {
        TextView b = text(label, 20, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(24), 0, dp(24), 0);
        b.setMinHeight(dp(60));
        b.setBackground(pressable(SLATE, AMBER));
        b.setOnClickListener(l);
        return b;
    }

    private TextView round(String label, View.OnClickListener l) {
        TextView b = text(label, 26, Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pressable(SLATE, AMBER));
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(64), dp(56)));
        return b;
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(2), stroke);
        return g;
    }

    private Drawable pressable(int fill, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, box(AMBER, AMBER, 14));
        s.addState(new int[]{}, box(fill, stroke, 14));
        return s;
    }

    private Boolean recIconActive;

    /** Círculo rojo (grabar) o cuadro blanco (detener), dibujados: la letra del sistema no trae ● ni ■. */
    private void setRecIcon(boolean active) {
        if (recIconActive != null && recIconActive == active) return;
        recIconActive = active;
        GradientDrawable g = new GradientDrawable();
        if (active) {
            g.setShape(GradientDrawable.RECTANGLE);
            g.setColor(Color.WHITE);
            g.setCornerRadius(dp(4));
            g.setSize(dp(28), dp(28));
        } else {
            g.setShape(GradientDrawable.OVAL);
            g.setColor(RED);
            g.setStroke(dp(3), Color.WHITE);
            g.setSize(dp(34), dp(34));
        }
        recButton.setCompoundDrawablesWithIntrinsicBounds(g, null, null, null);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
