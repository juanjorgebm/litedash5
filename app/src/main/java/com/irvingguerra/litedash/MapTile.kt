package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.map.MapDetail
import com.irvingguerra.litedash.feature.map.MapCanvas
import com.irvingguerra.litedash.feature.map.MapLook
import com.irvingguerra.litedash.feature.map.MapOrientation
import com.irvingguerra.litedash.feature.map.MapStyle
import com.irvingguerra.litedash.feature.map.MapView
import com.irvingguerra.litedash.feature.map.MapZoom
import com.irvingguerra.litedash.feature.map.MinimalPalette
import com.irvingguerra.litedash.feature.map.OsmTiles
import com.irvingguerra.litedash.feature.map.TileStore
import com.irvingguerra.litedash.feature.map.VectorTiles
import com.irvingguerra.litedash.feature.map.tilePx
import com.irvingguerra.litedash.feature.music.NotificationService

/**
 * Widget Mapa: tu posición del GPS en el mapa (minimalista de OpenFreeMap o completo de OpenStreetMap),
 * como diga su [data] ([MapLook]); navegando, la siguiente vuelta encima. Un toque lo muestra en grande
 * ([onExpand]; null = ya es grande y el toque aleja el zoom); el botón de la esquina abre tu app de mapas.
 * En una fila, o sin permiso de ubicación, se ve como el widget Navegar de antes (vuelta, Casa y
 * Trabajo) y un botón para pedir el permiso o ver el mapa. GPS y descargas solo mientras se ve.
 */
@Composable
fun MapTile(d: Dependencies, data: String, app: App?, editing: Boolean, onPlace: (String) -> Unit, onNavigate: () -> Unit, onExpand: (() -> Unit)?) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!granted || maxHeight < COMPACT_HEIGHT) {
            Box(Modifier.fillMaxSize()) {
                NavigationTile(d.navigation, app, d.prefs.homeAddress, d.prefs.workAddress, onPlace, onNavigate)
                // Sin permiso, el botón lo pide; con permiso (en una fila), abre el mapa en grande (si ya es grande,
                // no hay a dónde: no sale). Al acomodar se esconde (estorba al botón de tamaño).
                val action = if (granted) onExpand else ask
                if (!editing && action != null) IconButton(Icons.map, action, Modifier.align(Alignment.TopEnd).padding(6.dp), size = 40.dp, background = c.surfaceHigh, color = c.accent)
            }
            return@BoxWithConstraints
        }
        Card(Modifier.fillMaxSize()) {
            LiveMap(d, look, Modifier.fillMaxSize(), onTap = onExpand)
            NextTurn(d, Modifier.align(Alignment.TopStart).padding(8.dp))
            IconButton(Icons.navigate, onNavigate, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 44.dp, background = c.surface.copy(alpha = 0.85f), color = c.accent)
        }
    }
}

/**
 * El mapa en grande (media pantalla), como el widget que se tocó ([data]): el mapa en vivo (un toque
 * lo aleja), la siguiente vuelta, Casa y Trabajo, y abrir tu app de mapas.
 */
@Composable
fun MapExpanded(d: Dependencies, data: String, app: App?, onPlace: (String) -> Unit, onNavigate: () -> Unit, close: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    Card(modifier.fillMaxSize()) {
        if (granted) LiveMap(d, look, Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(stringResource(R.string.tap_to_enable), ask, primary = true) }
        NextTurn(d, Modifier.align(Alignment.TopStart).padding(12.dp))
        Row(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(Icons.navigate, onNavigate, size = 48.dp, background = c.surface.copy(alpha = 0.9f), color = c.accent)
            IconButton(Icons.close, close, size = 48.dp, background = c.surface.copy(alpha = 0.9f))
        }
        val home = d.prefs.homeAddress
        val work = d.prefs.workAddress
        if (home != null || work != null) Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (home != null) Button(stringResource(R.string.place_home), { onPlace(home) }, primary = true)
            if (work != null) Button(stringResource(R.string.place_work), { onPlace(work) })
        }
    }
}

/** La siguiente vuelta que dice el aviso de Waze o Maps, en una tarjeta sobre el mapa (nada si no navegas). */
@Composable
private fun NextTurn(d: Dependencies, modifier: Modifier) {
    val repo = d.navigation
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val instruction = repo.instruction ?: return
    Box(modifier.clip(RoundedCornerShape(Radius.card)).background(LocalColors.current.surface.copy(alpha = 0.92f)).padding(8.dp)) {
        Turn(instruction, large = false)
    }
}

/** Abajo de esto el mapa no luce: se ve como el widget Navegar. */
private val COMPACT_HEIGHT = 120.dp

/**
 * El mapa con el GPS y las teselas corriendo solo mientras se ve (el widget, en grande y la vista previa)
 * y nada lo tapa ([LocalHomeCovered]).
 */
