package com.okavango.dashcam;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rastreo GPS: se presenta ante el servidor como un rastreador Teltonika (protocolo Codec 8 por TCP).
 * Cada punto se guarda primero en una cola local (SQLite) y se borra solo cuando el servidor confirma
 * que lo recibió, así que sin Internet los puntos esperan y se envían después, en orden.
 * El servidor y el ID del dispositivo son fijos: no se pueden editar.
 */
final class Tracker {

    static final String HOST = "app.ubicacr.com";
    /**
     * Puertos donde se prueba enviar la ubicación, en orden. Se recuerda el que el servidor aceptó;
     * si uno falla se prueba el otro.
     */
    static final int[] PORTS = {6232, 6027};
    static final String K_PORT = "tracker_port";
    volatile int port;
    static final String K_ON = "tracker_on", K_ID = "tracker_id";

    /** Cada cuánto se guarda un punto en marcha / detenido, y giro que fuerza un punto extra. */
    private static final long MOVING_MS = 10_000, IDLE_MS = 300_000, TURN_MS = 2_000;
    private static final float TURN_DEG = 20, MOVING_KMH = 3;
    private static final int BATCH = 25, MAX_ROWS = 100_000;

    final String imei;
    volatile boolean enabled;
    volatile String status = "";
    volatile long sent;
    volatile int pending;

    private final SQLiteDatabase db;
    private final SharedPreferences prefs;
    private volatile boolean running = true;
    /** Se llama cuando la plataforma pide una foto con el comando camreq. */
    volatile Runnable onPhotoRequest;
    private final Thread sender;

    private Location last;          // último punto con señal
    private long lastSavedAt;       // elapsedRealtime del último punto guardado
    private float lastBearing = -1;
    private boolean lastIgnition = true, lastMoving;

