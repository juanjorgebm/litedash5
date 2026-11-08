package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.AppProbe
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.music.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.core.content.ContextCompat

/*
 * "Enviar un reporte" (Ajustes → Sistema → Tu pantalla): el usuario elige con qué hay un problema,
 * cuál (si aplica) y qué pasa, y se escribe `reports/{id}` en Firestore por REST con eso, su nota
 * y los datos técnicos de la pantalla ([systemInfo]). Solo al tocar Enviar; nadie los lee desde la app.
 */

/** Variantes de un módulo; [key] es lo que se guarda. */
private enum class ReportDetail(val key: String, val title: Int) {
    USB_PLAYER("usb", R.string.report_music_usb),
    HEAD_BLUETOOTH("bluetooth", R.string.report_music_bt),
    MUSIC_APP("app", R.string.report_music_app),
    FM("fm", R.string.report_radio_fm),
    INTERNET("internet", R.string.report_radio_internet),
    USB_RECEIVER("usb", R.string.tires_source_usb),
    BLUETOOTH("bluetooth", R.string.sys_bluetooth),
    WIFI("wifi", R.string.sys_wifi),
    USB("usb", com.irvingguerra.litedash.feature.music.R.string.source_usb),
}

/** Qué pasa; [key] es lo que se guarda. */
private enum class ReportProblem(val key: String, val title: Int) {
    SILENT("silent", R.string.report_silent),
    NO_INFO("no_info", R.string.report_no_info),
    BUTTONS("buttons", R.string.report_buttons),
    BROKEN("broken", R.string.report_broken),
    LOOKS_WRONG("looks_wrong", R.string.report_looks_wrong),
    CRASH("crash", R.string.report_crash),
    OTHER("other", R.string.reminder_other),
}

private val AUDIO_PROBLEMS = listOf(ReportProblem.SILENT, ReportProblem.NO_INFO, ReportProblem.BUTTONS, ReportProblem.OTHER)
private val GENERIC_PROBLEMS = listOf(ReportProblem.BROKEN, ReportProblem.LOOKS_WRONG, ReportProblem.CRASH, ReportProblem.OTHER)

/** Con qué hay un problema; [audio] = Música y Radio (sus problemas y la ayuda de ponerla a sonar). */
private enum class ReportModule(val key: String, val title: Int, val details: List<ReportDetail> = emptyList(), val audio: Boolean = false) {
    MUSIC("music", R.string.tile_music, listOf(ReportDetail.USB_PLAYER, ReportDetail.HEAD_BLUETOOTH, ReportDetail.MUSIC_APP), audio = true),
    RADIO("radio", R.string.tile_radio, listOf(ReportDetail.FM, ReportDetail.INTERNET), audio = true),
    CAR("car", R.string.tile_car),
    TIRES("tires", R.string.tires, listOf(ReportDetail.USB_RECEIVER, ReportDetail.BLUETOOTH)),
    WORKSHOP("workshop", R.string.report_workshop, listOf(ReportDetail.BLUETOOTH, ReportDetail.WIFI, ReportDetail.USB)),
    MAP("map", R.string.templates_tab_map),
    SPLIT("split", R.string.tab_split),
    PROJECTION("projection", R.string.report_projection),
    LIGHTS("lights", R.string.report_lights),
    UPDATES("updates", R.string.tab_updates),
    OTHER("other", R.string.reminder_other),
    ;

    val problems get() = if (audio) AUDIO_PROBLEMS else GENERIC_PROBLEMS
}

private sealed interface ReportState {
    data object Idle : ReportState
    data object Sending : ReportState
    data object Error : ReportState
    data object Wait : ReportState
    data class Sent(val code: String) : ReportState
}

private const val NOTE_MAX = 500
private const val INFO_MAX = 20_000
private const val REPORT_GAP_MS = 60_000L

/** Cuándo se mandó el último (reloj desde el arranque): a lo más uno por minuto. */
private var lastReport = 0L

