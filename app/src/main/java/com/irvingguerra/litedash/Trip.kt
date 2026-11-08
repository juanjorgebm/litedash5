package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.location.LocationListenerCompat
import kotlin.concurrent.thread
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.Source
import com.irvingguerra.litedash.core.vehicle.VehicleBus
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat

/** Un punto del GPS de hace menos que esto manda sobre la red. */
private const val GPS_FRESH_MS = 5_000L

/**
 * Velocidad (al bus), rumbo y distancia del viaje con el GPS. Solo escucha mientras algo
 * lo pide ([acquire]/[release]) y la app está al frente: así no gasta batería
 * con la pantalla en otra app ni en segundo plano. Con solo la ubicación aproximada usa la
 * red (sin velocidad casi nunca, pero el viaje cuenta igual lo que pueda).
 */
class TripRepository(private val context: Context, private val bus: VehicleBus) {
    /** km/h del GPS; null = sin señal todavía. Las piezas leen la velocidad del bus (el OBD gana). */
    private var speedKmh: Float? = null
    var bearing by mutableStateOf<Float?>(null)
        private set
    /** La última posición (la lee el mapa); se queda aunque el GPS se apague. */
    var position by mutableStateOf<Location?>(null)
        private set
    var meters by mutableDoubleStateOf(0.0)
        private set
    /** Desde el primer punto en movimiento (0 = todavía no arranca): no cuenta lo que estuvo parado. */
    var startedAt by mutableLongStateOf(0L)
        private set

    private var users = 0
    private var last: Location? = null
    private val manager by lazy { ContextCompat.getSystemService(context, LocationManager::class.java)!! }
    // Compat: en Android 6–10 LocationListener tiene más métodos abstractos que el lambda no
    // implementa (AbstractMethodError al perder el GPS o apagar la ubicación).
    private val listener = LocationListenerCompat { update(it) }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Ubicación precisa o aproximada (en Android 12+ el usuario puede dar solo la aproximada). */
    fun hasPermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /**
     * Con la precisa, el GPS y también la red: muchas pantallas no traen antena GPS (o no está
     * conectada) y Google Maps las ubica por Wi-Fi; sin la red, el mapa se quedaba buscando para
     * siempre. Con la aproximada, solo la red.
     */
    private fun providers(): List<String> {
        val wanted = if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            else listOf(LocationManager.NETWORK_PROVIDER)
        val existing = runCatching { manager.allProviders }.getOrDefault(emptyList())
        return wanted.filter { it in existing }
    }

    @SuppressLint("MissingPermission") // se revisa con hasPermission()
    fun acquire() {
        users++
        if (users == 1 && hasPermission()) {
            val providers = providers()
            for (provider in providers) runCatching { manager.requestLocationUpdates(provider, 1_000L, 0f, listener, Looper.getMainLooper()) }
            // Mientras llega el primer punto, la última conocida (el mapa no espera en blanco). Es una
            // llamada al sistema: fuera del hilo principal (el estado se puede escribir desde ahí).
            if (position == null) thread(name = "last-location") {
                val last = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
                // En el principal: si mientras tanto llegó un punto, no se pisa con uno más viejo.
                if (last != null) main.post { if (position == null) position = last }
            }
        }
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            runCatching { manager.removeUpdates(listener) }
            speedKmh = null
            bus.publish(Signal.SPEED, null, Source.GPS)
        }
    }

    fun reset() {
        meters = 0.0
        startedAt = 0L
        last = null
    }

    /** Reloj de arranque del último punto del GPS: mientras llega, la red (menos precisa) no lo pisa. */
    private var lastGpsAt = 0L
    private val main = Handler(Looper.getMainLooper())

    private fun update(l: Location) {
        val now = SystemClock.elapsedRealtime()
        if (l.provider != LocationManager.GPS_PROVIDER) {
            // La red solo mueve el punto del mapa: no trae velocidad ni rumbo y no cuenta para el viaje
            // (sin GPS, el velocímetro se queda con lo último en vez de apagarse en un túnel).
            if (now - lastGpsAt >= GPS_FRESH_MS) position = l
            return
        }
        lastGpsAt = now
        position = l
        speedKmh = if (l.hasSpeed()) l.speed * 3.6f else null
        bus.publish(Signal.SPEED, speedKmh, Source.GPS)
        if (l.hasBearing() && (speedKmh ?: 0f) > 3f) bearing = l.bearing
        val previous = last
        // Solo puntos buenos y en movimiento: parado, el GPS "camina" solo unos metros.
        if (l.accuracy <= 30f && (speedKmh ?: 0f) > 3f) {
            if (startedAt == 0L) startedAt = System.currentTimeMillis()
            if (previous != null) meters += previous.distanceTo(l)
            last = l
        }
    }
}

/**
 * El inicio está tapado por algo a pantalla completa (el auto en grande, Llantas o estacionado): sus
 * widgets siguen compuestos debajo, pero lo que gasta (GPS, mapa) descansa.
 */
val LocalHomeCovered = compositionLocalOf { false }

/** Escucha el GPS mientras la pieza se ve (sin nada encima) y la app está al frente. */
@Composable
fun KeepTripRunning(trip: TripRepository) {
    // Tapado (el auto en grande, Llantas o estacionado encima), el GPS descansa.
    if (LocalHomeCovered.current) return
    LifecycleStartEffect(trip) {
        trip.acquire()
        onStopOrDispose { trip.release() }
    }
}

