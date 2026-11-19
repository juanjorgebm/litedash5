// Ajustes → Inicio y apariencia: inicio, interfaz, día y noche, unidades y widgets.
package com.irvingguerra.litedash

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Brand
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.DarkColors
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LightColors
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import com.irvingguerra.litedash.feature.tpms.PressureUnit
import kotlin.math.round
import androidx.core.content.ContextCompat

/** Los temas a elegir, en Inicio y apariencia y en la bienvenida: oscuro, claro, día y noche y el del sistema. */
internal val ThemeOptions = listOf(
    ThemeMode.DARK to R.string.theme_dark,
    ThemeMode.LIGHT to R.string.theme_light,
    ThemeMode.SUN to R.string.theme_sun,
    ThemeMode.SYSTEM to R.string.theme_system,
)

/**
 * Pestañas de Inicio y apariencia, en el orden en que se busca: el inicio (acomodo, barras, fondo y
 * entrada), la interfaz (color, tamaño, idioma, nombre, clima y estacionado), día y noche, unidades y,
 * al final, la lista de widgets para agregar.
 */
private enum class AppearanceTab(val title: Int) {
    HOME(R.string.tab_home), INTERFACE(R.string.tab_interface), DAY_NIGHT(R.string.tab_day_night), UNITS(R.string.units_title), WIDGETS(R.string.tab_tiles),
}

@Composable
internal fun Appearance(d: Dependencies, revision: Int, onEdit: () -> Unit, onTemplates: () -> Unit) {
    when (AppearanceTab.entries[tabs(AppearanceTab.entries.map { it.title }, "appearance")]) {
        AppearanceTab.HOME -> HomeSettings(d, onEdit, onTemplates)
        AppearanceTab.INTERFACE -> InterfaceSettings(d)
        AppearanceTab.DAY_NIGHT -> DayNightSettings(d.prefs, revision)
        AppearanceTab.UNITS -> SettingsCard { UnitsSettings(d) }
        AppearanceTab.WIDGETS -> Tiles(d, onEdit)
    }
}

/** Interfaz: su color, tamaño e idioma, tu nombre, el clima y el modo estacionado. */
@Composable
private fun InterfaceSettings(d: Dependencies) {
    val prefs = d.prefs
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.accent_title), stringResource(R.string.accent_help)) { AccentSettings(prefs) }
    SettingsCard(stringResource(R.string.scale), stringResource(R.string.scale_help)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(Icons.minus, { prefs.scale = (prefs.scale - 0.1f).coerceAtLeast(0.8f).roundToTenth() }, background = c.surfaceHigh)
            Label("${(prefs.scale * 100).toInt()} %", Typography.title, modifier = Modifier.widthIn(min = 90.dp))
            IconButton(Icons.plus, { prefs.scale = (prefs.scale + 0.1f).coerceAtMost(1.5f).roundToTenth() }, background = c.surfaceHigh)
        }
    }
    SettingsCard(stringResource(R.string.language)) {
        val activity = context as? Activity
        Segmented(
            listOf("" to R.string.language_system, "es" to R.string.language_es, "en" to R.string.language_en).map { (code, text) -> code to stringResource(text) },
            prefs.language,
            { code ->
                if (prefs.language != code) {
                    prefs.language = code
                    activity?.let(MainActivity::restart)
                }
            },
        )
    }
    SettingsCard(stringResource(R.string.your_name), stringResource(R.string.your_name_help)) {
        TextInput(prefs.driverName ?: "", { prefs.driverName = it.take(24).ifBlank { null } }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
    }
    // Sin título: el interruptor ya dice qué es.
    SettingsCard { WeatherSwitch(d.weather) }
    SettingsCard { ParkedSettings(prefs) }
}

/** Los colores rápidos de la interfaz (vivos, para verse sobre oscuro); el claro los oscurece un poco. */
private val ACCENTS = listOf(
    Brand.Amber, Color(0xFFFF453A), Color(0xFFFF7A1A), Color(0xFF30D158),
    Color(0xFF2BD4E8), Color(0xFF0A84FF), Color(0xFFBF5AF2), Color(0xFFFF4F9A),
)