/** La tarjeta con el flujo por pasos; siempre se puede regresar al anterior. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendReport(music: MusicRepository, pro: ProAccess, dashboard: DashboardState) {
    val c = LocalColors.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var module by remember { mutableStateOf<ReportModule?>(null) }
    var detail by remember { mutableStateOf<ReportDetail?>(null) }
    var problem by remember { mutableStateOf<ReportProblem?>(null) }
    var note by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<ReportState>(ReportState.Idle) }

    SettingsCard(stringResource(R.string.report_title), stringResource(R.string.report_help)) {
        val m = module
        val sent = state as? ReportState.Sent
        when {
            sent != null -> {
                Label(stringResource(R.string.report_thanks), Typography.subtitle)
                Label(stringResource(R.string.report_code, sent.code), Typography.body, c.textMuted)
                Button(stringResource(R.string.done), {
                    module = null; detail = null; problem = null; note = ""; state = ReportState.Idle
                }, primary = true)
            }
            m == null -> {
                Label(stringResource(R.string.report_what), Typography.label, c.textMuted)
                Choices(ReportModule.entries.map { it to it.title }) { module = it }
            }
            m.details.isNotEmpty() && detail == null -> {
                BackRow(listOf(m.title)) { module = null }
                Label(stringResource(R.string.report_which), Typography.label, c.textMuted)
                Choices(m.details.map { it to it.title }) { detail = it }
            }
            else -> {
                BackRow(listOfNotNull(m.title, detail?.title)) {
                    problem = null
                    state = ReportState.Idle
                    if (detail != null) detail = null else module = null
                }
                Label(stringResource(R.string.report_problem), Typography.label, c.textMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in m.problems) Button(stringResource(p.title), { problem = p }, primary = p == problem)
                }
                TextInput(note, { note = it.take(NOTE_MAX) }, stringResource(R.string.report_note), Modifier.fillMaxWidth())
                if (m.audio) Label(stringResource(R.string.report_play_first), Typography.small, c.textMuted)
                val chosen = problem
                val sending = state == ReportState.Sending
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        stringResource(if (sending) R.string.report_sending else R.string.report_send),
                        {
                            // Se lee el estado de ahora (dos toques en el mismo cuadro no mandan dos).
                            if (chosen == null || state == ReportState.Sending) return@Button
                            state = ReportState.Sending
                            scope.launch { state = submitReport(context, music, pro.isPro, dashboard, m, detail, chosen, note) }
                        },
                        Modifier.graphicsLayer { alpha = if (chosen == null || sending) 0.45f else 1f },
                        primary = true,
                    )
                    when (state) {
                        ReportState.Error -> Label(stringResource(R.string.report_error), Typography.small, c.danger)
                        ReportState.Wait -> Label(stringResource(R.string.report_wait), Typography.small, c.textMuted)
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** Opciones de un paso: un botón por opción; tocar una pasa al siguiente. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<T, Int>>, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((value, title) in options) Button(stringResource(title), { onPick(value) })
    }
}

/** "Atrás" y lo elegido hasta ahora ("Música · USB…"). */
@Composable
private fun BackRow(picked: List<Int>, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(stringResource(R.string.back), onBack)
        Label(picked.map { stringResource(it) }.joinToString(" · "), Typography.body, maxLines = 1)
    }
}

/** Manda un reporte respetando el límite de uno por minuto: [ReportState.Sent], [ReportState.Error] o [ReportState.Wait]. */
private suspend fun submitReport(
    context: Context,
    music: MusicRepository,
    pro: Boolean,
    dashboard: DashboardState,
    module: ReportModule,
    detail: ReportDetail?,
    problem: ReportProblem,
    note: String,
): ReportState {
    if (lastReport != 0L && SystemClock.elapsedRealtime() - lastReport < REPORT_GAP_MS) return ReportState.Wait
    val id = sendReport(context, music, pro, dashboard, module, detail, problem, note) ?: return ReportState.Error
    lastReport = SystemClock.elapsedRealtime()
    return ReportState.Sent(id.take(6).uppercase())
}

/** Escribe `reports/{id}` (nuevo, nunca pisa otro); regresa el id o null si no se pudo. */
private suspend fun sendReport(
    context: Context,
    music: MusicRepository,
    pro: Boolean,
    dashboard: DashboardState,
    module: ReportModule,
    detail: ReportDetail?,
    problem: ReportProblem,
    note: String,
): String? {
    // Lo de la música se arma aquí, en el hilo principal: su estado lo tocan los avisos que llegan ahí.
    val musicInfo = music.describe()
    val layout = dashboard.describe()
    val silent = music.silentSources()
    return withContext(Dispatchers.IO) { runCatching {
        val id = Firestore.newId()
        val fields = JSONObject()
            .put("version", Firestore.int(BuildConfig.VERSION_CODE.toLong()))
            .put("channel", Firestore.text(BuildConfig.CHANNEL))
            .put("android", Firestore.int(Build.VERSION.SDK_INT.toLong()))
            .put("debug", Firestore.bool(BuildConfig.DEBUG))
            .put("module", Firestore.text(module.key))
            .put("detail", Firestore.text(detail?.key ?: ""))
            .put("problem", Firestore.text(problem.key))
            .put("note", Firestore.text(note.trim().take(NOTE_MAX)))
            .put("info", Firestore.text(systemInfo(context, pro, layout, musicInfo, silent).take(INFO_MAX)))
        val write = JSONObject()
            .put("update", JSONObject().put("name", Firestore.document("reports/$id")).put("fields", fields))
            .put("currentDocument", JSONObject().put("exists", false))
            .put("updateTransforms", Firestore.serverTime("sent"))
        id.takeIf { Firestore.commit(context, write) }
    }.getOrNull() }
}

