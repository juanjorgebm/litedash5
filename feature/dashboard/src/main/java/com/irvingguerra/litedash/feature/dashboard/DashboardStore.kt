package com.irvingguerra.litedash.feature.dashboard

import android.content.Context

/**
 * Guarda un acomodo por tamaño de cuadrícula: la misma app en otra pantalla (u
 * otra escala) no rompe el acomodo de la primera. Formato de texto simple, sin
 * librerías: una pieza por línea, campos separados por tabulador.
 */
class DashboardStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun read(c: Grid): List<Tile>? {
        val text = prefs.getString(LAYOUT_KEY_PREFIX + c.key, null) ?: return null
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 6) return@mapNotNull null
            // Una línea dañada (escritura cortada) se salta; no tumba el inicio.
            val (col, row, width, height) = (2..5).map { f[it].toIntOrNull() ?: return@mapNotNull null }
            // El widget Navegar (hasta la 1.4.68) es ahora el Mapa, en el mismo lugar y tamaño (sus
            // datos eran otra cosa: el mapa queda como de fábrica).
            val page = f.getOrElse(8) { "" }.toIntOrNull()?.coerceAtLeast(0) ?: 0
            if (f[1] == LEGACY_NAVIGATE) return@mapNotNull Tile(f[0], TileTypes.MAP, col, row, width, height, "", plain = f.getOrElse(7) { "" } == "1", page = page)
            Tile(f[0], f[1], col, row, width, height, f.getOrElse(6) { "" }, plain = f.getOrElse(7) { "" } == "1", page = page)
        }.filter { p -> p.col >= 0 && p.row >= 0 }.toList()
    }

    fun save(c: Grid, tiles: List<Tile>) {
        val text = tiles.joinToString("\n") {
            // El campo nuevo va al final: las versiones de antes lo ignoran.
            listOf(it.id, it.type, it.col, it.row, it.width, it.height, it.data.replace('\t', ' ').replace('\n', ' '), if (it.plain) "1" else "", it.page)
                .joinToString("\t")
        }
        prefs.edit().putString(LAYOUT_KEY_PREFIX + c.key, text).putLong(SAVED_AT_PREFIX + c.key, System.currentTimeMillis()).apply()
    }

    /** Cuándo se guardó el acomodo de [c] (0 si nunca o si es de antes de la 1.4.100). */
    fun savedAt(c: Grid): Long = prefs.getLong(SAVED_AT_PREFIX + c.key, 0L)

    /** Las cuadrículas que tienen acomodo guardado. */
    fun grids(): List<Grid> = prefs.all.keys.mapNotNull { key ->
        val size = key.removePrefix(LAYOUT_KEY_PREFIX).takeIf { key.startsWith(LAYOUT_KEY_PREFIX) } ?: return@mapNotNull null
        val (columns, rows) = size.split('x').takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return@mapNotNull null } ?: return@mapNotNull null
        Grid(columns, rows)
    }

    /** La pieza grande de las plantillas (tipo y datos): el auto 3D si nunca se eligió otra. */
    fun hero(): Pair<String, String> =
        (prefs.getString(HERO_TYPE, null) ?: TileTypes.CAR) to (prefs.getString(HERO_DATA, null) ?: "")

    /** Elige la pieza grande de las plantillas; sus datos quedan vacíos (los de fábrica). */
    fun saveHero(type: String) {
        prefs.edit().putString(HERO_TYPE, type).putString(HERO_DATA, "").apply()
    }
}

// Nombres guardados en el dispositivo (en inglés desde la 1.4.47; la migración copió los viejos).
private const val PREFS_FILE = "dashboard"
private const val LAYOUT_KEY_PREFIX = "layout_"
/** Hora en que se guardó cada acomodo (no empieza con "layout_" para no confundirse con ellos). */
private const val SAVED_AT_PREFIX = "saved_at_"
// La migración de la 1.4.49 (pieza del auto en tres) también los escribe: mismos nombres allá.
/** El tipo que tenía el widget Navegar en los acomodos guardados. */
private const val LEGACY_NAVIGATE = "navigate"
private const val HERO_TYPE = "hero_type"
private const val HERO_DATA = "hero_data"
