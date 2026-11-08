package com.irvingguerra.litedash.feature.music

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.media.MediaBrowserService
import android.service.notification.StatusBarNotification
import android.view.KeyEvent
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import com.irvingguerra.litedash.feature.musica.ServicioMusica
import kotlin.concurrent.thread

/** Recibe los avisos que pasa el [NotificationService] (la app solo mira los de navegación). */
interface NotificationListener {
    /**
     * Alguien los quiere ahora (la app ya está armada). Si no, el servicio no pasa nada: Android
     * lo conecta solo (también tras forzar el cierre) y no hay por qué armar la app por un aviso.
     */
    val wanted: Boolean
    fun onPosted(notification: StatusBarNotification)
    fun onRemoved(notification: StatusBarNotification)

    /** Android acaba de conectar el servicio: ya se pueden pedir los avisos que hay ([NotificationService.active]). */
    fun onConnected() = Unit
}

/** Nombre en inglés del servicio de avisos, que por compatibilidad conserva su nombre original. */
typealias NotificationService = ServicioMusica

private const val STORE = "music"
private const val KEY_LAST_APP = "last_app"
/** Ya se intentó adivinar las apps de las fuentes (una sola vez, al estrenar la pieza). */
private const val KEY_SOURCES_GUESSED = "sources_guessed"
/** La pieza Radio prefiere la de internet (true) o la FM de la pantalla. */
private const val KEY_RADIO_INTERNET = "radio_internet"

/** Palabras con las que se reconoce la app de cada fuente (en el paquete o en el nombre). */
private val SOURCE_WORDS = mapOf(
    SourceKind.BLUETOOTH to listOf("bluetooth", "bt", "a2dp"),
    SourceKind.RADIO to listOf("radio", "fm"),
)

/**
 * Radios de fábrica de las pantallas Geely (ECARX/GKUI, como la del Okavango): se enlazan solas si
 * están instaladas. Los nombres son los que usan esas pantallas; si la del auto usa otro, se busca
 * cualquier app del sistema con "radio" en el paquete o el nombre.
 */
private val GEELY_RADIO_APPS = listOf(
    "com.neusoft.shockwave.radio", // la "Radio" de fábrica del Okavango (Neusoft, RadioService)
    "com.ecarx.radio", "com.ecarx.xui.radio", "com.ecarx.fmradio", "ecarx.radio",
    "com.geely.radio", "com.geely.fmradio", "com.geely.gkui.radio", "com.gkui.radio",
    "com.android.fmradio", "com.caf.fmradio", "com.qualcomm.fmradio", "com.mediatek.fmradio",
)

/** El reproductor "Música" de fábrica de la pantalla del Okavango (Neusoft): es el que de verdad suena. */
const val CAR_MUSIC_APP = "com.neusoft.shockwave.Mutimedia"

/** Apps de proyección (AutoKit, CarPlay, etc.): también publican sesión, pero no son la música del carro. */
private val PROJECTION_WORDS = listOf("autokit", "zlink", "carlink", "carplay", "androidauto", "easyconn", "tlink", "phonelink", "carbit", "headunit")

private fun isProjection(pkg: String) = pkg.lowercase().let { p -> PROJECTION_WORDS.any { it in p } }

/** Orden al elegir sesión: primero la Música del carro, al final las de proyección. */
private fun sessionRank(pkg: String) = when {
    pkg == CAR_MUSIC_APP -> 0
    isProjection(pkg) -> 2
    else -> 1
}

/** La Radio de fábrica de la pantalla del Okavango (Neusoft). */
const val CAR_RADIO_APP = "com.neusoft.shockwave.radio"
private const val KEY_CAR_RADIO_LINKED = "car_radio_linked"

/** Cuánto se espera a que la app despertada empiece a sonar antes de abrirla. */
private const val WAKE_TIMEOUT_MS = 4_000L
/** Tras abrir una radio sin control, cuánto se espera para regresar al inicio (y luego para anotar si sigue el audio). */
private const val RADIO_RETURN_MS = 3_000L
/** Marca de "abriendo TopWay": si se queda puesta, la app se cerró al abrirlo. */
private const val KEY_TOPWAY_OPENING = "topway_opening"
private const val TOPWAY_XT_BIND = "com.tw.service.xt.CommandService.Bind"
/** Lo que tarda el sintonizador de TopWay en contestar (la estación o que ya tiene el audio). */
private const val TOPWAY_ANSWER_MS = 2_000L
/** Lo que LiteDash supone de una app de fábrica (suena / en pausa) deja de valer tras este rato (p. ej. apagaron el auto). */
private const val ASSUME_MAX_MS = 3 * 60 * 60_000L
/** Tras un play a una app de música, cuánto se espera a que suene antes de abrirla. */
private const val PLAY_CHECK_MS = 4_000L
/** Tras abrirla, cuánto se espera para regresar al inicio y darle play otra vez (Spotify tarda en despertar). */
private const val PLAY_RETURN_MS = 4_000L
/** Cuántos eventos guarda el registro de "Enviar un reporte". */
private const val TRACE_SIZE = 60
/** Un aviso (broadcast) de qué suena vale como respaldo durante este rato. */
private const val BROADCAST_FRESH_MS = 10 * 60_000L

// Ofrecen su audio a Android pero no son apps de música (el asistente, video).
private val NOT_MUSIC_APPS = setOf(
    "com.google.android.googlequicksearchbox", "com.google.android.youtube", "com.google.android.gms",
)

/** Apps de Bluetooth de marca (chip propio, no el de Android): para saber la familia en los reportes. */
private val BRAND_BT_APPS = listOf(
    "com.syu.bt", "com.syu.ms", "com.fyt.bt",           // FYT
    "com.tw.bt", "com.tw.service", "com.ts.bt",         // TopWay
    "com.microntek.bt",                                  // Microntek (MTCD/PX5)
    "com.goodocom.gocsdk",                               // Goodocom gocsdk
    "com.autochips.bluetooth",                           // Autochips (AC8227L…)
    "com.yftech.service.bluetooth",                      // YF Tech (GAC)
)

/**
 * Lo que suena en cualquier app (Spotify, YouTube Music, la radio de la pantalla…)
 * vía las sesiones de medios de Android. Solo escucha mientras alguna pieza de
 * música está en pantalla ([use]/[release] cuentan cuántas): nada de polling.
 */
