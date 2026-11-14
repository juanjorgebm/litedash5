package com.irvingguerra.litedash.feature.player

import com.irvingguerra.litedash.core.ui.MyCar
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.security.NetworkSecurityPolicy
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import androidx.core.content.ContextCompat

/** Una estación de radio por internet (de la base abierta Radio Browser). */
class Station(
    val id: String,
    val name: String,
    override val uri: Uri,
    val logoUrl: String?,
    /** Género o lo que la estación dice de sí ("pop, noticias"). */
    val tags: String,
    val country: String,
    val state: String,
    val codec: String,
    val bitrateKbps: Int,
    val latitude: Double?,
    val longitude: Double?,
    /** Veces que la sintonizaron las últimas 24 h (el directorio no da oyentes en vivo). */
    val clicks: Int = 0,
) : Playable {
    override val title get() = name
    override val subtitle get() = listOf(tags, state.ifBlank { country }).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Radio" }
    override val key get() = "radio:$id"

    /** AAC en vivo (ADTS): va por [AacStream]; lo demás (MP3, HLS) por MediaPlayer. */
    val isAac: Boolean get() = codec.uppercase().startsWith("AAC") && !uri.toString().contains(".m3u8")

    /** Kilómetros desde [lat],[lon]; null si la estación no dice dónde está. */
    fun distanceKm(lat: Double, lon: Double): Double? {
        val la = latitude ?: return null
        val lo = longitude ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, la, lo, out)
        return out[0] / 1000.0
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("url", uri.toString()).put("logo", logoUrl ?: JSONObject.NULL)
        .put("tags", tags).put("country", country).put("state", state).put("codec", codec).put("bitrate", bitrateKbps)
        .put("lat", latitude ?: JSONObject.NULL).put("lon", longitude ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Station? {
            val url = o.optString("url").ifBlank { return null }
            return Station(
                id = o.optString("id"), name = o.optString("name"), uri = Uri.parse(url),
                logoUrl = o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
                tags = o.optString("tags"), country = o.optString("country"), state = o.optString("state"),
                codec = o.optString("codec"), bitrateKbps = o.optInt("bitrate"),
                latitude = o.optDouble("lat").takeIf { !it.isNaN() }, longitude = o.optDouble("lon").takeIf { !it.isNaN() },
            )
        }
    }
}

/** Dónde está la pantalla (última ubicación conocida), para buscar las estaciones de cerca. */
class Here(val latitude: Double, val longitude: Double)

/**
 * Estaciones de Radio Browser (radio-browser.info, base abierta y gratuita, sin llave):
 * las de cerca según el GPS, las del país y por nombre. Varias copias del servidor: si
 * una falla se prueba la siguiente.
 */
object Radio {
    private val SERVERS = listOf("https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info", "https://at1.api.radio-browser.info", "https://fi1.api.radio-browser.info")
    private const val USER_AGENT = "LiteDash/1.4 (+https://litedash.web.app)"
    private const val LIMIT = 150
    private const val ROUNDS = 2

    /** Hasta dónde cuenta como "de aquí" (metros): el alcance típico de una FM potente. */
    const val NEARBY_METERS = 120_000