/** El color de la interfaz: "Natural" (el de fábrica), los rápidos o uno libre. */
@Composable
private fun AccentSettings(prefs: Preferences) {
    val c = LocalColors.current
    val natural = stringResource(R.string.accent_natural)
    // Natural: mitad blanco (o negro) y mitad ámbar, como se ve de fábrica.
    val split = if (c.dark) DarkColors else LightColors
    val leading = @Composable {
        Swatch(
            prefs.accentColor == null,
            Brush.linearGradient(0f to split.primary, 0.5f to split.primary, 0.5f to split.accent, 1f to split.accent),
            Modifier.semantics { contentDescription = natural },
        ) { prefs.accentColor = null }
    }
    ColorPicker(prefs.accentColor ?: Color.Unspecified, prefs::previewAccent, ACCENTS, leading) { prefs.accentColor = it }
}

/** Día y noche: el tema, cuándo es de noche y el brillo. */
@Composable
private fun DayNightSettings(prefs: Preferences, revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.theme)) {
        Segmented(
            ThemeOptions.map { (mode, text) -> mode to stringResource(text) },
            prefs.theme,
            { prefs.theme = it },
        )
    }
    SettingsCard(stringResource(R.string.night_source), stringResource(R.string.night_source_help)) {
        val hasSensor = remember { ContextCompat.getSystemService(context, SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_LIGHT) != null }
        val options = listOfNotNull(
            NightSource.SUN to R.string.night_sun,
            (NightSource.SENSOR to R.string.night_sensor).takeIf { hasSensor },
            NightSource.LIGHTS to R.string.night_lights,
        )
        Segmented(options.map { (s, text) -> s to stringResource(text) }, prefs.nightSource, { prefs.nightSource = it })
        if (!hasSensor) Label(stringResource(R.string.night_no_sensor), Typography.small, c.textMuted)
        if (prefs.nightSource == NightSource.LIGHTS) {
            Label(stringResource(R.string.lights_help), Typography.small, c.textMuted)
            LightsSettings(prefs)
        }
    }
    SettingsCard(stringResource(R.string.brightness), stringResource(R.string.brightness_help)) {
        BrightnessSettings(prefs, revision)
    }
}

/** Unidades: velocidad y distancia, temperatura y (con llantas) presión. Automático = según idioma y región. */
@Composable
private fun UnitsSettings(d: Dependencies) {
    val c = LocalColors.current
    val prefs = d.prefs
    val auto = imperialUnits(prefs.language)
    Label(stringResource(R.string.units_speed), Typography.label, c.textMuted)
    Segmented(
        listOf(
            SpeedUnits.AUTO to stringResource(R.string.units_auto),
            SpeedUnits.METRIC to stringResource(R.string.units_metric),
            SpeedUnits.IMPERIAL to stringResource(R.string.units_imperial),
        ),
        prefs.speedUnits,
        { prefs.speedUnits = it },
    )
    Label(stringResource(R.string.units_temp), Typography.label, c.textMuted)
    Segmented(
        listOf(
            TempUnits.AUTO to stringResource(R.string.units_auto),
            TempUnits.CELSIUS to stringResource(R.string.unit_c),
            TempUnits.FAHRENHEIT to stringResource(R.string.unit_f),
        ),
        prefs.tempUnits,
        { prefs.tempUnits = it },
    )
    if (prefs.speedUnits == SpeedUnits.AUTO || prefs.tempUnits == TempUnits.AUTO) {
        Label(
            stringResource(
                R.string.units_auto_now,
                stringResource(if (auto) R.string.unit_mph else R.string.unit_kmh),
                stringResource(if (auto) R.string.unit_f else R.string.unit_c),
            ),
            Typography.small, c.textMuted,
        )
    }
    if (d.hasTpmsSource) {
        val tpms = d.tpms
        Label(stringResource(R.string.units_pressure), Typography.label, c.textMuted)
        Segmented(PressureUnit.entries.map { it to it.label }, tpms.unit, { tpms.unit = it })
    }
}

