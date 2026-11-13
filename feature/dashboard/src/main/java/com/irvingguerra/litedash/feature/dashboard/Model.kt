package com.irvingguerra.litedash.feature.dashboard

import androidx.compose.runtime.Immutable

/** Lado mínimo de una celda: de él sale cuántas columnas y filas caben en la pantalla. */
const val MIN_CELL_DP = 96

/** Tamaño de la cuadrícula en celdas. Más pantalla = más celdas = más libertad. */
@Immutable
data class Grid(val columns: Int, val rows: Int) {
    val vertical get() = rows >= columns
    val key get() = "${columns}x$rows"

    companion object {
        fun forSize(widthDp: Float, heightDp: Float) = Grid(
            columns = (widthDp / MIN_CELL_DP).toInt().coerceAtLeast(4),
            rows = (heightDp / MIN_CELL_DP).toInt().coerceAtLeast(3),
        )
    }
}

/**
 * Una pieza del tablero. [type] dice qué se dibuja (lo registra la app) y
 * [data] guarda lo que la pieza necesite, como el componente de una app.
 */
@Immutable
data class Tile(
    val id: String,
    val type: String,
    val col: Int,
    val row: Int,
    val width: Int,
    val height: Int,
    val data: String = "",
    /** Sin fondo de tarjeta: lo de adentro flota sobre el fondo del inicio. */
    val plain: Boolean = false,
    /** Página del inicio (0 = la primera): lo que no cabe en una página pasa a la siguiente. */
    val page: Int = 0,
) {
    fun overlaps(other: Tile) =
        page == other.page && col < other.col + other.width && other.col < col + width &&
            row < other.row + other.height && other.row < row + height
}

/**
 * El tamaño mínimo de un tipo de pieza (en celdas). No hay máximo: cada pieza crece lo que quiera
 * mientras quepa en la cuadrícula ([clamp] con el espacio que le queda).
 */
data class SizeLimits(val minWidth: Int, val minHeight: Int) {
    fun clamp(width: Int, height: Int, maxWidth: Int, maxHeight: Int) =
        width.coerceIn(minWidth, maxOf(minWidth, maxWidth)) to height.coerceIn(minHeight, maxOf(minHeight, maxHeight))
}

/** ¿Cabe [p] en la cuadrícula sin encimarse con las demás (ignorando a ella misma)? */
fun List<Tile>.fits(p: Tile, c: Grid): Boolean =
    p.col >= 0 && p.row >= 0 && p.width >= 1 && p.height >= 1 &&
        p.col + p.width <= c.columns && p.row + p.height <= c.rows &&
        none { it.id != p.id && it.overlaps(p) }

/** Primer lugar libre (de arriba abajo, de izquierda a derecha) para una pieza de ese tamaño. */
fun List<Tile>.freeSpot(width: Int, height: Int, c: Grid, page: Int = 0): Pair<Int, Int>? {
    for (row in 0..c.rows - height) for (col in 0..c.columns - width) {
        if (fits(Tile("?", "?", col, row, width, height, page = page), c)) return col to row
    }
    return null
}

/** Primer lugar libre desde la página [from]; si ninguna tiene, el principio de una página nueva. Devuelve (col, row, página). */
fun List<Tile>.freeSpotAnyPage(width: Int, height: Int, c: Grid, from: Int = 0): Triple<Int, Int, Int>? {
    val last = (maxOfOrNull { it.page } ?: 0) + 1
    for (page in from..maxOf(from, last)) {
        freeSpot(width, height, c, page)?.let { (col, row) -> return Triple(col, row, page) }
    }
    return null
}

/** Quita las páginas vacías: las que quedan se numeran seguidas desde 0. */
fun List<Tile>.compactPages(): List<Tile> {
    val pages = map { it.page }.distinct().sorted()
    if (pages == pages.indices.toList()) return this
    return map { t -> t.copy(page = pages.indexOf(t.page)) }
}