    /** La última ubicación que tenga Android (GPS o red), si LiteDash tiene permiso; sin pedir nada. */
    @SuppressLint("MissingPermission") // se revisa justo antes
    fun here(context: Context): Here? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        // Sin permiso o sin ubicación: Palmares, Alajuela ([MyCar]).
        val home = Here(kotlin.math.round(MyCar.LATITUDE * 10) / 10, kotlin.math.round(MyCar.LONGITUDE * 10) / 10)
        if (!fine && !coarse) return home
        val manager = ContextCompat.getSystemService(context, LocationManager::class.java) ?: return home
        return runCatching {
            manager.allProviders.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        // Redondeada a 0.1° (~10 km): para estaciones a 120 km sobra, y no se manda la ubicación exacta.
        }.getOrNull()?.let { Here(kotlin.math.round(it.latitude * 10) / 10, kotlin.math.round(it.longitude * 10) / 10) } ?: home
    }

    /** Estaciones a menos de [NEARBY_METERS] de [here], las más cercanas primero. */
    fun nearby(context: Context, here: Here): List<Station> =
        search(context, "geo_lat=${here.latitude}&geo_long=${here.longitude}&geo_distance=$NEARBY_METERS&order=clickcount&reverse=true")
            .sortedBy { it.distanceKm(here.latitude, here.longitude) ?: Double.MAX_VALUE }

    /** Las más escuchadas del país de la pantalla (cuando no hay ubicación). */
    fun country(context: Context, code: String = MyCar.COUNTRY): List<Station> =
        search(context, "countrycode=${encode(code)}&order=clickcount&reverse=true")

    /** Las más escuchadas del mundo. */
    fun world(context: Context): List<Station> = search(context, "order=clickcount&reverse=true")

    /** Parecidas a [station]: su género principal en su país, las más escuchadas primero. */
    fun similar(context: Context, station: Station): List<Station> {
        val tag = station.tags.split(',').firstOrNull()?.trim().orEmpty()
        val query = buildString {
            if (tag.isNotBlank()) append("tag=${encode(tag)}&")
            val code = java.util.Locale.getISOCountries().firstOrNull { java.util.Locale("", it).getDisplayCountry(java.util.Locale.ENGLISH).equals(station.country, true) || it.equals(station.country, true) }
            if (code != null) append("countrycode=$code&")
            append("order=clickcount&reverse=true")
        }
        return search(context, query).filter { it.id != station.id }
    }

    /** Lo que una estación dice de sí y de lo que suena (metadatos ICY del flujo). */
    class NowInfo(val song: String?, val name: String?, val description: String?, val genre: String?, val bitrate: Int?)

    private val nowCache = java.util.concurrent.ConcurrentHashMap<String, Pair<NowInfo?, Long>>()

    /** [nowInfo] recordado 25 s; llamarla fuera del hilo principal. */
    fun now(context: Context, station: Station): NowInfo? {
        val t = System.currentTimeMillis()
        nowCache[station.id]?.takeIf { t - it.second < 25_000 }?.let { return it.first }
        val info = nowInfo(context, station.uri.toString())
        nowCache[station.id] = info to t
        return info
    }

    private fun nowInfo(context: Context, url: String): NowInfo? {
        val c = runCatching {
            Https.open(context, url).apply {
                connectTimeout = 6_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Icy-MetaData", "1")
            }
        }.getOrNull() ?: return null
        return try {
            if (c.responseCode !in 200..299) return null
            val every = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull()
            var song: String? = null
            if (every != null && every in 1..65_536) {
                val input = c.inputStream
                var left = every
                val sink = ByteArray(4096)
                while (left > 0) {
                    val n = input.read(sink, 0, minOf(left, sink.size))
                    if (n < 0) return null
                    left -= n
                }
                val size = input.read() * 16
                if (size > 0) {
                    val block = ByteArray(size)
                    var got = 0
                    while (got < size) { val n = input.read(block, got, size - got); if (n < 0) break; got += n }
                    val text = String(block, 0, got, Charsets.UTF_8).trimEnd('\u0000')
                    song = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?.trim()
                }
            }
            fun clean(v: String?) = v?.trim()?.takeIf { it.isNotEmpty() }
            NowInfo(clean(song), clean(c.getHeaderField("icy-name")), clean(c.getHeaderField("icy-description")), clean(c.getHeaderField("icy-genre")), c.getHeaderField("icy-br")?.substringBefore(',')?.trim()?.toIntOrNull())
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Por nombre, en todo el mundo. */
    fun byName(context: Context, name: String): List<Station> =
        search(context, "name=${encode(name)}&order=clickcount&reverse=true")

    /** Un país o un género del directorio, con cuántas estaciones tiene. [code]: código de país o nombre del género. */
    data class Category(val name: String, val code: String, val count: Int, val genre: Boolean)

    /** Los países con más estaciones. */
    fun countries(context: Context): List<Category> =
        listJson(context, "/json/countries?order=stationcount&reverse=true&hidebroken=true") { o ->
            val code = o.optString("iso_3166_1").takeIf { it.length == 2 } ?: return@listJson null
            val n = o.optInt("stationcount").takeIf { it >= 3 } ?: return@listJson null
            Category(java.util.Locale("", code).displayCountry.ifBlank { o.optString("name") }, code, n, genre = false)
        }.take(150)

    /** Los géneros (etiquetas) con más estaciones. */
    fun tags(context: Context): List<Category> =
        listJson(context, "/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=90") { o ->
            val name = o.optString("name").trim().takeIf { it.length in 2..24 } ?: return@listJson null
            val n = o.optInt("stationcount").takeIf { it >= 5 } ?: return@listJson null
            Category(name.replaceFirstChar { it.uppercase() }, name, n, genre = true)
        }

    /** Las más escuchadas de un país ([code] de dos letras). */
    fun byCountry(context: Context, code: String): List<Station> =
        search(context, "countrycode=${encode(code)}&order=clickcount&reverse=true")

    /** Las más escuchadas de un género. */
    fun byTag(context: Context, tag: String): List<Station> =
        search(context, "tag=${encode(tag)}&tagExact=true&order=clickcount&reverse=true")

    private fun <T : Any> listJson(context: Context, path: String, map: (JSONObject) -> T?): List<T> {
        repeat(ROUNDS) {
            for (server in SERVERS) {
                val text = runCatching {
                    open(context, server + path).use { c ->
                        if (c.responseCode != 200) return@runCatching null
                        c.inputStream.bufferedReader().readText()
                    }
                }.getOrNull() ?: continue
                val array = runCatching { JSONArray(text) }.getOrNull() ?: continue
                return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(map) }
            }
        }
        return emptyList()
    }

    // Lo que se probó: id → (suena, cuándo). Se reutiliza 15 minutos.
    private val probed = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val PROBE_TTL_MS = 15 * 60_000L

    /**
     * ¿[station] suena ahora? Se abre su flujo y se leen unos bytes (muchas del directorio ya no contestan o devuelven
     * una página web). Llamarla fuera del hilo principal; el resultado se recuerda un rato.
     */
    fun isAlive(context: Context, station: Station): Boolean {
        val now = System.currentTimeMillis()
        probed[station.id]?.takeIf { now - it.second < PROBE_TTL_MS }?.let { return it.first }
        val ok = runCatching { probe(context, station.uri.toString()) }.getOrDefault(false)
        probed[station.id] = ok to now
        return ok
    }

    private fun probe(context: Context, url: String): Boolean {
        val c = Https.open(context, url).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (c.responseCode !in 200..299) return false
            if (c.contentType.orEmpty().lowercase().startsWith("text/html")) return false
            return c.inputStream.use { it.read(ByteArray(2048)) } > 0
        } finally {
            c.disconnect()
        }
    }

    /** Le dice a Radio Browser que alguien sintonizó [station] (así ordena las populares); sin esperar respuesta. */
    fun countClick(context: Context, station: Station) {
        Thread {
            for (server in SERVERS) {
                if (runCatching { open(context, "$server/json/url/${station.id}").use { it.responseCode == 200 } }.getOrDefault(false)) break
            }
        }.start()
    }

    /** Bytes de [url] (el logo de una estación), hasta [maxBytes]; null si no se pudo. */
    fun fetchBytes(context: Context, url: String, maxBytes: Int): ByteArray? = runCatching {
        open(context, url).use { c ->
            if (c.responseCode != 200) return null
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return null
                }
            }
            out.toByteArray()
        }
    }.getOrNull()

    /** Los servidores fallan a ratos: se prueban todos y, si ninguno contestó, una segunda vuelta. */
    private fun search(context: Context, query: String): List<Station> {
        repeat(ROUNDS) {
            for (server in SERVERS) {
                val text = runCatching {
                    open(context, "$server/json/stations/search?$query&hidebroken=true&limit=$LIMIT").use { c ->
                        if (c.responseCode != 200) return@runCatching null
                        c.inputStream.bufferedReader().readText()
                    }
                }.getOrNull() ?: continue
                return parse(text)
            }
        }
        return emptyList()
    }

    private fun parse(text: String): List<Station> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Station>(array.length())
        val seen = HashSet<String>()
        // Si Android no deja tráfico sin cifrar (lo normal desde la 9), las http:// no sonarían: no se listan.
        // Android 5 no tiene esa política: ahí siempre se permite.
        val cleartext = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.ifBlank { continue }
            if (!cleartext && !url.startsWith("https://", ignoreCase = true)) continue
            val name = o.optString("name").trim().ifBlank { continue }
            // La misma estación aparece varias veces con distintos flujos: una sola.
            if (!seen.add(name.lowercase())) continue
            out += Station(
                id = o.optString("stationuuid"), name = name, uri = Uri.parse(url),
                logoUrl = o.optString("favicon").takeIf { it.startsWith("http") },
                tags = o.optString("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", "),
                country = o.optString("country"), state = o.optString("state"),
                codec = o.optString("codec"), bitrateKbps = o.optInt("bitrate"),
                latitude = o.optDouble("geo_lat").takeIf { !it.isNaN() }, longitude = o.optDouble("geo_long").takeIf { !it.isNaN() },
                clicks = o.optInt("clickcount"),
            )
        }
        return out
    }

    private fun open(context: Context, url: String): HttpURLConnection = Https.open(context, url).apply {
        connectTimeout = 8_000
        readTimeout = 10_000
        setRequestProperty("User-Agent", USER_AGENT)
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** Cerrar la conexión al terminar (HttpURLConnection no es Closeable). */
private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T = try { block(this) } finally { disconnect() }
