package com.irvingguerra.litedash

import android.content.Context
import android.location.Location
import com.irvingguerra.litedash.core.ui.Https
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Dónde está uno, en texto: la calle y el lugar (Nominatim de OpenStreetMap). */
class Address(val road: String?, val locality: String?, val region: String?) {
    /** "Calle, Lugar" para mostrar. */
    val line: String get() = listOfNotNull(road, locality).joinToString(", ")
}

/** De qué tipo es un lugar cercano. */
enum class PlaceKind(val title: Int) {
    FUEL(R.string.place_fuel),
    FOOD(R.string.place_food),
    PHARMACY(R.string.place_pharmacy),
    HEALTH(R.string.place_health),
    MONEY(R.string.place_money),
    STORE(R.string.place_store),
    POLICE(R.string.place_police),
}

class Place(val name: String, val kind: PlaceKind, val latitude: Double, val longitude: Double, val meters: Int)

/**
 * Datos de lugares sin llave y gratis, de OpenStreetMap: la dirección de unas coordenadas (Nominatim: un pedido por
 * segundo como máximo, con nombre de la app) y lo que hay cerca (Overpass). Todo se recuerda un rato.
 */
object Places {
    private const val USER_AGENT = "LiteDash/1.4 (+https://litedash.web.app)"
    private val addresses = ConcurrentHashMap<String, Pair<Address?, Long>>()
    private val nearby = ConcurrentHashMap<String, Pair<List<Place>, Long>>()
    private val gate = Any()
    private var lastCall = 0L

