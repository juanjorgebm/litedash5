package com.irvingguerra.litedash.feature.obd

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A qué adaptador conectarse. Se guarda para reconectar solo la próxima vez. */
sealed interface ObdTarget {
    val name: String
    data class Bluetooth(val address: String, override val name: String) : ObdTarget
    /** Bluetooth LE: no se empareja, se encuentra buscando ([ObdRepository.scanBle]). */
    data class Ble(val address: String, override val name: String) : ObdTarget
    data class Wifi(val host: String, val port: Int) : ObdTarget {
        override val name get() = "$host:$port"
    }
    /** Cable USB (la pantalla es el host); se reconoce por fabricante y producto. */
    data class Usb(val vendorId: Int, val productId: Int, override val name: String) : ObdTarget
}

enum class ObdState { OFF, CONNECTING, ON, ERROR }

/** Por qué una búsqueda de Bluetooth LE no pudo buscar (para decirlo claro). */
enum class ScanProblem { BLUETOOTH_OFF, LOCATION_OFF, FAILED }

/** Lo que dice el auto del motor: luz de falla, códigos y monitores; VIN y batería. */
data class EngineReport(
    val readiness: Readiness?,
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val vin: String?,
    val voltage: Float?,
)

/** Nombres comunes de adaptadores ELM327 (para ponerlos primero en la lista). */
private val OBD_NAMES = Regex("obd|elm|vlink|vgate|icar|obdlink|konnwei|v-link", RegexOption.IGNORE_CASE)

/**
 * El escáner OBD. No existe hasta que algo lo pide (el Taller, o el velocímetro si ya
 * hay un adaptador guardado) y solo pregunta al auto lo que se está mostrando ([VehicleBus.wanted]):
 * lo rápido (velocidad, rpm) unas 10 veces por segundo y lo lento cada 2 s.
 */
class ObdRepository(private val context: Context, private val bus: VehicleBus) {
    var state by mutableStateOf(ObdState.OFF)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var adapter by mutableStateOf<String?>(null)
        private set
    /** PIDs que contesta este auto (vacío hasta conectar). */
    var supported by mutableStateOf<Set<Int>>(emptySet())
        private set

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var link: ElmLink? = null
    private var elm: Elm327? = null
    private var job: Job? = null
    /** Avisa al instante si se desenchufa el cable USB mientras está conectado. */
    private var detachReceiver: BroadcastReceiver? = null

    // Se lee del disco una vez; como estado de Compose, el Taller se entera al cambiarlo.
    private var savedTarget by mutableStateOf(readTarget())

    var target: ObdTarget?
        get() = savedTarget
        private set(t) {
            savedTarget = t
            prefs.edit().apply {
                when (t) {
                    is ObdTarget.Bluetooth -> putString("type", "bt").putString("address", t.address).putString("name", t.name)
                    is ObdTarget.Ble -> putString("type", "ble").putString("address", t.address).putString("name", t.name)
                    is ObdTarget.Wifi -> putString("type", "wifi").putString("address", t.host).putInt("port", t.port)
                    is ObdTarget.Usb -> putString("type", "usb").putInt("vendor_id", t.vendorId).putInt("product_id", t.productId).putString("name", t.name)
                    null -> clear()
                }
            }.apply()
        }

    private fun readTarget(): ObdTarget? = when (prefs.getString("type", null)) {
        "bt" -> ObdTarget.Bluetooth(prefs.getString("address", "")!!, prefs.getString("name", "")!!)
        "ble" -> ObdTarget.Ble(prefs.getString("address", "")!!, prefs.getString("name", "")!!)
        "wifi" -> ObdTarget.Wifi(prefs.getString("address", "")!!, prefs.getInt("port", 35000))
        "usb" -> ObdTarget.Usb(prefs.getInt("vendor_id", 0), prefs.getInt("product_id", 0), prefs.getString("name", "")!!)
        else -> null
    }

    /** Reconexiones seguidas sin lograr leer (se reinicia al conectar bien). */
    private var reconnects = 0

    fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Adaptadores emparejados en Ajustes de Android; los que parecen OBD primero. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<ObdTarget.Bluetooth> {
        if (!hasPermission()) return emptyList()
        val adapter = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter ?: return emptyList()
        return runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            .map { ObdTarget.Bluetooth(it.address, it.name ?: it.address) }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    /** Adaptadores BLE que se encontraron en la última búsqueda (los que parecen OBD primero). */
    var found by mutableStateOf<List<ObdTarget.Ble>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    /** Ya se buscó al menos una vez (para decir "no se encontró ninguno"). */
    var searched by mutableStateOf(false)
        private set
    var scanProblem by mutableStateOf<ScanProblem?>(null)
        private set
    private var stopScan: (() -> Unit)? = null

    /**
     * Buscar requiere su propio permiso: "dispositivos cercanos" desde Android 12 (sin
     * ubicación) y, antes, la ubicación (así lo pide Android para Bluetooth LE).
     */
    fun scanPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION

    fun canScan() = ContextCompat.checkSelfPermission(context, scanPermission()) == PackageManager.PERMISSION_GRANTED

    /** Busca adaptadores Bluetooth LE unos segundos; solo cuando el usuario lo pide. */
    @SuppressLint("MissingPermission")
    fun scanBle() {
        if (scanning || !canScan()) return
        scanProblem = null
        val adapter = ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return run { scanProblem = ScanProblem.BLUETOOTH_OFF }
        // Antes de Android 12 la búsqueda no regresa nada con la ubicación apagada.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !locationOn()) return run { scanProblem = ScanProblem.LOCATION_OFF }
        val seen = linkedMapOf<String, ObdTarget.Ble>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                // Sin nombre no hay cómo reconocerlo en la lista.
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (seen.put(result.device.address, ObdTarget.Ble(result.device.address, name)) == null) {
                    found = seen.values.sortedByDescending { looksLikeObd(it.name) }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scanProblem = ScanProblem.FAILED
                stopScan?.invoke()
            }
        }
        found = emptyList()
        scanning = true
        val job = scope.launch(Dispatchers.Main) {
            runCatching { scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback) }
                .onFailure { scanProblem = ScanProblem.FAILED; stopScan?.invoke(); return@launch }
            delay(SCAN_MS)
            stopScan?.invoke()
        }
        stopScan = {
            runCatching { scanner.stopScan(callback) }
            job.cancel()
            scanning = false
            searched = true
            stopScan = null
        }
    }

    /** Cables USB conectados a la pantalla (cualquier puerto serie; el que parezca OBD primero). */
    fun usbDevices(): List<ObdTarget.Usb> {
        val manager = ContextCompat.getSystemService(context, UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values
            .map { ObdTarget.Usb(it.vendorId, it.productId, usbName(it)) }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    private fun usbName(d: UsbDevice): String =
        d.productName?.takeIf { it.isNotBlank() } ?: "USB %04X:%04X".format(d.vendorId, d.productId)

    private fun usbDevice(t: ObdTarget.Usb): UsbDevice? =
        ContextCompat.getSystemService(context, UsbManager::class.java)?.deviceList?.values
            ?.firstOrNull { it.vendorId == t.vendorId && it.productId == t.productId }

    /**
     * Conecta a un cable USB: Android pide permiso la primera vez (su propio diálogo); al
     * darlo, se conecta solo. Mientras, [state] queda en CONNECTING.
     */
    fun connectUsb(t: ObdTarget.Usb) {
        val manager = ContextCompat.getSystemService(context, UsbManager::class.java)
        val device = usbDevice(t)
        if (manager == null || device == null) {
            error = "No está conectado el cable USB"
            state = ObdState.ERROR
            return
        }
        if (manager.hasPermission(device)) return connect(t)
        if (state == ObdState.CONNECTING) return
        disconnect()
        state = ObdState.CONNECTING
        error = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                runCatching { context.unregisterReceiver(this) }
                val granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) {
                    state = ObdState.OFF
                    connect(t)
                } else if (state == ObdState.CONNECTING) {
                    error = "Sin permiso para el cable USB"
                    state = ObdState.ERROR
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /** Ubicación del sistema encendida (por proveedor: funciona desde Android 8). */
    private fun locationOn(): Boolean {
        val m = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return true
        return runCatching {
            m.isProviderEnabled(LocationManager.GPS_PROVIDER) || m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(true)
    }

    /** Se conecta a [t] (o al último que funcionó) y empieza a leer. */
    fun connect(t: ObdTarget? = target) {
        if (t == null || state == ObdState.CONNECTING || state == ObdState.ON && target == t) return
        // Android olvida el permiso del cable al desenchufarlo: si ya no lo hay, se vuelve a
        // pedir (con su diálogo) y al darlo [connectUsb] regresa aquí. Sin cable, sigue y
        // dice que no está conectado.
        if (t is ObdTarget.Usb) {
            val device = usbDevice(t)
            val manager = ContextCompat.getSystemService(context, UsbManager::class.java)
            if (device != null && manager != null && !manager.hasPermission(device)) return connectUsb(t)
        }
        // Buscar y conectar a la vez hace fallar la conexión BLE en varios Android.
        stopScan?.invoke()
        disconnect()
        state = ObdState.CONNECTING
        error = null
        job = scope.launch {
            // El enlace de este intento: se cierra aquí si algo falla (aunque todavía no sea
            // el actual), y los campos compartidos solo se tocan mientras este job siga vivo.
            var l: ElmLink? = null
            try {
                val (opened, e, version) = openAndInit(t)
                l = opened
                val pids = lock.withLock { e.supportedPids() }
                ensureActive()
                withContext(Dispatchers.Main) {
                    link = l
                    elm = e
                    supported = pids
                    adapter = version
                    target = t
                    // Conexión nueva: lo leído en otra ya no vale (el Taller y su pieza vuelven a leer).
                    lastReport = null
                    tiresCan = null
                    tiresCandidate = null
                    tiresMissed = false
                    state = ObdState.ON
                    reconnects = 0
                    if (t is ObdTarget.Usb) watchUsbDetach(t)
                }
                poll(e)
            } catch (x: Exception) {
                runCatching { l?.close() }
                if (isActive) withContext(Dispatchers.Main + NonCancellable) {
                    // Se cayó estando conectado (el adaptador se reinició, el auto se apagó un
                    // momento): se vuelve a intentar solo unas veces, sin que el usuario abra el
                    // Taller. Si nunca conectó, el error se queda a la vista.
                    val dropped = state == ObdState.ON
                    error = x.message ?: x.javaClass.simpleName
                    state = ObdState.ERROR
                    link = null
                    elm = null
                    unwatchUsbDetach()
                    bus.clear(Source.OBD)
                    if (dropped && reconnects < MAX_RECONNECTS) {
                        reconnects++
                        scope.launch(Dispatchers.Main) {
                            delay(RECONNECT_MS)
                            if (state == ObdState.ERROR && target == t) connect(t)
                        }
                    }
                }
            }
        }
    }

    /**
     * Mientras se lee por cable, si Android avisa que se desenchufó ese cable se corta ya
     * (sin esperar a que dejen de llegar respuestas) y sin reintentos: sin cable no hay a qué.
     */
    private fun watchUsbDetach(t: ObdTarget.Usb) {
        unwatchUsbDetach()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                if (d.vendorId != t.vendorId || d.productId != t.productId || target != t) return
                // Cancelar el job antes de cerrar: así su catch no programa reconexiones.
                job?.cancel()
                job = null
                closeLink()
                bus.clear(Source.OBD)
                error = "Se desconectó el cable USB"
                state = ObdState.ERROR
            }
        }
        // Es un aviso del sistema: tiene que poder llegar de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED)
        detachReceiver = receiver
    }

    private fun unwatchUsbDetach() {
        detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        detachReceiver = null
    }

    fun disconnect() {
        job?.cancel()
        job = null
        closeLink()
        state = ObdState.OFF
    }

    /** Olvida el adaptador guardado (para elegir otro). */
    fun forget() {
        disconnect()
        // Sin adaptador guardado (se borra su archivo con la receta de llantas): otro auto empieza de cero.
        target = null
        tireProfile = null
    }

    /** El último reporte del motor que se leyó (la pieza Taller y Salud lo muestran sin volver a leer). */
    var lastReport by mutableStateOf<EngineReport?>(null)
        private set

    /**
     * Lee todo lo del motor de una vez para el Taller. Si ya se está leyendo (el Taller y la pieza
     * Salud piden a la vez al conectar), espera esa misma lectura: el auto no contesta dos veces.
     */
    suspend fun report(): EngineReport? {
        val running = reading?.takeIf { it.isActive }
            ?: scope.async { readReport().also { if (it != null) lastReport = it } }.also { reading = it }
        return running.await()
    }
    private var reading: Deferred<EngineReport?>? = null

    private suspend fun readReport(): EngineReport? = withElm { e ->
        EngineReport(
            readiness = e.pid(0x01)?.let(::parseReadiness),
            stored = e.troubleCodes("03"),
            pending = e.troubleCodes("07"),
            permanent = runCatching { e.troubleCodes("0A") }.getOrDefault(emptyList()),
            vin = runCatching { e.vin() }.getOrNull(),
            voltage = runCatching { e.voltage() }.getOrNull(),
        )
    }

    /** La receta de llantas que dio presiones con este auto (se guarda: la próxima vez se pregunta directo). */
    private var tireProfile = prefs.getString(KEY_TIRE_PROFILE, null)
    /**
     * Lo que se sabe de las llantas en esta conexión (se olvida al conectar): si el protocolo es CAN
     * de 11 bits (las recetas son de ahí, y regresar al motor con "ATSH7DF" en otro dejaría de
     * leerlo), la receta que contestó aunque aún no haya presiones, y si ya se probaron todas sin suerte.
     */
    private var tiresCan: Boolean? = null
    private var tiresCandidate: TireProfile? = null
    private var tiresMissed = false

    /**
     * La presión de fábrica ([FactoryTires]): la receta de esta conexión o la guardada; si no, se
     * prueban todas (las de la marca del VIN primero) una sola vez por conexión, cada petición con el
     * candado por separado para no detener los medidores. Una receta se guarda hasta que da una
     * presión creíble (Ford y Mazda comparten módulo).
     */
    suspend fun factoryTires(): FactoryTires {
        val e = elm?.takeIf { state == ObdState.ON } ?: return FactoryTires.NotConnected
        val can = tiresCan ?: (withElm { it.command("ATDPN").firstOrNull()?.removePrefix("A") in CAN_11_BIT } ?: return FactoryTires.NotConnected)
            .also { if (elm === e) tiresCan = it }
        if (!can) return FactoryTires.Unsupported
        val known = tiresCandidate ?: TireProfiles.firstOrNull { it.id == tireProfile }
        if (known != null) readTires(e, known)?.let { return keep(known, it) }
        if (tiresMissed) return FactoryTires.Unsupported
        val vin = lastReport?.vin ?: withElm { runCatching { it.vin() }.getOrNull() }
        for (p in ordered(vin)) {
            if (elm !== e) return FactoryTires.NotConnected
            if (p === known) continue
            val found = readTires(e, p) ?: continue
            return keep(p, found)
        }
        // Si se cayó a media búsqueda no cuenta como "sin suerte": se vuelve a buscar al reconectar.
        if (elm !== e) return FactoryTires.NotConnected
        tiresMissed = true
        return FactoryTires.Unsupported
    }

    /** [p] contestó: se usa en esta conexión y se guarda en cuanto da una presión creíble. */
    private fun keep(p: TireProfile, v: FactoryTires.Values): FactoryTires.Values {
        tiresCandidate = p
        if (tireProfile != p.id && v.kpa.any { it != null && it in CREDIBLE_KPA }) {
            tireProfile = p.id
            prefs.edit().putString(KEY_TIRE_PROFILE, p.id).apply()
        }
        return v
    }

    /**
     * Una receta con el adaptador [e] (null si ya es otra conexión): es de este auto si su módulo
     * contestó que sí a todas sus peticiones (aunque los sensores aún no digan nada: parado y recién
     * encendido regresan 0 o FF).
     */
    private suspend fun readTires(e: Elm327, p: TireProfile): FactoryTires.Values? {
        val replies = withContext(Dispatchers.IO) {
            runCatching { lock.withLock { if (elm === e) e.module(p.setup, p.requests, RESTORE_ENGINE) else null } }.getOrNull()
        } ?: return null
        if (replies.size < p.requests.size || replies.any { it == null }) return null
        val v = runCatching { p.decode(replies.filterNotNull()) }.getOrNull() ?: return null
        return FactoryTires.Values(v.kpa, v.tempC, p.id)
    }

    /** Borra los códigos (el Taller pide confirmar dos veces antes). */
    suspend fun clearCodes(): Boolean = withElm { it.clearCodes() } ?: false

    private suspend fun <T> withElm(block: (Elm327) -> T): T? {
        val e = elm ?: return null
        return withContext(Dispatchers.IO) { runCatching { lock.withLock { block(e) } }.getOrNull() }
    }

    /**
     * ¿Se puede mostrar [signal] con este auto? Las calculadas, si da lo que necesitan;
     * el voltaje siempre (si no lo da el auto, lo mide el adaptador).
     */
    fun canRead(signal: String): Boolean {
        if (signal == Signal.VOLTAGE) return true
        DerivedSignals.firstOrNull { it.signal == signal }?.let { d ->
            return d.needs.all(::canRead) && (d.anyOf.isEmpty() || d.anyOf.any(::canRead))
        }
        return Pids.any { it.signal == signal && it.code in supported }
    }

    private suspend fun poll(e: Elm327) {
        var lastSlow = 0L
        // Consultas seguidas que no contestó: una suelta pasa (adaptador lento, el auto
        // ocupado); varias seguidas sí es que se perdió la conexión.
        var failures = 0
        fun <T> ask(read: () -> T?): T? = try {
            read().also { failures = 0 }
        } catch (x: IOException) {
            if (++failures >= MAX_FAILURES) throw x
            null
        }
        // Lo último que se leyó de cada señal (para calcular las que dependen de varias).
        val last = mutableMapOf<String, Float>()
        val smoothed = mutableMapOf<String, Float>()
        while (true) {
            val shown = bus.wanted
            val derived = DerivedSignals.filter { it.signal in shown }
            // De anyOf solo el primero que dé el auto: compute ignora los demás si está ese.
            val wanted = shown + derived.flatMap { d -> d.needs + listOfNotNull(d.anyOf.firstOrNull(::canRead)) + d.extras }
            val pids = Pids.filter { it.signal in wanted && it.code in supported }
            // Sin el PID 42 el voltaje lo mide el adaptador (ATRV), con lo lento.
            val adapterVoltage = Signal.VOLTAGE in wanted && VOLTAGE_PID !in supported
            if (pids.isEmpty() && !adapterVoltage) {
                delay(250)
                continue
            }
            val slow = System.currentTimeMillis() - lastSlow > 2_000
            if (slow) lastSlow = System.currentTimeMillis()
            // Se leen todos y se publican juntos: un solo salto al hilo principal por vuelta.
            val values = mutableListOf<Pair<String, Float?>>()
            for (p in pids) if (p.fast || slow) values += p.signal to lock.withLock { ask { e.pid(p.code) } }?.let(p.decode)
            if (adapterVoltage && slow) values += Signal.VOLTAGE to lock.withLock { ask { runCatching { e.voltage() }.getOrNull() } }
            for ((signal, value) in values) if (value == null) last.remove(signal) else last[signal] = value
            // Lo que dejó de mostrarse no suaviza con un valor viejo al volver.
            smoothed.keys.retainAll(derived.map { it.signal }.toSet())
            for (d in derived) {
                val raw = d.compute(last)
                val prev = smoothed[d.signal]
                val value = if (raw == null || prev == null) raw else prev + (raw - prev) * d.smoothing
                if (value == null) smoothed.remove(d.signal) else smoothed[d.signal] = value
                values += d.signal to value
            }
            withContext(Dispatchers.Main) { for ((signal, value) in values) bus.publish(signal, value, Source.OBD) }
            delay(60)
        }
    }

    /** Abre el enlace y prepara el adaptador. */
    @SuppressLint("MissingPermission") // device() revisa BLUETOOTH_CONNECT
    private suspend fun openAndInit(t: ObdTarget): Triple<ElmLink, Elm327, String> {
        val l = when (t) {
            is ObdTarget.Wifi -> WifiLink(t.host, t.port)
            is ObdTarget.Bluetooth -> BluetoothLink(device(t.address))
            is ObdTarget.Ble -> BleLink(context, device(t.address))
            is ObdTarget.Usb -> return openUsb(t)
        }
        val e = Elm327(l)
        val version = try { lock.withLock { e.init() } } catch (x: Exception) { runCatching { l.close() }; throw x }
        return Triple(l, e, version)
    }

    /**
     * Por USB no se sabe a qué velocidad habla el cable: se prueban las comunes
     * ([USB_BAUDS]) hasta que el ELM327 conteste.
     */
    private suspend fun openUsb(t: ObdTarget.Usb): Triple<ElmLink, Elm327, String> {
        val manager = ContextCompat.getSystemService(context, UsbManager::class.java) ?: throw IOException("Esta pantalla no tiene USB")
        val device = usbDevice(t) ?: throw IOException("No está conectado el cable USB")
        var last: Exception? = null
        for (baud in USB_BAUDS) {
            val l = try { UsbLink(manager, device, baud) } catch (x: IOException) { last = x; continue }
            val e = Elm327(l)
            try {
                return Triple(l, e, lock.withLock { e.init() })
            } catch (x: IOException) {
                last = x
                runCatching { l.close() }
            }
        }
        throw last ?: IOException("El cable USB no respondió")
    }

    private fun device(address: String): BluetoothDevice {
        if (!hasPermission()) throw SecurityException("Sin permiso de Bluetooth")
        return ContextCompat.getSystemService(context, BluetoothManager::class.java)!!.adapter.getRemoteDevice(address)
    }

    private fun closeLink() {
        unwatchUsbDetach()
        runCatching { link?.close() }
        link = null
        elm = null
        scope.launch(Dispatchers.Main) { bus.clear(Source.OBD) }
    }

    private fun looksLikeObd(name: String) = OBD_NAMES.containsMatchIn(name)

    companion object {
        /** Archivo de ajustes (la app lo escucha para ofrecer LiteDash al conectar el cable). */
        const val PREFS = "obd"
        private const val ACTION_USB_PERMISSION = "com.irvingguerra.litedash.USB_PERMISSION"
        /** Cuánto dura una búsqueda de adaptadores Bluetooth LE. */
        private const val SCAN_MS = 10_000L
        /** Voltaje del módulo de control (el de la batería con el motor andando). */
        private const val VOLTAGE_PID = 0x42
        /** Consultas seguidas sin respuesta que se toman como conexión perdida. */
        private const val MAX_FAILURES = 3
        /** Reintentos seguidos tras caerse ya conectado, y cuánto esperar entre ellos. */
        private const val MAX_RECONNECTS = 5
        private const val RECONNECT_MS = 8_000L
        private const val KEY_TIRE_PROFILE = "tire_profile"
        /** Una presión de llanta creíble (kPa): con eso se guarda la receta. */
        private val CREDIBLE_KPA = 100f..450f
        /** "ATDPN": CAN de 11 bits a 500 o 250 kbps. */
        private val CAN_11_BIT = setOf("6", "8")

        /** Hay un adaptador guardado (sin crear el escáner ni tocar Bluetooth). */
        fun hasSavedTarget(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("type")

        /** El adaptador guardado es un cable USB. */
        fun hasUsbTarget(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("type", null) == "usb"
    }
}
