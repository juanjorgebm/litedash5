package com.irvingguerra.litedash

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import com.irvingguerra.litedash.feature.obd.ObdState
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.apps.AppGrid
import com.irvingguerra.litedash.feature.dashboard.TileTypes

/** Panel a pantalla completa sobre el inicio, con título y botón para cerrar. */
@Composable
internal fun PanelScaffold(
    title: String,
    close: () -> Unit,
    actions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
            actions()
            IconButton(Icons.close, close, background = c.surfaceHigh)
        }
        content()
    }
}

@Composable
fun DrawerPanel(d: Dependencies, apps: List<App>, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<App?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var noRoom by remember { mutableStateOf(false) }
    // Sin dock propio se ven las sugeridas: fijar parte de ellas (no de cero).
    val dock = d.prefs.dock ?: remember(apps) { dockFavorites(d, apps).map { it.key } }
    val hidden = d.prefs.hidden
    val visible = remember(apps, hidden, showHidden) { if (showHidden) apps else apps.filter { it.key !in hidden } }
    PanelScaffold(stringResource(R.string.apps), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        val app = selected
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        if (app == null) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.drawer_help), Typography.small, c.textMuted, modifier = Modifier.weight(1f))
                if (hidden.isNotEmpty()) {
                    Label(
                        stringResource(if (showHidden) R.string.hide_hidden else R.string.show_hidden, hidden.size),
                        Typography.small, c.accent,
                        modifier = Modifier.clickable { showHidden = !showHidden }.padding(8.dp),
                    )
                }
            }
        } else {
            // Acciones de la app que se dejó presionada.
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Label(app.name, Typography.subtitle, maxLines = 1, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
                val inDock = app.key in dock
                Button(stringResource(if (inDock) R.string.unpin_from_dock else R.string.pin_to_dock), {
                    d.prefs.dock = if (inDock) dock - app.key else dock + app.key
                    selected = null
                })
                Button(stringResource(R.string.add_to_home), {
                    noRoom = !d.dashboard.addTile(TileTypes.APP, app.key)
                    selected = null
                })
                val isHidden = app.key in hidden
                Button(stringResource(if (isHidden) R.string.show_app else R.string.hide_app), {
                    d.prefs.hidden = if (isHidden) hidden - app.key else hidden + app.key
                    selected = null
                })
                Button(stringResource(R.string.app_info), {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${app.pkg}".toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                    selected = null
                })
                IconButton(Icons.close, { selected = null }, size = 48.dp, background = c.surfaceHigh)
            }
        }
        AppGrid(
            d.apps, visible, query,
            onClick = { d.apps.open(context, it); close() },
            onLongPress = { selected = it },
            marked = setOfNotNull(selected?.key),
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

@Composable
fun PickAppPanel(d: Dependencies, apps: List<App>, tileId: String?, close: () -> Unit) {
    val dashboard = d.dashboard
    var query by remember { mutableStateOf("") }
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.pick_app), close) {
        TextInput(query, { query = it }, stringResource(R.string.search_app), Modifier.fillMaxWidth().widthIn(max = 640.dp), icon = true)
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, LocalColors.current.danger, modifier = Modifier.padding(top = 10.dp, start = 8.dp))
        AppGrid(
            d.apps, apps, query,
            onClick = { app ->
                if (tileId != null) {
                    dashboard.setData(tileId, app.key)
                    close()
                } else if (dashboard.addTile(TileTypes.APP, app.key)) {
                    close()
                } else {
                    noRoom = true
                }
            },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
        )
    }
}

/**
 * Qué dato del auto muestra un medidor: los que lee el OBD, con su ícono. Los que
 * este auto no da (ya conectado) se ven apagados y avisan.
 */
