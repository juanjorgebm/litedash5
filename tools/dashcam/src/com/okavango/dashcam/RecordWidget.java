package com.okavango.dashcam;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Widget de un botón: círculo rojo + GRABAR cuando está libre, cuadro + DETENER (fondo rojo) mientras graba. */
public class RecordWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RecorderService.start(c);
        RecorderService s = RecorderService.instance;
        render(c, s != null && s.recording, s == null ? "Dashcam" : s.widgetSubtitle());
    }

    static void render(Context c, boolean recording, String sub) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        // Iconos dibujados: la letra del sistema de esta pantalla no trae ● ni ■ (salía un cuadrito).
        v.setTextViewText(R.id.widget_label, recording ? "DETENER" : "GRABAR");
        v.setImageViewResource(R.id.widget_icon, recording ? R.drawable.ic_stop : R.drawable.ic_rec);
        v.setTextViewText(R.id.widget_sub, sub);
        v.setInt(R.id.widget_root, "setBackgroundResource", recording ? R.drawable.widget_rec : R.drawable.widget_idle);
        Intent i = new Intent(c, RecorderService.class).setAction(RecorderService.ACTION_TOGGLE);
        v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getService(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT));
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        m.updateAppWidget(new ComponentName(c, RecordWidget.class), v);
    }
}
