package com.irvingguerra.litedash.feature.tpms

import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import com.irvingguerra.litedash.feature.obd.UsbLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.irvingguerra.litedash.feature.obd.FactoryTires
import java.io.File
import java.io.IOException

/** De dónde salen las llantas. Se guarda ("usb", "ble", "obd", "demo"). */
enum class TpmsSource(val key: String) {
    USB("usb"), BLE("ble"), OBD("obd"), DEMO("demo");

    companion object {
        fun fromKey(key: String?): TpmsSource? = entries.firstOrNull { it.key == key }
    }
}

enum class TpmsStatus {
    /** Sin fuente o sin nadie que lo necesite. */
    OFF,
    /** Esperando el receptor (permiso de USB) o el primer sensor Bluetooth. */
    SEARCHING,
    NO_DEVICE,
    NO_PERMISSION,
    CONNECTED,
}

/** Lo que salió mal (la app lo dice en su idioma). */
enum class TpmsError { USB_MISSING, USB_DENIED, USB_OPEN_FAILED, USB_UNPLUGGED, USB_LOST, SWAP_UNCONFIRMED, OBD_OFF, OBD_UNSUPPORTED }

/**
 * La presión que ya sabe la computadora del auto (sus sensores de fábrica), por el adaptador OBD
 * del Taller. La pone la app: así Llantas no carga el escáner si no se usa esta fuente.
 */
fun interface CarTires {
    /** Las cuatro llantas en el orden de [Wheel.ROAD]. */
    suspend fun read(): FactoryTires
}

/** Emparejar un sensor con el receptor USB. */
enum class PairState { IDLE, WAITING, DONE, TIMEOUT }

/** Mientras exista (sin cerrar), las llantas se leen aunque nada las muestre. */
interface TpmsHold : AutoCloseable

/**
 * Presión de llantas: receptor USB de 433 MHz, tapones Bluetooth LE o demostración. No
 * existe hasta que algo lo pide, y solo lee (cable abierto o búsqueda Bluetooth) mientras una
 * pieza muestra una llanta ([VehicleBus.wanted]), las alertas están prendidas o alguien tiene
 * un [keepAwake] (la pantalla de ajustes). Todo el estado vive en el hilo principal.
 */
