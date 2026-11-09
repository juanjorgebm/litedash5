package com.okavango.dashcam;

import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Dónde guardar: la USB si hay una con permiso de escritura (su nombre cambia al reconectarla), si no la memoria interna. */
final class Storage {
    static final String FOLDER = "Dashcam";
    /** Espacio libre mínimo: al bajar de esto se borran los videos más viejos. */
    static final long MIN_FREE_USB = 2L << 30, MIN_FREE_INTERNAL = 1L << 30;

    private Storage() { }

    static List<File> usbRoots() {
        List<File> out = new ArrayList<>();
        for (String base : new String[]{"/storage/usbotg", "/mnt/media_rw", "/storage"}) {
            File[] list = new File(base).listFiles();
            if (list == null) continue;
            for (File d : list) {
                String n = d.getName();
                if (!d.isDirectory() || !d.canWrite()) continue;
                if (base.equals("/storage") && !(n.startsWith("usb") || n.startsWith("udisk"))) continue;
                if (n.equals("usbotg")) continue;
                out.add(d);
            }
        }
        return out;
    }

    static File internalDir() {
        return new File(Environment.getExternalStorageDirectory(), FOLDER);
    }

    /** Carpeta de grabación preferida (se crea si hace falta). */
    static File recordDir() {
        for (File root : usbRoots()) {
            File d = new File(root, FOLDER);
            if ((d.isDirectory() || d.mkdirs()) && d.canWrite()) return d;
        }
        File d = internalDir();
        d.mkdirs();
        return d;
    }

    static boolean isUsb(File dir) {
        return !dir.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath());
    }

    /** Todos los videos de todas las carpetas Dashcam, el más nuevo primero. */
    static List<File> allVideos() {
        List<File> dirs = new ArrayList<>();
        for (File root : usbRoots()) dirs.add(new File(root, FOLDER));
        dirs.add(internalDir());
        List<File> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.getName().endsWith(".mp4")) continue;
                // La misma USB aparece en /storage/usbotg y /mnt/media_rw: no repetir.
                String key = f.getName() + "/" + f.length();
                if (seen.contains(key)) continue;
                seen.add(key);
                out.add(f);
            }
        }
        sortNewestFirst(out);
        return out;
    }

    static void sortNewestFirst(List<File> files) {
        java.util.Collections.sort(files, new Comparator<File>() {
            public int compare(File a, File b) { return b.getName().compareTo(a.getName()); }
        });
    }

    /** Borra los videos más viejos de la carpeta hasta tener el espacio mínimo libre. Devuelve cuántos borró. */
    static int freeSpace(File dir, File keep) {
        long min = isUsb(dir) ? MIN_FREE_USB : MIN_FREE_INTERNAL;
        int deleted = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        List<File> videos = new ArrayList<>();
        for (File f : files) if (f.getName().endsWith(".mp4") && !f.equals(keep)) videos.add(f);
        sortNewestFirst(videos);
        while (dir.getUsableSpace() < min && !videos.isEmpty()) {
            File oldest = videos.remove(videos.size() - 1);
            if (oldest.delete()) deleted++;
        }
        return deleted;
    }

    /** "2026-11-09_08-15-30.mp4" → "09/11/2026  08:15:30   ·   130 MB   ·   USB" */
    static String label(File f) {
        String n = f.getName().replace(".mp4", "");
        String txt = n;
        if (n.length() >= 19) {
            txt = n.substring(8, 10) + "/" + n.substring(5, 7) + "/" + n.substring(0, 4) + "   "
                    + n.substring(11, 13) + ":" + n.substring(14, 16) + ":" + n.substring(17, 19);
        }
        return txt + "   ·   " + human(f.length()) + "   ·   " + (isUsb(f.getParentFile()) ? "USB" : "memoria interna");
    }

    static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        return String.format(java.util.Locale.US, "%d MB", bytes >> 20);
    }

    static List<File> asList(File[] a) { return a == null ? new ArrayList<File>() : new ArrayList<>(Arrays.asList(a)); }
}
