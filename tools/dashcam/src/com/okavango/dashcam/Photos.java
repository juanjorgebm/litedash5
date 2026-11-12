package com.okavango.dashcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

/**
 * Fotos hacia la plataforma con el protocolo de transferencia de la cámara Teltonika DualCam:
 * el dispositivo se conecta, avisa que tiene una foto, el servidor la pide y se manda en pedazos
 * de 1024 bytes con verificación. Las fotos esperan en una carpeta local y se borran solo cuando
 * el servidor terminó de recibirlas, así que sin Internet se envían después.
 */
final class Photos {

    static final int PORT = 6232;
    static final String K_ON = "photos_on", K_MIN = "photos_min";
    static final int[] MINUTES = {1, 2, 5, 10, 15, 30};
    private static final int MAX_FILES = 200, CHUNK = 1024;

    private static final int MSG_INIT = 0, MSG_START = 1, MSG_RESUME = 2, MSG_SYNC = 3, MSG_DATA = 4,
            MSG_COMPLETE = 5, MSG_FILE_REQUEST = 8, MSG_INIT_REQUEST = 9;
    /** Bit de "tengo una foto de la cámara de frente" en el paquete de inicio. */
    private static final int HAS_PHOTO_FRONT = 1 << 26;

    volatile boolean enabled;
    volatile int everyMin;
    volatile String status = "";
    volatile long sent;
    volatile int pending;

    private final File dir;
    private final long imei;
    private final SharedPreferences prefs;
    private volatile boolean running = true;
    private final Thread sender;

    Photos(Context c, SharedPreferences prefs, String imei) {
        this.prefs = prefs;
        this.imei = Long.parseLong(imei);
        enabled = prefs.getBoolean(K_ON, true);
        everyMin = prefs.getInt(K_MIN, 5);
        dir = new File(c.getFilesDir(), "fotos");
        dir.mkdirs();
        pending = list().length;
        sender = new Thread(new Runnable() { public void run() { sendLoop(); } }, "fotos");
        sender.setDaemon(true);
        sender.start();
    }

    void setEnabled(boolean on) {
        enabled = on;
        prefs.edit().putBoolean(K_ON, on).apply();
        if (!on) status = "";
    }

    /** Pasa al intervalo siguiente o anterior de la lista. */
    void stepInterval(int d) {
        int i = 0;
        for (int k = 0; k < MINUTES.length; k++) if (MINUTES[k] <= everyMin) i = k;
        i = Math.max(0, Math.min(MINUTES.length - 1, i + d));
        everyMin = MINUTES[i];
        prefs.edit().putInt(K_MIN, everyMin).apply();
    }

    void close() {
        running = false;
        sender.interrupt();
    }

    /** Guarda la foto en la cola (en otro hilo, para no frenar a quien la tomó). */
    void add(final Bitmap b) {
        if (!enabled || b == null) return;
        new Thread(new Runnable() {
            public void run() {
                try {
                    File tmp = new File(dir, System.currentTimeMillis() + ".tmp");
                    FileOutputStream o = new FileOutputStream(tmp);
                    b.compress(Bitmap.CompressFormat.JPEG, 80, o);
                    o.close();
                    b.recycle();
                    tmp.renameTo(new File(dir, tmp.getName().replace(".tmp", ".jpg")));
                    File[] all = list();
                    // Mucho tiempo sin Internet: se pierden las más viejas.
                    for (int i = 0; i < all.length - MAX_FILES; i++) all[i].delete();
                    pending = list().length;
                } catch (Throwable t) {
                    status = "No se pudo guardar la foto: " + t.getMessage();
                }
            }
        }, "foto").start();
    }

    /** Fotos en espera, la más vieja primero (el nombre es la hora en que se tomó). */
    private File[] list() {
        File[] f = dir.listFiles(new java.io.FilenameFilter() {
            public boolean accept(File d, String n) { return n.endsWith(".jpg"); }
        });
        if (f == null) return new File[0];
        Arrays.sort(f);
        return f;
    }