class TpmsRepository(context: Context, private val bus: VehicleBus) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ---- Estado ----

    var status by mutableStateOf(TpmsStatus.OFF)
        private set
    /** El nombre del receptor USB conectado (como lo da el aparato); null con otras fuentes. */
    var detail by mutableStateOf<String?>(null)
        private set
    /** Lo que salió mal con el receptor USB. */
    var error by mutableStateOf<TpmsError?>(null)
        private set

    /** Por qué no se escucha por Bluetooth. */
    var bleIssue by mutableStateOf<BleProblem?>(null)
        private set

    /**
     * Lo último de cada sensor que se oye (asignado o no), con el ajuste de calibración. No es
     * estado de Compose: los sensores de otros autos no deben rearmar la pantalla; lo que se ve
     * sale de [wheelReading], [sensorCount] y [nearby].
     */
    private val _readings = HashMap<String, TireReading>()

    /** Cuántos sensores se oyen ahora. */
    var sensorCount by mutableIntStateOf(0)
        private set

    /** Los sensores que se oyen, para acomodarlos (solo con un [keepAwake]; si no, vacía). */
    var nearby by mutableStateOf<List<TireReading>>(emptyList())
        private set

    private val _assignment = mutableStateMapOf<Wheel, String>()
    /** Qué sensor va en cada llanta. */
    val assignment: Map<Wheel, String> get() = _assignment

    /** Una por llanta: cada vista se rearma solo cuando cambia la suya. */
    private val wheelStates = Wheel.entries.associateWith { mutableStateOf<TireReading?>(null) }

    fun wheelReading(w: Wheel): TireReading? = wheelStates.getValue(w).value

    /** Pasa lo último de cada sensor asignado a su llanta (igual = sin aviso a Compose). */
    private fun syncWheels() {
        for ((w, state) in wheelStates) state.value = _assignment[w]?.let { _readings[it] }
    }

    private fun forget(id: String) {
        _readings.remove(id)
        lastUpdate.remove(id)
    }

    private fun clearReadings() {
        _readings.clear()
        lastUpdate.clear()
        sensorCount = 0
        syncWheels()
    }

    /** Alertas de ahora (la más grave primero); vacía si no se está leyendo. */
    var alerts by mutableStateOf<List<TireAlert>>(emptyList())
        private set

    // ---- Ajustes ----

    private var savedSource by mutableStateOf(TpmsSource.fromKey(prefs.getString(KEY_SOURCE, null)))
    var source: TpmsSource?
        get() = savedSource
        set(s) {
            if (s == savedSource) return
            val before = savedSource
            savedSource = s
            prefs.edit().apply {
                if (s == null) remove(KEY_SOURCE) else putString(KEY_SOURCE, s.key)
                // La que se usó al último, para prender el módulo otra vez con la misma.
                (s ?: before)?.let { putString(KEY_LAST_SOURCE, it.key) }
            }.apply()
        }

    /** La última fuente que se eligió (aunque hoy esté apagado); null si nunca hubo. */
    val lastSource: TpmsSource? get() = TpmsSource.fromKey(prefs.getString(KEY_LAST_SOURCE, null))

    var recommendedFrontKpa by floatPref(KEY_FRONT, 230f)
    var recommendedRearKpa by floatPref(KEY_REAR, 230f)
    /** Avisa abajo de (100 − esto) % de la recomendada. */
    var lowPercent by intPref(KEY_LOW_PCT, 20)
    var highTempC by floatPref(KEY_HIGH_TEMP, 75f)
    /** Se suma a la presión que llega (el receptor USB puede venir corrido); el historial queda sin ella. */
    var offsetKpa by floatPref(KEY_OFFSET, 0f)
    var showSpare by boolPref(KEY_SHOW_SPARE, false)
    /** Alertas prendidas: entonces se lee siempre, aunque nada muestre las llantas. */
    var alertsEnabled by boolPref(KEY_ALERTS, true)

    private var savedUnit by mutableStateOf(PressureUnit.fromKey(prefs.getString(KEY_UNIT, null)) ?: PressureUnit.PSI)
    var unit: PressureUnit
        get() = savedUnit
        set(u) {
            savedUnit = u
            prefs.edit().putString(KEY_UNIT, u.key).apply()
        }

    val limits: TireLimits get() = TireLimits(recommendedFrontKpa, recommendedRearKpa, lowPercent, highTempC)

    // ---- Quién lo necesita ----

    private var holds by mutableIntStateOf(0)

    /** Lee mientras no se cierre (la pantalla de ajustes); también pone Bluetooth en modo rápido. */
    fun keepAwake(): TpmsHold {
        holds++
        return object : TpmsHold {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                holds--
            }
        }
    }

    private data class Want(val source: TpmsSource?, val foreground: Boolean)

    private var running: TpmsSource? = null
    private var foreground = false

    private fun applyWant(w: Want) {
        if (w.source != running) {
            stopSource()
            running = w.source
            foreground = w.foreground
            w.source?.let(::startSource)
        } else if (w.foreground != foreground) {
            foreground = w.foreground
            if (running == TpmsSource.BLE) startBle()
        }
    }

    /** Vuelve a intentar (tras dar un permiso, prender el Bluetooth o conectar el receptor). */
    fun retry() {
        val s = running ?: return
        stopSource()
        running = s
        startSource(s)
    }

    private fun startSource(s: TpmsSource) {
        error = null; bleIssue = null
        detail = null
        status = TpmsStatus.SEARCHING
        loadAssignment(s)
        updateSlowLeaks()
        when (s) {
            TpmsSource.USB -> startUsb()
            TpmsSource.BLE -> { watchBluetooth(); startBle() }
            TpmsSource.OBD -> startObd()
            TpmsSource.DEMO -> startDemo()
        }
        tickJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                pruneStale()
                refresh()
            }
        }
    }

    private fun stopSource() {
        tickJob?.cancel(); tickJob = null
        demoJob?.cancel(); demoJob = null
        obdJob?.cancel(); obdJob = null
        obdProfile = null
        stopUsb()
        ble?.stop()
        unwatchBluetooth()
        cancelIdentify()
        if (running != TpmsSource.BLE) _assignment.clear()
        clearReadings()
        alerts = emptyList()
        nearby = emptyList()
        ble?.unknown?.clear()
        for (signal in TireSignals.ALL) bus.publish(signal, null, TPMS_SOURCE)
        saveHistory(force = true)
        slowLeaks.clear()
        demoHistory = TireHistory()
        running = null
        status = TpmsStatus.OFF
        detail = null
    }

    private var tickJob: Job? = null

    // ---- Lecturas ----

    private val lastUpdate = mutableMapOf<String, Long>()

    /** Entra una lectura de cualquier fuente (hilo principal). */
    private fun ingest(raw: TireReading) {
        // Bluetooth anuncia muchas veces por segundo: con una por segundo por sensor basta.
        val prev = lastUpdate[raw.sensorId]
        if (prev != null && raw.atMillis - prev < MIN_UPDATE_MS && raw.family != DemoTpms.FAMILY) return
        lastUpdate[raw.sensorId] = raw.atMillis
        val r = if (running == TpmsSource.DEMO || offsetKpa == 0f) raw else raw.copy(pressureKpa = raw.pressureKpa?.let(::calibrated))
        if (_readings.put(r.sensorId, r) == null) sensorCount = _readings.size
        // Sysgration dice su llanta: se acomoda sola si esa llanta está libre.
        if (running == TpmsSource.BLE && r.reportedWheel != null && r.reportedWheel !in _assignment && r.sensorId !in _assignment.values) {
            assign(r.reportedWheel, r.sensorId)
        }
        val wheel = _assignment.entries.firstOrNull { it.value == r.sensorId }?.key
        // El historial va sin calibrar.
        if (wheel != null && !raw.lost && raw.pressureKpa != null) addHistory(wheel, raw.atMillis, raw.pressureKpa)
        identifier?.check(_readings, r.atMillis)?.let { identified = it; identifying = false; identifier = null }
        if (running == TpmsSource.BLE || running == TpmsSource.DEMO || running == TpmsSource.OBD) status = TpmsStatus.CONNECTED
        if (wheel != null) syncWheels()
        refreshSoon()
    }

    private var refreshPending = false

    /** Publica y recalcula alertas a lo más una vez por segundo. */
    private fun refreshSoon() {
        if (refreshPending) return
        refreshPending = true
        scope.launch {
            delay(MIN_UPDATE_MS)
            refreshPending = false
            refresh()
        }
    }

    private fun refresh() {
        if (running == null) return
        syncWheels()
        val now = System.currentTimeMillis()
        for (w in Wheel.entries) {
            // Lo viejo no se publica como si fuera de ahora.
            val r = wheelReading(w)?.takeUnless { it.lost || it.stale(now) }
            bus.publish(TireSignals.pressure(w), r?.pressureKpa, TPMS_SOURCE)
            bus.publish(TireSignals.temp(w), r?.tempC, TPMS_SOURCE)
        }
        val byWheel = Wheel.entries.filter { it != Wheel.SPARE || showSpare }.mapNotNull { w -> wheelReading(w)?.let { w to it } }.toMap()
        val h = activeHistory()
        // La fuga rápida lee solo la cola del historial; la lenta se recalcula al entrar una muestra.
        val trends = byWheel.keys.associateWith { w ->
            TireTrend(h?.maxSince(w, now - FAST_LEAK_WINDOW_MS)?.let(::calibrated), slowLeaks[w] == true)
        }
        alerts = tireAlerts(byWheel, trends, limits, now)
        nearby = if (holds > 0) _readings.values.toList() else emptyList()
        saveHistory(force = false)
    }

    /** El ajuste del usuario sobre una presión del sensor (la demostración no lo lleva). */
    private fun calibrated(kpa: Float) = if (running == TpmsSource.DEMO || offsetKpa == 0f) kpa else (kpa + offsetKpa).coerceAtLeast(0f)

    /**
     * Bluetooth: los sensores sin asignar que ya no se oyen (autos que pasaron al lado) se
     * olvidan; los asignados también si ya ninguno se oye (auto parado o sin Bluetooth), para
     * no mostrar presiones viejas como de ahora. Si solo uno calla, queda y avisa "sin señal".
     */
    private fun pruneStale() {
        if (running != TpmsSource.BLE) return
        val now = System.currentTimeMillis()
        val assigned = _assignment.values.toSet()
        val anyAssignedFresh = assigned.any { _readings[it]?.stale(now) == false }
        val gone = _readings.filter { (id, r) -> r.stale(now) && (id !in assigned || !anyAssignedFresh) }.keys.toList()
        if (gone.isEmpty()) return
        gone.forEach(::forget)
        sensorCount = _readings.size
        if (_readings.isEmpty() && status == TpmsStatus.CONNECTED) status = TpmsStatus.SEARCHING
    }

    // ---- Asignación ----

    private fun loadAssignment(s: TpmsSource) {
        _assignment.clear()
        if (s == TpmsSource.DEMO) Wheel.ROAD.forEach { _assignment[it] = DemoTpms.id(it) }
        if (s == TpmsSource.OBD) Wheel.ROAD.forEach { _assignment[it] = obdId(it) }
        if (s != TpmsSource.BLE) return
        for (w in Wheel.entries) prefs.getString(KEY_ASSIGN + w.key, null)?.let { _assignment[w] = it }
    }

    private fun saveAssignment() {
        if (running != TpmsSource.BLE && savedSource != TpmsSource.BLE) return
        prefs.edit().apply {
            for (w in Wheel.entries) {
                val id = _assignment[w]
                if (id == null) remove(KEY_ASSIGN + w.key) else putString(KEY_ASSIGN + w.key, id)
            }
        }.apply()
    }

    /**
     * Pone [sensorId] en [w] (y lo quita de donde estuviera). Por USB no aplica: el receptor
     * ya sabe las posiciones (para cambiar una, [pairUsb] o [swap]).
     */
    fun assign(w: Wheel, sensorId: String) {
        if (running == TpmsSource.USB) return
        _assignment.entries.filter { it.value == sensorId }.map { it.key }.forEach { _assignment.remove(it) }
        _assignment[w] = sensorId
        saveAssignment()
        refresh()
    }

    fun unassign(w: Wheel) {
        if (running == TpmsSource.USB) return
        _assignment.remove(w)
        saveAssignment()
        refresh()
    }

    /** Hay un cambio de posiciones esperando la confirmación del receptor USB. */
    var swapping by mutableStateOf(false)
        private set
    private var swapJob: Job? = null
    private var pendingSwap: Pair<Wheel, Wheel>? = null

    /**
     * Intercambia lo de [a] y [b]. Por USB también se lo dice al receptor y espera su
     * confirmación (hasta 5 s) para que los dos queden iguales.
     */
    fun swap(a: Wheel, b: Wheel) {
        if (a == b) return
        if (running != TpmsSource.USB) {
            swapLocal(a, b)
            saveAssignment()
            refresh()
            return
        }
        val session = usb ?: return
        if (swapping) return
        swapping = true
        pendingSwap = a to b
        session.send(UsbTpmsProtocol.swap(a, b))
        swapJob = scope.launch {
            delay(SWAP_TIMEOUT_MS)
            swapping = false
            pendingSwap = null
            error = TpmsError.SWAP_UNCONFIRMED
        }
    }

    private fun swapLocal(a: Wheel, b: Wheel) {
        val ia = _assignment[a]
        val ib = _assignment[b]
        if (ib == null) _assignment.remove(a) else _assignment[a] = ib
        if (ia == null) _assignment.remove(b) else _assignment[b] = ia
    }

    // ---- ¿Cuál sensor es? ----

    private var identifier: SensorIdentifier? = null
    var identifying by mutableStateOf(false)
        private set
    /** El sensor que cambió de presión (o apareció) desde [startIdentify]; null si aún no. */
    var identified by mutableStateOf<String?>(null)
        private set
    private var identifyJob: Job? = null

    /** Empieza a buscar el sensor al que el usuario le saca o pone aire (120 s). */
    fun startIdentify() {
        identified = null
        val now = System.currentTimeMillis()
        identifier = SensorIdentifier(_readings.mapValues { it.value.pressureKpa }, now)
        identifying = true
        identifyJob?.cancel()
        identifyJob = scope.launch {
            delay(SensorIdentifier.TIMEOUT_MS)
            identifier = null
            identifying = false
        }
    }

    fun cancelIdentify() {
        identifyJob?.cancel(); identifyJob = null
        identifier = null
        identifying = false
    }

    // ---- Historial ----

    private var history: TireHistory? = null
    /** La demostración no ensucia el historial de verdad. */
    private var demoHistory = TireHistory()
    private var lastSave = 0L
    /** Una escritura a la vez (el .tmp es uno solo). */
    private val disk = Mutex()

    private fun activeHistory(): TireHistory? = if (running == TpmsSource.DEMO) demoHistory else history

    /** Qué llantas pierden aire poco a poco; cambia solo cuando entra una muestra (cada 30 s). */
    private val slowLeaks = mutableMapOf<Wheel, Boolean>()

    private fun addHistory(w: Wheel, at: Long, kpa: Float) {
        val h = activeHistory() ?: return
        if (h.add(w, at, kpa)) slowLeaks[w] = slowLeak(h.samples(w, at - SLOW_LEAK_WINDOW_MS), at)
    }

    private fun updateSlowLeaks() {
        slowLeaks.clear()
        val h = activeHistory() ?: return
        val now = System.currentTimeMillis()
        for (w in Wheel.entries) slowLeaks[w] = slowLeak(h.samples(w, now - SLOW_LEAK_WINDOW_MS), now)
    }

    /** Presión de [w] desde [sinceMillis] con la calibración de hoy (una muestra cada 30 s, hasta 7 días). */
    fun history(w: Wheel, sinceMillis: Long = 0): List<TireSample> {
        val samples = activeHistory()?.samples(w, sinceMillis).orEmpty()
        return if (running == TpmsSource.DEMO || offsetKpa == 0f) samples else samples.map { it.copy(kpa = calibrated(it.kpa)) }
    }

    /**
     * Se escribe cada 10 min y al parar ([force]): se copia aquí y se codifica y escribe fuera del
     * hilo principal. Solo una escritura buena lo da por guardado; si falla, se reintenta en el
     * siguiente pulso (cada 30 s).
     */
    private fun saveHistory(force: Boolean) {
        val h = history ?: return
        val now = System.currentTimeMillis()
        if (!h.dirty || (!force && now - lastSave < SAVE_MS)) return
        lastSave = now
        val changes = h.changes
        val copy = h.snapshot()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                disk.withLock {
                    runCatching {
                        val bytes = copy.encode()
                        val tmp = File(context.filesDir, "$HISTORY_FILE.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(historyFile())) historyFile().writeBytes(bytes)
                    }.isSuccess
                }
            }
            // De vuelta en el hilo principal (el historial no es seguro entre hilos).
            if (ok) h.saved(changes) else lastSave = 0L
        }
    }

    private fun historyFile() = File(context.filesDir, HISTORY_FILE)

    // ---- Demostración ----

    private var demoJob: Job? = null

    private fun startDemo() {
        val demo = DemoTpms()
        demoJob = scope.launch {
            while (isActive) {
                demo.tick(System.currentTimeMillis()).forEach(::ingest)
                delay(DemoTpms.INTERVAL_MS)
            }
        }
    }

    // ---- La computadora del auto (OBD) ----

    /** Lo pone la app; null = no hay escáner (la fuente OBD dice que falta el adaptador). */
    var carTires: CarTires? = null

    /** El perfil de marca que le sacó la presión al auto (para Diagnóstico); null hasta que contesta. */
    var obdProfile by mutableStateOf<String?>(null)
        private set

    private var obdJob: Job? = null

    /**
     * Pregunta cada [OBD_INTERVAL_MS] (los sensores de fábrica avisan cada medio minuto o así; el
     * adaptador se comparte con los medidores). Sin adaptador espera y vuelve a intentar.
     */
    private fun startObd() {
        obdJob = scope.launch {
            while (isActive) {
                when (val r = carTires?.read() ?: FactoryTires.NotConnected) {
                    FactoryTires.NotConnected -> { status = TpmsStatus.NO_DEVICE; error = TpmsError.OBD_OFF }
                    FactoryTires.Unsupported -> { status = TpmsStatus.NO_DEVICE; error = TpmsError.OBD_UNSUPPORTED }
                    is FactoryTires.Values -> {
                        // Contestó aunque los sensores aún no digan nada (parado, recién encendido).
                        error = null
                        status = TpmsStatus.CONNECTED
                        obdProfile = r.profile
                        val now = System.currentTimeMillis()
                        Wheel.ROAD.forEachIndexed { i, w ->
                            val kpa = r.kpa.getOrNull(i) ?: return@forEachIndexed
                            ingest(TireReading(obdId(w), kpa, r.tempC.getOrNull(i), null, null, false, false, false, OBD_FAMILY, w, null, now))
                        }
                    }
                }
                delay(OBD_INTERVAL_MS)
            }
        }
    }

    private fun obdId(w: Wheel) = "obd-" + w.key

    // ---- Bluetooth LE ----

    private var ble by mutableStateOf<BleTpmsScanner?>(null)

    /** Anuncios con datos que no se reconocieron (diagnóstico; vacío si no se usa Bluetooth). */
    val unknownAdverts: List<UnknownAdvert> get() = ble?.unknown ?: emptyList()

    /** El permiso que hay que pedir para Bluetooth (dispositivos cercanos o ubicación). */
    fun blePermission(): String = scanner().permission()

    private fun scanner(): BleTpmsScanner = ble ?: BleTpmsScanner(context, { holds > 0 }, ::ingest).also { s ->
        s.onFailure = { p -> bleProblem(p) }
        ble = s
    }

    private fun startBle() {
        val problem = scanner().start(balanced = foreground) ?: run {
            if (_readings.isEmpty()) status = TpmsStatus.SEARCHING
            return
        }
        bleProblem(problem)
    }

    private fun bleProblem(p: BleProblem) {
        bleIssue = p
        status = if (p == BleProblem.NO_PERMISSION) TpmsStatus.NO_PERMISSION else TpmsStatus.NO_DEVICE
    }

    private var btReceiver: BroadcastReceiver? = null

    /**
     * Mientras la fuente sea Bluetooth: si lo apagan (Android corta la búsqueda sin avisar),
     * se para y se dice; al prenderlo, se vuelve a buscar solo.
     */
    private fun watchBluetooth() {
        if (btReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (running != TpmsSource.BLE) return
                when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> { bleIssue = null; startBle() }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        ble?.stop()
                        // Nada de lo que se oyó sigue siendo de ahora.
                        clearReadings()
                        refresh()
                        bleProblem(BleProblem.BLUETOOTH_OFF)
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, r, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        btReceiver = r
    }

    private fun unwatchBluetooth() {
        btReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        btReceiver = null
    }

    // ---- Receptor USB ----

    private var usb: UsbSession? = null
    private var usbReceiver: BroadcastReceiver? = null
    private var permissionReceiver: BroadcastReceiver? = null
    /** IDs que dio el receptor por llanta (si no, el sensor se llama "usb-fl"…). */
    private val usbIds = mutableMapOf<Wheel, String>()

    private val _usbLog = mutableStateListOf<String>()
    /** Las últimas 40 tramas del receptor en hexadecimal ("> " = lo que se mandó). */
    val usbLog: List<String> get() = _usbLog

    var pairing by mutableStateOf(PairState.IDLE)
        private set
    var pairingWheel by mutableStateOf<Wheel?>(null)
        private set
    private var pairJob: Job? = null

    private fun findUsbDevice(manager: UsbManager): UsbDevice? =
        manager.deviceList.values.firstOrNull { d -> USB_IDS.any { it.first == d.vendorId && it.second == d.productId } }

    private fun startUsb() {
        watchUsb()
        val manager = ContextCompat.getSystemService(context, UsbManager::class.java)
        val device = manager?.let(::findUsbDevice)
        if (manager == null || device == null) {
            status = TpmsStatus.NO_DEVICE
            error = TpmsError.USB_MISSING
            return
        }
        if (manager.hasPermission(device)) return openUsb(manager, device)
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                unwatchPermission()
                if (running != TpmsSource.USB) return
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openUsb(manager, device)
                else {
                    status = TpmsStatus.NO_PERMISSION
                    error = TpmsError.USB_DENIED
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        permissionReceiver = receiver
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        manager.requestPermission(device, PendingIntent.getBroadcast(context, 1, intent, flags))
    }

    private fun unwatchPermission() {
        permissionReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        permissionReceiver = null
    }

    private var opening: Job? = null

    /** Abre el cable (uno a la vez: el permiso y la conexión pueden llegar juntos). */
    private fun openUsb(manager: UsbManager, device: UsbDevice) {
        if (opening?.isActive == true || usb != null) return
        opening = scope.launch {
            val link = withContext(Dispatchers.IO) { runCatching { UsbLink(manager, device, UsbTpmsProtocol.BAUD) } }
            val l = link.getOrElse {
                if (running == TpmsSource.USB) {
                    status = TpmsStatus.NO_DEVICE
                    error = TpmsError.USB_OPEN_FAILED
                }
                return@launch
            }
            if (running != TpmsSource.USB || usb != null) return@launch run { runCatching { l.close() } }
            val session = UsbSession(l, device)
            usb = session
            status = TpmsStatus.CONNECTED
            error = null
            detail = device.productName?.takeIf { it.isNotBlank() } ?: "USB %04X:%04X".format(device.vendorId, device.productId)
            session.start()
            session.send(UsbTpmsProtocol.queryIds())
        }
    }

    /** Conectar y desconectar el receptor mientras se usa (sin reintentar en bucle). */
    private fun watchUsb() {
        if (usbReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = IntentCompat.getParcelableExtra(i, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
                if (USB_IDS.none { it.first == d.vendorId && it.second == d.productId } || running != TpmsSource.USB) return
                if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    if (usb?.device?.deviceName == d.deviceName) usbLost(TpmsError.USB_UNPLUGGED)
                } else if (usb == null) {
                    startUsb()
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED).apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED) }
        // Son avisos del sistema: tienen que poder llegar de fuera de la app.
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_EXPORTED)
        usbReceiver = r
    }

    private fun usbLost(why: TpmsError) {
        closeUsb()
        _assignment.clear()
        clearReadings()
        refresh()
        status = TpmsStatus.NO_DEVICE
        error = why
    }

    private fun closeUsb() {
        usb?.close()
        usb = null
        usbIds.clear()
        pairJob?.cancel(); pairJob = null
        if (pairing == PairState.WAITING) pairing = PairState.IDLE
        swapJob?.cancel(); swapJob = null
        swapping = false
        pendingSwap = null
    }

    private fun stopUsb() {
        closeUsb()
        unwatchPermission()
        usbReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        usbReceiver = null
    }

    /**
     * Pide al receptor aprender el sensor de [w]: luego hay que inflar/desinflar o girar esa
     * llanta. [pairing] queda en WAITING hasta 120 s.
     */
    fun pairUsb(w: Wheel) {
        val session = usb ?: return
        pairJob?.cancel()
        pairingWheel = w
        pairing = PairState.WAITING
        session.reader.pairing = true
        session.send(UsbTpmsProtocol.pair(w))
        pairJob = scope.launch {
            delay(PAIR_TIMEOUT_MS)
            session.reader.pairing = false
            pairing = PairState.TIMEOUT
            session.send(UsbTpmsProtocol.stopPair())
        }
    }

    fun cancelPair() {
        pairJob?.cancel(); pairJob = null
        usb?.let {
            it.reader.pairing = false
            if (pairing == PairState.WAITING) it.send(UsbTpmsProtocol.stopPair())
        }
        pairing = PairState.IDLE
        pairingWheel = null
    }

    private fun onUsbFrame(session: UsbSession, f: UsbFrame) {
        if (usb !== session) return
        val now = System.currentTimeMillis()
        when (f) {
            is UsbFrame.State -> {
                val w = f.wheel ?: return
                val id = usbIds[w] ?: "usb-${w.key}"
                _assignment[w]?.takeIf { it != id }?.let(::forget)
                _assignment[w] = id
                ingest(
                    TireReading(
                        sensorId = id, pressureKpa = f.pressureKpa, tempC = f.tempC, batteryPct = null, batteryVolts = null,
                        lowBattery = f.lowBattery, leak = f.leak, lost = f.lost, family = "USB", reportedWheel = w, rssi = null, atMillis = now,
                    ),
                )
            }
            is UsbFrame.SensorId -> {
                val w = f.wheel ?: return
                val newId = if (f.empty) null else f.id
                if (usbIds[w] == newId) return
                if (newId == null) usbIds.remove(w) else usbIds[w] = newId
                // Lo que ya se sabe de esa llanta pasa al nombre nuevo.
                val old = _assignment[w] ?: return
                val r = _readings[old] ?: return
                forget(old)
                val id = newId ?: "usb-${w.key}"
                _readings[id] = r.copy(sensorId = id)
                _assignment[w] = id
                syncWheels()
            }
            is UsbFrame.PairOk -> {
                // El latido (06 19 00) se parece a la respuesta de la delantera izquierda: solo cuenta la llanta pedida.
                if (pairing != PairState.WAITING || f.wheel != pairingWheel) return
                pairJob?.cancel(); pairJob = null
                session.reader.pairing = false
                pairing = PairState.DONE
                session.send(UsbTpmsProtocol.queryIds())
            }
            is UsbFrame.SwapAck -> {
                val (a, b) = pendingSwap ?: return
                val pa = UsbTpmsProtocol.posOf(a)
                val pb = UsbTpmsProtocol.posOf(b)
                if (!(f.a == pa && f.b == pb || f.a == pb && f.b == pa)) return
                swapJob?.cancel(); swapJob = null
                pendingSwap = null
                swapping = false
                val ia = usbIds[a]
                val ib = usbIds[b]
                if (ib == null) usbIds.remove(a) else usbIds[a] = ib
                if (ia == null) usbIds.remove(b) else usbIds[b] = ia
                swapLocal(a, b)
                session.send(UsbTpmsProtocol.queryIds())
                refresh()
            }
            is UsbFrame.Heartbeat -> if (f.needsAck) session.send(UsbTpmsProtocol.ack(), logIt = false)
            is UsbFrame.Unknown -> Unit
        }
    }

    private fun log(line: String) {
        _usbLog.add(line)
        while (_usbLog.size > MAX_LOG) _usbLog.removeAt(0)
    }

    /** El cable abierto: un hilo lee y arma tramas; lo demás pasa en el hilo principal. */
    private inner class UsbSession(private val link: UsbLink, val device: UsbDevice) {
        val reader = UsbTpmsProtocol.FrameReader()
        @Volatile private var open = true
        private val writeLock = Any()
        private var heartbeatJob: Job? = null

        fun start() {
            Thread({ pump() }, "tpms-usb").apply { isDaemon = true }.start()
            heartbeatJob = scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    // Mientras se empareja, el latido se confundiría con la respuesta.
                    if (!reader.pairing) send(UsbTpmsProtocol.heartbeat(), logIt = false)
                }
            }
        }

        fun send(bytes: ByteArray, logIt: Boolean = true) {
            if (logIt) log("> " + bytes.hex())
            scope.launch(Dispatchers.IO) {
                runCatching {
                    synchronized(writeLock) {
                        link.output.write(bytes)
                        link.output.flush()
                    }
                }
            }
        }

        private fun pump() {
            val buf = ByteArray(256)
            val raws = mutableListOf<String>()
            // Los latidos (los suyos y su eco) no llenan el registro.
            reader.onRaw = { bytes, frame -> if (frame !is UsbFrame.Heartbeat) raws += bytes.hex() }
            try {
                while (open) {
                    val first = link.input.read()
                    if (first < 0) {
                        link.input.available() // lanza IOException si el enlace se cerró
                        continue
                    }
                    buf[0] = first.toByte()
                    var n = 1
                    val more = link.input.available().coerceAtMost(buf.size - 1)
                    repeat(more) { val b = link.input.read(); if (b >= 0) buf[n++] = b.toByte() }
                    val frames = reader.feed(buf, n)
                    if (frames.isEmpty() && raws.isEmpty()) continue
                    val lines = raws.toList()
                    raws.clear()
                    scope.launch {
                        lines.forEach(::log)
                        frames.forEach { onUsbFrame(this@UsbSession, it) }
                    }
                }
            } catch (e: IOException) {
                scope.launch { if (usb === this@UsbSession) usbLost(TpmsError.USB_LOST) }
            }
        }

        fun close() {
            open = false
            heartbeatJob?.cancel()
            runCatching { link.close() }
        }
    }

    // ---- Preferencias como estado de Compose ----

    private fun floatPref(key: String, default: Float) = Pref(key, prefs.getFloat(key, default)) { k, v -> putFloat(k, v) }
    private fun intPref(key: String, default: Int) = Pref(key, prefs.getInt(key, default)) { k, v -> putInt(k, v) }
    private fun boolPref(key: String, default: Boolean) = Pref(key, prefs.getBoolean(key, default)) { k, v -> putBoolean(k, v) }

    private inner class Pref<T>(
        private val key: String,
        initial: T,
        private val put: android.content.SharedPreferences.Editor.(String, T) -> Unit,
    ) : kotlin.properties.ReadWriteProperty<Any?, T> {
        private var value by mutableStateOf(initial)
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) {
            this.value = value
            prefs.edit().apply { put(key, value) }.apply()
            refreshSoon()
        }
    }

    // Al final: todo lo de arriba ya existe cuando el primer cambio llega (hilo principal inmediato).
    init {
        scope.launch {
            snapshotFlow {
                val shown = bus.wanted.any(TireSignals::isTire)
                val need = shown || holds > 0 || alertsEnabled
                Want(savedSource.takeIf { need }, shown || holds > 0)
            }.distinctUntilChanged().collect { applyWant(it) }
        }
        scope.launch {
            history = withContext(Dispatchers.IO) { disk.withLock { TireHistory.decode(runCatching { historyFile().readBytes() }.getOrNull()) } }
            if (running != null && running != TpmsSource.DEMO) updateSlowLeaks()
        }
    }

    companion object {
        /** Archivo de ajustes (la app lo escucha para prender o apagar [UsbAttachActivity]). */
        const val PREFS = "tires"
        private const val KEY_SOURCE = "source"
        private const val KEY_LAST_SOURCE = "last_source"
        private const val KEY_FRONT = "front_kpa"
        private const val KEY_REAR = "rear_kpa"
        private const val KEY_LOW_PCT = "low_percent"
        private const val KEY_HIGH_TEMP = "high_temp_c"
        private const val KEY_OFFSET = "offset_kpa"
        private const val KEY_UNIT = "unit"
        private const val KEY_SHOW_SPARE = "show_spare"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_ASSIGN = "assign_"
        private const val HISTORY_FILE = "tire_history.bin"
        private const val ACTION_USB_PERMISSION = "com.irvingguerra.litedash.TPMS_USB_PERMISSION"

        /** Chips USB‑serie que usan los receptores (CH340, FTDI ×2, CP210x, PL2303). */
        val USB_IDS = listOf(0x1A86 to 0x7523, 0x0403 to 0x6001, 0x0403 to 0x6015, 0x10C4 to 0xEA60, 0x067B to 0x2303)

        private const val MIN_UPDATE_MS = 1_000L
        private const val TICK_MS = 30_000L
        /** Cada cuánto se le pregunta la presión a la computadora del auto. */
        private const val OBD_INTERVAL_MS = 15_000L
        private const val OBD_FAMILY = "obd"
        private const val SAVE_MS = 10 * 60_000L
        private const val HEARTBEAT_MS = 1_000L
        private const val PAIR_TIMEOUT_MS = 120_000L
        private const val SWAP_TIMEOUT_MS = 5_000L
        private const val MAX_LOG = 40

        /** Hay una fuente de llantas elegida (sin crear el repositorio ni tocar USB/Bluetooth). */
        fun hasSavedSource(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_SOURCE)

        /** Las llantas se leen del receptor USB (sin crear el repositorio). */
        fun usesUsb(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SOURCE, null) == TpmsSource.USB.key
    }
}
