// Plantillas del inicio: nombres, pestañas por estilo y sus miniaturas.
package com.irvingguerra.litedash

import com.irvingguerra.litedash.core.ui.Radius
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Grid
import com.irvingguerra.litedash.feature.dashboard.ScreenSize
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.hasCar
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import com.irvingguerra.litedash.feature.dashboard.tiles

fun nameOf(p: Template) = when (p) {
    Template.CENTER -> R.string.template_center
    Template.CARDS -> R.string.template_cards
    Template.SIDE -> R.string.template_side
    Template.COLUMNS -> R.string.template_columns
    Template.PANEL -> R.string.template_panel
    Template.VERTICAL -> R.string.template_vertical
    Template.SPEED -> R.string.template_speed
    Template.CAR_SPEED -> R.string.template_car_speed
    Template.SPORT -> R.string.template_sport
    Template.MINIMAL -> R.string.template_minimal
    Template.SPEED_WIDGETS -> R.string.template_speed_widgets
    Template.WALL_CARDS -> R.string.template_wall_cards
    Template.WALL_SIDE -> R.string.template_wall_side
    Template.MUSIC_RADIO -> R.string.template_music_radio
    Template.MAP_CAR -> R.string.template_map_car
    Template.COPILOT -> R.string.template_copilot
    Template.ROUTE -> R.string.template_route
    Template.VERTICAL_MAP -> R.string.template_vertical_map
    Template.OKAVANGO -> R.string.template_okavango
}

private fun nameOf(t: ScreenSize) = when (t) {
    ScreenSize.SMALL -> R.string.screens_small
    ScreenSize.MEDIUM -> R.string.screens_medium
    ScreenSize.LARGE -> R.string.screens_large
    ScreenSize.VERTICAL -> R.string.screens_vertical
}

/** Las que lucen el mapa: van en su pestaña y no se repiten en la del auto. */
private val MAP_TEMPLATES = listOf(Template.MAP_CAR, Template.COPILOT, Template.ROUTE, Template.VERTICAL_MAP)

/**
 * Estilos de plantilla (una pestaña cada uno): con el auto 3D, con el mapa de protagonista,
 * con la carretera en lugar del auto ([hero]), solo velocímetro (sin OBD: usa el GPS) o para
 * lucir tu fondo, como BYD (ahí va la Mínima: hora y música, sin velocímetro).
 */
private enum class TemplateTab(val title: Int, val templates: List<Template>, val hero: String = TileTypes.CAR) {
    CAR(R.string.templates_tab_car, Template.entries.filter { it.hasCar && it !in MAP_TEMPLATES }),
    MAP(R.string.templates_tab_map, MAP_TEMPLATES),
    ROAD(R.string.templates_tab_road, listOf(Template.MAP_CAR, Template.CARDS, Template.CENTER, Template.CAR_SPEED, Template.SPORT, Template.VERTICAL), TileTypes.ROAD),
    SPEED(R.string.templates_tab_speed, listOf(Template.SPEED, Template.SPEED_WIDGETS)),
    WALLPAPER(R.string.templates_tab_wallpaper, listOf(Template.WALL_CARDS, Template.WALL_SIDE, Template.MINIMAL)),
}

/**
 * Plantillas en pestañas por estilo y, en cada una, agrupadas por tamaño de pantalla;
 * primero el de la tuya, con el recomendado marcado.
 */
@Composable
fun TemplatesPanel(dashboard: DashboardState, prefs: Preferences, close: () -> Unit) {
    val grid = dashboard.grid ?: return
    val c = LocalColors.current
    val yours = grid.size
    val suggested = grid.suggestedTemplate()
    PanelScaffold(stringResource(R.string.templates), close) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Label(stringResource(R.string.templates_help), Typography.body, c.textMuted)
            val tab = TemplateTab.entries[tabs(TemplateTab.entries.map { it.title }, "templates")]
            fun apply(p: Template) {
                dashboard.template(p, tab.hero)
                close()
            }
            when (tab) {
                TemplateTab.MAP -> Label(stringResource(R.string.templates_map_help), Typography.small, c.textMuted)
                TemplateTab.ROAD -> Label(stringResource(R.string.templates_road_help), Typography.small, c.textMuted)
                TemplateTab.SPEED -> Label(stringResource(R.string.templates_speed_help), Typography.small, c.textMuted)
                TemplateTab.WALLPAPER -> {
                    Label(stringResource(R.string.templates_wallpaper_help), Typography.small, c.textMuted)
                    WallpaperSettings()
                }
                TemplateTab.CAR -> Unit
            }
            for (group in listOf(yours) + ScreenSize.entries.filter { it != yours }) {
                val templates = tab.templates.filter { group in it.sizes }
                if (templates.isEmpty()) continue
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Label(stringResource(nameOf(group)).uppercase(), Typography.label, c.textMuted)
                        if (group == yours) Label(stringResource(R.string.your_screen).uppercase(), Typography.label, c.accent)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (p in templates) {
                            Card(Modifier.width(260.dp), onClick = { apply(p) }) {
                                Column(Modifier.padding(16.dp)) {
                                    TemplateThumbnail(p, grid, tab.hero, dashboard.hidden)
                                    Spacer(Modifier.height(12.dp))
                                    Label(stringResource(nameOf(p)), Typography.subtitle, maxLines = 1)
                                    if (tab == TemplateTab.CAR && group == yours && p == suggested) {
                                        Label(stringResource(R.string.recommended).uppercase(), Typography.label, c.accent, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Dibujo de la plantilla en chiquito, con las proporciones de esta pantalla. */
@Composable
private fun TemplateThumbnail(p: Template, grid: Grid, hero: String, skip: (String) -> Boolean) {
    val c = LocalColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(grid.columns.toFloat() / grid.rows).clip(RoundedCornerShape(Radius.inner)).background(c.background)) {
        val cw = maxWidth / grid.columns
        val ch = maxHeight / grid.rows
        // Igual que al aplicarla: sin piezas de módulos apagados.
        val tiles = p.tiles(grid, hero, skip)
        // La protagonista en el color de la interfaz: el auto (o la carretera) o, en las que no lo llevan, el velocímetro o la primera pieza.
        val main = (tiles.firstOrNull { it.type == hero } ?: tiles.firstOrNull { it.type == TileTypes.SPEEDOMETER } ?: tiles.firstOrNull())?.id
        for (tile in tiles) {
            val width = cw * tile.width
            val height = ch * tile.height
            Box(
                Modifier
                    .offset(cw * tile.col, ch * tile.row)
                    .size(width, height)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(Radius.small))
                    .background(if (tile.id == main) c.accent.copy(alpha = 0.55f) else c.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                // El ícono de cada widget: se ve qué es cada cosa (mapa, música, radio…) sin aplicarla.
                Icon(iconOf(tile.type), if (tile.id == main) c.surface else c.textMuted, minOf(width, height, 22.dp) * 0.7f)
            }
        }
    }
}