@Composable
fun PickGaugePanel(d: Dependencies, tileId: String?, close: () -> Unit) {
    val c = LocalColors.current
    val obd = d.obdIfCreated()?.takeIf { it.state == ObdState.ON }
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.pick_gauge), close) {
        Label(stringResource(R.string.pick_gauge_help), Typography.body, c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        CardGrid(GaugeSpecs, minWidth = 200.dp, height = 120.dp, onClick = { spec ->
            if (tileId != null) {
                // Cambia el dato y se queda con su estilo.
                val old = d.dashboard.tiles.firstOrNull { it.id == tileId }?.data ?: ""
                d.dashboard.setData(tileId, GaugeLook.parse(old).encode(spec.signal))
                close()
            } else if (d.dashboard.addTile(TileTypes.GAUGE, spec.signal)) close()
            else noRoom = true
        }) { spec ->
            val readable = obd?.canRead(spec.signal) ?: true
            Icon(spec.icon, if (readable) c.accent else c.textMuted, 28.dp)
            Label(stringResource(spec.name), Typography.subtitle, if (readable) c.text else c.textMuted, maxLines = 1)
            if (!readable) Label(stringResource(R.string.gauge_unsupported), Typography.small, c.textMuted, maxLines = 1)
        }
    }
}

/**
 * Tarjetas en cuadrícula, todas a la vista: tantas columnas como quepan (de [minWidth]
 * o más, mínimo dos) y, si aun así no alcanza el alto (pantallas muy chicas), se desplaza.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ColumnScope.CardGrid(
    items: List<T>,
    minWidth: Dp,
    height: Dp,
    onClick: (T) -> Unit,
    content: @Composable ColumnScope.(T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        val gap = 14.dp
        val columns = ((maxWidth + gap) / (minWidth + gap)).toInt().coerceAtLeast(2)
        val width = (maxWidth - gap * (columns - 1)) / columns
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            for (item in items) {
                Card(Modifier.width(width).height(height), onClick = { onClick(item) }) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content(item) }
                }
            }
        }
    }
}

@Composable
fun AddTilePanel(d: Dependencies, close: () -> Unit, onPickApp: () -> Unit, onPickGauge: () -> Unit, onPickWidget: () -> Unit) {
    val c = LocalColors.current
    var noRoom by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.add_tile), close) {
        if (noRoom) {
            Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        }
        // Todas a la vista: tantas columnas como quepan (de 180 dp o más).
        // Los de un módulo apagado no se ofrecen.
        CardGrid(TileSpecs.filter { moduleOff(d, it.type) == null }, minWidth = 180.dp, height = if (isSmallScreen()) 100.dp else 120.dp, onClick = { spec ->
            if (spec.type == TileTypes.APP) onPickApp()
            else if (spec.type == TileTypes.GAUGE) onPickGauge()
            else if (spec.type == TileTypes.WIDGET) onPickWidget()
            else if (d.dashboard.addTile(spec.type)) close()
            else noRoom = true
        }) { spec ->
            Icon(iconOf(spec.type), c.accent, 28.dp)
            Label(stringResource(spec.name), Typography.subtitle, maxLines = 1)
        }
    }
}

internal fun iconOf(type: String) = when (type) {
    TileTypes.CAR -> Icons.car
    TileTypes.ROAD -> Icons.road
    TileTypes.IMAGE -> Icons.image
    TileTypes.MUSIC -> Icons.music
    TileTypes.RADIO -> Icons.radio
    TileTypes.MAP -> Icons.map
    TileTypes.CLOCK -> Icons.clock
    TileTypes.PHONE -> Icons.phone
    TileTypes.WEATHER -> Icons.sun
    TileTypes.TRIP -> Icons.location
    TileTypes.SPEEDOMETER -> Icons.speed
    TileTypes.GAUGE -> Icons.tachometer
    TileTypes.WORKSHOP -> Icons.engine
    TileTypes.PROJECTION -> Icons.phoneCar
    TileTypes.TIRES -> Icons.tire
    TileTypes.WIFI -> Icons.wifi
    TileTypes.BLUETOOTH -> Icons.bluetooth
    TileTypes.FAVORITES -> Icons.phone
    TileTypes.CAR_NAV -> Icons.navigate
    TileTypes.COMPASS -> Icons.navigate
    TileTypes.WIDGET -> Icons.apps
    else -> Icons.apps
}
