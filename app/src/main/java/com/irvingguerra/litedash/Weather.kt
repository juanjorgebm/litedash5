package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.MyCar
import androidx.core.content.ContextCompat

/** Clima tal como se ve: temperaturas redondeadas en la unidad elegida (códigos WMO de Open-Meteo). */
data class Weather(
    val temperature: Int, val code: Int, val day: Boolean, val days: List<WeatherDay>,
    /** Las próximas horas (vacío si no llegaron) y el nombre del lugar donde se pidió el clima. */
    val hours: List<WeatherHour> = emptyList(), val place: String = "",
)

/** Una hora del pronóstico: [time] "HH:mm", y la probabilidad de lluvia en %. */
data class WeatherHour(val time: String, val code: Int, val temperature: Int, val rain: Int)

data class WeatherDay(val date: String, val code: Int, val max: Int, val min: Int)

/** Como llegó de Open-Meteo: °C con decimales, para que °F salga exacto (20.4 °C = 69 °F, no 68). */
data class Forecast(
    val temperature: Double, val code: Int, val day: Boolean, val days: List<ForecastDay>, val fetchedAt: Long,
    val hours: List<ForecastHour> = emptyList(),
    /** Dónde se pidió (NaN = no se sabe) y cómo se llama el lugar. */
    val lat: Double = Double.NaN, val lon: Double = Double.NaN, val place: String = "",
)

data class ForecastHour(val time: String, val code: Int, val temperature: Double, val rain: Int)

data class ForecastDay(val date: String, val code: Int, val max: Double, val min: Double)

/**
 * °F, mph y millas solo donde se usan (Estados Unidos y pocos más). Con la app en
 * español siempre métrico, aunque el sistema esté en inglés de EE. UU.
 */
fun imperialUnits(language: String) = language != "es" &&
    Locale.getDefault().country in setOf("US", "LR", "MM")

/** [this] en °F ([fahrenheit]) o °C, redondeado al final. */
fun Forecast.inUnits(fahrenheit: Boolean): Weather {
    val shown: (Double) -> Int = { c -> Math.round(if (fahrenheit) c * 9.0 / 5.0 + 32.0 else c).toInt() }
    return Weather(
        shown(temperature), code, day, days.map { WeatherDay(it.date, it.code, shown(it.max), shown(it.min)) },
        hours.map { WeatherHour(it.time, it.code, shown(it.temperature), it.rain) }, place,
    )
}

/** Qué cielo es cada código WMO, para el ícono y el texto. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, STORM, SNOW }

fun skyOf(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

/**
 * Clima con Open-Meteo: gratis, sin clave ni anuncios, y solo con la ubicación
 * aproximada. Nada corre en segundo plano: mientras se ve el inicio se pide si el
 * último tiene más de [STALE_MS] ([WeatherRefresh]), y se guarda para verlo al
 * instante (y sin red). Apagado (Ajustes → Inicio y apariencia) no pide ubicación ni va a la red.
 */
class WeatherRepository(private val context: Context, private val prefs: Preferences) {
    private val store = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** Como llegó de Open-Meteo, en °C con decimales. */
    private var saved by mutableStateOf(read())

    /** El clima en la unidad elegida: se convierte una vez por cambio (de clima o de unidad), sin ir a la red. */
    val weather: Weather? by derivedStateOf { saved?.inUnits(prefs.units.fahrenheit) }

    // Solo si el usuario lo activó: el permiso de ubicación también llega al activar
    // Viaje, y eso no debe encender el clima. Quien ya tenía clima guardado lo conserva.
    /** Prendido por el usuario (aunque aún falte el permiso). */
    // Prendido de fábrica: sin ubicación usa Palmares ([MyCar]).
    var enabled by mutableStateOf(store.getBoolean(KEY_ON, true))
        private set

    /** Con permiso de ubicación aproximada se usa la de la pantalla; si no, Palmares. */
    private val hasLocation get() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Activado (no hace falta el permiso: sin él se usa Palmares). */
    val active get() = enabled

    fun enable() = turn(true)

    /** Apagado: se esconde y ya no pide ubicación ni va a la red (lo guardado se queda). */
    fun disable() = turn(false)

    private fun turn(on: Boolean) {
        enabled = on
        store.edit { putBoolean(KEY_ON, on) }
    }

    /** De dónde sacar la posición del GPS del viaje (la app ya lo escucha); null = solo lo que diga el sistema. */
    var positionHint: (() -> Location?)? = null

