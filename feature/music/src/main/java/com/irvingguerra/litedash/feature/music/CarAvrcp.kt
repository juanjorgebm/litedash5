package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings

/**
 * Música del celular por Bluetooth en la pantalla del Okavango (chip NForetek, `com.nforetek.bt`): no hay sesión de
 * Android, pero su servicio `NfServiceAvrcp` (interfaz `INfCommandAvrcp`, vista en su APK) manda play, pausa, siguiente
 * y anterior al celular. La fuente activa de la pantalla está en `Settings.System`: `AllSource` (`audio_bt` = Bluetooth).
 */
object CarAvrcp {
    private const val PKG = "com.nforetek.bt"
    private const val ACTION = "com.nforetek.bt.service.NfServiceAvrcp"
    private const val DESCRIPTOR = "com.nforetek.bt.aidl.INfCommandAvrcp"

    const val PLAY = 11
    const val PAUSE = 13
    const val NEXT = 14
    const val PREVIOUS = 15

    private val main = Handler(Looper.getMainLooper())

    /** ¿La fuente de audio de la pantalla es el Bluetooth (música del celular)? */
    fun active(context: Context): Boolean = runCatching {
        Settings.System.getString(context.contentResolver, "AllSource").equals("audio_bt", ignoreCase = true)
    }.getOrDefault(false)

    /** Manda [code] (PLAY, PAUSE, NEXT o PREVIOUS) al celular; [onDone] dice si el servicio lo aceptó. */
    fun send(context: Context, code: Int, onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val ok = runCatching {
                    val data = Parcel.obtain(); val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DESCRIPTOR)
                        binder.transact(code, data, reply, 0)
                        reply.readException()
                        reply.readInt() != 0
                    } finally { data.recycle(); reply.recycle() }
                }.getOrDefault(false)
                runCatching { app.unbindService(this) }
                main.post { onDone(ok) }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching { app.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) { runCatching { app.unbindService(conn) }; onDone(false) }
    }
}
