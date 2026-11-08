package com.irvingguerra.litedash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings

/**
 * Mantiene activo el Bluetooth de la pantalla del Okavango (Neusoft, chip Goodocom con el servicio `com.nforetek.bt`).
 * Se habla directo con su servicio `NfServiceBluetooth` (interfaz `INfCommandBluetooth`, sacada de su APK):
 * setBtEnable (código 5) y setBtAutoConnect (27), que hace que la pantalla busque sola el último celular.
 */
object CarBluetoothKeeper {
    private const val PKG = "com.nforetek.bt"
    private const val ACTION = "com.nforetek.bt.service.NfServiceBluetooth"
    private const val DESCRIPTOR = "com.nforetek.bt.aidl.INfCommandBluetooth"
    private const val TX_IS_ENABLED = 16
    private const val TX_SET_ENABLE = 5
    private const val TX_SET_AUTOCONNECT = 27
    private val KEYS = listOf("com.neusoft.geely.bluetooth.state", "BTPhone", "BTAudio", "ACC_OFF_ON_STATE")

    private val main = Handler(Looper.getMainLooper())

    /** Enciende el Bluetooth del carro y le pide reconectar solo; false si la pantalla no tiene ese servicio. */
    fun ensure(context: Context, onResult: (String) -> Unit = {}) {
        val app = context.applicationContext
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val msg = runCatching {
                    val enabled = call(binder, TX_IS_ENABLED) { }
                    if (!enabled) call(binder, TX_SET_ENABLE) { it.writeInt(1) }
                    send(binder, TX_SET_AUTOCONNECT) { it.writeInt(1); it.writeInt(5) }
                    if (enabled) "ok-ya-encendido" else "ok-encendido"
                }.getOrElse { "error: ${it.javaClass.simpleName} ${it.message}" }
                runCatching { app.unbindService(this) }
                main.post { onResult(msg) }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching {
            app.bindService(Intent(ACTION).setPackage(PKG), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) { runCatching { app.unbindService(conn) }; onResult("sin-servicio") }
    }

    private fun call(b: IBinder, code: Int, write: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR); write(data)
            b.transact(code, data, reply, 0); reply.readException(); reply.readInt() != 0
        } finally { data.recycle(); reply.recycle() }
    }

    private fun send(b: IBinder, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try { data.writeInterfaceToken(DESCRIPTOR); write(data); b.transact(code, data, reply, 0); reply.readException() }
        finally { data.recycle(); reply.recycle() }
    }

    /** Vigila los ajustes de la pantalla (encendido del carro, estado del Bluetooth) y llama [action] sin repetirse de seguido. */
    fun watch(context: Context, action: () -> Unit): () -> Unit {
        val resolver = context.contentResolver
        var last = 0L
        val fire = Runnable { last = System.currentTimeMillis(); action() }
        val observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(fire)
                // Espera unos segundos a que la pantalla termine de arrancar y no repite antes de 20 s.
                main.postDelayed(fire, if (System.currentTimeMillis() - last < 20_000) 20_000 else 4_000)
            }
        }
        val ok = runCatching { KEYS.forEach { resolver.registerContentObserver(Settings.System.getUriFor(it), false, observer) } }.isSuccess
        main.postDelayed(fire, 3_000)
        return { main.removeCallbacks(fire); if (ok) runCatching { resolver.unregisterContentObserver(observer) } }
    }
}