    suspend fun refreshIfStale(force: Boolean = false) {
        if (!active) return
        val last = saved
        val stale = last == null || System.currentTimeMillis() - last.fetchedAt >= STALE_MS
        // Dónde está el carro ahora: si se movió lejos de donde se pidió el clima, se pide otra vez.
        val place = positionHint?.invoke() ?: if (hasLocation) runCatching { location() }.getOrNull() else null
        val moved = last != null && place != null && !last.lat.isNaN() && run {
            val out = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, place.latitude, place.longitude, out)
            out[0] > MOVED_M
        }
        if (!force && !stale && !moved) return
        val lat = place?.latitude ?: MyCar.LATITUDE
        val lon = place?.longitude ?: MyCar.LONGITUDE
        val fresh = runCatching { fetch(lat, lon) }.getOrNull() ?: return
        saved = fresh
        withContext(Dispatchers.IO) { store.edit(commit = true) { putString(KEY, fresh.toJson().toString()) } }
    }

    @SuppressLint("MissingPermission") // [active] revisa el permiso antes
    private suspend fun location(): Location? {
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java)!!
        // La última conocida se lee del sistema (binder): fuera del hilo principal.
        val last = withContext(Dispatchers.IO) {
            manager.getProviders(true)
                .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        }
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000L) return last
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 5 a 10: una lectura nueva (getCurrentLocation no existe), con tiempo límite; si no llega, la última.
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) } ?: return last
            val fresh = withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(l: Location) { if (cont.isActive) cont.resume(l) }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }
                    cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                    runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
            return fresh ?: last
        }
        if (last != null) return last
        // "fused" existe desde Android 12.
        val fused = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(LocationManager.FUSED_PROVIDER) else emptyList()
        val provider = (listOf(LocationManager.NETWORK_PROVIDER) + fused + LocationManager.GPS_PROVIDER)
            .firstOrNull { manager.isProviderEnabled(it) } ?: return null
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
            }
        }
    }

    private suspend fun fetch(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f".format(Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&hourly=temperature_2m,weather_code,precipitation_probability&forecast_hours=5" +
            "&timezone=auto&forecast_days=4"
        val connection = Https.open(context, url).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "LiteDash")
        }
        val json = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        // Las próximas 4 horas (la primera es la de ahora y se salta).
        val hourly = json.optJSONObject("hourly")
        val hours = hourly?.let { h ->
            val times = h.getJSONArray("time")
            (1 until times.length()).map { i ->
                ForecastHour(
                    time = times.getString(i).substringAfter('T').take(5),
                    code = h.getJSONArray("weather_code").optInt(i),
                    temperature = h.getJSONArray("temperature_2m").optDouble(i),
                    rain = h.optJSONArray("precipitation_probability")?.optInt(i) ?: 0,
                )
            }
        }.orEmpty()
        // El nombre del lugar (si no hay red para eso, el clima sale igual); el mismo si no se movió.
        val prev = saved
        val sameSpot = prev != null && !prev.lat.isNaN() && Math.abs(prev.lat - lat) < 0.02 && Math.abs(prev.lon - lon) < 0.02 && prev.place.isNotBlank()
        val place = if (sameSpot) prev!!.place else Places.reverse(context, lat, lon)?.let { it.locality ?: it.region }.orEmpty()
        Forecast(
            temperature = current.getDouble("temperature_2m"),
            code = current.getInt("weather_code"),
            day = current.optInt("is_day", 1) == 1,
            days = (0 until dates.length()).map { i ->
                ForecastDay(
                    date = dates.getString(i),
                    code = daily.getJSONArray("weather_code").getInt(i),
                    max = daily.getJSONArray("temperature_2m_max").getDouble(i),
                    min = daily.getJSONArray("temperature_2m_min").getDouble(i),
                )
            },
            fetchedAt = System.currentTimeMillis(),
            hours = hours, lat = lat, lon = lon, place = place,
        )
    }

    /** Lo guardado; las versiones viejas guardaban enteros y algunas en °F ("f"): se pasan a °C. */
    private fun read(): Forecast? = store.getString(KEY, null)?.let { text ->
        runCatching {
            val o = JSONObject(text)
            val celsius: (Double) -> Double = if (o.optBoolean("f")) { f -> (f - 32.0) * 5.0 / 9.0 } else { c -> c }
            val days = o.getJSONArray("days")
            Forecast(
                celsius(o.getDouble("t")), o.getInt("c"), o.getBoolean("d"),
                (0 until days.length()).map { i ->
                    val x = days.getJSONObject(i)
                    ForecastDay(x.getString("date"), x.getInt("c"), celsius(x.getDouble("max")), celsius(x.getDouble("min")))
                },
                o.getLong("at"),
                hours = o.optJSONArray("hours")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { ForecastHour(it.getString("time"), it.getInt("c"), it.getDouble("t"), it.optInt("r")) } }
                }.orEmpty(),
                lat = o.optDouble("lat", Double.NaN), lon = o.optDouble("lon", Double.NaN), place = o.optString("place"),
            )
        }.getOrNull()
    }

    // "f" = false: las versiones viejas que lean esto saben que va en °C.
    private fun Forecast.toJson() = JSONObject()
        .put("t", temperature).put("c", code).put("d", day).put("at", fetchedAt).put("f", false)
        .put("days", JSONArray(days.map { JSONObject().put("date", it.date).put("c", it.code).put("max", it.max).put("min", it.min) }))
        .put("hours", JSONArray(hours.map { JSONObject().put("time", it.time).put("c", it.code).put("t", it.temperature).put("r", it.rain) }))
        .put("lat", lat).put("lon", lon).put("place", place)

    companion object {
        private const val STORE = "weather"
        private const val KEY = "last"
        private const val KEY_ON = "enabled"

        /** Cada cuánto se renueva mientras se ve el inicio. */
        const val STALE_MS = 30 * 60_000L

        /** Cada cuánto se mira si el carro se movió (sin ir a la red si no). */
        const val CHECK_MS = 5 * 60_000L

        /** Cuánto debe moverse el carro (metros) para pedir el clima de donde está. */
        const val MOVED_M = 5_000f
    }
}
