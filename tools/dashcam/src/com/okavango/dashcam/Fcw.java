package com.okavango.dashcam;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Aviso de choque frontal (experimental). Busca carros, motos, buses, camiones, bicis y personas en
 * la vista de frente (mitad derecha de la imagen del 360) con un detector SSD MobileNet (COCO) y
 * estima el peligro por lo cerca que está (ancho en la imagen) y lo rápido que se acerca (tiempo
 * hasta el choque = cuánto tarda en crecer su tamaño). NO reemplaza la atención del conductor.
 */
final class Fcw {

    static final int SIZE = 300;
    /** Clases del modelo (0 = persona, 1 = bici, 2 = carro, 3 = moto, 5 = bus, 7 = camión). */
    private static final String[] NAMES = new String[91];
    static {
        NAMES[0] = "persona"; NAMES[1] = "bici"; NAMES[2] = "carro"; NAMES[3] = "moto"; NAMES[5] = "bus"; NAMES[7] = "camión";
    }

    static final class Result {
        int level;            // 0 nada, 1 atención, 2 peligro, 3 choque inminente
        String what = "";
        float width, ttc = -1, score;
        @Override public String toString() {
            if (what.isEmpty()) return "sin vehículos adelante";
            return String.format(Locale.US, "%s adelante · tamaño %.0f%%%s", what, width * 100,
                    ttc > 0 ? String.format(Locale.US, " · choque en %.1f s", ttc) : "");
        }
    }

    private final Interpreter tflite;
    private final ByteBuffer input = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder());
    private final int[] pixels = new int[SIZE * SIZE];
    private final float[][][] boxes = new float[1][10][4];
    private final float[][] classes = new float[1][10], scores = new float[1][10];
    private final float[] count = new float[1];
    /** (tiempo, ancho) del vehículo de adelante en los últimos ~1.5 s. */
    private final ArrayDeque<float[]> history = new ArrayDeque<>();
    private int lowerStreak;
    private int lastLevel;

    Fcw(Context c) throws Exception {
        InputStream in = c.getAssets().open("ssd_mobilenet_v1_quant.tflite");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        byte[] bytes = bo.toByteArray();
        ByteBuffer model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        model.put(bytes);
        model.rewind();
        tflite = new Interpreter(model, new Interpreter.Options().setNumThreads(4));
    }

    /**
     * frame: imagen de 300×300 ya recortada a la vista de frente.
     * sensitivity: 0.7 baja, 1.0 media, 1.4 alta.
     */
    Result analyze(Bitmap frame, float sensitivity, long nowMs) {
        frame.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
        input.rewind();
        for (int p : pixels) {
            input.put((byte) ((p >> 16) & 0xFF));
            input.put((byte) ((p >> 8) & 0xFF));
            input.put((byte) (p & 0xFF));
        }
        input.rewind();
        Map<Integer, Object> out = new HashMap<>();
        out.put(0, boxes);
        out.put(1, classes);
        out.put(2, scores);
        out.put(3, count);
        tflite.runForMultipleInputsOutputs(new Object[]{input}, out);

        // El "de adelante": el objeto relevante más grande cuyo centro cae en el carril (centro de la imagen).
        Result r = new Result();
        float best = 0;
        int found = Math.min(10, (int) count[0]);
        for (int i = 0; i < found; i++) {
            int cls = (int) classes[0][i];
            if (cls < 0 || cls >= NAMES.length || NAMES[cls] == null || scores[0][i] < 0.45f) continue;
            float ymin = boxes[0][i][0], xmin = boxes[0][i][1], ymax = boxes[0][i][2], xmax = boxes[0][i][3];
            float cx = (xmin + xmax) / 2;
            if (cx < 0.28f || cx > 0.72f) continue;      // fuera de mi carril
            if (ymax < 0.35f) continue;                   // muy arriba: lejos o no está en la vía
            float w = xmax - xmin;
            // Personas y bicis/motos son angostas: se comparan como si fueran más anchas.
            float eff = cls == 0 ? w * 2.5f : (cls == 1 || cls == 3) ? w * 1.8f : w;
            if (eff > best) {
                best = eff;
                r.what = NAMES[cls];
                r.width = Math.min(1f, eff);
                r.score = scores[0][i];
            }
        }

        // Tiempo hasta el choque por crecimiento del tamaño.
        if (best > 0) {
            history.addLast(new float[]{nowMs, best});
            while (history.size() > 1 && nowMs - history.peekFirst()[0] > 1500) history.removeFirst();
            float[] old = history.peekFirst();
            float dt = (nowMs - old[0]) / 1000f;
            if (dt >= 0.4f && old[1] > 0) {
                float scale = best / old[1];
                if (scale > 1.03f) r.ttc = dt / (scale - 1f);
            }
        } else {
            history.clear();
        }

        float k = sensitivity;
        float w = r.width, t = r.ttc;
        int level = 0;
        if (best > 0) {
            if ((t > 0 && t < 1.2f * k && w > 0.15f) || w > 0.55f / k) level = 3;
            else if ((t > 0 && t < 2.0f * k && w > 0.12f) || w > 0.42f / k) level = 2;
            else if ((t > 0 && t < 3.0f * k && w > 0.10f) || w > 0.30f / k) level = 1;
        }
        // Sube de inmediato; baja solo después de 2 lecturas más bajas (evita parpadeos).
        if (level >= lastLevel) { lastLevel = level; lowerStreak = 0; }
        else if (++lowerStreak >= 2) { lastLevel = level; lowerStreak = 0; }
        r.level = lastLevel;
        return r;
    }

    void reset() {
        history.clear();
        lastLevel = 0;
        lowerStreak = 0;
    }

    void close() {
        try { tflite.close(); } catch (Throwable ignored) { }
    }
}