class MusicRepository(private val context: Context) {
    private val sessionManager = ContextCompat.getSystemService(context, MediaSessionManager::class.java)!!
    private val component = ComponentName(context, NotificationService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var users = 0

    /** Lo que ve la pieza Música (todo menos la radio). Atajo de [music]. */
    val song: Song? get() = music.song

    /** La app que la pieza Música está despertando ("Abriendo Spotify…"); null = nada. Atajo de [music]. */
    val starting: String? get() = music.starting

    /** Nombre del celular conectado por Bluetooth a la pantalla; null = ninguno (o sin permiso para verlo). */
    var bluetoothDevice by mutableStateOf<String?>(null)
        private set

    /** Nombre de la memoria USB conectada; null = ninguna. */
    var usbDrive by mutableStateOf<String?>(null)
        private set

    // La última app que sonó (nunca LiteDash): sin app elegida, play la despierta aunque ya no esté abierta.
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
    // Spotify avisa a cada rato: solo se escribe al disco cuando cambia la app.
    private var lastSavedApp: String? = store.getString(KEY_LAST_APP, null)

    /**
     * Tu app de música, elegida en Ajustes (paquete); null = ninguna: la pieza muestra lo que
     * suene. Los botones de la música en grande no la cambian ([active] es lo del momento).
     */
    var preferred: String?
        get() = preferredState
        set(v) {
            preferredState = v
            if (users > 0) choose(sessions())
        }
    // Estado de Compose: la fila de fuentes resalta la elegida al momento.
    private var preferredState by mutableStateOf<String?>(null)

    /** Guarda la elección de [choose] (la pone el app: va a sus preferencias). */
    var onPreferredChanged: ((String?) -> Unit)? = null

    /**
     * La fuente que el usuario tocó en la música en grande (paquete; el de LiteDash = USB);
     * null = la app elegida o lo que suene. No se guarda: si otra app empieza a sonar sola, se suelta.
     */
    var active by mutableStateOf<String?>(null)
        private set

    /** Tocaron USB y el reproductor propio aún busca qué tocar: no soltar [active] mientras. */
    private var ownPending = false

    /** Sube con cada toque a USB o a otra fuente: la respuesta de un toque viejo se ignora. */
    private var ownToken = 0

    /**
     * Apps de la pantalla enlazadas a Bluetooth y radio (Ajustes → Música); se guardan en [store].
     * USB no se enlaza: es siempre el reproductor de LiteDash.
     */
    var sourceApps by mutableStateOf(SourceKind.entries.filter { it != SourceKind.USB }.mapNotNull { k -> store.getString(k.key, null)?.let { k to it } }.toMap())
        private set

    /**
     * Quien tenía una "app de USB" enlazada (antes de la 1.4.57) la conserva como su app de música,
     * pero solo si la eligió él: las del sistema eran adivinadas y no se suben. Una sola vez.
     */
    fun migrateUsbApp() {
        val old = store.getString(SourceKind.USB.key, null) ?: return
        store.edit().remove(SourceKind.USB.key).apply()
        val system = runCatching { context.packageManager.getApplicationInfo(old, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(true)
        if (preferred == null && !system) choose(old)
    }

    /** Enlaza (o con null desenlaza) la app de Bluetooth o la de radio. */
    fun setSource(kind: SourceKind, pkg: String?) {
        sourceApps = if (pkg == null) sourceApps - kind else sourceApps + (kind to pkg)
        // Al enlazar otra vez (quizá tras actualizar la app de la pantalla) se vuelve a preguntar su receptor.
        if (pkg != null) receivers.remove(pkg)
        music.forget()
        if (kind != SourceKind.RADIO) radio.forget()
        store.edit().putString(kind.key, pkg).apply()
        if (users > 0) loadSourceNotes()
        if (kind == SourceKind.RADIO) radioChanged()
        // Enlazar o quitar la radio cambia qué pieza se queda con cada sesión.
        if (users > 0) choose(sessions())
    }

    private val SourceKind.key get() = "source_" + name.lowercase()

    /** La radio por internet de LiteDash está prendida (la pone el app según su ajuste). */
    var ownRadio: Boolean
        get() = ownRadioState
        set(v) {
            if (v == ownRadioState) return
            val before = appFor(SourceKind.RADIO)
            ownRadioState = v
            if (appFor(SourceKind.RADIO) != before) radioChanged()
        }
    private var ownRadioState by mutableStateOf(false)

    /**
     * Cambió con qué suena la pieza Radio (se enlazó o quitó la FM, se prendió la de internet o se
     * escogió entre las dos): lo que se supo de la anterior (la estación de TopWay, el play que se
     * le mandó) ya no vale, y TopWay solo escucha mientras la FM sea la radio escogida.
     */
    private fun radioChanged() {
        mainHandler.removeCallbacks(topWayConfirm)
        radio.clear()
        if (users > 0) startTopWay()
    }

    /**
     * Pide al reproductor propio que suene esa fuente y avisa en el hilo principal: true = ya
     * suena (o va a sonar), false = no hay nada que tocar (la vista abre la biblioteca).
     */
    var ownPlay: ((SourceKind, (Boolean) -> Unit) -> Unit)? = null

    /** Pausa la USB del reproductor propio y cancela un arranque pendiente (lo pone el app). */
    var ownPause: (() -> Unit)? = null

    /** Qué fuente propia está sonando ahora; null si el reproductor propio no suena. */
    var ownSourceNow: (() -> SourceKind?)? = null

    /** El paquete de LiteDash: el "app" de las fuentes que toca el reproductor propio. */
    val ownPackage: String get() = context.packageName

    fun isOwn(pkg: String) = pkg == ownPackage

    /**
     * La app que reproduce [kind]: USB es siempre LiteDash; Bluetooth, la enlazada. La radio: la
     * de internet si está prendida y se prefiere (o no hay FM enlazada), si no la FM enlazada.
     */
    fun appFor(kind: SourceKind): String? = when (kind) {
        SourceKind.USB -> ownPackage
        SourceKind.BLUETOOTH -> sourceApps[kind]
        SourceKind.RADIO -> if (ownRadio && (radioInternet || sourceApps[kind] == null)) ownPackage else sourceApps[kind]
    }

    /** La pantalla trae el Bluetooth de Android (AVRCP): la fuente Bluetooth no pide app. */
    val bluetoothBuiltIn: Boolean get() = bluetoothService != null

    /** La app que toca el Bluetooth: el propio Android (AVRCP) si lo trae, si no la enlazada. */
    fun bluetoothApp(): String? = bluetoothService?.packageName ?: sourceApps[SourceKind.BLUETOOTH]

    /** La pieza Radio usa la radio por internet de LiteDash (si no, la FM de la pantalla). Se guarda. */
    var radioInternet: Boolean
        get() = radioInternetState
        set(v) {
            val before = appFor(SourceKind.RADIO)
            radioInternetState = v
            store.edit().putBoolean(KEY_RADIO_INTERNET, v).apply()
            if (appFor(SourceKind.RADIO) != before) radioChanged()
            // Al pasar a la FM, la de internet se calla (si no, su sesión en pausa seguiría mandando).
            if (!v && ownSourceNow?.invoke() == SourceKind.RADIO) ownStop?.invoke()
        }

    /** Detiene el reproductor propio (lo pone el app). */
    var ownStop: (() -> Unit)? = null
    private var radioInternetState by mutableStateOf(store.getBoolean(KEY_RADIO_INTERNET, false))

    /** Hay FM de la pantalla enlazada y también internet: la pieza Radio deja escoger. */
    val radioHasBoth: Boolean get() = ownRadio && sourceApps[SourceKind.RADIO] != null

    /**
     * Tocaron USB: que suene el reproductor propio (sin cambiar la app elegida). Busca qué tocar
     * en segundo plano; si no hay nada, [onEmpty] (la vista abre la biblioteca para escoger).
     */
    fun playOwn(onEmpty: () -> Unit) {
        val play = ownPlay ?: return
        val token = ++ownToken
        active = ownPackage
        ownPending = true
        music.forget()
        play(SourceKind.USB) { ok ->
            if (token != ownToken) return@play
            ownPending = false
            if (!ok) onEmpty()
            if (users > 0) choose(sessions())
        }
    }

    private val icons = HashMap<String, Bitmap?>()
    // Nombres de las apps: se piden una vez a Android (es una llamada entre procesos).
    private val labels = HashMap<String, String>()

    fun hasPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            ContextCompat.getSystemService(context, NotificationManager::class.java)!!.isNotificationListenerAccessGranted(component)
        } else {
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.contains(component.flattenToString()) == true
        }

    fun requestPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private val onSessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { choose(it.orEmpty()) }

    // Celular por Bluetooth y memoria USB: solo se escuchan mientras la música está en pantalla.
    private val devices = DeviceWatcher(context, onBluetooth = ::phoneChanged, onUsb = { usbDrive = it })

    // El Bluetooth de Android como receptor (A2DP sink): si la pantalla trae el servicio AVRCP,
    // LiteDash lee y controla la música del celular sin app externa. Solo vivo mientras la música
    // esté a la vista; el controlador solo cuenta con un celular conectado.
    private var avrcp: BluetoothAvrcp? = null
    private var avrcpController: MediaController? = null
    /** El servicio AVRCP del Bluetooth de Android; se busca una vez, sin importar si la música está a la vista. */
    private val bluetoothService: ServiceInfo? by lazy { BluetoothAvrcp.find(context) }

    // El celular llegó o se fue: con AVRCP, se conecta o desconecta; sin él, la música Bluetooth
    // que se suponía sonando ya no suena.
    private fun phoneChanged(name: String?) {
        bluetoothDevice = name
        if (name != null) {
            avrcp?.connect()
            return
        }
        avrcp?.disconnect()
        avrcpController = null
        if (users > 0) choose(sessions())
        val app = sourceApps[SourceKind.BLUETOOTH] ?: return
        for (lens in listOf(music, radio)) if (lens.assumedPkg == app) lens.forget()
    }

    /** Si la pantalla trae el Bluetooth de Android (AVRCP), se prepara para leer la música del celular. */
    private fun startAvrcp() {
        if (avrcp != null) return
        val service = bluetoothService ?: return
        avrcp = BluetoothAvrcp(context, service, ::onAvrcpController)
        if (bluetoothDevice != null) avrcp?.connect()
    }

    /** El controlador AVRCP ya (des)conectó: se vuelve a repartir; sin celular conectado, no cuenta. */
    private fun onAvrcpController(controller: MediaController?) {
        avrcpController = controller?.takeIf { bluetoothDevice != null }
        mainHandler.post { if (users > 0) choose(sessions()) }
    }

    fun use() {
        users++
        if (users == 1) start()
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) stop()
    }

    /** Llamar al volver a la app: si se acaba de dar el permiso, empieza a escuchar las sesiones. */
    fun recheck() {
        if (users > 0 && !listening) listen()
    }

    // Ya escucha las sesiones de medios (con el permiso de avisos).
    private var listening = false

    /**
     * Respaldo de "qué suena" para apps sin sesión de medios (la música Bluetooth de muchas
     * pantallas chinas): sus avisos (broadcasts). La radio enlazada va a su pieza; lo demás, a Música.
     */
    private val broadcasts = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val extras = i.extras.toMap()
            trace("aviso ${i.action} [${extras.keys.joinToString()}]")
            val meta = BroadcastMeta.parse(i.action ?: return, extras) ?: return
            val fm = meta.pkg != null && meta.pkg == sourceApps[SourceKind.RADIO]
            // La FM avisando mientras la pieza Radio está en internet: no es lo que se escogió.
            if (fm && appFor(SourceKind.RADIO) != meta.pkg) return
            (if (fm) radio else music).heard(meta)
        }
    }

    private fun start() {
        devices.start()
        startAvrcp()
        // Son avisos de otras apps: el receptor debe aceptar avisos de fuera. Cualquier app podría mandar
        // un título falso; solo se muestra (sin sesión de por medio) y la sesión real siempre manda.
        val filter = IntentFilter().apply { BroadcastMeta.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(context, broadcasts, filter, ContextCompat.RECEIVER_EXPORTED)
        NotificationService.media = notifications
        loadSourceNotes()
        startTopWay()
        // La Música de fábrica del Okavango es la app de música por defecto (y no AutoKit u otra de proyección).
        if ((preferred == null || isProjection(preferred.orEmpty())) && runCatching { context.packageManager.getPackageInfo(CAR_MUSIC_APP, 0) }.isSuccess) {
            choose(CAR_MUSIC_APP)
        }
        // Mientras no haya radio enlazada se vuelve a buscar la de la pantalla en cada arranque.
        if (!store.getBoolean(KEY_SOURCES_GUESSED, false) || SourceKind.RADIO !in sourceApps) {
            store.edit().putBoolean(KEY_SOURCES_GUESSED, true).apply()
            Thread(::guessSources, "music-sources").start()
        }
        listen()
        // Sin permiso de avisos no hay sesiones: se muestra lo último avisado por broadcast.
        if (!listening) {
            music.read()
            radio.read()
        }
    }

    private fun listen() {
        if (!hasPermission()) return
        runCatching {
            sessionManager.addOnActiveSessionsChangedListener(onSessionsChanged, component, mainHandler)
            listening = true
            choose(sessions())
        }
    }

    private fun stop() {
        devices.stop()
        avrcp?.disconnect()
        avrcpController = null
        runCatching { context.unregisterReceiver(broadcasts) }
        if (NotificationService.media === notifications) NotificationService.media = null
        if (listening) runCatching { sessionManager.removeOnActiveSessionsChangedListener(onSessionsChanged) }
        listening = false
        // Cerrado pero se queda (con la última estación) para el reporte; al volver se abre otro.
        topWayRun++
        topWay?.close()
        music.detach()
        radio.detach()
    }

    /**
     * Perfil TopWay ([TopWayRadio]): con la radio enlazada sin receptor ni servicio (`com.tw.radio`)
     * y el enlace de TopWay en la pantalla. Si alguna vez la app se cerró al abrirlo, no se vuelve a intentar.
     */
    private var topWay: TopWayRadio? = null
    /** Sube en cada arranque o parada: un TopWay que termina de abrir tarde se descarta. */
    private var topWayRun = 0

    /** Se abre en otro hilo (reflexión, el driver y el disco); se publica en el principal. */
    @SuppressLint("ApplySharedPref") // commit a propósito: debe quedar escrito antes de abrir
    private fun startTopWay() {
        topWay?.close()
        topWay = null
        val run = ++topWayRun
        // Solo si la FM es la radio escogida (no la de internet).
        val pkg = appFor(SourceKind.RADIO)?.takeUnless(::isOwn) ?: return
        thread(name = "topway") {
            if (!TopWayRadio.available || !isSilent(pkg)) return@thread
            val log: (String) -> Unit = { e -> mainHandler.post { trace(e) } }
            if (store.getBoolean(KEY_TOPWAY_OPENING, false)) return@thread log("topway apagado: la app se cerró al abrirlo")
            // Marcado antes de abrir (y guardado ya): si el firmware tumba la app, la próxima vez no se abre.
            store.edit().putBoolean(KEY_TOPWAY_OPENING, true).commit()
            val tw = TopWayRadio(onState = { radio.heard(it.copy(pkg = pkg)) }, trace = log)
            val opened = tw.open()
            store.edit().putBoolean(KEY_TOPWAY_OPENING, false).apply()
            mainHandler.post { if (opened && run == topWayRun) topWay = tw else tw.close() }
        }
    }

    /** Sin receptor del botón de play ni servicio de música: no hay cómo hablarle (sin caché: puede ir en otro hilo). */
    private fun isSilent(pkg: String) = mediaButtonReceivers(pkg).isEmpty() && browserService(pkg) == null

    /** La radio de la pieza se controla por el perfil TopWay. */
    private fun topWayFor(lens: Lens): TopWayRadio? = topWay?.takeIf { lens === radio && it.open }

    /**
     * Las sesiones activas y, de las apps enlazadas (Bluetooth, FM), la de su aviso de música si
     * Android no la cuenta como activa (algunas pantallas solo la dejan ver por ahí).
     */
    private fun sessions(): List<MediaController> {
        val active = runCatching { sessionManager.getActiveSessions(component) }.getOrDefault(emptyList())
        val extra = sourceNotes.values.filter { n -> active.none { it.packageName == n.packageName } }.mapNotNull { n ->
            n.sessionToken()?.let { runCatching { MediaController(context, it) }.getOrNull() }
        }
        val all = active + extra
        // El Bluetooth de Android (AVRCP) no sale entre las sesiones activas: se agrega.
        val av = avrcpController ?: return all
        return if (all.any { it.packageName == av.packageName }) all else all + av
    }

    /**
     * El aviso de música de cada app enlazada (paquete → aviso), al día con lo que pasa el servicio:
     * así no se le pide a Android la lista de todos los avisos (con sus imágenes) en cada lectura.
     * Solo en el hilo principal.
     */
    private val sourceNotes = HashMap<String, StatusBarNotification>()

    /** Vuelve a leer los avisos de las apps enlazadas (al empezar, al conectarse el servicio o al enlazar otra). */
    private fun loadSourceNotes() {
        sourceNotes.clear()
        val apps = sourceApps.values.toSet()
        if (apps.isEmpty()) return
        NotificationService.active().filter { it.packageName in apps && it.isMedia() }.forEach { sourceNotes[it.packageName] = it }
    }

    /** Título y texto del aviso de música de [pkg]; null si no tiene. */
    private fun notified(pkg: String): Pair<String, String>? {
        val extras = sourceNotes[pkg]?.notification?.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        return if (title.isEmpty()) null else title to extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    }

    /**
     * Si una app enlazada cambia su aviso de música (otra sesión u otra canción), se vuelve a mirar.
     * Los avisos que no cambian eso (algunas apps lo actualizan cada segundo con el avance) no hacen nada.
     */
    private val notifications = object : NotificationListener {
        override val wanted: Boolean get() = users > 0
        override fun onPosted(notification: StatusBarNotification) = changed(notification, removed = false)
        override fun onRemoved(notification: StatusBarNotification) = changed(notification, removed = true)
        override fun onConnected() {
            mainHandler.post {
                if (users == 0) return@post
                loadSourceNotes()
                choose(sessions())
            }
        }

        private fun changed(n: StatusBarNotification, removed: Boolean) {
            mainHandler.post {
                val pkg = n.packageName
                if (users == 0 || pkg !in sourceApps.values) return@post
                val before = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                when {
                    !removed && n.isMedia() -> sourceNotes[pkg] = n
                    sourceNotes[pkg]?.key == n.key -> sourceNotes.remove(pkg)
                }
                val after = sourceNotes[pkg]?.let { it.sessionToken() to notifiedTitle(it) }
                if (before == after) return@post
                trace("aviso de música ${if (after == null) "quitado" else "nuevo"} $pkg")
                choose(sessions())
            }
        }

        private fun notifiedTitle(n: StatusBarNotification) = n.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    /** ¿[pkg] es la radio: la app FM enlazada de la pantalla o LiteDash tocando radio por internet? */
    fun isRadio(pkg: String): Boolean =
        pkg == sourceApps[SourceKind.RADIO] || (isOwn(pkg) && ownSourceNow?.invoke() == SourceKind.RADIO)

    /** La pieza Música: todo menos la radio; sigue lo que tocó el usuario ([active]), la app elegida o lo que suene. */
    val music = Lens(accepts = { !isRadio(it) }, fixed = { active ?: preferred }, remembersLast = true)

    /** La pieza Radio: solo la radio de la pantalla (FM) o la de LiteDash (internet). */
    val radio = Lens(accepts = { isRadio(it) }, fixed = { null }, remembersLast = false)

    init {
        music.onWake = ::resume
        radio.onWake = { resumeRadio() }
    }

    /** Cada pieza elige su sesión de la misma lista. */
    private fun choose(list: List<MediaController>) {
        release(list)
        // Otra app empezó a sonar: lo que LiteDash suponía de la de Bluetooth o FM ya no vale.
        val playing = list.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }.map { it.packageName }
        for (lens in listOf(music, radio)) if (lens.assumedPkg != null && playing.any { it != lens.assumedPkg }) lens.forget()
        music.choose(list)
        radio.choose(list)
    }

    /**
     * Suelta [active] cuando ya no manda: el reproductor propio se fue, o lo tocado no suena y
     * otra app empezó a sonar sola (p. ej. abrieron Spotify a mano). Nunca mientras USB arranca.
     */
    private fun release(list: List<MediaController>) {
        val pick = active ?: return
        if (ownPending) return
        val mine = list.filter { !isRadio(it.packageName) }
        if (isOwn(pick) && mine.none { isOwn(it.packageName) }) {
            active = null
            return
        }
        if (mine.none { it.packageName == pick && (it.busy() || it.playbackState?.state == PlaybackState.STATE_ERROR) } && mine.any { it.packageName != pick && it.playbackState?.state == PlaybackState.STATE_PLAYING }) active = null
    }

    /**
     * Una "vista" de las sesiones de medios: la pieza Música y la pieza Radio miran la misma
     * lista pero cada una se queda con lo suyo ([accepts]); [fixed] es la app fija (la elegida
     * en Ajustes) y [remembersLast] si guarda la última app que sonó (para que play la despierte).
     */
    @Stable
    inner class Lens internal constructor(
        private val accepts: (String) -> Boolean,
        private val fixed: () -> String?,
        private val remembersLast: Boolean,
    ) {
        var song by mutableStateOf<Song?>(null)
            private set

        /** La app que esta pieza está despertando ("Abriendo Spotify…"); null = nada. */
        var starting by mutableStateOf<String?>(null)
            internal set

        internal var controller: MediaController? = null

        /**
         * La app de la pantalla (Bluetooth, FM) a la que LiteDash le mandó play o pausa sin que
         * una sesión diga qué hace: se supone que obedeció ([assumedPlaying]) hasta que otra app
         * suene o se cambie de fuente. Así el botón pasa a pausa y la pieza muestra la fuente.
         */
        internal var assumedPkg: String? = null
        private var assumedPlaying = false
        private var assumedAt = 0L

        internal fun assume(pkg: String, playing: Boolean) {
            assumedPkg = pkg
            assumedPlaying = playing
            assumedAt = SystemClock.elapsedRealtime()
            read()
            if (song != null) starting = null
        }

        /** Lo que se supone de [pkg] (suena o no), si se le mandó play o pausa hace menos de [ASSUME_MAX_MS]. */
        private fun assumedFor(pkg: String?): Boolean? =
            assumedPlaying.takeIf { pkg != null && pkg == assumedPkg && SystemClock.elapsedRealtime() - assumedAt < ASSUME_MAX_MS }

        internal fun forget() {
            if (assumedPkg == null) return
            assumedPkg = null
            read()
        }

        /** Olvida todo lo que se supo sin sesión (avisos y supuestos): cambió la app de la pieza. */
        internal fun clear() {
            mainHandler.removeCallbacks(expire)
            heard = null
            assumedPkg = null
            starting = null
            read()
        }

        /** Lo último que avisó una app por broadcast ([BroadcastMeta]) y cuándo; respaldo sin sesión. */
        private var heard: BroadcastMeta? = null
        private var heardAt = 0L
        private val expire = Runnable {
            if (users == 0) return@Runnable
            // La estación de TopWay solo llega al cambiar: se vuelve a pedir y se lee cuando contesta
            // (si contesta, el aviso nuevo ya la dejó vigente y no parpadea).
            val tw = topWayFor(this) ?: return@Runnable read()
            tw.refresh()
            mainHandler.postDelayed({ if (users > 0) read() }, TOPWAY_ANSWER_MS)
        }

        internal fun heard(meta: BroadcastMeta) {
            heard = meta.over(heard)
            heardAt = SystemClock.elapsedRealtime()
            // Pasado el rato, deja de mostrarse aunque no llegue otro aviso.
            mainHandler.removeCallbacks(expire)
            mainHandler.postDelayed(expire, BROADCAST_FRESH_MS)
            // Una sesión con canción manda; una vacía (Bluetooth de fábrica) no dice nada: el aviso sí.
            if (controller?.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty()) return
            read()
            if (song != null) starting = null
        }

        private val callback = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) = read()
            override fun onPlaybackStateChanged(state: PlaybackState?) = read()
            override fun onSessionDestroyed() = this@MusicRepository.choose(sessions())
        }

        // Con app fija, solo esa (salvo el reproductor propio sonando). Si no: la que suena; si ninguna, la más reciente con canción
        // (Android las da en ese orden), para no quedarse con sesiones vacías.
        internal fun choose(all: List<MediaController>) {
            val list = all.filter { accepts(it.packageName) }.sortedBy { sessionRank(it.packageName) }
            val p = fixed()
            val withTitle = list.filter { it.metadata.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty() }
            // El reproductor propio sonando (o cargando) siempre se ve: lo puso a sonar el usuario.
            val own = list.firstOrNull { isOwn(it.packageName) && it.busy() }
            val chosen = if (own != null) {
                own
            } else if (p != null) {
                list.firstOrNull { it.packageName == p }
            } else {
                withTitle.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: withTitle.firstOrNull()
                    ?: list.firstOrNull()
            }
            if (chosen?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(callback)
                controller = chosen
                chosen?.registerCallback(callback, mainHandler)
            }
            read()
        }

        internal fun detach() {
            mainHandler.removeCallbacks(expire)
            controller?.unregisterCallback(callback)
            controller = null
            song = null
        }

        internal fun read() {
            val c = controller
            val m = c?.metadata
            if (c == null) {
                song = fromBroadcast() ?: fromAssumed()
                return
            }
            // La sesión de LiteDash cambia de USB a radio (o al revés): que la tome la otra pieza.
            if (!accepts(c.packageName)) return this@MusicRepository.choose(sessions())
        val state = c.playbackState
        val reported = state?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_PAUSED || it == PlaybackState.STATE_BUFFERING || it == PlaybackState.STATE_CONNECTING }
        // Una sesión que no dice si suena (con o sin título): lo que LiteDash le mandó.
        val assumed = if (reported) null else assumedFor(c.packageName)
        val playing = assumed ?: (state?.state == PlaybackState.STATE_PLAYING)
        val app = appLabel(c.packageName)
        val shown = notified(c.packageName)
        val named = m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifBlank { shown?.first.orEmpty() }
        // Sesión vacía que ni siquiera dice si suena (la abren las apps de Bluetooth y FM de fábrica
        // solo para atrapar el volante): manda lo que avisó la app o lo que LiteDash le pidió.
        if (named.isBlank() && !reported) {
            val blind = fromBroadcast() ?: fromAssumed()
            if (blind != null) {
                song = blind
                return
            }
        }
        // Sin título y en pausa = apps que solo dejan la sesión abierta: "nada sonando".
        // Sin título pero sonando = la música por Bluetooth de muchas pantallas (el
        // celular no manda la canción): se muestra con el nombre de la app y sus controles,
        // y sigue en pantalla al pausarla para poder darle play otra vez.
        if (named.isBlank() && !playing && song?.pkg != c.packageName) {
            song = null
            return
        }
        val title = named.ifBlank { app }
        starting = null
        if (remembersLast && playing && !isOwn(c.packageName) && c.packageName != avrcpPackage && c.packageName != lastSavedApp) {
            lastSavedApp = c.packageName
            store.edit().putString(KEY_LAST_APP, c.packageName).apply()
        }
        song = withCover(Song(
            title = title,
            artist = m.text(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST).ifBlank { shown?.second.orEmpty() },
            app = app,
            artwork = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = playing,
            durationMs = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            positionMs = state?.position ?: 0,
            readAt = state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = state?.playbackSpeed ?: 1f,
            pkg = c.packageName,
            seekable = (state?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L,
            actions = state?.customActions.orEmpty().map {
                MediaAction(it.action, it.name.toString(), icon(c.packageName, it.icon))
            },
            source = sourceOf(c.packageName),
            buffering = state?.state == PlaybackState.STATE_BUFFERING || state?.state == PlaybackState.STATE_CONNECTING,
            error = state?.takeIf { it.state == PlaybackState.STATE_ERROR }?.errorMessage?.toString(),
            blind = assumed != null,
        ))
        }

        /**
         * Sin sesión, lo último avisado por broadcast si es reciente: título, artista y estado
         * (sin carátula ni avance). Sin título, el nombre de la app (o "Bluetooth"), como la sesión.
         */
        private fun fromBroadcast(): Song? {
            val h = heard?.takeIf { SystemClock.elapsedRealtime() - heardAt < BROADCAST_FRESH_MS } ?: return null
            val pkg = h.pkg ?: sourceApps[SourceKind.BLUETOOTH]?.takeIf { h.bluetooth }
            val app = pkg?.let(::appLabel).orEmpty()
            // Si LiteDash le mandó play o pausa después del último aviso, eso manda; si el aviso no dice, suena.
            val sent = assumedFor(pkg ?: assumedPkg)?.takeIf { assumedAt > heardAt }
            val playing = sent ?: h.playing ?: true
            if (h.title == null && !playing) return null
            val title = h.title ?: app.ifBlank { if (h.bluetooth) context.getString(R.string.source_bluetooth) else return null }
            return withCover(Song(
                title = title,
                artist = h.artist.orEmpty(),
                app = app,
                artwork = null,
                playing = playing,
                durationMs = 0,
                positionMs = 0,
                readAt = SystemClock.elapsedRealtime(),
                speed = 1f,
                pkg = pkg.orEmpty(),
                seekable = false,
                actions = emptyList(),
                source = pkg?.let(::sourceOf) ?: SourceKind.BLUETOOTH.takeIf { h.bluetooth },
                buffering = false,
                error = null,
                blind = true,
            ))
        }

        /** Lo que LiteDash supone de la app a la que le mandó play o pausa ([assume]). */
        private fun fromAssumed(): Song? {
            val pkg = assumedPkg ?: return null
            val playing = assumedFor(pkg) ?: return null
            val kind = sourceOf(pkg)
            val app = appLabel(pkg)
            val fallback = context.getString(if (kind == SourceKind.RADIO) R.string.source_radio else R.string.source_bluetooth)
            return Song(
                title = app.ifBlank { fallback },
                artist = "",
                app = app,
                artwork = null,
                playing = playing,
                durationMs = 0,
                positionMs = 0,
                readAt = SystemClock.elapsedRealtime(),
                speed = 1f,
                pkg = pkg,
                seekable = false,
                actions = emptyList(),
                source = kind,
                buffering = false,
                error = null,
                blind = true,
            )
        }

        /**
         * Carátula de respaldo para la música por Bluetooth: si la sesión (o el aviso) no trajo
         * carátula pero sí título y artista, se toma la ya resuelta ([CoverArt.cached], en memoria)
         * o se busca en iTunes ([CoverArt.fetch], que avisa en el hilo principal solo si sigue
         * siendo la misma canción). Fuera de Bluetooth no se busca: su carátula ya llega sola.
         */
        private fun withCover(s: Song): Song {
            if (s.artwork != null || s.source != SourceKind.BLUETOOTH) return s
            val title = s.title
            val artist = s.artist
            if (title.isBlank() || artist.isBlank()) return s
            val cached = CoverArt.cached(title, artist)
            if (cached == null) {
                CoverArt.fetch(context, title, artist) { bmp ->
                    val cur = song
                    if (bmp != null && cur != null && cur.artwork == null && cur.title == title && cur.artist == artist) {
                        song = cur.copy(artwork = bmp)
                    }
                }
                return s
            }
            return s.copy(artwork = cached)
        }

        /**
         * Play o pausa; sin nada sonando, se despierta lo que toque ([onWake]). Con una sesión
         * que obedece, por la sesión. Si no (la música Bluetooth y la radio FM de muchas
         * pantallas: sin sesión, vacía o que no anuncia play), como el botón del volante pero
         * explícito: PAUSA si se cree que suena y PLAY si no (con play/pausa alternado, varias
         * apps de fábrica no hacen nada); y LiteDash recuerda que lo mandó ([assume]).
         */
        fun toggle() {
            val s = song ?: return onWake()
            topWayFor(this)?.let { tw ->
                if (!s.playing) return onWake()
                trace("topway pausa")
                mainHandler.removeCallbacks(topWayConfirm)
                return tw.releaseAudio()
            }
            val t = controller?.transportControls?.takeUnless { keysOnly(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) }
            if (!s.blind && t != null) {
                if (s.playing) return t.pause()
                if (this === music) controller?.packageName?.let(::watchPlay)
                if (controller?.packageName == avrcpPackage) ensureBluetoothSound()
                return t.play()
            }
            val play = !s.playing
            val pkg = target(this)
            if (!press(this, if (play) KeyEvent.KEYCODE_MEDIA_PLAY else KeyEvent.KEYCODE_MEDIA_PAUSE)) return onWake()
            if (pkg != null && !isOwn(pkg)) assume(pkg, play)
        }

        /**
         * Sin sesión que obedezca, como los botones del volante (a la app de la pieza o al
         * sistema de audio). En la radio FM, siguiente/anterior es lo que la app haga con esas
         * teclas (casi siempre la estación guardada que sigue o buscar la siguiente).
         */
        fun next() {
            if (carMusicBlind(this)) return resume()
            topWayFor(this)?.let { trace("topway siguiente"); return it.next() }
            controller?.transportControls?.takeUnless { song?.blind == true || keysOnly(PlaybackState.ACTION_SKIP_TO_NEXT) }?.skipToNext() ?: press(this, KeyEvent.KEYCODE_MEDIA_NEXT)
        }

        fun previous() {
            if (carMusicBlind(this)) return resume()
            topWayFor(this)?.let { trace("topway anterior"); return it.previous() }
            controller?.transportControls?.takeUnless { song?.blind == true || keysOnly(PlaybackState.ACTION_SKIP_TO_PREVIOUS) }?.skipToPrevious() ?: press(this, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }

        /**
         * La radio FM tiene sesión pero no anuncia [action] (varias FM de pantalla publican la
         * sesión solo para mostrar la estación): mejor la tecla a su receptor, como el volante.
         * La música no: sus apps sí atienden la sesión.
         */
        private fun keysOnly(action: Long): Boolean {
            val c = controller ?: return false
            if (this !== radio || isOwn(c.packageName)) return false
            return (c.playbackState?.actions ?: 0L) and action == 0L && canKey(this)
        }
        fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
        fun action(a: MediaAction) = controller?.transportControls?.sendCustomAction(a.id, null)

        /** Qué hacer con play sin sesión: la música despierta la última app; la radio, la FM o la de internet. */
        internal var onWake: () -> Unit = {}
    }

    /** La fuente a la que está enlazada [pkg] (o la que toca LiteDash); null = una app normal. */
    fun sourceOf(pkg: String): SourceKind? = when {
        isOwn(pkg) -> ownSourceNow?.invoke()
        pkg == avrcpPackage -> SourceKind.BLUETOOTH
        else -> sourceApps.entries.firstOrNull { it.value == pkg }?.key
    }

    /** Paquete del servicio AVRCP del Bluetooth de Android; null si la pantalla no lo trae. */
    private val avrcpPackage: String? get() = avrcp?.service?.packageName

    private fun appLabel(pkg: String) = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault("")
    }

    // Los íconos de las acciones viven en los recursos de la otra app; se leen una vez.
    private fun icon(pkg: String, id: Int): Bitmap? = icons.getOrPut("$pkg:$id") {
        runCatching {
            val d = context.packageManager.getResourcesForApplication(pkg).getDrawable(id, null)
            val side = (32 * context.resources.displayMetrics.density).toInt()
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bmp ->
                d.setBounds(0, 0, side, side)
                d.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    // Atajos de la pieza Música (el volante y la pieza usan estos).
    fun toggle() = music.toggle()
    fun next() = music.next()
    fun previous() = music.previous()
    fun seekTo(ms: Long) = music.seekTo(ms)
    fun action(a: MediaAction) = music.action(a)

    /** Nombre de la app elegida en Ajustes ("" si no hay). */
    fun preferredName(): String = preferred?.let(::appLabel).orEmpty()

    /** Nombre de la app [pkg] como lo muestra Android ("" si no está). */
    fun appName(pkg: String): String = appLabel(pkg)

    /** Nombre de la app enlazada a [kind] ("" si no hay). */
    fun sourceName(kind: SourceKind): String = sourceApps[kind]?.let(::appLabel).orEmpty()

    /** Elige tu app de música (o ninguna con null) y la guarda. */
    fun choose(pkg: String?) {
        preferred = pkg
        onPreferredChanged?.invoke(pkg)
    }

    /**
     * Tocaron la app elegida o Bluetooth en la música en grande: se calla la USB propia (y se
     * cancela si iba arrancando) y suena [pkg]. La app elegida en Ajustes no cambia.
     */
    fun switchTo(pkg: String) {
        ownToken++
        ownPending = false
        ownPause?.invoke()
        if (music.assumedPkg != pkg) music.forget()
        active = pkg
        if (users > 0) choose(sessions())
        resume()
    }

    /** La app a la que va el play (o una tecla) de [lens] cuando no hay sesión que lo reciba. */
    private fun target(lens: Lens): String? =
        // Lo que se supo sin sesión (aviso o supuesto) manda: la sesión elegida puede ser otra app vacía.
        lens.song?.takeIf { it.blind }?.pkg?.ifEmpty { null } ?: lens.controller?.packageName ?: lens.song?.pkg?.ifEmpty { null }
            ?: if (lens === radio) appFor(SourceKind.RADIO) else active ?: preferred ?: store.getString(KEY_LAST_APP, null)

    /**
     * Que suene lo tocado, la app elegida o, sin ninguna, la última que sonó. Con una canción
     * cargada (en pausa), play directo. Si no, se despierta la app ([wake]): su receptor del
     * botón de play o su sesión, y su servicio de música, como Android Auto; sin ninguno, el
     * botón por el sistema de audio, que Android lleva a la última app de música. Si no suena,
     * [watchPlay] abre la app, regresa y le vuelve a dar play. Mientras, [starting] dice cuál se
     * está abriendo.
     */
    fun resume() {
        val pkg = active ?: preferred ?: music.controller?.packageName ?: store.getString(KEY_LAST_APP, null)
        val session = music.controller?.takeIf { song != null && (pkg == null || it.packageName == pkg) }
        if (session != null) {
            watchPlay(session.packageName)
            if (session.packageName == avrcpPackage) ensureBluetoothSound()
            return session.transportControls.play()
        }
        if (pkg == null) return systemPlay()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.USB) {} ?: Unit
        // La Música de fábrica del Okavango no publica sesión, receptor ni servicio de música: no hay cómo
        // darle play ni leer su canción desde fuera. Se abre su pantalla (sin abrir y regresar en bucle).
        if (pkg == CAR_MUSIC_APP) {
            trace("música del carro: sin control externo, se abre $pkg")
            music.starting = null
            open(pkg)
            return
        }
        watchPlay(pkg)
        wake(pkg, music)
    }

    /**
     * Apps como Spotify, dormidas en segundo plano, a veces dicen que suenan y no suenan hasta abrirlas.
     * Tras un play a una app de música normal (no Bluetooth ni FM: esas suenan por fuera de Android), si en
     * [PLAY_CHECK_MS] Android no tiene audio y la pieza no está en pausa, se abre la app, se regresa al
     * inicio y se le vuelve a dar play.
     */
    private fun watchPlay(pkg: String) {
        if (isOwn(pkg) || sourceOf(pkg) != null) return
        playPkg = pkg
        mainHandler.removeCallbacks(playCheck)
        mainHandler.removeCallbacks(returnAndPlay)
        mainHandler.postDelayed(playCheck, PLAY_CHECK_MS)
    }

    private var playPkg: String? = null
    private val playCheck = Runnable {
        val pkg = playPkg ?: return@Runnable
        // En pausa = la pausaron (solo si la pieza muestra esa misma app).
        if (users == 0 || musicActive() || music.song?.takeIf { it.pkg == pkg }?.playing == false) return@Runnable trace("play $pkg: audio=${yes(musicActive())}")
        music.starting = null
        val over = open(pkg)
        trace("play $pkg: no suena, se abre${if (over) " y se regresa" else ""}")
        if (!over) return@Runnable
        mainHandler.postDelayed(returnAndPlay, PLAY_RETURN_MS)
    }
    private val returnAndPlay = Runnable {
        val pkg = playPkg ?: return@Runnable
        returnHome?.invoke()
        if (musicActive()) return@Runnable trace("regreso: $pkg ya suena")
        val session = sessions().firstOrNull { it.packageName == pkg }
        val by = when {
            session != null -> runCatching { session.transportControls.play() }.let { "sesión" }
            buttonPlay(pkg) -> "receptor"
            else -> "nada"
        }
        trace("regreso: play otra vez a $pkg por $by")
    }

    /**
     * Play de la pieza Radio: sigue la radio que suena; si no, despierta la FM de la pantalla
     * o la radio por internet de LiteDash. Sin con qué (sin app enlazada ni estación), [onIdle]:
     * la pieza abre sus ajustes o la lista de estaciones.
     */
    fun resumeRadio(onIdle: () -> Unit = {}) {
        val session = radio.controller?.takeIf { radio.song != null }
        if (session != null) return session.transportControls.play()
        val pkg = appFor(SourceKind.RADIO) ?: return onIdle()
        if (isOwn(pkg)) return ownPlay?.invoke(SourceKind.RADIO) { ok -> if (!ok) onIdle() } ?: Unit
        wake(pkg, radio)
    }

    /** Abre la app de radio de la pantalla (si hay una enlazada). */
    fun openRadioApp() {
        sourceApps[SourceKind.RADIO]?.let(::open)
    }

    /** Abre la app de Bluetooth enlazada (para vincular en pantallas con chip propio). */
    fun openBluetoothApp() {
        sourceApps[SourceKind.BLUETOOTH]?.let(::open)
    }

    /**
     * Despierta a [pkg] para que suene en [lens], por un solo camino para que no le llegue play dos
     * veces (varias apps de fábrica lo toman como play/pausa): el botón de play a su receptor o, si
     * no tiene, a su sesión aunque esté vacía; y su servicio de música (varias apps de Bluetooth
     * tienen servicio pero no hacen caso por ahí). Sin nada de eso, el botón del sistema (nunca en
     * la radio: llevaría a Spotify). Las de fábrica no dicen si suenan: se da por hecho que sí
     * ([Lens.assume]). Solo se abre la app si no hubo a quién mandarle el botón.
     */
    private fun wake(pkg: String, lens: Lens) {
        lens.starting = appLabel(pkg)
        val radioLens = lens === radio
        val button = buttonPlay(pkg)
        val session = !button && sessions().firstOrNull { it.packageName == pkg }?.also { runCatching { it.transportControls.play() } } != null
        val service = connectAndPlay(pkg, fallback = !radioLens && !button && !session)
        trace("despertar $pkg: servicio=${yes(service)} receptor=${yes(button)} sesión=${yes(session)}")
        val none = !service && !button && !session
        if (none && radioLens) return topWayFor(lens)?.let { playTopWay(it, pkg, lens) } ?: openAndReturn(pkg, lens)
        if (none) systemPlay()
        if ((button || session) && (sourceOf(pkg) != null || !service)) lens.assume(pkg, true)
        // Las apps de música normales las abre [watchPlay] (y regresa); aquí solo las de la pantalla.
        val opens = none && sourceOf(pkg) != null
        mainHandler.postDelayed({
            if (lens.song == null && lens.starting != null && opens) open(pkg)
            lens.starting = null
        }, WAKE_TIMEOUT_MS)
    }

    /**
     * Radios de fábrica que no dejan nada por fuera (p. ej. las TopWay, `com.tw.radio`): solo suenan
     * al abrirlas. Se abre la radio y, si quedó encima de LiteDash, se regresa al inicio en
     * [RADIO_RETURN_MS] (Android deja a la app volver al frente unos segundos después de abrir otra).
     * Lo que pasa con el audio queda en el registro, para los reportes.
     */
    private fun openAndReturn(pkg: String, lens: Lens) {
        lens.starting = null
        val over = open(pkg)
        trace("radio sin control: se abre $pkg${if (over) " y se regresa" else ""}")
        if (!over) return
        // Un solo regreso pendiente aunque se toque play dos veces.
        mainHandler.removeCallbacks(returnFromRadio)
        mainHandler.postDelayed(returnFromRadio, RADIO_RETURN_MS)
    }

    /**
     * FM TopWay: play sin abrir su app (se le pide el audio al sintonizador). Si en [TOPWAY_ANSWER_MS]
     * el sintonizador no dice que tiene el audio, se abre la radio como antes ([openAndReturn]).
     */
    private fun playTopWay(tw: TopWayRadio, pkg: String, lens: Lens) {
        val asked = SystemClock.elapsedRealtime()
        trace("topway play sin abrir")
        tw.takeAudio()
        mainHandler.removeCallbacks(topWayConfirm)
        topWayConfirm = Runnable {
            lens.starting = null
            // Se cerró (cambiaron de radio o LiteDash se fue): ya no toca.
            if (topWay !== tw || !tw.open) return@Runnable
            if (tw.audioAt >= asked) return@Runnable trace("topway suena sin abrir")
            trace("topway no confirmó: se abre $pkg")
            openAndReturn(pkg, lens)
        }
        mainHandler.postDelayed(topWayConfirm, TOPWAY_ANSWER_MS)
    }

    /** La confirmación pendiente del play de TopWay; se cancela al pausar o cambiar de radio. */
    private var topWayConfirm = Runnable {}

    private val radioCheck = Runnable { trace("radio tras regresar: audio=${yes(musicActive())}") }
    private val returnFromRadio = Runnable {
        trace("regreso a LiteDash: audio=${yes(musicActive())}")
        returnHome?.invoke()
        topWayFor(radio)?.started()
        mainHandler.postDelayed(radioCheck, RADIO_RETURN_MS)
    }

    /**
     * LiteDash volvió al frente por su cuenta (el usuario regresó o abrió otra app desde el inicio):
     * ya no se regresa solo. No va en [stop]: al abrir la radio, LiteDash se detiene y eso lo cancelaría.
     */
    fun cancelReturn() {
        mainHandler.removeCallbacks(returnFromRadio)
        mainHandler.removeCallbacks(returnAndPlay)
    }

    private fun musicActive() = ContextCompat.getSystemService(context, AudioManager::class.java)!!.isMusicActive

    /**
     * FYT (pantallas cuyo MCU elige la fuente de audio): dar play al celular por AVRCP no basta,
     * el MCU tiene que pasar la fuente a Bluetooth. Si al poco rato el celular dice que suena pero
     * Android no tiene audio, se alterna la fuente con su servicio y se vuelve a dar play. No se
     * puede verificar sin la pantalla: todo queda en el registro para los reportes.
     */
    private fun ensureBluetoothSound() {
        if (!isFyt()) return
        mainHandler.postDelayed({
            if (users == 0 || bluetoothDevice == null) return@postDelayed
            val c = avrcpController ?: return@postDelayed
            if (c.playbackState?.state != PlaybackState.STATE_PLAYING || musicActive()) return@postDelayed
            trace("fyt: el celular suena por AVRCP pero no hay audio; se alterna la fuente")
            runCatching {
                context.startService(Intent("com.syu.bt.byav.widgetPlayPause").setClassName("com.syu.bt", "com.syu.bt.broadcast.BtavPlayPauseService"))
            }
            // El servicio alterna la fuente; el play va poco después (no dos veces en <1.5 s).
            mainHandler.postDelayed({ runCatching { c.transportControls.play() } }, 700)
        }, PLAY_CHECK_MS)
    }

    /** Es una pantalla FYT (tiene la app de Bluetooth de su MCU). */
    private fun isFyt() = runCatching { context.packageManager.getPackageInfo("com.syu.bt", 0) }.isSuccess

    /** Trae LiteDash al frente si otra app lo tapa (lo pone la app). */
    var returnHome: (() -> Unit)? = null

    /**
     * Una tecla multimedia a la app de [lens], como el volante, por un solo camino: a su receptor o,
     * sin receptor, a su sesión aunque esté vacía. Sin ninguno, al sistema de audio, salvo la radio y
     * el reproductor propio: ahí el sistema la llevaría a otra app (y podría arrancar Spotify).
     * False = no hubo a quién.
     */
    /** ¿La pieza apunta a la Música de fábrica del carro sin sesión? Ahí no hay cómo mandar teclas. */
    private fun carMusicBlind(lens: Lens): Boolean =
        lens !== radio && target(lens) == CAR_MUSIC_APP && lens.controller?.packageName != CAR_MUSIC_APP

    private fun press(lens: Lens, code: Int): Boolean {
        val pkg = target(lens)
        val receiver = keyReceiver(pkg)
        val session = if (receiver != null) null else pkg?.takeUnless(::isOwn)?.let { p -> lens.controller?.takeIf { it.packageName == p } ?: sessions().firstOrNull { it.packageName == p } }
        trace("tecla ${KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_MEDIA_")} $pkg: receptor=${yes(receiver != null)} sesión=${yes(session != null)}")
        when {
            receiver != null -> sendMediaKey(context, code, receiver)
            session != null -> for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) runCatching { session.dispatchMediaButtonEvent(KeyEvent(action, code)) }
            lens === radio || pkg != null && isOwn(pkg) -> return false
            else -> sendMediaKey(context, code)
        }
        return true
    }

    /**
     * ¿Hay a quién mandarle una tecla de [lens]? La música siempre (al sistema); la radio, a su receptor
     * (si no tiene, su sesión ya recibe lo mismo por sus controles).
     */
    private fun canKey(lens: Lens): Boolean = lens !== radio || keyReceiver(target(lens)) != null

    /**
     * El receptor del botón de play de [pkg] (nunca el de LiteDash: su reproductor tiene sesión).
     * Se pregunta una vez por app (es una llamada al sistema y la pieza lo consulta al pintarse).
     */
    private fun keyReceiver(pkg: String?): ComponentName? =
        pkg?.takeUnless(::isOwn)?.let { p -> receivers.getOrPut(p) { mediaButtonReceivers(p).firstOrNull() } }
    private val receivers = HashMap<String, ComponentName?>()

    /** La pieza Radio sin sesión pero con una FM de la pantalla que atiende teclas: lleva anterior/siguiente. */
    fun radioHasKeys(): Boolean {
        val pkg = appFor(SourceKind.RADIO) ?: return false
        return !isOwn(pkg) && keyReceiver(pkg) != null
    }

    /** El servicio de música (MediaBrowserService) de [pkg]; null si no ofrece. */
    private fun browserService(pkg: String) = runCatching {
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(pkg), 0).firstOrNull()?.serviceInfo
    }.getOrNull()

    /**
     * Se conecta al servicio de música de [pkg] y le pide play; si no lo acepta y [fallback], el botón
     * del sistema. False = no tiene servicio.
     */
    private fun connectAndPlay(pkg: String, fallback: Boolean): Boolean {
        val service = browserService(pkg) ?: return false
        lateinit var browser: MediaBrowser
        browser = MediaBrowser(context, ComponentName(service.packageName, service.name), object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                runCatching { MediaController(context, browser.sessionToken).transportControls.play() }
                browser.disconnect()
            }

            override fun onConnectionFailed() {
                if (fallback) systemPlay()
            }
        }, null)
        // Si el servicio nunca contesta, la conexión no se queda colgada para siempre.
        mainHandler.postDelayed({ runCatching { browser.disconnect() } }, WAKE_TIMEOUT_MS)
        return runCatching { browser.connect() }.isSuccess
    }

    /** El botón de play directo al receptor de [pkg] (las apps de Bluetooth/radio lo atienden así). */
    private fun buttonPlay(pkg: String): Boolean {
        val receiver = keyReceiver(pkg) ?: return false
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY, receiver)
        return true
    }

    /** El botón de play como el del volante: Android lo lleva a la última app de música. */
    private fun systemPlay() = sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** Receptores del botón de play de [pkg] (o de todas las apps con null). */
    private fun mediaButtonReceivers(pkg: String? = null): List<ComponentName> = runCatching {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply { if (pkg != null) setPackage(pkg) }
        context.packageManager.queryBroadcastReceivers(intent, 0)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }.getOrDefault(emptyList())

    /**
     * Apps de música instaladas: las que ofrecen su biblioteca a Android
     * (Spotify, Amazon Music, YouTube Music, Deezer, el reproductor de la pantalla…).
     */
    fun musicApps(): Set<String> = runCatching {
        context.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
            .filter { it != context.packageName && it !in NOT_MUSIC_APPS }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Apps del sistema de la pantalla que atienden el botón de play: ahí están sus
     * reproductores de USB, Bluetooth y radio (para adivinar las fuentes en Ajustes).
     */
    fun systemPlayers(): Set<String> = runCatching {
        val pm = context.packageManager
        mediaButtonReceivers().map { it.packageName }.distinct()
            .filter { pkg -> pkg != context.packageName && runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false) }
            .toSet()
    }.getOrDefault(emptySet())

    /**
     * Adivina, una vez, qué app de la pantalla reproduce cada fuente: entre las del sistema
     * que atienden el botón de play y se pueden abrir, la que lleva "usb", "bt"/"bluetooth"
     * o "radio"/"fm" en el paquete o en el nombre. Solo si hay una sola candidata (si hay
     * dudas, mejor nada: el usuario la elige en Ajustes). Corre en un hilo aparte (son
     * varias consultas al sistema) y guarda el resultado en el hilo principal.
     */
    private fun guessSources() {
        val pm = context.packageManager
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        val candidates = systemPlayers().filter { it in launchable }.map { pkg -> pkg to tokens(pkg, appLabel(pkg)) }
        val found = SOURCE_WORDS.mapNotNull { (kind, words) ->
            candidates.filter { (_, t) -> words.any { it in t } }.singleOrNull()?.let { kind to it.first }
        }.toMap().toMutableMap()
        if (SourceKind.RADIO !in found) guessRadio(launchable)?.let { found[SourceKind.RADIO] = it }
        // Una sola vez: la Radio de fábrica del Okavango manda sobre una radio adivinada antes (otra app).
        val carRadio = CAR_RADIO_APP.takeIf { it in launchable && !store.getBoolean(KEY_CAR_RADIO_LINKED, false) }
        if (carRadio != null) found[SourceKind.RADIO] = carRadio
        if (found.isEmpty()) return
        mainHandler.post {
            for ((kind, pkg) in found) if (kind !in sourceApps || (pkg == carRadio && sourceApps[kind] != pkg)) setSource(kind, pkg)
            if (carRadio != null) store.edit().putBoolean(KEY_CAR_RADIO_LINKED, true).apply()
        }
    }

    /**
     * La radio de la pantalla aunque no atienda el botón de play: primero las de Geely conocidas;
     * si no, la única app del sistema que se pueda abrir con "radio" (o "fm") en el paquete o el nombre.
     */
    private fun guessRadio(launchable: Set<String>): String? {
        GEELY_RADIO_APPS.firstOrNull { it in launchable }?.let { return it }
        val pm = context.packageManager
        val system = launchable.filter { pkg ->
            pkg != context.packageName && runCatching { pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)
        }
        val withRadio = system.filter { "radio" in tokens(it, appLabel(it)) || it.lowercase().contains("radio") }
        withRadio.singleOrNull()?.let { return it }
        // Varias: la que lo dice en el paquete (no solo en el nombre visible).
        withRadio.filter { it.lowercase().contains("radio") }.singleOrNull()?.let { return it }
        if (withRadio.isNotEmpty()) return null
        return system.filter { "fm" in tokens(it, appLabel(it)) }.singleOrNull()
    }

    /** Palabras sueltas del paquete y del nombre, en minúsculas ("com.syu.bt" → com, syu, bt). */
    private fun tokens(pkg: String, label: String): Set<String> =
        (pkg.split('.') + label.split(Regex("[^\\p{L}\\p{N}]+"))).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    // Lo último que pasó con las teclas, los avisos y el despertar (solo en memoria, para los reportes).
    private val traceLog = ArrayDeque<String>()

    private fun trace(event: String) {
        traceLog.addLast("${SystemClock.elapsedRealtime() / 1000 % 100_000}s $event")
        while (traceLog.size > TRACE_SIZE) traceLog.removeFirst()
    }

    private fun yes(v: Boolean) = if (v) "sí" else "no"

    /** Para los reportes: qué Bluetooth trae la pantalla y qué apps de marca, para saber su familia. */
    private fun describeBluetooth(): String = buildString {
        val adapter = runCatching { ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter }.getOrNull()
        val service = bluetoothService
        appendLine("Bluetooth de Android: AVRCP=${service?.let { "${it.packageName}/${it.name}" } ?: "no"}; adaptador=${adapter?.let { if (it.isEnabled) "encendido" else "apagado" } ?: "no"}")
        val brand = BRAND_BT_APPS.filter { runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess }
        appendLine("Apps de Bluetooth de marca: ${brand.joinToString().ifBlank { "ninguna" }}")
    }

    /**
     * Pistas dentro de la app [pkg] (se lee su APK, que Android deja leer): textos de su manifiesto y de su
     * código que parecen acciones de música ("...ACTION.NEXT", "com.neusoft...play"). Es lento: fuera del hilo principal.
     */
    fun apkHints(pkg: String = preferred ?: CAR_MUSIC_APP): String = runCatching {
        val path = context.packageManager.getApplicationInfo(pkg, 0).sourceDir
        val found = java.util.TreeSet<String>()
        val interesting = Regex("(?i)(neusoft|shockwave|mutimedia|mengjia|action|broadcast|media_button|android\\.intent)[\\w.$:/-]{3,}")
        val keywords = Regex("(?i)(play|pause|next|prev|music|media|source|usb|song|track|key|audio)")
        java.util.zip.ZipFile(path).use { zip ->
            for (name in listOf("AndroidManifest.xml", "classes.dex", "classes2.dex")) {
                val entry = zip.getEntry(name) ?: continue
                var bytes = zip.getInputStream(entry).use { it.readBytes() }
                // El manifiesto guarda los textos en UTF-16: se quitan los ceros para leerlos como ASCII.
                if (name.endsWith(".xml")) bytes = bytes.filter { it != 0.toByte() }.toByteArray()
                val text = String(bytes, Charsets.ISO_8859_1)
                for (m in interesting.findAll(text)) {
                    val v = m.value
                    if (v.length in 8..90 && keywords.containsMatchIn(v) && !v.startsWith("androidx") && !v.contains("Landroid")) found += v
                }
            }
        }
        if (found.isEmpty()) "Sin pistas en $pkg" else "Pistas en $pkg (${found.size}):\n" + found.take(70).joinToString("\n") { "  $it" }
    }.getOrElse { "No se pudo leer la app: ${it.javaClass.simpleName}" }

    /**
     * Para ver en pantalla (Ajustes → Música → Diagnóstico): lo mismo que [describe] y los servicios y
     * receptores de la app de música elegida, para saber por dónde se le puede hablar.
     */
    fun diagnostic(): String = buildString {
        append(describe())
        val pkg = preferred ?: CAR_MUSIC_APP
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS)
            appendLine("Componentes de $pkg:")
            info.services.orEmpty().forEach { appendLine("  servicio ${it.name.substringAfterLast('.')}${if (it.exported) " (público)" else ""}") }
            info.receivers.orEmpty().forEach { appendLine("  receptor ${it.name.substringAfterLast('.')}${if (it.exported) " (público)" else ""}") }
        }.onFailure { appendLine("Componentes de $pkg: no se pudieron leer") }
    }

    /**
     * Lo que LiteDash ve de la música en esta pantalla, para los reportes: las fuentes enlazadas
     * (con su receptor, servicio y aviso), cada sesión con su estado, acciones y si trae canción,
     * qué suponen las piezas, si Android tiene audio sonando y lo último que pasó. Sin nombres
     * de canciones ni del celular.
     */
    fun describe(): String = buildString {
        // Solo apps del sistema y las que el usuario enlazó o eligió: no la lista de todo lo instalado.
        val buttons = mediaButtonReceivers().map { it.packageName }.distinct().filter(::relevant)
        val browsers = musicApps().filter(::relevant)
        appendLine("Música elegida: ${preferred ?: "ninguna"}")
        appendLine("Fuentes: ${SourceKind.entries.joinToString { "${it.name.lowercase()}=${appFor(it) ?: "-"}" }}")
        val notes = sourceApps.values.toSet().let { apps -> NotificationService.active().filter { it.packageName in apps } }
        for ((kind, pkg) in sourceApps) {
            val own = notes.filter { it.packageName == pkg }
            appendLine(
                "  ${kind.name.lowercase()} $pkg: receptor=${yes(keyReceiver(pkg) != null)} servicio=${yes(browserService(pkg) != null)} " +
                    "avisos=${own.size} música=${yes(own.any { it.isMedia() })}",
            )
        }
        appendLine("Bluetooth: ${if (bluetoothDevice != null) "celular conectado" else "-"}; USB: ${if (usbDrive != null) "memoria conectada" else "-"}")
        append(describeBluetooth())
        appendLine("Apps de música: ${browsers.joinToString().ifBlank { "ninguna" }}")
        appendLine("Botón de play: ${buttons.joinToString().ifBlank { "ninguna" }}")
        if (!hasPermission()) appendLine("Sesiones: sin acceso a notificaciones")
        else {
            val list = sessions()
            appendLine("Sesiones: ${if (list.isEmpty()) "ninguna" else ""}")
            for (c in list) {
                val m = c.metadata
                appendLine(
                    "  ${c.packageName} estado=${c.playbackState?.state ?: "-"} acciones=${actionNames(c.playbackState?.actions ?: 0L)} " +
                        "título=${yes(m.text(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE).isNotEmpty())} " +
                        "artista=${yes(m.text(MediaMetadata.METADATA_KEY_ARTIST).isNotEmpty())}",
                )
            }
        }
        for ((name, lens) in listOf("Pieza música" to music, "Pieza radio" to radio)) {
            val s = lens.song
            appendLine(
                "$name: ${s?.let { "${it.pkg.ifEmpty { "?" }} ${if (it.playing) "suena" else "en pausa"}${if (it.blind) " (supuesto)" else ""}" } ?: "nada"}" +
                    (lens.assumedPkg?.let { "; supone $it" } ?: ""),
            )
        }
        appendLine("Audio sonando en Android: ${yes(ContextCompat.getSystemService(context, AudioManager::class.java)!!.isMusicActive)}")
        if (TopWayRadio.available) {
            val tw = topWay
            appendLine(
                "TopWay: abierto=${yes(tw?.open == true)} estación=${tw?.station ?: "-"} " +
                    "nombre=${tw?.name?.ifEmpty { "-" } ?: "-"} audio=${tw?.on?.let(::yes) ?: "?"} " +
                    "servicioXT=${yes(xtService())} se cerró=${yes(store.getBoolean(KEY_TOPWAY_OPENING, false))}",
            )
        }
        append("Últimos eventos:")
        if (traceLog.isEmpty()) append(" ninguno") else traceLog.forEach { append("\n  ").append(it) }
    }

    /** Fuentes enlazadas sin receptor ni servicio: no hay cómo hablarles (el reporte las describe con [AppProbe]). */
    fun silentSources(): List<String> = sourceApps.values.distinct().filter { !isOwn(it) && isSilent(it) }

    /** El servicio de comandos de TopWay (otra vía para la radio, aún sin usar): para los reportes. */
    private fun xtService() = runCatching {
        context.packageManager.queryIntentServices(Intent(TOPWAY_XT_BIND), 0).isNotEmpty()
    }.getOrDefault(false)

    /** ¿[pkg] va en el reporte? Las del sistema, las enlazadas y la elegida. */
    private fun relevant(pkg: String): Boolean =
        pkg == preferred || pkg in sourceApps.values ||
            runCatching { context.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 }.getOrDefault(false)

    private fun actionNames(actions: Long): String = listOf(
        PlaybackState.ACTION_PLAY to "play", PlaybackState.ACTION_PAUSE to "pause", PlaybackState.ACTION_PLAY_PAUSE to "play_pause",
        PlaybackState.ACTION_SKIP_TO_NEXT to "next", PlaybackState.ACTION_SKIP_TO_PREVIOUS to "prev", PlaybackState.ACTION_STOP to "stop",
        PlaybackState.ACTION_SEEK_TO to "seek",
    ).filter { actions and it.first != 0L }.joinToString(",", "[", "]") { it.second }

    /** Abre la app que está sonando o, si no hay, la tocada o la elegida en Ajustes. */
    fun openApp() {
        open(music.controller?.packageName ?: song?.pkg?.ifEmpty { null } ?: active ?: preferred ?: return)
    }

    /** Si devuelve true, ya abrió la app a su manera (p. ej. en pantalla dividida). */
    var opener: ((Context, Intent, String) -> Boolean)? = null

    /** True = se abrió encima de LiteDash (no a un lado en pantalla dividida). */
    private fun open(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
        if (opener?.invoke(context, intent, pkg) == true) return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/** Sonando o cargando. */
private fun MediaController.busy(): Boolean = when (playbackState?.state) {
    PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> true
    else -> false
}

/** Los extras de un aviso como mapa (un extra que no se puede leer deja el mapa vacío). */
@Suppress("DEPRECATION") // Bundle.get: los avisos traen tipos variados
private fun Bundle?.toMap(): Map<String, Any?> {
    val b = this ?: return emptyMap()
    return runCatching { b.keySet().associateWith { b.get(it) } }.getOrDefault(emptyMap())
}

/** El primer texto no vacío de [keys] (algunas apps mandan el título vacío y el bueno en otro campo). */
private fun MediaMetadata?.text(vararg keys: String): String =
    keys.firstNotNullOfOrNull { this?.getString(it)?.takeIf(String::isNotBlank) }.orEmpty()

/** La sesión que comparte un aviso de música; null si no trae. */
private fun StatusBarNotification.sessionToken(): MediaSession.Token? =
    BundleCompat.getParcelable(notification.extras, Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)

/** Aviso de música: con su sesión o con el estilo multimedia ("android.template"; la constante es de la API 24). */
private fun StatusBarNotification.isMedia(): Boolean {
    val extras = notification.extras
    return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || extras.getString("android.template").orEmpty().endsWith("MediaStyle")
}
