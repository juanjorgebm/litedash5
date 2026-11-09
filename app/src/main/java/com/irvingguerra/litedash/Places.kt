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
            synchronized(gate) {
                // Nominatim pide no pasar de un pedido por segundo.
                val wait = 1_100 - (System.currentTimeMillis() - lastCall)
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
            }
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

    /** Lo que hay a menos de [radius] metros, lo más cerca primero. Sin red, lo último que llegó para ese sitio. */
    fun around(context: Context, lat: Double, lon: Double, radius: Int = 2500): List<Place> {
        val key = "%.3f,%.3f".format(Locale.US, lat, lon)
        val now = System.currentTimeMillis()
        nearby[key]?.takeIf { now - it.second < 10 * 60_000L }?.let { return it.first }
        val query = "[out:json][timeout:20];(" +
            "nwr(around:$radius,$lat,$lon)[amenity~\"^(fuel|restaurant|fast_food|cafe|pharmacy|hospital|clinic|bank|atm|police)\$\"];" +
            "nwr(around:$radius,$lat,$lon)[shop~\"^(supermarket|convenience)\$\"];" +
            ");out center 250;"
        val list = runCatching {
            val c = Https.open(context, "https://overpass-api.de/api/interpreter?data=" + URLEncoder.encode(query, "UTF-8")).apply {
                connectTimeout = 10_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                if (c.responseCode != 200) return@runCatching null
                val elements = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("elements") ?: return@runCatching null
                (0 until elements.length()).mapNotNull { i -> parse(elements.getJSONObject(i), lat, lon) }
            } finally {
                c.disconnect()
            }
        }.getOrNull() ?: return nearby[key]?.first.orEmpty()
        val sorted = list.sortedBy { it.meters }
        nearby[key] = sorted to now
        return sorted
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