/**
 * Casi la misma cuadrícula (misma orientación, ±1 columna y ±1 fila): la misma pantalla con una barra
 * del sistema de más o de menos. Entre ellas se usa el último acomodo que se guardó.
 */
fun Grid.near(other: Grid) =
    vertical == other.vertical && kotlin.math.abs(columns - other.columns) <= 1 && kotlin.math.abs(rows - other.rows) <= 1

/**
 * Lleva un acomodo de otra cuadrícula a [c]: cada pieza se queda donde estaba si cabe; si se sale, se
 * recorta hasta su mínimo ([limits]) o se recorre hacia adentro; si choca con otra, va al primer hueco
 * (con su tamaño o el mínimo). La que no cabe de ninguna forma se queda fuera.
 */
fun List<Tile>.adaptTo(c: Grid, limits: (String) -> SizeLimits): List<Tile> {
    val placed = mutableListOf<Tile>()
    for (t in this) {
        val m = limits(t.type)
        if (m.minWidth > c.columns || m.minHeight > c.rows) continue
        val width = t.width.coerceAtMost(maxOf(m.minWidth, c.columns - t.col)).coerceIn(m.minWidth, c.columns)
        val height = t.height.coerceAtMost(maxOf(m.minHeight, c.rows - t.row)).coerceIn(m.minHeight, c.rows)
        val inside = t.copy(
            col = t.col.coerceIn(0, c.columns - width),
            row = t.row.coerceIn(0, c.rows - height),
            width = width,
            height = height,
        )
        val spot = if (placed.fits(inside, c)) inside else
            listOf(width to height, m.minWidth to m.minHeight).firstNotNullOfOrNull { (w, h) ->
                placed.freeSpotAnyPage(w, h, c, t.page)?.let { (col, row, page) -> t.copy(col = col, row = row, width = w, height = h, page = page) }
            }
        if (spot != null) placed += spot
    }
    return placed
}

/** Reemplaza la pieza con el mismo id. */
fun List<Tile>.replace(p: Tile) = map { if (it.id == p.id) p else it }

// Tipos de pieza que conocen las plantillas. La app registra cómo se dibuja cada uno.
// Los valores se guardan en cada acomodo: van en inglés y no se traducen.
object TileTypes {
    /** El auto 3D que gira con el dedo. */
    const val CAR = "car"
    /** Tu auto de espaldas en una carretera que avanza con la velocidad. */
    const val ROAD = "road"
    /** Una foto tuya; [Tile.data] es el nombre de su archivo (vacío = sin foto todavía). */
    const val IMAGE = "image"
    const val MUSIC = "music"
    /** La radio de la pantalla (FM) o la de LiteDash por internet. */
    const val RADIO = "radio"
    /** Mapa de OpenStreetMap con tu posición (feature/map). */
    const val MAP = "map"
    const val CLOCK = "clock"
    const val PHONE = "phone"
    const val WEATHER = "weather"
    const val TRIP = "trip"
    const val SPEEDOMETER = "speedometer"
    /** Un dato del auto por OBD; [Tile.data] dice cuál (una señal del bus). */
    const val GAUGE = "gauge"
    const val WORKSHOP = "workshop"
    const val PROJECTION = "projection"
    /** Presión de las cuatro llantas (módulo de llantas). */
    const val TIRES = "tires"
    const val APP = "app"
    /** Enciende y apaga el Wi‑Fi. */
    const val WIFI = "wifi"
    /** Enciende y apaga el Bluetooth. */
    const val BLUETOOTH = "bluetooth"
    /** Contactos favoritos (con estrella) de la agenda; un toque llama. */
    const val FAVORITES = "favorites"
    /** Un widget de otra app de Android (p. ej. Musixmatch); [Tile.data] es su appWidgetId. */
    const val WIDGET = "widget"
    /** La guía de la navegación del carro (calle, giro, distancias, límite); viene por broadcast de Amap. */
    const val CAR_NAV = "car_nav"
    /** Brújula con el rumbo del carro (rumbo del GPS). */
    const val COMPASS = "compass"
    /** Cambia entre el tema claro y el oscuro con un toque. */
    const val THEME = "theme"
    /** Las últimas apps abiertas desde LiteDash: una por casilla, tantas como casillas ocupe. */
    const val RECENT_APPS = "recent_apps"
    /** El asistente: un toque lo abre para preguntarle hablando. */
    const val ASSISTANT = "assistant"
}

