package com.okavango.camdiag;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.os.Environment;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import java.util.Collections;

/** Vista previa en vivo de una cámara (Camera1 o Camera2) para comprobar si da imagen. */
public class PreviewActivity extends Activity implements TextureView.SurfaceTextureListener {

    static final String EXTRA_API = "api";
    static final String EXTRA_ID = "id";

    private TextView status;
    private String api;
    private String id;

    private Camera camera1;
    private CameraDevice camera2;
    private CameraCaptureSession session;
    private MediaRecorder recorder;
    private File recordFile;
    private Button recordButton;
    private HandlerThread bg;
    private Handler bgHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        api = getIntent().getStringExtra(EXTRA_API);
        id = getIntent().getStringExtra(EXTRA_ID);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        TextureView tv = new TextureView(this);
        tv.setSurfaceTextureListener(this);
        root.addView(tv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        status = new TextView(this);
        status.setTextColor(Color.rgb(0xFF, 0xB5, 0x47));
        status.setBackgroundColor(0x99000000);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        status.setPadding(24, 16, 24, 16);
        status.setText(api + " · cámara " + id + " · abriendo…");
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START));
        if (!"camera2".equals(api)) {
            recordButton = new Button(this);
            recordButton.setText("Grabar prueba de 20 s");
            recordButton.setAllCaps(false);
            recordButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            recordButton.setOnClickListener(new android.view.View.OnClickListener() {
                public void onClick(android.view.View v) {
                    recordButton.setEnabled(false);
                    bgHandler.post(new Runnable() { public void run() { startRecording(); } });
                }
            });
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            // Arriba a la derecha: la barra de clima del carro tapa la parte de abajo de la pantalla.
            lp.rightMargin = 24;
            lp.topMargin = 110;
            root.addView(recordButton, lp);
        }
        setContentView(root);

        bg = new HandlerThread("cam");
        bg.start();
        bgHandler = new Handler(bg.getLooper());
    }

    private void setStatus(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { status.setText(api + " · cámara " + id + " · " + s); }
        });
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture st, int w, int h) {
        if ("camera2".equals(api)) {
            openCamera2(st);
        } else {
            bgHandler.post(new Runnable() {
                public void run() { openCamera1(st); }
            });
        }
    }

    private void openCamera1(SurfaceTexture st) {
        try {
            camera1 = Camera.open(Integer.parseInt(id));
            Camera.Parameters p = camera1.getParameters();
            Camera.Size s = p.getPreviewSize();
            camera1.setPreviewTexture(st);
            camera1.setErrorCallback(new Camera.ErrorCallback() {
                public void onError(int error, Camera c) { setStatus("error de cámara " + error); }
            });
            camera1.startPreview();
            setStatus("✔ vista previa activa " + s.width + "x" + s.height
                    + " (si la pantalla sigue negra, la cámara no envía imagen)");
        } catch (Throwable t) {
            setStatus("✘ " + t);
        }
    }

    /** Graba 20 s del video de la cámara (sin audio) en /sdcard/DCIM para comprobar que sirve de dashcam. */
    private void startRecording() {
        if (camera1 == null) {
            setStatus("✘ la cámara no está abierta");
            return;
        }
        int camId = Integer.parseInt(id);
        String how;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "DCIM");
            dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            recordFile = new File(dir, "prueba_camara" + id + "_" + stamp + ".mp4");
            Camera.Size s = camera1.getParameters().getPreviewSize();
            camera1.unlock();
            recorder = new MediaRecorder();
            recorder.setCamera(camera1);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            if (CamcorderProfile.hasProfile(camId, CamcorderProfile.QUALITY_HIGH)) {
                CamcorderProfile prof = CamcorderProfile.get(camId, CamcorderProfile.QUALITY_HIGH);
                recorder.setOutputFormat(prof.fileFormat);
                recorder.setVideoFrameRate(prof.videoFrameRate);
                recorder.setVideoSize(prof.videoFrameWidth, prof.videoFrameHeight);
                recorder.setVideoEncodingBitRate(prof.videoBitRate);
                recorder.setVideoEncoder(prof.videoCodec);
                how = "perfil " + prof.videoFrameWidth + "x" + prof.videoFrameHeight + " " + prof.videoFrameRate + " fps";
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setVideoFrameRate(25);
                recorder.setVideoSize(s.width, s.height);
                recorder.setVideoEncodingBitRate(4000000);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
                how = "manual " + s.width + "x" + s.height + " 25 fps";
            }
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
        } catch (Throwable t) {
            setStatus("✘ no se pudo grabar: " + t);
            stopRecording(false);
            return;
        }
        final String mode = how;
        for (int i = 20; i > 0; i--) {
            setStatus("● grabando (" + mode + ") · " + i + " s");
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            if (recorder == null) return;
        }
        stopRecording(true);
        setStatus("✔ video guardado: " + recordFile.getAbsolutePath() + " · " + (recordFile.length() / 1024) + " KB (" + mode + ")");
    }

    private void stopRecording(boolean ok) {
        try { if (recorder != null && ok) recorder.stop(); } catch (Throwable t) { setStatus("✘ al detener: " + t); }
        try { if (recorder != null) recorder.release(); } catch (Throwable ignored) { }
        recorder = null;
        try { if (camera1 != null) camera1.lock(); } catch (Throwable ignored) { }
        runOnUiThread(new Runnable() { public void run() { if (recordButton != null) recordButton.setEnabled(true); } });
    }

    private void openCamera2(final SurfaceTexture st) {
        try {
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    camera2 = d;
                    try {
                        st.setDefaultBufferSize(1280, 720);
                        final Surface surface = new Surface(st);
                        final CaptureRequest.Builder b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(surface);
                        d.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        try {
                                            s.setRepeatingRequest(b.build(), null, bgHandler);
                                            setStatus("✔ vista previa activa (si la pantalla sigue negra, no hay imagen)");
                                        } catch (Throwable t) {
                                            setStatus("✘ " + t);
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        setStatus("✘ no se pudo configurar la sesión");
                                    }
                                }, bgHandler);
                    } catch (Throwable t) {
                        setStatus("✘ " + t);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    setStatus("desconectada");
                    d.close();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    setStatus("✘ error " + error + (error == 1 ? " (en uso por otra app)" : error == 2 ? " (máximo de cámaras en uso)" : error == 3 ? " (deshabilitada por el sistema)" : ""));
                    d.close();
                }
            }, bgHandler);
        } catch (Throwable t) {
            setStatus("✘ " + t);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    @Override
    protected void onPause() {
        super.onPause();
        release();
        finish();
    }

    private void release() {
        try { if (recorder != null) { recorder.stop(); } } catch (Throwable ignored) { }
        try { if (recorder != null) { recorder.release(); } } catch (Throwable ignored) { }
        recorder = null;
        try { if (camera1 != null) { camera1.stopPreview(); camera1.release(); } } catch (Throwable ignored) { }
        camera1 = null;
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        session = null;
        try { if (camera2 != null) camera2.close(); } catch (Throwable ignored) { }
        camera2 = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        release();
        if (bg != null) bg.quitSafely();
    }
}