    // ------------------------------------------------------------------ envío

    private void sendLoop() {
        while (running) {
            Socket sock = null;
            try {
                File[] files = enabled ? list() : new File[0];
                pending = files.length;
                if (files.length == 0) { Thread.sleep(5000); continue; }
                status = "Conectando…";
                sock = new Socket();
                sock.connect(new InetSocketAddress(Tracker.HOST, PORT), 15_000);
                sock.setSoTimeout(30_000);
                DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(sock.getOutputStream(), 8192));
                DataInputStream in = new DataInputStream(sock.getInputStream());
                for (File f : files) {
                    if (!running || !enabled) break;
                    sendOne(f, in, out);
                    f.delete();
                    sent++;
                    pending = list().length;
                    status = "Conectado";
                }
                // Ya no queda nada: inicio sin archivos, y el servidor cierra la sesión.
                try { init(out, 0); } catch (Throwable ignored) { }
                status = "Al día";
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                String m = t instanceof java.net.SocketTimeoutException
                        ? "El servidor no respondió (¿el ID ya está registrado en la plataforma?)"
                        : t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException
                        || t instanceof java.net.NoRouteToHostException ? "Sin conexión con el servidor"
                        : "Error: " + t.getMessage();
                status = m + " · se reintenta en 1 min";
                try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
                sock = null;
                try { Thread.sleep(60_000); } catch (InterruptedException e) { break; }
            } finally {
                try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
            }
        }
    }

    private void init(DataOutputStream out, int settings) throws IOException {
        out.writeShort(MSG_INIT);
        out.writeShort(5);          // versión del protocolo
        out.writeLong(imei);
        out.writeInt(settings);
        out.flush();
    }

    private void sendOne(File f, DataInputStream in, DataOutputStream out) throws IOException {
        byte[] data = new byte[(int) f.length()];
        FileInputStream fi = new FileInputStream(f);
        try { new DataInputStream(fi).readFully(data); } finally { fi.close(); }
        int packets = (data.length + CHUNK - 1) / CHUNK;
        if (packets == 0) return;
        // El CRC de cada pedazo arranca del CRC del pedazo anterior (el primero, de 0).
        int[] crc = new int[packets + 1];
        for (int p = 0; p < packets; p++) {
            int c = crc[p], end = Math.min(data.length, (p + 1) * CHUNK);
            for (int i = p * CHUNK; i < end; i++) {
                c ^= data[i] & 0xFF;
                for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ 0x8408 : c >>> 1;
            }
            crc[p + 1] = c;
        }

        // Algunos servidores mandan estos avisos sin el campo de largo: se descarta lo que sobre.
        while (in.available() > 0) in.read();
        init(out, HAS_PHOTO_FRONT);
        int type = in.readUnsignedShort();
        if (type != MSG_FILE_REQUEST) throw new IOException("el servidor no pidió la foto (respuesta " + type + ")");
        in.skipBytes(in.readUnsignedShort());      // "%photof"

        out.writeShort(MSG_START);
        out.writeShort(6);
        out.writeInt(packets);
        out.writeShort(0);
        out.flush();

        for (int tries = 0; tries < 4; tries++) {
            type = in.readUnsignedShort();
            if (type == MSG_INIT_REQUEST || type == MSG_COMPLETE) return;       // recibida
            if (type != MSG_RESUME) throw new IOException("respuesta inesperada del servidor (" + type + ")");
            in.skipBytes(in.readUnsignedShort() - 4);
            int from = Math.max(1, Math.min(in.readInt(), packets));
            out.writeShort(MSG_SYNC);
            out.writeShort(4);
            out.writeInt(from);
            for (int p = from - 1; p < packets; p++) {
                int off = p * CHUNK, n = Math.min(CHUNK, data.length - off);
                out.writeShort(MSG_DATA);
                out.writeShort(n + 2);
                out.write(data, off, n);
                out.writeShort(crc[p + 1]);
            }
            out.flush();
        }
        throw new IOException("el servidor rechazó la foto varias veces");
    }
}
