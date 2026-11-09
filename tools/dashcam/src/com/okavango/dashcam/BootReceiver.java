package com.okavango.dashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al encender la pantalla arranca la dashcam si estaba activada. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences(RecorderService.PREFS, Context.MODE_PRIVATE).getBoolean(RecorderService.K_ARMED, true)) {
            RecorderService.start(c);
        }
    }
}
