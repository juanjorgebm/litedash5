package com.okavango.dashcam;

import android.app.Activity;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;

/** Reproductor: pausa, adelantar/atrasar con la barra, y anterior/siguiente video. */
public class PlayerActivity extends Activity {

    static final String EXTRA_LIST = "list", EXTRA_POS = "pos";

    private VideoView video;
    private TextView title;
    private ArrayList<String> list;
    private int pos;
    private TextView stamp, place;
    /** Ubicación GPS por segundo, leída del .srt del video (vacío si no hubo señal). */
    private java.util.List<String> gps = new java.util.ArrayList<>();
    private long startWall;
    private final android.os.Handler h = new android.os.Handler();

    /** Fecha y hora real del cuadro que se ve, en la esquina (inicio del archivo + posición). */
    private final Runnable tickStamp = new Runnable() {
        public void run() {
            if (startWall > 0) {
                long t = startWall + video.getCurrentPosition();
                stamp.setText(new java.text.SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", java.util.Locale.US).format(new java.util.Date(t)));
            }
            int sec = video.getCurrentPosition() / 1000;
            String g = sec < gps.size() ? gps.get(sec) : "";
            place.setText(g);
            place.setVisibility(g.length() > 0 ? View.VISIBLE : View.GONE);
            h.postDelayed(this, 300);
        }
    };

    @Override
    protected void onResume() { super.onResume(); h.post(tickStamp); }

    @Override
    protected void onPause() { super.onPause(); h.removeCallbacks(tickStamp); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        list = getIntent().getStringArrayListExtra(EXTRA_LIST);
        pos = getIntent().getIntExtra(EXTRA_POS, 0);
        if (list == null || list.isEmpty()) { finish(); return; }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setBackgroundColor(0x99000000);
        title.setPadding(20, 10, 20, 10);
        root.addView(title, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        stamp = new TextView(this);
        stamp.setTextColor(Color.WHITE);
        stamp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stamp.setBackgroundColor(0x88000000);
        stamp.setPadding(12, 4, 12, 4);
        root.addView(stamp, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
        place = new TextView(this);
        place.setTextColor(Color.WHITE);
        place.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        place.setBackgroundColor(0x88000000);
        place.setPadding(12, 4, 12, 4);
        root.addView(place, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        setContentView(root);

        MediaController mc = new MediaController(this);
        mc.setAnchorView(video);
        mc.setPrevNextListeners(new View.OnClickListener() {
            // Anterior = tramo grabado antes (la lista va del más nuevo al más viejo).
            public void onClick(View v) { if (pos + 1 < list.size()) { pos++; play(); } }
        }, new View.OnClickListener() {
            public void onClick(View v) { if (pos > 0) { pos--; play(); } }
        });
        video.setMediaController(mc);
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            // Al terminar un tramo sigue con el tramo siguiente en el tiempo (la lista va del más nuevo al más viejo).
            public void onCompletion(MediaPlayer mp) { if (pos > 0) { pos--; play(); } }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            public boolean onError(MediaPlayer mp, int what, int extra) {
                title.setText(title.getText() + "   ✘ no se puede reproducir (¿se está grabando todavía?)");
                return true;
            }
        });
        play();
    }

    private void play() {
        File f = new File(list.get(pos));
        title.setText(Storage.label(f) + "   (" + (pos + 1) + " de " + list.size() + ")");
        try {
            startWall = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).parse(f.getName().substring(0, 19)).getTime();
        } catch (Throwable t) { startWall = 0; }
        gps = readGps(new File(f.getParentFile(), f.getName().replace(".mp4", ".srt")));
        video.setVideoURI(Uri.fromFile(f));
        video.start();
    }

    /** Del .srt: bloques "n / tiempo / fecha / [ubicación]"; devuelve la ubicación de cada segundo. */
    private static java.util.List<String> readGps(File srt) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(srt));
            java.util.List<String> block = new java.util.ArrayList<>();
            String line;
            while (true) {
                line = r.readLine();
                if (line == null || line.trim().isEmpty()) {
                    if (!block.isEmpty()) out.add(block.size() >= 4 ? block.get(3) : "");
                    block.clear();
                    if (line == null) break;
                } else block.add(line);
            }
            r.close();
        } catch (Throwable ignored) { }
        return out;
    }
}
