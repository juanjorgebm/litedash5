package com.okavango.camdiag;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico de cámaras para la pantalla del Geely Okavango (Android 5).
 * Muestra qué cámaras ve Android (Camera1, Camera2, /dev/video*), qué apps de cámara/360/DVR
 * hay instaladas y deja probar la imagen de cada cámara. El informe se guarda en
 * /sdcard/diagnostico_camaras.txt para poder copiarlo por USB.
 */
public class MainActivity extends Activity {

    private static final String[] KEYWORDS = {
            "camera", "cam", "avm", "360", "dvr", "park", "rvc", "evs", "video", "record",
            "panor", "surround", "reverse", "rear", "backcar", "aroundview", "dashcam", "tachograph"
    };
    private static final String[] VENDORS = {
            "neusoft", "ecarx", "geely", "desay", "hsae", "autochips", "mediatek", "mtk", "allwinner",
            "flyaudio", "pateo", "byd", "yfve", "visteon", "harman", "joyson", "baidu", "qq", "tencent"
    };

    private TextView reportView;
    private LinearLayout buttonsRow;
    private LinearLayout appsRow;
    /** Apps de cámara de fábrica que se abren directamente (paquete, actividad, nombre). */
    private static final String[][] FACTORY_APPS = {
            {"com.neusoft.cardvr", "com.neusoft.cardvr.view.MainActivity", "Driving record"},
            {"com.neusoft.dvrlogin", "com.neusoft.dvrlogin.MainActivity", "DVR"},
            {"com.neusoft.avm", "com.neusoft.avm.MainActivity", "Vista 360°"},
    };
    private final List<String[]> exportedActivities = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private final List<String> camera1Ids = new ArrayList<>();
    private final List<String> camera2Ids = new ArrayList<>();
    private final List<String> launchablePackages = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x17, 0x1B, 0x21));
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Diagnóstico de cámaras · Okavango · v1.3");
        title.setTextColor(Color.rgb(0xFF, 0xB5, 0x47));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        buttonsRow = new LinearLayout(this);
        buttonsRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonsRow.setGravity(Gravity.CENTER_VERTICAL);
        hs.addView(buttonsRow);
        root.addView(hs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView hs2 = new HorizontalScrollView(this);
        appsRow = new LinearLayout(this);
        appsRow.setOrientation(LinearLayout.HORIZONTAL);
        appsRow.setGravity(Gravity.CENTER_VERTICAL);
        hs2.addView(appsRow);
        root.addView(hs2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildAppButtons();

        ScrollView sv = new ScrollView(this);
        reportView = new TextView(this);
        reportView.setTextColor(Color.WHITE);
        reportView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        reportView.setTextIsSelectable(true);
        reportView.setText("Analizando…");
        sv.addView(reportView);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        runDiagnostics();
    }

    private void runDiagnostics() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                report.setLength(0);
                camera1Ids.clear();
                camera2Ids.clear();
                launchablePackages.clear();
                exportedActivities.clear();
                safe("Equipo", new Section() { public void run() throws Exception { deviceInfo(); } });
                safe("Camera1 (API clásica)", new Section() { public void run() throws Exception { camera1(); } });
                safe("Camera2", new Section() { public void run() throws Exception { camera2(); } });
                safe("Video4Linux (/dev/video*)", new Section() { public void run() throws Exception { v4l2(); } });
                safe("Funciones del sistema", new Section() { public void run() throws Exception { features(); } });
                safe("Apps de cámara / 360 / DVR", new Section() { public void run() throws Exception { cameraApps(); } });
                safe("Apps que graban video", new Section() { public void run() throws Exception { videoCapture(); } });
                safe("Servicios en ejecución", new Section() { public void run() throws Exception { services(); } });
                safe("Propiedades del sistema", new Section() { public void run() throws Exception { props(); } });
                safe("Apps del fabricante", new Section() { public void run() throws Exception { vendorApps(); } });
                final String saved = saveReport();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        reportView.setText((saved != null ? "Informe guardado en " + saved + "\n\n" : "")
                                + report.toString());
                        buildButtons();
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- secciones

    private void deviceInfo() {
        line("Fabricante: " + Build.MANUFACTURER + " · Marca: " + Build.BRAND);
        line("Modelo: " + Build.MODEL + " · Equipo: " + Build.DEVICE + " · Producto: " + Build.PRODUCT);
        line("Placa: " + Build.BOARD + " · Hardware: " + Build.HARDWARE);
        line("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        line("Compilación: " + Build.DISPLAY);
        line("Huella: " + Build.FINGERPRINT);
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        Point p = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(p);
        line("Pantalla: " + p.x + " x " + p.y + " px · densidad " + dm.densityDpi + " dpi (x" + dm.density + ")");
    }

    private void camera1() {
        int n = Camera.getNumberOfCameras();
        line("Número de cámaras: " + n);
        for (int i = 0; i < n; i++) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            try {
                Camera.getCameraInfo(i, info);
                line("Cámara " + i + ": " + (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? "frontal" : "trasera")
                        + " · orientación " + info.orientation + "°");
            } catch (Throwable t) {
                line("Cámara " + i + ": no se pudo leer info (" + t + ")");
            }
            Camera c = null;
            try {
                c = Camera.open(i);
                Camera.Parameters prm = c.getParameters();
                StringBuilder sizes = new StringBuilder();
                for (Camera.Size s : prm.getSupportedPreviewSizes()) {
                    if (sizes.length() > 0) sizes.append(", ");
                    sizes.append(s.width).append("x").append(s.height);
                }
                line("   ✔ Se pudo ABRIR. Tamaños de vista previa: " + sizes);
                List<Integer> fps = prm.getSupportedPreviewFrameRates();
                if (fps != null) line("   FPS: " + fps);
                List<Camera.Size> vs = prm.getSupportedVideoSizes();
                if (vs != null && !vs.isEmpty()) line("   Tamaños de video: " + vs.size() + " opciones");
            } catch (Throwable t) {
                line("   ✘ No se pudo abrir: " + t);
            } finally {
                if (c != null) try { c.release(); } catch (Throwable ignored) { }
            }
            camera1Ids.add(String.valueOf(i));
        }
    }

    private void camera2() throws Exception {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] ids = cm.getCameraIdList();
        line("IDs: " + Arrays.toString(ids));
        for (String id : ids) {
            camera2Ids.add(id);
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            Integer level = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            String lv = level == null ? "?" : level == 2 ? "LEGACY" : level == 0 ? "LIMITED" : level == 1 ? "FULL" : String.valueOf(level);
            String f = facing == null ? "?" : facing == 0 ? "frontal" : facing == 1 ? "trasera" : "externa";
            line("ID " + id + ": " + f + " · nivel " + lv);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] s = map.getOutputSizes(SurfaceTexture.class);
                if (s != null) line("   Tamaños: " + Arrays.toString(s));
            }
        }
    }

    private void v4l2() {
        File dev = new File("/dev");
        String[] names = dev.list();
        boolean any = false;
        if (names != null) {
            Arrays.sort(names);
            for (String n : names) {
                if (n.startsWith("video") || n.startsWith("v4l") || n.contains("cam") || n.contains("avm")
                        || n.contains("isp") || n.contains("tvd") || n.contains("cvbs")) {
                    File f = new File(dev, n);
                    line("/dev/" + n + " · leer " + yes(f.canRead()) + " · escribir " + yes(f.canWrite()));
                    any = true;
                }
            }
        } else {
            line("No se puede listar /dev");
        }
        File sys = new File("/sys/class/video4linux");
        String[] v = sys.list();
        if (v != null) {
            for (String n : v) {
                line("v4l " + n + " → " + readFirstLine(new File(sys, n + "/name")));
                any = true;
            }
        }
        if (!any) line("No se encontraron dispositivos de video");
    }

    private void features() {
        PackageManager pm = getPackageManager();
        for (FeatureInfo fi : pm.getSystemAvailableFeatures()) {
            if (fi.name != null && (fi.name.contains("camera") || fi.name.contains("automotive"))) {
                line(fi.name);
            }
        }
    }

    private void cameraApps() {
        PackageManager pm = getPackageManager();
        List<PackageInfo> pkgs = pm.getInstalledPackages(PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES);
        Collections.sort(pkgs, (a, b) -> a.packageName.compareTo(b.packageName));
        int count = 0;
        for (PackageInfo p : pkgs) {
            String label = String.valueOf(p.applicationInfo.loadLabel(pm));
            if (!matches(p.packageName.toLowerCase(Locale.ROOT) + " " + label.toLowerCase(Locale.ROOT), KEYWORDS)) continue;
            count++;
            boolean sys = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            line("• " + label + "  [" + p.packageName + "] " + (sys ? "sistema" : "usuario") + " v" + p.versionName);
            if (pm.getLaunchIntentForPackage(p.packageName) != null) launchablePackages.add(p.packageName);
            if (p.activities != null) {
                for (ActivityInfo a : p.activities) {
                    line("     actividad: " + a.name + (a.exported ? " (exportada)" : ""));
                    if (a.exported && !p.packageName.equals(getPackageName())) {
                        exportedActivities.add(new String[]{p.packageName, a.name, label});
                    }
                }
            }
            if (p.services != null) {
                for (ServiceInfo s : p.services) {
                    line("     servicio: " + s.name + (s.exported ? " (exportado)" : ""));
                }
            }
        }
        if (count == 0) line("Ninguna encontrada");
    }

    private void videoCapture() {
        PackageManager pm = getPackageManager();
        List<ResolveInfo> l = pm.queryIntentActivities(new Intent(MediaStore.ACTION_VIDEO_CAPTURE), 0);
        if (l.isEmpty()) line("Ninguna");
        for (ResolveInfo r : l) line(r.activityInfo.packageName + "/" + r.activityInfo.name);
    }

    @SuppressWarnings("deprecation")
    private void services() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> l = am.getRunningServices(500);
        int count = 0;
        for (ActivityManager.RunningServiceInfo s : l) {
            String n = s.service.flattenToShortString();
            if (matches(n.toLowerCase(Locale.ROOT), KEYWORDS)) {
                line(n);
                count++;
            }
        }
        if (count == 0) line("Ninguno relacionado con cámaras");
    }

    private void props() throws Exception {
        Process p = Runtime.getRuntime().exec("getprop");
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String s;
        int count = 0;
        while ((s = r.readLine()) != null) {
            String low = s.toLowerCase(Locale.ROOT);
            int colon = low.indexOf("]:");
            String key = colon > 0 ? low.substring(0, colon) : low;
            if (matches(key, KEYWORDS) || key.contains("ro.product") || key.contains("ro.board")
                    || key.contains("ro.hardware")) {
                line(s);
                count++;
            }
        }
        r.close();
        if (count == 0) line("Ninguna");
    }

    private void vendorApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Collections.sort(apps, (a, b) -> a.packageName.compareTo(b.packageName));
        for (ApplicationInfo a : apps) {
            if (matches(a.packageName.toLowerCase(Locale.ROOT), VENDORS)) {
                line(a.packageName + "  (" + a.loadLabel(pm) + ")");
            }
        }
    }

    // ---------------------------------------------------------------- botones

    private void buildButtons() {
        buttonsRow.removeAllViews();
        addButton("Repetir", new View.OnClickListener() {
            public void onClick(View v) { reportView.setText("Analizando…"); runDiagnostics(); }
        });
        for (final String id : camera1Ids) {
            addButton("Ver cámara " + id, new View.OnClickListener() {
                public void onClick(View v) { openPreview("camera1", id, null); }
            });
            addButton("Cámara " + id + " HD 1280x720", new View.OnClickListener() {
                public void onClick(View v) { openPreview("camera1", id, "1280x720"); }
            });
            addButton("Cámara " + id + " 1920x720", new View.OnClickListener() {
                public void onClick(View v) { openPreview("camera1", id, "1920x720"); }
            });
        }
        for (final String id : camera2Ids) {
            addButton("Camera2 " + id, new View.OnClickListener() {
                public void onClick(View v) { openPreview("camera2", id, null); }
            });
        }
        buildAppButtons();
    }

    /** Segunda fila: abrir las apps de cámara de fábrica por su actividad exacta. */
    private void buildAppButtons() {
        appsRow.removeAllViews();
        List<String> seen = new ArrayList<>();
        for (String[] a : FACTORY_APPS) {
            addAppButton(a[0], a[1], "Abrir " + a[2]);
            seen.add(a[0] + "/" + a[1]);
        }
        for (String[] a : exportedActivities) {
            if (seen.contains(a[0] + "/" + a[1])) continue;
            seen.add(a[0] + "/" + a[1]);
            String shortName = a[1].substring(a[1].lastIndexOf('.') + 1);
            addAppButton(a[0], a[1], "Abrir " + a[2] + " · " + shortName);
        }
    }

    private void addAppButton(final String pkg, final String activity, String text) {
        Button b = makeButton(text, new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.setClassName(pkg, activity);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "No se pudo abrir " + pkg + ": " + t, Toast.LENGTH_LONG).show();
                }
            }
        });
        appsRow.addView(b, b.getLayoutParams());
    }

    private void openPreview(String api, String id, String size) {
        Intent i = new Intent(this, PreviewActivity.class);
        if (size != null) i.putExtra(PreviewActivity.EXTRA_SIZE, size);
        i.putExtra(PreviewActivity.EXTRA_API, api);
        i.putExtra(PreviewActivity.EXTRA_ID, id);
        startActivity(i);
    }

    private void addButton(String text, View.OnClickListener l) {
        Button b = makeButton(text, l);
        buttonsRow.addView(b, b.getLayoutParams());
    }

    private Button makeButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(64));
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    // ---------------------------------------------------------------- utilidades

    private interface Section { void run() throws Exception; }

    private void safe(String name, Section s) {
        report.append("\n=== ").append(name).append(" ===\n");
        try {
            s.run();
        } catch (Throwable t) {
            line("ERROR: " + t);
        }
    }

    private void line(String s) {
        report.append(s).append('\n');
    }

    private String saveReport() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        String body = "Diagnóstico de cámaras · " + stamp + "\n" + report;
        File[] targets = {
                new File(Environment.getExternalStorageDirectory(), "diagnostico_camaras.txt"),
                new File(getExternalFilesDir(null), "diagnostico_camaras.txt")
        };
        for (File f : targets) {
            try {
                FileOutputStream out = new FileOutputStream(f);
                out.write(body.getBytes("UTF-8"));
                out.close();
                return f.getAbsolutePath();
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean matches(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static String yes(boolean b) { return b ? "sí" : "no"; }

    private static String readFirstLine(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String s = r.readLine();
            r.close();
            return s;
        } catch (Throwable t) {
            return "?";
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
