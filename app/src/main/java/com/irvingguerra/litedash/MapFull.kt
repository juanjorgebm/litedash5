package com.irvingguerra.litedash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MyCar
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.apps.App
import com.irvingguerra.litedash.feature.map.MapLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * El mapa a pantalla completa: el mapa en vivo a un lado y, al otro, dónde estás (calle y lugar) y los lugares cercanos
 * por categoría (gasolineras, comida, farmacias…) con la distancia; un toque en uno abre la ruta en tu app de mapas.
 */
@Composable
fun MapFullScreen(d: Dependencies, data: String, app: App?, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val (granted, ask) = rememberGpsPermission(d)
    val look = remember(data) { MapLook.parse(data) }
    val position = d.trip.position
    // Redondeado a unos 300 m: al moverse poco no se vuelve a buscar.
    val lat = Math.round((position?.latitude ?: MyCar.LATITUDE) * 300) / 300.0
    val lon = Math.round((position?.longitude ?: MyCar.LONGITUDE) * 300) / 300.0
    val address by produceState<Address?>(null, lat, lon) { value = withContext(Dispatchers.IO) { Places.reverse(context, lat, lon) } }
    var retry by remember { mutableIntStateOf(0) }
    val nearby by produceState<Places.Nearby?>(null, lat, lon, retry) { value = withContext(Dispatchers.IO) { Places.around(context, lat, lon) } }
    var kind by remember { mutableStateOf<PlaceKind?>(null) }
    PanelScaffold(stringResource(R.string.tile_map), close) {
        Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.weight(1.1f).fillMaxHeight()) {
                if (granted) LiveMap(d, look, Modifier.fillMaxSize())
                else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Button(stringResource(R.string.tap_to_enable), ask, primary = true) }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Label(stringResource(R.string.places_here).uppercase(), Typography.label, c.textMuted, 1)
                Label(address?.line?.ifBlank { null } ?: address?.region ?: MyCar.PLACE, Typography.title, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                val result = nearby
                if (result == null) {
                    Label(stringResource(R.string.places_loading), Typography.body, c.textMuted)
                    return@Column
                }
                val found = result.places
                if (result.failed && found.isEmpty()) {
                    // No es falta de internet de la pantalla: no contestaron los servidores de lugares; se dice por qué.
                    Label(stringResource(R.string.places_failed), Typography.body, c.textMuted)
                    Spacer(Modifier.height(10.dp))
                    Button(stringResource(R.string.places_retry), { retry++ }, primary = true)
                    Spacer(Modifier.height(10.dp))
                    Label(Places.lastError, Typography.small, c.textMuted, 6)
                    return@Column
                }
                // Solo las categorías que sí tienen algo cerca.
                val kinds = PlaceKind.entries.filter { k -> found.any { it.kind == k } }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip(stringResource(R.string.places_all), kind == null) { kind = null } }
                    items(kinds) { k -> Chip(stringResource(k.title), kind == k) { kind = k } }
                }
                Spacer(Modifier.height(10.dp))
                val shown = found.filter { kind == null || it.kind == kind }.take(60)
                if (shown.isEmpty()) {
                    Label(stringResource(R.string.places_none), Typography.body, c.textMuted)
                    return@Column
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(shown) { p -> PlaceRow(p) { navigateTo(context, app, "${p.latitude},${p.longitude}") } }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.clip(RoundedCornerShape(Radius.inner)).background(if (selected) c.primary else c.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Label(text, Typography.small, if (selected) c.onPrimary else c.text, 1) }
}

@Composable
private fun PlaceRow(p: Place, onClick: () -> Unit) {
    val c = LocalColors.current
    val distance = if (p.meters < 1000) stringResource(R.string.places_m, p.meters) else stringResource(R.string.places_km, String.format(Locale.US, "%.1f", p.meters / 1000.0))
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(Radius.inner)).background(c.surface).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label(p.name.ifBlank { stringResource(p.kind.title) }, Typography.body, c.text, 1)
            Label(stringResource(p.kind.title) + " · " + distance, Typography.small, c.textMuted, 1)
        }
        Spacer(Modifier.width(10.dp))
        Icon(Icons.navigate, c.accent, 24.dp)
    }
}
