package com.okavango.dashcam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/** Actualizador: compara con la última versión publicada en GitHub, la descarga e inicia la instalación. */
final class Updater {
    static final String BASE = "https://github.com/juanjorgebm/litedash5/releases/download/dashcam-okavango/";

    interface Progress { void update(String text); }

    private Updater() { }

    static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Throwable t) { return 0; }
    }

    static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    /** Devuelve {código, nombre} de la última versión publicada. Se llama fuera del hilo principal. */
    static String[] latest() throws Exception {
        HttpURLConnection con = open(BASE + "version.txt?t=" + System.currentTimeMillis());
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
            String line = r.readLine();
            r.close();
            if (line == null) throw new Exception("respuesta vacía");
            String[] parts = line.trim().split("\\s+", 2);
            return new String[]{parts[0], parts.length > 1 ? parts[1] : parts[0]};
        } finally {
            con.disconnect();
        }
    }

    /** Descarga el APK a Descargas y abre el instalador de Android. */
    static void downloadAndInstall(Context c, Progress p) throws Exception {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        File apk = new File(dir, "dashcam-okavango.apk");
        HttpURLConnection con = open(BASE + "dashcam-okavango.apk?t=" + System.currentTimeMillis());
        try {
            int total = con.getContentLength();
            InputStream in = con.getInputStream();
            FileOutputStream out = new FileOutputStream(apk);
            byte[] buf = new byte[16384];
            int n, done = 0, lastPct = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                int pct = total > 0 ? done * 100 / total : -1;
                if (pct != lastPct) { lastPct = pct; p.update("Descargando… " + (pct >= 0 ? pct + "%" : (done >> 10) + " KB")); }
            }
            out.close();
            in.close();
        } finally {
            con.disconnect();
        }
        p.update("Abriendo el instalador…");
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private static HttpURLConnection open(String url) throws Exception {
        // GitHub redirige a otro servidor (https → https); se siguen las redirecciones a mano por si acaso.
        for (int hops = 0; hops < 5; hops++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "DashcamOkavango");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                url = con.getHeaderField("Location");
                con.disconnect();
                continue;
            }
            if (code != 200) { con.disconnect(); throw new Exception("GitHub respondió " + code); }
            return con;
        }
        throw new Exception("demasiadas redirecciones");
    }
}