/**
 * Inicio: acomodo y plantillas, la barra de apps (de LiteDash) junto a la de Android (la de arriba),
 * el fondo, la animación de entrada y hacerlo tu pantalla de inicio.
 */
@Composable
private fun HomeSettings(d: Dependencies, onEdit: () -> Unit, onTemplates: () -> Unit) {
    val prefs = d.prefs
    val context = LocalContext.current
    SettingsCard(stringResource(R.string.layout), stringResource(R.string.arrange_help)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(stringResource(R.string.arrange), onEdit, primary = true)
            Button(stringResource(R.string.templates), onTemplates)
        }
    }
    SettingsCard(stringResource(R.string.bar_position), stringResource(R.string.bar_position_help)) {
        Segmented(
            listOf(BarPosition.AUTO to stringResource(R.string.bar_auto), BarPosition.SIDE to stringResource(R.string.bar_side), BarPosition.BOTTOM to stringResource(R.string.bar_bottom)),
            prefs.barPosition,
            { prefs.barPosition = it },
        )
    }
    SettingsCard(stringResource(R.string.android_bar)) {
        SwitchRow(stringResource(R.string.hide_status_bar), prefs.hideStatusBar, { prefs.hideStatusBar = it }, stringResource(R.string.hide_status_bar_help))
    }
    SettingsCard(stringResource(R.string.wallpaper_title), stringResource(R.string.wallpaper_help)) {
        WallpaperSettings()
    }
    // Sin título: el interruptor ya dice qué es.
    SettingsCard { IntroSettings(prefs) }
    val (requestHome, returns) = rememberRequestHome()
    val isHome = remember(returns) { isHomeApp(context) }
    SettingsCard(stringResource(R.string.welcome_home_title)) {
        if (isHome) {
            Label(stringResource(R.string.welcome_already_home), Typography.body, LocalColors.current.accent)
        } else {
            Button(stringResource(R.string.make_home), { prefs.dontAskHome = false; requestHome() }, primary = true)
        }
    }
}

private fun Float.roundToTenth() = round(this * 10) / 10f

/** Inicio → Widgets: todo lo que puedes poner en tu inicio y si ya está (sin los de módulos apagados). */
@Composable
private fun Tiles(d: Dependencies, onEdit: () -> Unit) {
    val c = LocalColors.current
    // Tipo que no cupo al agregarlo: se avisa y se ofrece acomodar el inicio.
    var noRoom by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (spec in TileSpecs.filter { it.type != TileTypes.APP && moduleOff(d, it.type) == null }) {
            val onHome = d.dashboard.tiles.any { it.type == spec.type }
            val didNotFit = noRoom == spec.type && !onHome
            val detail = if (didNotFit) stringResource(R.string.no_room) else spec.detail?.let { stringResource(it) } ?: ""
            TileRow(iconOf(spec.type), stringResource(spec.name), detail, if (didNotFit) c.danger else c.textMuted) {
                when {
                    onHome -> Label(stringResource(R.string.on_your_home), Typography.small, c.accent)
                    didNotFit -> Button(stringResource(R.string.arrange), onEdit, primary = true)
                    else -> Button(stringResource(R.string.add), { noRoom = if (d.dashboard.addTile(spec.type)) null else spec.type })
                }
            }
        }
    }
}

/** Un renglón de Inicio → Widgets: ícono, nombre, detalle y su botón. */
@Composable
private fun TileRow(
    icon: ImageVector,
    title: String,
    detail: String,
    detailColor: Color = LocalColors.current.textMuted,
    action: @Composable () -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, c.accent, 26.dp)
        Column(Modifier.weight(1f)) {
            Label(title, Typography.subtitle, maxLines = 1)
            Label(detail, Typography.small, detailColor, maxLines = 2)
        }
        action()
    }
}
