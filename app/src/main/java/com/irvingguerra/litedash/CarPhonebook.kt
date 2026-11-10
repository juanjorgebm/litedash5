package com.irvingguerra.litedash

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * La agenda del celular conectado a la pantalla del Okavango. La pantalla no la guarda en ningún lado que otra app
 * pueda leer (ni contactos de Android ni proveedor): la app Teléfono de fábrica la baja del celular cada vez, por
 * PBAP, con el servicio Bluetooth `com.nforetek.bt`. Aquí se hace igual (interfaces sacadas de su APK):
 * `NfServiceHfp.getHfpConnectedAddress` (código 6) da el celular conectado y `NfServicePbap.reqPbapDownloadRange`
 * (código 8; memoria del teléfono = 2, propiedades = 431, hasta 2000) manda cada contacto a un callback
 * (`INfCallbackPbap.retPbapDownloadedContact`, código 3). Lo bajado se guarda para mostrarlo de una vez la próxima.
 */
object CarPhonebook {
    class Entry(val name: String, val number: String)

    private const val PKG = "com.nforetek.bt"
    private const val HFP_ACTION = "com.nforetek.bt.service.NfServiceHfp"
    private const val HFP = "com.nforetek.bt.aidl.INfCommandHfp"
    private const val PBAP_ACTION = "com.nforetek.bt.service.NfServicePbap"
    private const val PBAP = "com.nforetek.bt.aidl.INfCommandPbap"
    private const val CALLBACK = "com.nforetek.bt.aidl.INfCallbackPbap"
    private const val TX_HFP_ADDRESS = 6
    private const val TX_HFP_DIAL = 16
    private const val TX_REGISTER = 2
    private const val TX_UNREGISTER = 3
    private const val TX_IS_DOWNLOADING = 5
    private const val TX_DOWNLOAD_RANGE = 8
    private const val CB_CONTACT = 3
    private const val STORAGE_PHONE = 2
    private const val PROPERTIES = 431
    private const val MAX = 2000