    Tracker(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        imei = deviceId(c, prefs);
        enabled = prefs.getBoolean(K_ON, true);
        port = prefs.getInt(K_PORT, PORTS[0]);
        db = new SQLiteOpenHelper(c, "tracker.db", null, 1) {
            @Override public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE q (id INTEGER PRIMARY KEY AUTOINCREMENT, rec BLOB NOT NULL)");
            }
            @Override public void onUpgrade(SQLiteDatabase d, int a, int b) { }
        }.getWritableDatabase();
        pending = count();
        sender = new Thread(new Runnable() { public void run() { sendLoop(); } }, "tracker");
        sender.setDaemon(true);
        sender.start();
    }

    void setEnabled(boolean on) {
        enabled = on;
        prefs.edit().putBoolean(K_ON, on).apply();
        if (!on) status = "";
    }

    void close() {
        running = false;
        sender.interrupt();
    }

    // ------------------------------------------------------------------ ID fijo

    /**
     * ID tipo IMEI (15 dígitos, con dígito de control) calculado del identificador de la pantalla:
     * siempre da el mismo, aunque se reinstale la app.
     */
    private static String deviceId(Context c, SharedPreferences prefs) {
        String saved = prefs.getString(K_ID, null);
        if (saved != null && saved.length() == 15) return saved;
        String seed = null;
        try { seed = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID); } catch (Throwable ignored) { }
        if (seed == null || seed.isEmpty()) seed = android.os.Build.SERIAL + "/" + android.os.Build.FINGERPRINT;
        long n = 0;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(("okavango-dashcam:" + seed).getBytes("UTF-8"));
            for (int i = 0; i < 8; i++) n = (n << 8) | (h[i] & 0xFF);
        } catch (Throwable t) { n = seed.hashCode(); }
        n = (n & Long.MAX_VALUE) % 1_000_000_000_000L;
        String body = "35" + String.format(Locale.US, "%012d", n);
        int sum = 0;
        for (int i = 0; i < 14; i++) {
            int d = body.charAt(i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        String id = body + ((10 - sum % 10) % 10);
        prefs.edit().putString(K_ID, id).apply();
        return id;
    }

    // ------------------------------------------------------------------ puntos

    /** Llega con cada lectura del GPS (una por segundo); decide si vale la pena guardar el punto. */
    void onFix(Location l, boolean ignition, boolean moving) {
        last = l;
        if (!enabled) return;
        long now = SystemClock.elapsedRealtime();
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0;
        long since = now - lastSavedAt;
        boolean save = lastSavedAt == 0 || ignition != lastIgnition || moving != lastMoving
                || since >= IDLE_MS || (kmh >= MOVING_KMH && since >= MOVING_MS);
        if (!save && kmh >= MOVING_KMH && l.hasBearing() && lastBearing >= 0 && since >= TURN_MS) {
            float d = Math.abs(l.getBearing() - lastBearing) % 360;
            if (d > 180) d = 360 - d;
            save = d >= TURN_DEG;
        }
        if (save) save(l, l.getTime(), ignition, moving, kmh);
    }

    /** Al encender o apagar el carro se guarda un punto con la última posición conocida. */
    void onIgnition(boolean ignition) {
        if (!enabled || last == null || ignition == lastIgnition) return;
        save(last, System.currentTimeMillis(), ignition, false, 0);
    }

    private void save(Location l, long timeMs, boolean ignition, boolean moving, float kmh) {
        lastSavedAt = SystemClock.elapsedRealtime();
        lastIgnition = ignition;
        lastMoving = moving;
        if (l.hasBearing()) lastBearing = l.getBearing();
        try {
            ContentValues v = new ContentValues();
            v.put("rec", record(l, timeMs, ignition, moving, kmh));
            db.insert("q", null, v);
            pending++;
            if (pending > MAX_ROWS) {   // sin Internet por mucho tiempo: se pierden los más viejos
                db.execSQL("DELETE FROM q WHERE id IN (SELECT id FROM q ORDER BY id LIMIT 1000)");
                pending = count();
            }
        } catch (Throwable t) {
            status = "No se pudo guardar el punto: " + t.getMessage();
        }
    }

    /** Un registro AVL de Codec 8. */
    private static byte[] record(Location l, long timeMs, boolean ignition, boolean moving, float kmh) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(40);
        DataOutputStream o = new DataOutputStream(bo);
        int sats = 8;
        Bundle ex = l.getExtras();
        if (ex != null && ex.getInt("satellites", 0) > 0) sats = Math.min(ex.getInt("satellites"), 50);
        int speed = Math.max(0, Math.min(Math.round(kmh), 400));
        o.writeLong(timeMs);
        o.writeByte(0);                                          // prioridad
        o.writeInt((int) Math.round(l.getLongitude() * 1e7));
        o.writeInt((int) Math.round(l.getLatitude() * 1e7));
        o.writeShort(l.hasAltitude() ? (int) Math.max(-1000, Math.min(l.getAltitude(), 9000)) : 0);
        o.writeShort(l.hasBearing() ? ((int) l.getBearing()) % 360 : 0);
        o.writeByte(sats);
        o.writeShort(speed);
        o.writeByte(0);                                          // sin evento
        o.writeByte(3);                                          // total de datos extra
        o.writeByte(2);                                          // de 1 byte
        o.writeByte(239); o.writeByte(ignition ? 1 : 0);         // encendido
        o.writeByte(240); o.writeByte(moving ? 1 : 0);           // movimiento
        o.writeByte(1);                                          // de 2 bytes
        o.writeByte(24); o.writeShort(speed);                    // velocidad
        o.writeByte(0);                                          // de 4 bytes
        o.writeByte(0);                                          // de 8 bytes
        return bo.toByteArray();
    }

    private int count() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM q", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    // ------------------------------------------------------------------ envío

    private void sendLoop() {
        Socket sock = null;
        DataOutputStream out = null;
        DataInputStream in = null;
        boolean rejected = false;
                while (running) {
            try {
                if (!enabled) {
                    if (sock != null) { sock.close(); sock = null; }
                    Thread.sleep(3000);
                    continue;
                }
                List<byte[]> recs = new ArrayList<>();
                long lastId = -1;
                Cursor c = db.rawQuery("SELECT id, rec FROM q ORDER BY id LIMIT " + BATCH, null);
                try { while (c.moveToNext()) { lastId = c.getLong(0); recs.add(c.getBlob(1)); } } finally { c.close(); }
                if (recs.isEmpty()) {
                    pending = 0;
                    // La conexión queda abierta para recibir comandos de la plataforma (pedir una foto).
                    if (sock != null && in.available() >= 12) command(in, out);
                    Thread.sleep(2000);
                    continue;
                }
                if (sock == null) {
                    status = "Conectando…";
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(HOST, port), 15_000);
                    s.setSoTimeout(20_000);
                    sock = s;
                    out = new DataOutputStream(s.getOutputStream());
                    in = new DataInputStream(s.getInputStream());
                    out.writeShort(imei.length());
                    out.write(imei.getBytes("US-ASCII"));
                    out.flush();
                    int ack;
                    try { ack = in.readUnsignedByte(); } catch (java.net.SocketTimeoutException e) {
                        throw new java.io.IOException("conectó pero el servidor no contestó al ID");
                    } catch (java.io.EOFException e) {
                        throw new java.io.IOException("conectó pero el servidor cerró la conexión");
                    }
                    if (ack != 1) {
                        rejected = true;
                        throw new java.io.IOException("el servidor no aceptó el ID: falta registrarlo en la plataforma");
                    }
                    if (prefs.getInt(K_PORT, 0) != port) prefs.edit().putInt(K_PORT, port).apply();
                }
                while (in.available() >= 12) command(in, out);
                write(out, recs);
                int ok = in.readInt();
                if (ok != recs.size()) throw new java.io.IOException("el servidor confirmó " + ok + " de " + recs.size() + " puntos");
                db.delete("q", "id <= ?", new String[]{String.valueOf(lastId)});
                sent += ok;
                pending = count();
                status = "Conectado al puerto " + port;
            } catch (InterruptedException e) {
                break;
            } catch (Throwable t) {
                try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
                sock = null;
                String m = t instanceof java.net.UnknownHostException ? "sin Internet (no se encontró el servidor)"
                        : t instanceof java.net.ConnectException ? "el servidor rechazó la conexión"
                        : t instanceof java.net.SocketTimeoutException ? "el servidor no respondió a tiempo"
                        : t instanceof java.net.NoRouteToHostException ? "sin Internet"
                        : String.valueOf(t.getMessage());
                int failed = port;
                // Si el servidor contestó "ID no aceptado" ese es el puerto correcto; si no, se prueba el otro.
                if (!rejected && !(t instanceof java.net.UnknownHostException)) {
                    int i = 0;
                    for (int k = 0; k < PORTS.length; k++) if (PORTS[k] == port) i = k;
                    port = PORTS[(i + 1) % PORTS.length];
                }
                rejected = false;
                status = "Puerto " + failed + ": " + m + " · en 15 s prueba el " + port;
                try { Thread.sleep(15_000); } catch (InterruptedException e) { break; }
            }
        }
        try { if (sock != null) sock.close(); } catch (Throwable ignored) { }
    }

    /**
     * Comando de la plataforma (Codec 12). "camreq" pide una foto; a todo se le contesta con un texto.
     */
    private void command(DataInputStream in, DataOutputStream out) throws Exception {
        if (in.readInt() != 0) throw new java.io.IOException("comando mal formado");
        int size = in.readInt();
        if (size < 8 || size > 2048) throw new java.io.IOException("comando mal formado");
        byte[] b = new byte[size];
        in.readFully(b);
        in.readInt();   // CRC
        if ((b[0] & 0xFF) != 0x0C || b[2] != 0x05) return;
        int len = ((b[3] & 0xFF) << 24) | ((b[4] & 0xFF) << 16) | ((b[5] & 0xFF) << 8) | (b[6] & 0xFF);
        if (len < 0 || len > size - 8) return;
        String cmd = new String(b, 7, len, "US-ASCII").trim();
        String answer;
        if (cmd.toLowerCase(Locale.US).startsWith("camreq")) {
            Runnable r = onPhotoRequest;
            if (r != null) r.run();
            answer = "Photo request received, preparing to send";
        } else answer = "Command not supported: " + cmd;
        byte[] a = answer.getBytes("US-ASCII");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(body);
        o.writeByte(0x0C); o.writeByte(1); o.writeByte(0x06);
        o.writeInt(a.length); o.write(a); o.writeByte(1);
        frame(out, body.toByteArray());
    }

    /** Paquete Codec 8: preámbulo, largo, [codec, cantidad, registros, cantidad], CRC-16/IBM. */
    private static void write(OutputStream out, List<byte[]> recs) throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x08);
        body.write(recs.size());
        for (byte[] r : recs) body.write(r);
        body.write(recs.size());
        frame(out, body.toByteArray());
    }

    private static void frame(OutputStream out, byte[] b) throws Exception {
        int crc = 0;
        for (byte x : b) {
            crc ^= x & 0xFF;
            for (int i = 0; i < 8; i++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        ByteArrayOutputStream pk = new ByteArrayOutputStream(b.length + 12);
        DataOutputStream o = new DataOutputStream(pk);
        o.writeInt(0);
        o.writeInt(b.length);
        o.write(b);
        o.writeInt(crc);
        out.write(pk.toByteArray());
        out.flush();
    }
}