/** Sin fondo de tarjeta siempre: el auto, la carretera y la imagen flotan sobre el inicio. */
val Tile.cardless get() = type == TileTypes.CAR || type == TileTypes.ROAD || type == TileTypes.IMAGE

/** Tamaños de pantalla, para agrupar los acomodos y sugerir el mejor. */
enum class ScreenSize { SMALL, MEDIUM, LARGE, VERTICAL }

/** Qué tamaño es esta cuadrícula: de 7" (10×4) a 11" o más (13×6 y arriba). */
val Grid.size: ScreenSize
    get() = when {
        vertical -> ScreenSize.VERTICAL
        columns * rows >= 75 -> ScreenSize.LARGE
        columns * rows >= 55 -> ScreenSize.MEDIUM
        else -> ScreenSize.SMALL
    }

/** Los acomodos de inicio y para qué pantallas lucen mejor ([sizes]). */
enum class Template(val sizes: Set<ScreenSize>) {
    /** Como el Model 3: el auto grande al centro y abajo la música con el mapa (o la hora, si no cabe). */
    CENTER(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM)),
    /** Como DuduAuto: el auto a todo lo ancho y una fila de tarjetas abajo (mapa, música, velocidad). */
    CARDS(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** El auto a la izquierda y a la derecha la hora, la música y el mapa. */
    SIDE(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tres columnas altas: mapa, música y el auto. */
    COLUMNS(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Tablero completo: tarjetas a los dos lados y el auto en medio. */
    PANEL(setOf(ScreenSize.LARGE)),
    /** Pantallas paradas: auto arriba, música y mapa abajo. */
    VERTICAL(setOf(ScreenSize.VERTICAL)),
    /** Solo el velocímetro, grande al centro: nada más que ver. */
    SPEED(ScreenSize.entries.toSet()),
    /** El auto con el velocímetro al lado; música y mapa abajo. */
    CAR_SPEED(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Deportiva: el auto arriba y abajo revoluciones, velocidad y temperatura del motor. */
    SPORT(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Sin mapa: el auto, la música grande y la radio. */
    MUSIC_RADIO(ScreenSize.entries.toSet()),
    /** Mínima: la hora en grande y la música, sin auto. */
    MINIMAL(ScreenSize.entries.toSet()),
    /** El velocímetro al centro con la hora y la fecha a un lado, y el clima y el viaje al otro. */
    SPEED_WIDGETS(ScreenSize.entries.toSet()),
    /** Para lucir tu fondo (como BYD): la hora flotando arriba y una fila de tarjetas abajo. */
    WALL_CARDS(ScreenSize.entries.toSet()),
    /** Tu fondo a la izquierda y una columna de tarjetas a la derecha, con la hora flotando. */
    WALL_SIDE(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Como el Model 3 navegando: el auto y la música del lado del conductor y el mapa grande (~55 %) al otro. */
    MAP_CAR(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** El mapa en columna alta, el auto al centro y la música y la radio del lado del copiloto. */
    COPILOT(setOf(ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Para manejar con navegación: el mapa casi completo y una columna angosta con velocidad y música. */
    ROUTE(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Pantallas paradas: el mapa arriba y abajo el auto con la música y la radio. */
    VERTICAL_MAP(setOf(ScreenSize.VERTICAL)),
    /** Diseño del Okavango: el auto con el clima, los favoritos al lado y abajo la música, el Wi‑Fi y el Bluetooth. */
    OKAVANGO(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
    /** Navegación del carro (giros) de un lado y la música del otro, sin auto. */
    NAV_MUSIC(setOf(ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE)),
}

/** Las que no llevan auto (para quien no lo quiere ver en el inicio). */
val Template.hasCar get() = this !in setOf(Template.SPEED, Template.MINIMAL, Template.SPEED_WIDGETS, Template.WALL_CARDS, Template.WALL_SIDE, Template.ROUTE, Template.NAV_MUSIC)

/**
 * Acomodos de inicio. Se calculan para cualquier cuadrícula: lo que no quepa se
 * omite, y nunca se enciman (lo revisan las pruebas). La pieza grande es el auto 3D
 * o, con [hero] = [TileTypes.ROAD], la carretera en su lugar. Los tipos de [skip] (de un
 * módulo apagado) no se ponen; la deportiva cambia sus medidores por la hora y el viaje.
 */
fun Template.tiles(c: Grid, hero: String = TileTypes.CAR, skip: (String) -> Boolean = { false }): List<Tile> {
    val cols = c.columns
    val rows = c.rows
    val list = mutableListOf<Tile>()
    fun place(kind: String, col: Int, row: Int, width: Int, height: Int, data: String = "", plain: Boolean = false) {
        val type = if (kind == TileTypes.CAR) hero else kind
        if (skip(type)) return
        val p = Tile("$type-${list.size}", type, col, row, width, height, data, plain)
        if (width >= 1 && height >= 1 && list.fits(p, c)) list += p
    }
    // El mapa solo luce de 3×2 para arriba; más chico va [fallback] en su lugar (o nada).
    fun mapOr(fallback: String?, col: Int, row: Int, width: Int, height: Int) {
        if (width >= 3 && height >= 2) place(TileTypes.MAP, col, row, width, height)
        else if (fallback != null) place(fallback, col, row, width, height)
    }
    // No caben columnas: el auto arriba y la música abajo.
    fun carOverMusic() {
        place(TileTypes.CAR, 0, 0, cols, rows - 1)
        place(TileTypes.MUSIC, 0, rows - 1, cols, 1)
    }
    // Música y radio apiladas (3 filas cuando mucho cada una); si sobra, la hora abajo.
    fun musicAndRadio(col: Int, row: Int, width: Int, height: Int) {
        val music = minOf(3, (height + 1) / 2)
        val radio = minOf(3, height - music)
        place(TileTypes.MUSIC, col, row, width, music)
        place(TileTypes.RADIO, col, row + music, width, radio)
        if (music + radio < height) place(TileTypes.CLOCK, col, row + music + radio, width, height - music - radio)
    }
    // Fila de tarjetas de abajo: de dos filas desde 5, para que quepa el mapa.
    val strip = if (rows >= 5) 2 else 1
    when (this) {
        Template.CENTER -> {
            val carWidth = (cols * 0.6).toInt().coerceAtLeast(3)
            val start = (cols - carWidth) / 2
            place(TileTypes.CAR, start, 0, carWidth, rows - strip)
            // Las tarjetas de abajo miden lo mismo que el auto, alineadas con él.
            val musicWidth = (carWidth * 0.6).toInt().coerceAtLeast(3)
            place(TileTypes.MUSIC, start, rows - strip, musicWidth, strip)
            mapOr(TileTypes.CLOCK, start + musicWidth, rows - strip, carWidth - musicWidth, strip)
        }
        Template.CARDS -> {
            place(TileTypes.CAR, 0, 0, cols, rows - strip)
            val music = maxOf(3, cols * 4 / 10)
            val rest = cols - music
            val row = rows - strip
            if (strip >= 2 && rest >= 3) {
                // Mapa, música (la más ancha) y, si sobra, la velocidad.
                val speed = if (rest >= 5) rest / 2 else 0
                val map = rest - speed
                place(TileTypes.MAP, 0, row, map, strip)
                place(TileTypes.MUSIC, map, row, music, strip)
                place(TileTypes.SPEEDOMETER, map + music, row, speed, strip)
            } else {
                // En una fila el mapa no luce: velocidad, música y hora.
                val speed = rest / 2
                place(TileTypes.SPEEDOMETER, 0, row, speed, strip)
                place(TileTypes.MUSIC, speed, row, music, strip)
                place(TileTypes.CLOCK, speed + music, row, rest - speed, strip)
            }
        }
        Template.SIDE -> {
            val carWidth = (cols * 0.4).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, carWidth, rows)
            val rest = cols - carWidth
            val music = if (rows >= 5) 2 else 1
            place(TileTypes.CLOCK, carWidth, 0, rest, 1)
            place(TileTypes.MUSIC, carWidth, 1, rest, music)
            // Sin lugar para el mapa (pantallas de 3 filas), la radio: sin huecos.
            mapOr(TileTypes.RADIO, carWidth, 1 + music, rest, rows - 1 - music)
        }
        Template.COLUMNS -> {
            if (cols < 9) carOverMusic()
            else {
                val mapWidth = maxOf(3, cols / 4)
                val musicWidth = maxOf(3, cols / 3)
                val carStart = mapWidth + musicWidth
                mapOr(null, 0, 0, mapWidth, rows)
                val musicHeight = minOf(rows, 3)
                place(TileTypes.MUSIC, mapWidth, 0, musicWidth, musicHeight)
                if (rows > musicHeight) place(TileTypes.CLOCK, mapWidth, musicHeight, musicWidth, rows - musicHeight)
                place(TileTypes.CAR, carStart, 0, cols - carStart, rows)
            }
        }
        Template.PANEL -> {
            val side = cols / 4
            if (side < 3 || cols - 2 * side < 3) carOverMusic()
            else {
                place(TileTypes.CAR, side, 0, cols - 2 * side, rows)
                val half = (rows + 1) / 2
                val music = minOf(half, 3)
                place(TileTypes.MUSIC, 0, 0, side, music)
                mapOr(TileTypes.RADIO, 0, music, side, rows - music)
                place(TileTypes.CLOCK, cols - side, 0, side, half)
                place(TileTypes.PHONE, cols - side, half, side, rows - half)
            }
        }
        Template.VERTICAL -> {
            val carHeight = (rows * 0.45).toInt().coerceAtLeast(3)
            place(TileTypes.CAR, 0, 0, cols, carHeight)
            place(TileTypes.MUSIC, 0, carHeight, cols, 2)
            mapOr(TileTypes.CLOCK, 0, carHeight + 2, cols, rows - carHeight - 2)
        }
        Template.OKAVANGO -> {
            val top = rows - strip
            // Los favoritos solo cuando hay ancho (en una pantalla angosta, el auto ocupa todo).
            val fav = if (cols >= 9) maxOf(3, cols * 3 / 10) else 0
            place(TileTypes.CAR, 0, 0, cols - fav, top)
            if (fav > 0) place(TileTypes.FAVORITES, cols - fav, 0, fav, top)
            val music = maxOf(3, cols * 4 / 10).coerceAtMost(cols)
            place(TileTypes.MUSIC, 0, rows - strip, music, strip)
            // Wi‑Fi y Bluetooth; lo que sobre queda libre para agregar un widget de Android.
            val rest = cols - music
            val unit = if (rest >= 4) 2 else 1
            if (rest >= unit) place(TileTypes.WIFI, music, rows - strip, unit, strip)
            if (rest >= 2 * unit) place(TileTypes.BLUETOOTH, music + unit, rows - strip, unit, strip)
        }
        Template.NAV_MUSIC -> {
            val music = maxOf(3, cols * 4 / 10).coerceAtMost(cols - 2)
            place(TileTypes.CAR_NAV, 0, 0, cols - music, rows)
            val musicRows = minOf(rows, 3)
            place(TileTypes.MUSIC, cols - music, 0, music, musicRows)
            if (rows > musicRows) place(TileTypes.CLOCK, cols - music, musicRows, music, rows - musicRows)
        }
        Template.SPEED -> {
            // Un poco más ancho que alto (el arco es así); en pantallas paradas, cuadrado al centro.
            val height = if (c.vertical) minOf(rows, cols) else rows
            val width = minOf(cols, maxOf(2, (height * 1.4f + 0.5f).toInt()))
            place(TileTypes.SPEEDOMETER, (cols - width) / 2, (rows - height) / 2, width, height, plain = true)
        }
        Template.CAR_SPEED -> {
            val speedWidth = maxOf(2, (cols * 0.35f).toInt())
            place(TileTypes.CAR, 0, 0, cols - speedWidth, rows - strip)
            place(TileTypes.SPEEDOMETER, cols - speedWidth, 0, speedWidth, rows - strip)
            val music = maxOf(3, cols * 6 / 10).coerceAtMost(cols)
            place(TileTypes.MUSIC, 0, rows - strip, music, strip)
            mapOr(TileTypes.CLOCK, music, rows - strip, cols - music, strip)
        }
        Template.SPORT -> {
            // Con 5 filas o más los medidores llevan arco; con menos, número y barra.
            place(TileTypes.CAR, 0, 0, cols, rows - strip)
            val third = cols / 3
            // Señales del bus (core/vehicle): revoluciones y temperatura del motor. Sin OBD, la hora y el viaje.
            val obd = !skip(TileTypes.GAUGE)
            if (obd) place(TileTypes.GAUGE, 0, rows - strip, third, strip, data = "rpm")
            else place(TileTypes.CLOCK, 0, rows - strip, third, strip)
            place(TileTypes.SPEEDOMETER, third, rows - strip, cols - 2 * third, strip)
            if (obd) place(TileTypes.GAUGE, cols - third, rows - strip, third, strip, data = "coolant_temp")
            else place(TileTypes.TRIP, cols - third, rows - strip, third, strip)
        }
        Template.MUSIC_RADIO -> {
            if (c.vertical) {
                val cards = minOf(6, rows / 2)
                place(TileTypes.CAR, 0, 0, cols, rows - cards)
                musicAndRadio(0, rows - cards, cols, cards)
            } else {
                val carWidth = maxOf(3, (cols * 0.45f).toInt())
                if (cols - carWidth < 3) carOverMusic()
                else {
                    place(TileTypes.CAR, 0, 0, carWidth, rows)
                    musicAndRadio(carWidth, 0, cols - carWidth, rows)
                }
            }
        }
        Template.MINIMAL -> {
            val bottom = if (rows >= 6) 2 else 1
            val width = minOf(cols, maxOf(4, cols * 6 / 10))
            val start = (cols - width) / 2
            place(TileTypes.CLOCK, start, 0, width, rows - bottom)
            place(TileTypes.MUSIC, start, rows - bottom, width, bottom)
        }
        Template.SPEED_WIDGETS -> {
            val side = cols / 4
            if (c.vertical || side < 2 || cols - 2 * side < 3) {
                // No caben las tres columnas: el velocímetro arriba y abajo la hora y el clima.
                val bottom = if (rows >= 6) 2 else 1
                val half = cols / 2
                place(TileTypes.SPEEDOMETER, 0, 0, cols, rows - bottom, plain = true)
                place(TileTypes.CLOCK, 0, rows - bottom, half, bottom)
                place(TileTypes.WEATHER, half, rows - bottom, cols - half, bottom)
            } else {
                val top = (rows + 1) / 2
                place(TileTypes.CLOCK, 0, 0, side, rows, plain = true)
                place(TileTypes.SPEEDOMETER, side, 0, cols - 2 * side, rows, plain = true)
                place(TileTypes.WEATHER, cols - side, 0, side, top)
                place(TileTypes.TRIP, cols - side, top, side, rows - top)
            }
        }
        Template.WALL_CARDS -> {
            // Arriba queda libre para el fondo; la hora flota sin tarjeta.
            val bottom = if (rows >= 6) 2 else 1
            place(TileTypes.CLOCK, 0, 0, maxOf(2, cols / 2), if (rows >= 5) 2 else 1, plain = true)
            val music = maxOf(3, cols * 4 / 10).coerceAtMost(cols)
            val rest = cols - music
            // Mapa (o radio en una fila) y clima; si no caben los dos, solo el clima.
            val first = if (rest >= 5) maxOf(3, rest / 2) else 0
            mapOr(TileTypes.RADIO, 0, rows - bottom, first, bottom)
            place(TileTypes.MUSIC, first, rows - bottom, music, bottom)
            place(TileTypes.WEATHER, first + music, rows - bottom, rest - first, bottom)
        }
        Template.WALL_SIDE -> {
            val width = maxOf(3, cols / 3)
            val start = cols - width
            val clock = if (rows >= 5) 2 else 1
            place(TileTypes.CLOCK, start, 0, width, clock, plain = true)
            val rest = rows - clock
            // Con 4 filas libres cabe el mapa (2) bajo la música (2); si no, la radio en una fila.
            val weather = if (rest >= 5 || rest == 3) 1 else 0
            if (weather > 0) place(TileTypes.WEATHER, start, clock, width, 1)
            val music = if (rest >= 4) 2 else 1
            place(TileTypes.MUSIC, start, clock + weather, width, music)
            val row = clock + weather + music
            mapOr(TileTypes.RADIO, start, row, width, rows - row)
        }
        Template.MAP_CAR -> {
            // Del lado del conductor el auto y la música; el resto (~55 %), el mapa.
            val side = maxOf(3, cols - (cols * 0.55f + 0.5f).toInt())
            if (cols - side < 3) carOverMusic()
            else {
                val music = if (rows >= 6) 2 else 1
                place(TileTypes.CAR, 0, 0, side, rows - music)
                place(TileTypes.MUSIC, 0, rows - music, side, music)
                mapOr(null, side, 0, cols - side, rows)
            }
        }
        Template.COPILOT -> {
            val side = maxOf(3, (cols * 0.3f + 0.5f).toInt())
            if (cols - 2 * side < 3) carOverMusic()
            else {
                mapOr(null, 0, 0, side, rows)
                place(TileTypes.CAR, side, 0, cols - 2 * side, rows)
                musicAndRadio(cols - side, 0, side, rows)
            }
        }
        Template.ROUTE -> {
            // Columna angosta del lado del conductor: velocidad arriba y música abajo.
            val side = maxOf(3, (cols * 0.3f + 0.5f).toInt())
            val music = if (rows >= 6) 2 else 1
            place(TileTypes.SPEEDOMETER, 0, 0, side, rows - music)
            place(TileTypes.MUSIC, 0, rows - music, side, music)
            mapOr(null, side, 0, cols - side, rows)
        }
        Template.VERTICAL_MAP -> {
            val mapHeight = rows / 2
            mapOr(null, 0, 0, cols, mapHeight)
            val half = cols / 2
            place(TileTypes.CAR, 0, mapHeight, half, rows - mapHeight)
            musicAndRadio(half, mapHeight, cols - half, rows - mapHeight)
        }
    }
    return list
}

/**
 * El acomodo que mejor luce en esta pantalla. El auto 3D sigue de protagonista al
 * estrenar; en las medianas, grandes y paradas el sugerido ya trae el mapa de 2 filas.
 */
fun Grid.suggestedTemplate() = when (size) {
    ScreenSize.SMALL, ScreenSize.MEDIUM, ScreenSize.LARGE -> Template.OKAVANGO
    ScreenSize.VERTICAL -> Template.VERTICAL
}