@Composable
internal fun LiveMap(d: Dependencies, look: MapLook, modifier: Modifier, onTap: (() -> Unit)? = null) {
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val context = LocalContext.current
    val density = LocalDensity.current.density
    // Una por fuente y, solo el minimalista (se dibuja ya en sus colores), por claro u oscuro; el completo usa un filtro.
    val darkTiles = look.detail == MapDetail.MINIMAL && look.dark(c.dark)
    val store = remember(look.detail, darkTiles) { newTileStore(context, look.detail, darkTiles, density) }
    val covered = LocalHomeCovered.current
    LifecycleStartEffect(store, covered) {
        if (!covered) store.resume()
        onStopOrDispose { store.pause() }
    }
    val position = d.trip.position
    // Del bus (la del OBD si hay escáner, si no la del GPS); solo la usa el zoom automático.
    val kmh = if (look.zoom == MapZoom.AUTO) d.bus.watch(Signal.SPEED) else null
    Box(modifier) {
        if (position == null) {
            Label(stringResource(R.string.map_waiting), Typography.body, c.textMuted, maxLines = 2, modifier = Modifier.align(Alignment.Center).padding(16.dp))
        } else {
            MapCanvas(store, position.latitude, position.longitude, d.trip.bearing, kmh, look, Modifier.fillMaxSize(), onTap)
        }
    }
}

private fun newTileStore(context: Context, detail: MapDetail, dark: Boolean, density: Float): TileStore {
    val userAgent = "LiteDash/${BuildConfig.VERSION_NAME} (+https://litedash.web.app)"
    val source = when (detail) {
        MapDetail.FULL -> OsmTiles
        MapDetail.MINIMAL -> VectorTiles(context, if (dark) MinimalPalette.DARK else MinimalPalette.LIGHT, tilePx(density).toInt(), density, userAgent)
    }
    return TileStore(context, userAgent, source)
}

/** Personalizar mapa: detalle, orientación, vista, zoom y colores, con el mapa en vivo como vista previa. */
@Composable
fun CustomizeMapPanel(d: Dependencies, tileId: String, close: () -> Unit) {
    val c = LocalColors.current
    val tile = d.dashboard.tiles.firstOrNull { it.id == tileId }
    if (tile == null) {
        // Cerrar escribe estado: fuera de la composición.
        LaunchedEffect(Unit) { close() }
        return
    }
    val look = MapLook.parse(tile.data)
    fun set(new: MapLook) = d.dashboard.setData(tileId, new.encode())
    val (granted, _) = rememberGpsPermission(d)
    PanelScaffold(stringResource(R.string.map_customize), close) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val preview = @Composable { m: Modifier -> if (granted) Card(m) { LiveMap(d, look, Modifier.fillMaxSize()) } }
            val options = @Composable { m: Modifier ->
                Column(m.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Label(stringResource(R.string.map_detail), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapDetail.MINIMAL to stringResource(R.string.map_detail_minimal), MapDetail.FULL to stringResource(R.string.map_detail_full)),
                        look.detail, { set(look.copy(detail = it)) },
                    )
                    Label(stringResource(if (look.detail == MapDetail.FULL) R.string.map_detail_full_help else R.string.map_detail_minimal_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.map_orientation), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapOrientation.NORTH to stringResource(R.string.map_north), MapOrientation.HEADING to stringResource(R.string.map_heading)),
                        look.orientation, { set(look.copy(orientation = it)) },
                    )
                    Label(stringResource(R.string.map_view), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapView.FLAT to stringResource(R.string.map_flat), MapView.TILTED to stringResource(R.string.map_tilted)),
                        look.view, { set(look.copy(view = it)) },
                    )
                    Label(stringResource(R.string.map_zoom), Typography.label, c.textMuted)
                    Segmented(
                        listOf(
                            MapZoom.AUTO to stringResource(R.string.map_zoom_auto), MapZoom.NEAR to stringResource(R.string.map_zoom_near),
                            MapZoom.MID to stringResource(R.string.map_zoom_mid), MapZoom.FAR to stringResource(R.string.map_zoom_far),
                        ),
                        look.zoom, { set(look.copy(zoom = it)) },
                    )
                    Label(stringResource(R.string.map_zoom_help), Typography.small, c.textMuted)
                    Label(stringResource(R.string.map_style), Typography.label, c.textMuted)
                    Segmented(
                        listOf(MapStyle.THEME to stringResource(R.string.map_style_theme), MapStyle.LIGHT to stringResource(R.string.map_style_light), MapStyle.DARK to stringResource(R.string.map_style_dark)),
                        look.style, { set(look.copy(style = it)) },
                    )
                    Box(Modifier.height(12.dp))
                }
            }
            val previewHeight = maxHeight * 0.4f
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    preview(Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp))
                    options(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    preview(Modifier.fillMaxWidth().height(previewHeight))
                    options(Modifier.weight(1f))
                }
            }
        }
    }
}
