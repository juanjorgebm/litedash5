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
        title.setText(FilesActivity.label(f) + "   (" + (pos + 1) + " de " + list.size() + ")");
        video.setVideoURI(Uri.fromFile(f));
        video.start();
    }
}
