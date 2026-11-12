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
    /** Abierta sola al encender el carro: arranca y pasa a segundo plano. */
    static final String EXTRA_MINIMIZE = "minimize";

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
    private Switch autoSwitch, impactSwitch, openSwitch, trackSwitch;
    private TextView trackId, trackLine, photoLine, photoVal;
    private Switch photoSwitch;
    private TextView speedVal, stopSpeedVal, gpsLine, storageLine, maxVal;
    private long lastStorage;
    private TextView impactVal, impactLive;
    private LinearLayout cameraRow;
    private int shownCamera = -1;
    private TextView startVal, stopVal, liveVib, updateInfo, updateButton;
    private boolean syncing, busy;
    private String[] available;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        RecorderService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(BG);
        // La barra del clima del carro ya queda fuera de la ventana: la app llega hasta abajo.
        root.setPadding(0, 0, 0, 0);

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
        minimizeIfAsked(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        minimizeIfAsked(i);
    }

    private void minimizeIfAsked(Intent i) {
        if (i == null || !i.getBooleanExtra(EXTRA_MINIMIZE, false)) return;
        i.removeExtra(EXTRA_MINIMIZE);
        // Un momento para que el servicio arranque; después vuelve a la pantalla de inicio.
        ui.postDelayed(new Runnable() { public void run() { moveTaskToBack(true); } }, 1500);
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

        // Botón = ícono + texto, centrados juntos en el recuadro.
        recBox = new LinearLayout(this);
        recBox.setGravity(Gravity.CENTER);
        recIcon = new android.widget.ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(34), dp(34));
        ilp.rightMargin = dp(18);
        recBox.addView(recIcon, ilp);
        recButton = text("GRABAR", 28, Color.WHITE);
        recButton.setTypeface(Typeface.DEFAULT_BOLD);
        recBox.addView(recButton);
        setRecIcon(false);
        recBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.toggle(); }
        });
        left.addView(recBox, new LinearLayout.LayoutParams(dp(360), dp(96)));

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
        // Separación entre videos: divisor transparente (setDivider(null) borra también la altura).
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(16));
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
                t.setMinHeight(dp(68));
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
                            public void onClick(DialogInterface d, int w) {
                                f.delete();
                                new File(f.getParentFile(), f.getName().replace(".mp4", ".srt")).delete();
                                reloadVideos();
                            }
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

        openSwitch = new Switch(this);
        openSwitch.setText("Arrancar la dashcam al encender el carro (se minimiza sola)   ");
        openSwitch.setTextColor(Color.WHITE);
        openSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        openSwitch.setPadding(0, dp(10), 0, 0);
        openSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && !syncing) s.setOpenOnAcc(on);
            }
        });
        autoCard.addView(openSwitch);

        LinearLayout gpsCard = card(col, "GPS y velocidad");
        gpsLine = text("", 17, GREY);
        gpsLine.setPadding(0, 0, 0, dp(6));
        gpsCard.addView(gpsLine);
        speedVal = text("", 22, AMBER);
        LinearLayout sr = new LinearLayout(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.addView(text("Con GPS, graba a partir de (km/h)", 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        sr.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setSpeed(s.speedThr - 5); }
        }));
        speedVal.setGravity(Gravity.CENTER);
        sr.addView(speedVal, new LinearLayout.LayoutParams(dp(90), -2));
        sr.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setSpeed(s.speedThr + 5); }
        }));
        gpsCard.addView(sr);
        stopSpeedVal = text("", 22, AMBER);
        LinearLayout sr2 = new LinearLayout(this);
        sr2.setGravity(Gravity.CENTER_VERTICAL);
        sr2.setPadding(0, dp(8), 0, 0);
        sr2.addView(text("Deja de grabar al bajar de (km/h)", 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        sr2.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setStopSpeed(s.stopKmh - 1); }
        }));
        stopSpeedVal.setGravity(Gravity.CENTER);
        sr2.addView(stopSpeedVal, new LinearLayout.LayoutParams(dp(90), -2));
        sr2.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setStopSpeed(s.stopKmh + 1); }
        }));
        gpsCard.addView(sr2);
        TextView gtip = text("Con señal GPS empieza a grabar al pasar de la primera velocidad y sigue hasta bajar de la segunda (0 = hasta detenerse del todo). La ubicación queda en el video. Sin GPS se usa la vibración (abajo).", 15, GREY);
        gtip.setPadding(0, dp(6), 0, 0);
        gpsCard.addView(gtip);

        LinearLayout thrCard = card(col, "Sensibilidad del movimiento (sin GPS)");
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

        LinearLayout impCard = card(col, "Golpes y frenadas bruscas");
        impactSwitch = new Switch(this);
        impactSwitch.setText("Grabar 1 minuto al detectar un golpe (también con el carro apagado)   ");
        impactSwitch.setTextColor(Color.WHITE);
        impactSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        impactSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && !syncing) s.setImpact(on, s.impactThr);
            }
        });
        impCard.addView(impactSwitch);
        impactLive = text("", 17, GREY);
        impactLive.setPadding(0, dp(6), 0, dp(4));
        impCard.addView(impactLive);
        impactVal = text("", 22, AMBER);
        LinearLayout ir = new LinearLayout(this);
        ir.setGravity(Gravity.CENTER_VERTICAL);
        ir.addView(text("Fuerza del golpe para grabar (m/s²)", 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        ir.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setImpact(s.impactOn, s.impactThr - 0.5f); }
        }));
        impactVal.setGravity(Gravity.CENTER);
        ir.addView(impactVal, new LinearLayout.LayoutParams(dp(90), -2));
        ir.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setImpact(s.impactOn, s.impactThr + 0.5f); }
        }));
        impCard.addView(ir);
        TextView itip = text("Más bajo = más sensible. Los videos de golpes salen marcados GOLPE y no se borran solos.", 15, GREY);
        itip.setPadding(0, dp(6), 0, 0);
        impCard.addView(itip);

        LinearLayout camCard = card(col, "Cámara");
        cameraRow = new LinearLayout(this);
        camCard.addView(cameraRow);
        TextView ctip = text("La pantalla muestra estas cámaras. La 0 es la salida del sistema 360 (la que ya funciona); la 1 la tiene ocupada el sistema, pero puedes intentarlo. Si una falla, el error sale en la pestaña Grabar.", 15, GREY);
        ctip.setPadding(0, dp(8), 0, 0);
        camCard.addView(ctip);

        LinearLayout stCard = card(col, "Almacenamiento");
        storageLine = text("", 17, GREY);
        storageLine.setPadding(0, 0, 0, dp(6));
        stCard.addView(storageLine);
        maxVal = text("", 22, AMBER);
        LinearLayout mr = new LinearLayout(this);
        mr.setGravity(Gravity.CENTER_VERTICAL);
        mr.addView(text("Sin USB, usar como máximo (GB)", 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        mr.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setMaxInternal(s.maxInternalGb - 0.5f); }
        }));
        maxVal.setGravity(Gravity.CENTER);
        mr.addView(maxVal, new LinearLayout.LayoutParams(dp(90), -2));
        mr.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setMaxInternal(s.maxInternalGb + 0.5f); }
        }));
        stCard.addView(mr);
        TextView stip = text("Con USB conectada graba ahí y usa todo el espacio (deja 2 GB libres). Sin USB graba en la memoria de la pantalla hasta este tamaño; al llegar, borra los videos más viejos (menos los de golpes).", 15, GREY);
        stip.setPadding(0, dp(6), 0, 0);
        stCard.addView(stip);

        LinearLayout trCard = card(col, "Rastreo GPS");
        trackSwitch = new Switch(this);
        trackSwitch.setText("Enviar la ubicación a " + Tracker.HOST + "   ");
        trackSwitch.setTextColor(Color.WHITE);
        trackSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        trackSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && s.tracker != null && !syncing) s.tracker.setEnabled(on);
            }
        });
        trCard.addView(trackSwitch);
        trackId = text("", 22, AMBER);
        trackId.setPadding(0, dp(10), 0, 0);
        trCard.addView(trackId);
        trackLine = text("", 16, GREY);
        trackLine.setPadding(0, dp(6), 0, 0);
        trCard.addView(trackLine);
        TextView ttip = text("Se conecta como un rastreador Teltonika a " + Tracker.HOST + " (prueba los puertos " + Tracker.PORTS[0] + " y " + Tracker.PORTS[1]
                + " y se queda con el que funcione). El servidor y el ID son fijos. Registrá ese ID (IMEI) en la plataforma. Sin Internet los puntos se guardan y se envían después.", 15, GREY);
        ttip.setPadding(0, dp(6), 0, 0);
        trCard.addView(ttip);

        LinearLayout phCard = card(col, "Fotos a la plataforma");
        photoSwitch = new Switch(this);
        photoSwitch.setText("Enviar fotos a " + Tracker.HOST + "   ");
        photoSwitch.setTextColor(Color.WHITE);
        photoSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        photoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean on) {
                RecorderService s = RecorderService.instance;
                if (s != null && s.photos != null && !syncing) s.photos.setEnabled(on);
            }
        });
        phCard.addView(photoSwitch);
        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.CENTER_VERTICAL);
        pr.setPadding(0, dp(8), 0, 0);
        pr.addView(text("Mientras graba, una foto cada (min)", 19, Color.WHITE), new LinearLayout.LayoutParams(dp(380), -2));
        pr.addView(round("−", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null && s.photos != null) s.photos.stepInterval(-1); }
        }));
        photoVal = text("", 22, AMBER);
        photoVal.setGravity(Gravity.CENTER);
        pr.addView(photoVal, new LinearLayout.LayoutParams(dp(90), -2));
        pr.addView(round("+", new View.OnClickListener() {
            public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null && s.photos != null) s.photos.stepInterval(1); }
        }));
        phCard.addView(pr);
        photoLine = text("", 16, GREY);
        photoLine.setPadding(0, dp(6), 0, 0);
        phCard.addView(photoLine);
        TextView ptip = text("Funciona como la cámara Teltonika DualCam (puerto " + Photos.PORT + ", mismo ID). Manda una foto al empezar a grabar, cada tantos minutos, al detectar un golpe y cuando la plataforma la pide. Las fotos salen de la grabación en curso: nunca se abre la cámara solo para una foto.", 15, GREY);
        ptip.setPadding(0, dp(6), 0, 0);
        phCard.addView(ptip);

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
                Tracker tr = s.tracker;
                if (tr != null) {
                    trackId.setText("ID del dispositivo (IMEI):  " + tr.imei);
                    trackLine.setText(!tr.enabled ? "Apagado: no guarda ni envía la ubicación."
                            : (tr.status.isEmpty() ? "Esperando puntos del GPS" : tr.status)
                            + "  ·  enviados " + tr.sent + "  ·  pendientes " + tr.pending
                            + (tr.lastPoint.isEmpty() ? "" : "\nÚltimo punto: " + tr.lastPoint)
                            + (tr.realNow() == 0 ? "\nTodavía sin hora de Internet: usa la del GPS."
                               : Math.abs(tr.screenClockOffset()) > 600_000 ? String.format(Locale.US,
                                    "\nEl reloj de la pantalla va corrido %.1f h; se usa la hora de Internet.", tr.screenClockOffset() / 3_600_000.0) : ""));
                }
                Photos ph = s.photos;
                if (ph != null) {
                    photoVal.setText(String.valueOf(ph.everyMin));
                    photoLine.setText(!ph.enabled ? "Apagado: no toma ni envía fotos."
                            : (ph.status.isEmpty() ? "Esperando la primera foto" : ph.status)
                            + "  ·  enviadas " + ph.sent + "  ·  pendientes " + ph.pending);
                }
                boolean active = s.recording || s.forcedUntil > now;
                recButton.setText(active ? "DETENER" : "GRABAR");
                setRecIcon(active);
                recBox.setBackground(active ? pressable(RED, Color.WHITE) : pressable(SLATE, AMBER));
                String vib = String.format(Locale.US, "Vibración %.3f  (últimos 10 s: %.3f – %.3f)  ·  %s",
                        s.vibration, s.vibMin10 > 8 ? 0 : s.vibMin10, s.vibMax10, s.moving ? "en marcha" : "detenido");
                String gpsTxt = s.gpsActive() ? String.format(Locale.US, "GPS %.0f km/h  ·  ", s.speedKmh) : "";
                detail.setText(gpsTxt + vib + (s.armed ? "  ·  automática encendida" : "  ·  automática apagada"));
                liveVib.setText(vib);
                startVal.setText(String.format(Locale.US, "%.2f", s.startThr));
                impactVal.setText(String.format(Locale.US, "%.1f", s.impactThr));
                speedVal.setText(String.format(Locale.US, "%.0f", s.speedThr));
                stopSpeedVal.setText(String.format(Locale.US, "%.0f", s.stopKmh));
                maxVal.setText(String.format(Locale.US, "%.1f", s.maxInternalGb));
                if (tab == 2 && now - lastStorage > 3000) {
                    lastStorage = now;
                    File in = Storage.internalDir();
                    boolean usbNow = !Storage.usbRoots().isEmpty();
                    storageLine.setText((usbNow ? "USB conectada: graba en la USB." : "Sin USB: graba en la memoria de la pantalla.")
                            + "  Memoria de la pantalla: videos " + Storage.human(Storage.folderSize(in))
                            + " de " + String.format(Locale.US, "%.1f GB", s.maxInternalGb)
                            + " · libre " + Storage.human(in.getParentFile().getUsableSpace()));
                }
                String g = !s.hasGps ? "Esta pantalla no tiene GPS: se usa la vibración"
                        : s.gpsActive() ? String.format(Locale.US, "GPS: %.0f km/h  ·  %.5f, %.5f", s.speedKmh, s.lat, s.lon)
                        : "GPS: buscando señal… (mientras tanto se usa la vibración)";
                gpsLine.setText(g);
                impactLive.setText(String.format(Locale.US, "Sacudida ahora: %.1f  (máx. últimos 10 s: %.1f)", s.jolt, s.joltMax10));
                if (shownCamera != s.cameraId) buildCameraButtons(s.cameraId);
                stopVal.setText(String.format(Locale.US, "%.2f", s.stopThr));
                syncing = true;
                autoSwitch.setChecked(s.armed);
                impactSwitch.setChecked(s.impactOn);
                openSwitch.setChecked(s.openOnAcc);
                if (s.tracker != null) trackSwitch.setChecked(s.tracker.enabled);
                if (s.photos != null) photoSwitch.setChecked(s.photos.enabled);
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

    private void buildCameraButtons(int selected) {
        shownCamera = selected;
        cameraRow.removeAllViews();
        int n;
        try { n = android.hardware.Camera.getNumberOfCameras(); } catch (Throwable t) { n = 0; }
        if (n == 0) { cameraRow.addView(text("No se encontraron cámaras", 18, RED)); return; }
        for (int i = 0; i < n; i++) {
            final int id = i;
            String facing = "";
            try {
                android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
                android.hardware.Camera.getCameraInfo(i, info);
                facing = info.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT ? " (frontal)" : " (trasera)";
            } catch (Throwable ignored) { }
            String name = "Cámara " + i + (i == 0 ? " · sistema 360" : facing);
            TextView b = button(name, new View.OnClickListener() {
                public void onClick(View v) { RecorderService s = RecorderService.instance; if (s != null) s.setCamera(id); }
            });
            if (i == selected) {
                b.setBackground(pressable(AMBER, AMBER));
                b.setTextColor(BG);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(60));
            lp.rightMargin = dp(12);
            cameraRow.addView(b, lp);
        }
    }

    private Boolean recIconActive;
    private LinearLayout recBox;
    private android.widget.ImageView recIcon;

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
        recIcon.setImageDrawable(g);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
