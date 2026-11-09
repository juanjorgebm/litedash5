package com.okavango.dashcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Lista de videos (USB y memoria interna). Tocar = reproducir; mantener presionado = borrar. */
public class FilesActivity extends Activity {

    private List<File> files = new ArrayList<>();
    private ArrayAdapter<File> adapter;
    private TextView header;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        root.setPadding(dp(20), dp(16), dp(20), dp(90));
        header = new TextView(this);
        header.setTextColor(MainActivity.AMBER);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        root.addView(header);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_1, files) {
            @Override
            public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                File f = getItem(pos);
                RecorderService s = RecorderService.instance;
                boolean live = s != null && f.equals(s.currentFile);
                t.setText(label(f) + (live ? "   ● grabando" : ""));
                t.setTextColor(live ? MainActivity.RED : Color.WHITE);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
                t.setMinHeight(dp(56));
                return t;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                ArrayList<String> paths = new ArrayList<>();
                for (File f : files) paths.add(f.getAbsolutePath());
                Intent i = new Intent(FilesActivity.this, PlayerActivity.class);
                i.putStringArrayListExtra(PlayerActivity.EXTRA_LIST, paths);
                i.putExtra(PlayerActivity.EXTRA_POS, pos);
                startActivity(i);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                final File f = files.get(pos);
                new AlertDialog.Builder(FilesActivity.this)
                        .setTitle("¿Borrar este video?")
                        .setMessage(label(f))
                        .setPositiveButton("Borrar", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { f.delete(); reload(); }
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        files.clear();
        files.addAll(Storage.allVideos());
        long total = 0;
        for (File f : files) total += f.length();
        header.setText(files.size() + " videos · " + Storage.human(total)
                + "   (toca para ver · mantén presionado para borrar)");
        adapter.notifyDataSetChanged();
    }

    /** "2026-11-09_08-15-30.mp4" → "09/11/2026  08:15:30   ·   130 MB   ·   USB" */
    static String label(File f) {
        String n = f.getName().replace(".mp4", "");
        String txt = n;
        if (n.length() >= 19) {
            txt = n.substring(8, 10) + "/" + n.substring(5, 7) + "/" + n.substring(0, 4) + "   "
                    + n.substring(11, 13) + ":" + n.substring(14, 16) + ":" + n.substring(17, 19);
        }
        return txt + "   ·   " + Storage.human(f.length()) + "   ·   " + (Storage.isUsb(f.getParentFile()) ? "USB" : "memoria interna");
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