    /** Sin contactos nuevos este rato = terminó; y el tope por si el celular no contesta. */
    private const val QUIET_MS = 4_000L
    private const val LIMIT_MS = 60_000L
    private const val FIRST_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())

    /** Sube al cambiar la agenda guardada (para repintar). */
    var version by mutableIntStateOf(0)
        private set
    var syncing by mutableStateOf(false)
        private set

    /** Qué pasó la última vez, para verlo en pantalla si no salen contactos. */
    var status by mutableStateOf("")
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("carphonebook", Context.MODE_PRIVATE)

    /** La agenda guardada de la última bajada. */
    fun cached(context: Context): List<Entry> = runCatching {
        val a = JSONArray(prefs(context).getString("entries", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Entry(it.optString("n"), it.optString("t")) } }
    }.getOrDefault(emptyList())

    fun age(context: Context): Long = System.currentTimeMillis() - prefs(context).getLong("at", 0L)

    private fun save(context: Context, entries: List<Entry>) {
        val a = JSONArray()
        for (e in entries) a.put(JSONObject().put("n", e.name).put("t", e.number))
        prefs(context).edit().putString("entries", a.toString()).putLong("at", System.currentTimeMillis()).apply()
    }

    private fun bind(app: Context, action: String, onBinder: (IBinder?, ServiceConnection) -> Unit) {
        var done = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!done) { done = true; onBinder(binder, this) }
            }
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        val bound = runCatching { app.bindService(Intent(action).setPackage(PKG), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) {
            runCatching { app.unbindService(conn) }
            done = true
            onBinder(null, conn)
        } else main.postDelayed({ if (!done) { done = true; runCatching { app.unbindService(conn) }; onBinder(null, conn) } }, 5_000)
    }

    private fun <T> transact(b: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(descriptor)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Baja la agenda del celular conectado (tarda unos segundos); no hace nada si ya está bajando. */
    fun sync(context: Context) {
        if (syncing) return
        val app = context.applicationContext
        syncing = true
        status = "Buscando el celular conectado…"
        bind(app, HFP_ACTION) { hfp, hfpConn ->
            if (hfp == null) return@bind finish(app, null, "La pantalla no tiene el servicio Bluetooth (NfServiceHfp)")
            Thread {
                val address = runCatching { transact(hfp, HFP, TX_HFP_ADDRESS, {}) { it.readString() } }
                runCatching { app.unbindService(hfpConn) }
                val mac = address.getOrNull()
                main.post {
                    when {
                        address.isFailure -> finish(app, null, "Bluetooth: ${address.exceptionOrNull()?.javaClass?.simpleName} ${address.exceptionOrNull()?.message.orEmpty().take(60)}")
                        mac.isNullOrBlank() || mac == "00:00:00:00:00:00" -> finish(app, null, "No hay celular conectado por Bluetooth")
                        else -> download(app, mac)
                    }
                }
            }.start()
        }
    }

    private fun download(app: Context, mac: String) {
        status = "Bajando contactos del celular…"
        bind(app, PBAP_ACTION) { pbap, conn ->
            if (pbap == null) return@bind finish(app, null, "La pantalla no tiene el servicio de agenda (NfServicePbap)")
            val found = ArrayList<Entry>()
            var last = 0L
            val callback = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                    if (code == CB_CONTACT) runCatching {
                        data.enforceInterface(CALLBACK)
                        if (data.readInt() != 0) {
                            data.readString() // dirección del celular
                            data.readInt() // de qué memoria
                            val first = data.readString().orEmpty()
                            val middle = data.readString().orEmpty()
                            val lastName = data.readString().orEmpty()
                            data.createIntArray()
                            val numbers = data.createStringArray().orEmpty()
                            val name = listOf(first, middle, lastName).filter { it.isNotBlank() }.joinToString(" ").trim()
                            synchronized(found) {
                                for (n in numbers) if (!n.isNullOrBlank()) found += Entry(name.ifBlank { n }, n)
                                last = System.currentTimeMillis()
                            }
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
            }
            callback.attachInterface(null, CALLBACK)
            Thread {
                val started = System.currentTimeMillis()
                val result = runCatching {
                    transact(pbap, PBAP, TX_REGISTER, { it.writeStrongBinder(callback) }) { it.readInt() }
                    val busy = transact(pbap, PBAP, TX_IS_DOWNLOADING, {}) { it.readInt() != 0 }
                    val accepted = busy || transact(pbap, PBAP, TX_DOWNLOAD_RANGE, {
                        it.writeString(mac); it.writeInt(STORAGE_PHONE); it.writeInt(PROPERTIES); it.writeInt(0); it.writeInt(MAX)
                    }) { it.readInt() != 0 }
                    if (!accepted) error("el servicio no aceptó la bajada")
                    while (true) {
                        Thread.sleep(500)
                        val now = System.currentTimeMillis()
                        val (count, lastAt) = synchronized(found) { found.size to last }
                        if (count > 0) main.post { status = "Bajando contactos del celular… $count" }
                        if (count > 0 && now - lastAt > QUIET_MS) break
                        if (count == 0 && now - started > FIRST_MS) break
                        if (now - started > LIMIT_MS) break
                    }
                }
                runCatching { transact(pbap, PBAP, TX_UNREGISTER, { it.writeStrongBinder(callback) }) { it.readInt() } }
                runCatching { app.unbindService(conn) }
                val entries = synchronized(found) { found.distinctBy { it.name + "|" + it.number.filter(Char::isDigit) } }
                main.post {
                    when {
                        result.isFailure -> finish(app, null, "Agenda: ${result.exceptionOrNull()?.javaClass?.simpleName} ${result.exceptionOrNull()?.message.orEmpty().take(70)}")
                        entries.isEmpty() -> finish(app, null, "El celular no mandó contactos: permite el acceso a contactos para la pantalla en el Bluetooth del celular")
                        else -> finish(app, entries, "${entries.size} contactos del celular")
                    }
                }
            }.start()
        }
    }

    /**
     * Marca [number] con el manos libres de la pantalla (`reqHfpDialCall`, código 16): la llamada sale por el celular
     * conectado y la pantalla de llamada de fábrica aparece sola. [onResult] dice si la pantalla aceptó marcar.
     */
    fun dial(context: Context, number: String, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val clean = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        bind(app, HFP_ACTION) { hfp, conn ->
            if (hfp == null || clean.isEmpty()) return@bind onResult(false)
            Thread {
                val ok = runCatching { transact(hfp, HFP, TX_HFP_DIAL, { it.writeString(clean) }) { it.readInt() != 0 } }.getOrDefault(false)
                runCatching { app.unbindService(conn) }
                main.post { onResult(ok) }
            }.start()
        }
    }

    private fun finish(app: Context, entries: List<Entry>?, message: String) {
        if (entries != null) save(app, entries)
        status = message
        syncing = false
        version++
    }
}