    /** La dirección de [lat],[lon]; null si no hay red o no se pudo. Llamarla fuera del hilo principal. */
    fun reverse(context: Context, lat: Double, lon: Double): Address? {
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        val now = System.currentTimeMillis()
        addresses[key]?.takeIf { now - it.second < 30 * 60_000L && it.first != null }?.let { return it.first }
        val found = runCatching {
            throttle()
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&addressdetails=1" +
                "&lat=%.5f&lon=%.5f&accept-language=%s".format(Locale.US, lat, lon, Locale.getDefault().language)
            val c = Https.open(context, url).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val a = JSONObject(c.inputStream.bufferedReader().readText()).optJSONObject("address") ?: return@runCatching null
                fun pick(vararg keys: String) = keys.firstNotNullOfOrNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
                Address(
                    road = pick("road", "pedestrian", "footway"),
                    locality = pick("suburb", "neighbourhood", "village", "town", "city", "municipality", "county"),
                    region = pick("state", "province", "county"),
                )
            } finally {
                c.disconnect()
            }
        }.getOrNull()
        addresses[key] = found to now
        return found
    }

    /** Lo que llegó de lugares cercanos; [failed] = no se pudo pedir (y [places] es lo último que se tenía). */
    class Nearby(val places: List<Place>, val failed: Boolean)

    /** Por qué falló la última vez (para mostrarlo chico en pantalla y poder buscar el problema). */
    @Volatile var lastError: String = ""
        private set

    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private fun throttle() = synchronized(gate) {
        // Nominatim pide no pasar de un pedido por segundo.
        val wait = 1_100 - (System.currentTimeMillis() - lastCall)
        if (wait > 0) Thread.sleep(wait)
        lastCall = System.currentTimeMillis()
    }

    private fun get(context: Context, url: String, readMs: Int): String {
        val c = Https.open(context, url).apply {
            connectTimeout = 10_000
            readTimeout = readMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    /**
     * Lo que hay a menos de [radius] metros, lo más cerca primero: de Overpass (con dos servidores de respaldo) y, si
     * ninguno contesta, de Nominatim por categoría. Sin nada que mostrar y sin red, [Nearby.failed].
     */
    fun around(context: Context, lat: Double, lon: Double, radius: Int = 1500): Nearby {
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        val now = System.currentTimeMillis()
        nearby[key]?.takeIf { now - it.second < 10 * 60_000L }?.let { return Nearby(it.first, false) }
        val query = "[out:json][timeout:10];(" +
            "nwr(around:$radius,$lat,$lon)[amenity~\"^(fuel|restaurant|fast_food|cafe|pharmacy|hospital|clinic|bank|atm|police)\$\"];" +
            "nwr(around:$radius,$lat,$lon)[shop~\"^(supermarket|convenience)\$\"];" +
            ");out center 120;"
        // En disco por 24 h: lo ya visto sale al instante (y sirve sin red).
        diskRead(context, key, now)?.let { nearby[key] = it to now; return Nearby(it, false) }
        val errors = ArrayList<String>()
        // Nominatim contesta en un segundo; Overpass suele estar saturado: queda de respaldo, con poca espera.
        var list: List<Place>? = runCatching { viaNominatim(context, lat, lon, radius) }
            .onFailure { errors += "nominatim: ${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}" }.getOrNull()
        if (list.isNullOrEmpty()) {
            for (server in OVERPASS) {
                val more = runCatching {
                    val text = get(context, server + "?data=" + URLEncoder.encode(query, "UTF-8"), readMs = 12_000)
                    val elements = JSONObject(text).optJSONArray("elements") ?: error("sin datos")
                    (0 until elements.length()).mapNotNull { i -> parse(elements.getJSONObject(i), lat, lon) }
                }.onFailure { errors += "${server.substringAfter("//").substringBefore('/')}: ${it.javaClass.simpleName} ${it.message.orEmpty().take(60)}" }.getOrNull()
                if (more != null) { list = more; break }
            }
        }
        if (list == null) {
            lastError = errors.joinToString(" | ")
            return Nearby(nearby[key]?.first.orEmpty(), true)
        }
        lastError = ""
        val sorted = list.sortedBy { it.meters }
        nearby[key] = sorted to now
        if (sorted.isNotEmpty()) diskWrite(context, key, sorted, now)
        return Nearby(sorted, false)
    }

    /** Nominatim sabe buscar "[amenity=fuel]"… dentro de una caja (1 pedido por categoría, 1 por segundo). */
    private fun viaNominatim(context: Context, lat: Double, lon: Double, radius: Int): List<Place> {
        val dLat = radius / 111_000.0
        val dLon = radius / (111_000.0 * Math.cos(Math.toRadians(lat)))
        val box = "%.5f,%.5f,%.5f,%.5f".format(Locale.US, lon - dLon, lat + dLat, lon + dLon, lat - dLat)
        val terms = listOf(
            "[amenity=fuel]" to PlaceKind.FUEL, "[amenity=restaurant]" to PlaceKind.FOOD, "[amenity=pharmacy]" to PlaceKind.PHARMACY,
            "[amenity=hospital]" to PlaceKind.HEALTH, "[amenity=bank]" to PlaceKind.MONEY, "[amenity=atm]" to PlaceKind.MONEY,
            "[shop=supermarket]" to PlaceKind.STORE, "[amenity=police]" to PlaceKind.POLICE, "[amenity=fast_food]" to PlaceKind.FOOD,
        )
        val out = ArrayList<Place>()
        var answered = 0
        for ((term, kind) in terms) {
            throttle()
            val text = runCatching {
                get(context, "https://nominatim.openstreetmap.org/search?format=jsonv2&bounded=1&limit=15&viewbox=$box&q=${URLEncoder.encode(term, "UTF-8")}", readMs = 12_000)
            }.getOrNull() ?: continue
            answered++
            val array = runCatching { org.json.JSONArray(text) }.getOrNull() ?: continue
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val la = o.optString("lat").toDoubleOrNull() ?: continue
                val lo = o.optString("lon").toDoubleOrNull() ?: continue
                val dist = FloatArray(1)
                Location.distanceBetween(lat, lon, la, lo, dist)
                out += Place(o.optString("name").ifBlank { o.optString("display_name").substringBefore(',').takeIf { n -> n.isNotBlank() && n.any { ch -> ch.isLetter() } }.orEmpty() }, kind, la, lo, dist[0].toInt())
            }
        }
        if (answered == 0) error("Nominatim no contesta")
        return out
    }

    private fun diskRead(context: Context, key: String, now: Long): List<Place>? = runCatching {
        val o = JSONObject(context.getSharedPreferences("places", Context.MODE_PRIVATE).getString(key, null) ?: return null)
        if (now - o.getLong("t") > 24 * 3_600_000L) return null
        val a = o.getJSONArray("p")
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Place(it.getString("n"), PlaceKind.valueOf(it.getString("k")), it.getDouble("la"), it.getDouble("lo"), it.getInt("m")) } }
    }.getOrNull()

    private fun diskWrite(context: Context, key: String, list: List<Place>, now: Long) {
        runCatching {
            val a = org.json.JSONArray(list.map { JSONObject().put("n", it.name).put("k", it.kind.name).put("la", it.latitude).put("lo", it.longitude).put("m", it.meters) })
            context.getSharedPreferences("places", Context.MODE_PRIVATE).edit().putString(key, JSONObject().put("t", now).put("p", a).toString()).apply()
        }
    }

    private fun parse(o: JSONObject, lat: Double, lon: Double): Place? {
        val tags = o.optJSONObject("tags") ?: return null
        val center = o.optJSONObject("center")
        val la = if (o.has("lat")) o.getDouble("lat") else center?.optDouble("lat") ?: return null
        val lo = if (o.has("lon")) o.getDouble("lon") else center?.optDouble("lon") ?: return null
        val kind = when (tags.optString("amenity").ifEmpty { tags.optString("shop") }) {
            "fuel" -> PlaceKind.FUEL
            "restaurant", "fast_food", "cafe" -> PlaceKind.FOOD
            "pharmacy" -> PlaceKind.PHARMACY
            "hospital", "clinic" -> PlaceKind.HEALTH
            "bank", "atm" -> PlaceKind.MONEY
            "supermarket", "convenience" -> PlaceKind.STORE
            "police" -> PlaceKind.POLICE
            else -> return null
        }
        val out = FloatArray(1)
        Location.distanceBetween(lat, lon, la, lo, out)
        // Sin nombre se muestra el tipo (un cajero o una gasolinera sin nombre sirven igual); lo vacío lo pone la pantalla.
        val name = tags.optString("name").ifBlank { tags.optString("brand") }.ifBlank { tags.optString("operator") }
        return Place(name, kind, la, lo, out[0].toInt())
    }
}
