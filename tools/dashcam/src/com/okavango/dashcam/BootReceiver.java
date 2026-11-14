package com.okavango.dashcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al encender la pantalla o el carro (POWER_ON del carro) arranca la dashcam y abre su pantalla. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (c.getSharedPreferences(RecorderService.PREFS, Context.MODE_PRIVATE).getBoolean(RecorderService.K_ARMED, true)) {
            RecorderService.start(c);
        }
        // POWER_ON del carro trae c3_power_flag: 2 = se encendió, 1 = se apagó (visto en el monitor).
        boolean powerOff = "android.intent.action.POWER_ON".equals(i.getAction())
                && i.getIntExtra("android.intent.extra.c3_power_flag", 2) != 2;
        if (!powerOff) RecorderService.openAppIfWanted(c);
    }
}
