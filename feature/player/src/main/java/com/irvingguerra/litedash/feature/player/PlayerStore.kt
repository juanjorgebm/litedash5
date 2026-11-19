package com.irvingguerra.litedash.feature.player

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

private const val STORE = "player"
private const val KEY_LAST_USB = "last_usb"
private const val KEY_LAST_RADIO = "last_radio"
private const val KEY_FAVORITES = "radio_favorites"
private const val KEY_NEARBY = "radio_nearby"
/** Ya se llenaron las favoritas con las de Costa Rica (una sola vez). */
private const val KEY_FAVORITES_SEEDED = "radio_favorites_seeded"
/** Cuántas estaciones de Costa Rica quedan de favoritas al estrenar. */
const val SEED_FAVORITES = 20
/** Cuántas estaciones de "Cerca de ti" se guardan para cuando no hay servidor. */
private const val NEARBY_KEEP = 60

/** Lo que el reproductor recuerda: lo último que sonó de cada fuente y las estaciones favoritas. */
class PlayerStore(context: Context) {
    private val p = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    fun rememberLast(source: PlayerSource, key: String) = p.edit { putString(keyFor(source), key) }

    fun last(source: PlayerSource): String? = p.getString(keyFor(source), null)

    /** La última estación completa (para volver a sintonizarla sin buscar). */
    fun lastStation(): Station? = p.getString(KEY_LAST_RADIO_STATION, null)?.let { runCatching { Station.fromJson(org.json.JSONObject(it)) }.getOrNull() }

    fun rememberStation(station: Station) = p.edit { putString(KEY_LAST_RADIO_STATION, station.toJson().toString()) }

    /** La última lista de "Cerca de ti" que llegó: se muestra si los servidores no contestan. */
    fun nearbyCache(): List<Station> = runCatching {
        val array = JSONArray(p.getString(KEY_NEARBY, "[]"))
        (0 until array.length()).mapNotNull { Station.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setNearbyCache(list: List<Station>) = p.edit { putString(KEY_NEARBY, JSONArray(list.take(NEARBY_KEEP).map { it.toJson() }).toString()) }

    fun favorites(): List<Station> = runCatching {
        val array = JSONArray(p.getString(KEY_FAVORITES, "[]"))
        (0 until array.length()).mapNotNull { Station.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun setFavorites(list: List<Station>) = p.edit { putString(KEY_FAVORITES, JSONArray(list.map { it.toJson() }).toString()) }

    /** Las favoritas ya se llenaron con las de Costa Rica (o el usuario ya tenía las suyas). */
    var favoritesSeeded: Boolean
        get() = p.getBoolean(KEY_FAVORITES_SEEDED, false)
        set(v) = p.edit { putBoolean(KEY_FAVORITES_SEEDED, v) }

    /** La carpeta de música que eligió el usuario con el selector de Android (null = ninguna). */
    var musicFolder: String?
        get() = p.getString(KEY_MUSIC_FOLDER, null)
        set(v) = p.edit { if (v == null) remove(KEY_MUSIC_FOLDER) else putString(KEY_MUSIC_FOLDER, v) }

    private fun keyFor(source: PlayerSource) = if (source == PlayerSource.USB) KEY_LAST_USB else KEY_LAST_RADIO

    private companion object {
        const val KEY_LAST_RADIO_STATION = "last_radio_station"
        const val KEY_MUSIC_FOLDER = "music_folder"
    }
}