private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
private val COMPASS_EN = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** La distancia en la unidad del usuario, con un decimal ("12.3"); la unidad con [distanceUnit]. */
@Composable
fun distanceText(meters: Double, miles: Boolean): String = String.format(currentLocale(), "%.1f", meters.metersToDistance(miles))

fun distanceUnit(miles: Boolean) = if (miles) "mi" else "km"

/** La velocidad sin decimales en la unidad del usuario (sin dato, 0); la unidad con [speedUnit]. */
@Composable
fun speedText(kmh: Float?, miles: Boolean): String = String.format(currentLocale(), "%.0f", (kmh ?: 0f).toMiles(miles))

@Composable
fun speedUnit(miles: Boolean) = stringResource(if (miles) R.string.unit_mph else R.string.unit_kmh)

/** Lo del viaje ya en texto, para la pieza Viaje y la carretera. */
class TripStats(val distance: String, val unit: String, val time: String, val average: String?, val heading: String?)

/** Los datos del viaje en la unidad del usuario; el tiempo avanza solo aunque el GPS no mande nada. */
@Composable
fun rememberTripStats(trip: TripRepository, miles: Boolean): TripStats {
    // Una vez por minuto basta para el tiempo.
    val elapsed by produceState(0L, trip.startedAt) {
        while (true) {
            value = if (trip.startedAt == 0L) 0L else System.currentTimeMillis() - trip.startedAt
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    val minutes = (elapsed / 60_000).toInt()
    val locale = currentLocale()
    val heading = trip.bearing?.let { (if (locale.language == "es") COMPASS else COMPASS_EN)[((it + 22.5f) / 45f).toInt() % 8] }
    // Promedio desde que arrancó (con paradas), cuando ya hay algo que promediar. Con la hora de
    // ahora (no la del último minuto): se vuelve a calcular con cada metro nuevo.
    val hours = if (trip.startedAt == 0L) 0.0 else (System.currentTimeMillis() - trip.startedAt) / 3_600_000.0
    val average = if (minutes >= 1 && trip.meters >= 100) "${speedText((trip.meters / 1000 / hours).toFloat(), miles)} ${speedUnit(miles)}" else null
    return TripStats(
        distance = distanceText(trip.meters, miles),
        unit = distanceUnit(miles),
        time = String.format(locale, "%d:%02d", minutes / 60, minutes % 60),
        average = average,
        heading = heading,
    )
}

/**
 * Pieza Viaje: solo lo del viaje (la velocidad ya está en el velocímetro y la carretera): la
 * distancia en grande, el tiempo, la velocidad promedio y el rumbo. Sin permiso, el botón para activarla.
 */
@Composable
fun TripTile(d: Dependencies) {
    val c = LocalColors.current
    val trip = d.trip
    val miles = d.prefs.units.miles
    val (granted, ask) = rememberGpsPermission(d)
    if (!granted) return ShortcutTile(Icons.location, stringResource(R.string.tile_trip), stringResource(R.string.tap_to_enable), ask)
    KeepTripRunning(trip)
    val stats = rememberTripStats(trip, miles)
    val (average, heading) = stats.average to stats.heading
    val locale = currentLocale()
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp)) {
            val tall = maxHeight > 120.dp
            val wide = maxWidth > 340.dp
            val huge = maxHeight > 200.dp
            if (!tall) {
                // Una fila: la distancia y, a un lado, tiempo, promedio y rumbo en chico.
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    BigValue(stats.distance, stats.unit, 34)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Label(listOfNotNull(stats.time, heading).joinToString(" · "), Typography.body, maxLines = 1)
                        if (average != null) Label(average, Typography.small, c.textMuted, maxLines = 1)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Label(stringResource(R.string.tile_trip).uppercase(locale), Typography.label, c.textMuted, maxLines = 1)
                            BigValue(stats.distance, stats.unit, if (huge) 64 else 48)
                        }
                        if (heading != null) Label(heading, Typography.title, c.accent, maxLines = 1)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(if (wide) 28.dp else 16.dp)) {
                        TripStat(stringResource(R.string.trip_time), stats.time)
                        if (average != null) TripStat(stringResource(R.string.trip_average), average)
                        Spacer(Modifier.weight(1f))
                        if (wide) Button(stringResource(R.string.trip_reset), { trip.reset() })
                    }
                }
            }
        }
    }
}

/** Un número grande con su unidad en chico a un lado (como el reloj). */
@Composable
private fun BigValue(value: String, unit: String, size: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Label(value, Typography.clock.copy(fontSize = size.sp), maxLines = 1)
        Label(unit, Typography.subtitle, LocalColors.current.textMuted, maxLines = 1, modifier = Modifier.padding(start = 6.dp, bottom = (size / 7).dp))
    }
}

/** Un dato del viaje: su nombre en chico arriba y el valor. */
@Composable
private fun TripStat(name: String, value: String) {
    Column {
        Label(name, Typography.small, LocalColors.current.textMuted, maxLines = 1)
        Label(value, Typography.subtitle, maxLines = 1)
    }
}