/** Datos técnicos de la pantalla para los reportes (equipo, Android, pantalla, acomodo [layout], luces, ubicación y si tiene Pro) más [musicInfo] y las apps [silent]. */
private fun systemInfo(context: Context, pro: Boolean, layout: String, musicInfo: String, silent: List<String>): String {
    val m = context.resources.displayMetrics
    return buildString {
        appendLine("LiteDash ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
        appendLine("Equipo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Plataforma: ${Build.HARDWARE} · ${Build.BOARD}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Pantalla: ${m.widthPixels}×${m.heightPixels} px · ${m.densityDpi} dpi")
        appendLine(layout)
        appendLine("Memoria: ${memoryMb(context)} MB")
        appendLine("CPU: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Pantalla dividida: ${if (SplitScreen.supported()) "sí" else "no"}")
        appendLine("Código de desarrollador: ${if (pro) "sí" else "no"}")
        appendLine(lightsInfo(context))
        appendLine(locationInfo(context))
        append(musicInfo)
        // Apps de fábrica sin receptor ni servicio: lo que dejan ver, para aprender a hablarles.
        for (pkg in silent) append("\nSin control $pkg:").append(AppProbe.describe(context, pkg).ifEmpty { " nada legible" })
    }
}

/**
 * Ubicación: si está prendida, qué proveedores tiene la pantalla (muchas no traen antena GPS) y la
 * última posición de cada uno, solo su edad y precisión (nunca dónde está).
 */
@SuppressLint("MissingPermission") // se revisa el permiso antes de leer la última posición
private fun locationInfo(context: Context): String {
    val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return "Ubicación: sin servicio"
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val on = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrDefault(false)
    val now = System.currentTimeMillis()
    val providers = runCatching { manager.allProviders }.getOrDefault(emptyList()).joinToString { p ->
        val enabled = runCatching { manager.isProviderEnabled(p) }.getOrDefault(false)
        val last = if (fine || coarse) runCatching { manager.getLastKnownLocation(p) }.getOrNull() else null
        "$p(${if (enabled) "on" else "off"}" + (last?.let { ", hace ${(now - it.time) / 60_000} min ±${it.accuracy.toInt()} m" } ?: "") + ")"
    }
    val permission = if (fine) "precisa" else if (coarse) "aproximada" else "sin permiso"
    return "Ubicación: ${if (on) "prendida" else "apagada"}, $permission; proveedores: ${providers.ifEmpty { "ninguno" }}"
}

/** La memoria (RAM) de la pantalla en MB. */
internal fun memoryMb(context: Context): Long {
    val memory = ActivityManager.MemoryInfo().also { ContextCompat.getSystemService(context, ActivityManager::class.java)!!.getMemoryInfo(it) }
    return memory.totalMem / (1024 * 1024)
}

/**
 * Para conectar los faros: si hay sensor de luz y qué ajustes de la pantalla hablan de
 * luces o iluminación (cada marca los nombra distinto; solo se leen los que Android deja).
 */
private fun lightsInfo(context: Context): String {
    val sensor = ContextCompat.getSystemService(context, SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT)
    val names = Regex("illum|headl|lamp|light|dimm|night|backlight|ill_", RegexOption.IGNORE_CASE)
    val found = listOf(
        "system" to Settings.System.CONTENT_URI,
        "global" to Settings.Global.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI,
    ).flatMap { (table, uri) ->
        runCatching {
            context.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                // Sin los de autollenado ("autofill_" contiene "ill_"); valores cortos (las luces son números).
                buildList {
                    while (c.moveToNext()) c.getString(0)?.takeIf { names.containsMatchIn(it) && !it.startsWith("autofill") }
                        ?.let { add("$table/$it=${c.getString(1).orEmpty().take(24)}") }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
    return "Sensor de luz: ${sensor?.name ?: "no"}\nLuces: ${found.joinToString().ifEmpty { "—" }}"
}
