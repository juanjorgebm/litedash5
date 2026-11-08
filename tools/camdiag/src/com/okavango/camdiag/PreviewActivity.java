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
import android.widget.FrameLayout;
import android.widget.TextView;

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
