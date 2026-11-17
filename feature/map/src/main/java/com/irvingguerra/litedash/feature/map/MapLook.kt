package com.irvingguerra.litedash.feature.map

import androidx.compose.runtime.Immutable

/** Qué queda arriba: el norte, o hacia donde vas (el mapa gira y tu flecha siempre apunta arriba). */
enum class MapOrientation(val stored: String) { NORTH("north"), HEADING("heading") }

/** El mapa plano, o acostado hacia el horizonte (como al navegar: se ve más camino adelante). */
enum class MapView(val stored: String) { FLAT("flat"), TILTED("tilted") }

/** Según la velocidad, o fijo (cerca = calles, lejos = colonias). */
enum class MapZoom(val stored: String, val level: Int?) { AUTO("auto", null), NEAR("near", 17), MID("mid", 16), FAR("far", 15) }

/** Los colores: como el tema de LiteDash (día y noche), o siempre claros u oscuros. */
enum class MapStyle(val stored: String) { THEME("theme"), LIGHT("light"), DARK("dark") }

/** Minimalista (solo calles, agua y parques; de fábrica desde la 1.4.71) o todo lo de OpenStreetMap (calles, negocios, nombres). */
enum class MapDetail(val stored: String) { FULL("full"), MINIMAL("minimal") }

/**
 * Cómo se ve el widget Mapa; se guarda en su `Tile.data` como "orientation=heading;view=tilted…".
 * Lo de fábrica (minimalista, hacia donde vas, plano, automático, como el tema) no se escribe.
 */
@Immutable
data class MapLook(
    val orientation: MapOrientation = MapOrientation.HEADING,
    val view: MapView = MapView.FLAT,
    val zoom: MapZoom = MapZoom.AUTO,
    val style: MapStyle = MapStyle.THEME,
    val detail: MapDetail = MapDetail.MINIMAL,
) {
    /** ¿Se ve oscuro? Con [MapStyle.THEME], lo que diga el tema de LiteDash ([themeDark]). */
    fun dark(themeDark: Boolean) = when (style) {
        MapStyle.THEME -> themeDark
        MapStyle.LIGHT -> false
        MapStyle.DARK -> true
    }

    fun encode(): String = listOfNotNull(
        orientation.takeIf { it != MapOrientation.HEADING }?.let { "$ORIENTATION=${it.stored}" },
        view.takeIf { it != MapView.FLAT }?.let { "$VIEW=${it.stored}" },
        zoom.takeIf { it != MapZoom.AUTO }?.let { "$ZOOM=${it.stored}" },
        style.takeIf { it != MapStyle.THEME }?.let { "$STYLE=${it.stored}" },
        detail.takeIf { it != MapDetail.MINIMAL }?.let { "$DETAIL=${it.stored}" },
    ).joinToString(";")

    companion object {
        private const val ORIENTATION = "orientation"
        private const val VIEW = "view"
        private const val ZOOM = "zoom"
        private const val STYLE = "style"
        private const val DETAIL = "detail"

        fun parse(data: String): MapLook {
            val f = data.split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            return MapLook(
                orientation = MapOrientation.entries.firstOrNull { it.stored == f[ORIENTATION] } ?: MapOrientation.HEADING,
                view = MapView.entries.firstOrNull { it.stored == f[VIEW] } ?: MapView.FLAT,
                zoom = MapZoom.entries.firstOrNull { it.stored == f[ZOOM] } ?: MapZoom.AUTO,
                style = MapStyle.entries.firstOrNull { it.stored == f[STYLE] } ?: MapStyle.THEME,
                detail = MapDetail.entries.firstOrNull { it.stored == f[DETAIL] } ?: MapDetail.MINIMAL,
            )
        }
    }
}
