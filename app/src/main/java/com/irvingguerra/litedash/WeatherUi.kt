package com.irvingguerra.litedash

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.SwitchRow
import com.irvingguerra.litedash.core.ui.Typography
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

fun iconOf(sky: Sky, day: Boolean) = when (sky) {
    Sky.CLEAR -> if (day) Icons.sun else Icons.moon
    Sky.PARTLY -> Icons.partlyCloudy
    Sky.CLOUDY -> Icons.cloud
    Sky.FOG -> Icons.fog
    Sky.RAIN -> Icons.rain
    Sky.STORM -> Icons.storm
    Sky.SNOW -> Icons.snow
}

fun textOf(sky: Sky) = when (sky) {
    Sky.CLEAR -> R.string.weather_clear
    Sky.PARTLY -> R.string.weather_partly
    Sky.CLOUDY -> R.string.weather_cloudy
    Sky.FOG -> R.string.weather_fog
    Sky.RAIN -> R.string.weather_rain
    Sky.STORM -> R.string.weather_storm
    Sky.SNOW -> R.string.weather_snow
}

/** Pide la ubicación aproximada y, si la dan, trae el clima al momento. */
@Composable
fun rememberEnableWeather(repo: WeatherRepository): () -> Unit {
    val scope = rememberCoroutineScope()
    // Sin el permiso también se prende: usa Palmares, Alajuela.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        repo.enable()
        scope.launch { repo.refreshIfStale(force = true) }
    }
    return { launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }
}

/**
 * Mantiene el clima al día mientras el inicio se ve: al volver ([revision]) y cada
 * [WeatherRepository.STALE_MS] con LiteDash al frente. Apagado o en segundo plano, nada.
 */
@Composable
fun WeatherRefresh(repo: WeatherRepository, revision: Int) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val on = repo.enabled
    LaunchedEffect(repo, revision, on) {
        if (!on) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repo.refreshIfStale()
                delay(WeatherRepository.CHECK_MS)
            }
        }
    }
}

/** Ajustes → Inicio y apariencia: prender o apagar el clima (al prenderlo pide la ubicación aproximada). */
@Composable
fun WeatherSwitch(repo: WeatherRepository) {
    val enable = rememberEnableWeather(repo)
    SwitchRow(
        stringResource(R.string.weather_switch),
        repo.enabled,
        { on -> if (on) enable() else repo.disable() },
        stringResource(R.string.weather_switch_help),
    )
}

/** Ícono y temperatura, para la barra de arriba y el reloj. */
@Composable
fun WeatherChip(repo: WeatherRepository) {
    val w = repo.weather ?: return
    if (!repo.active) return
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(iconOf(skyOf(w.code), w.day), c.accent, 22.dp)
        Label("${w.temperature}°", Typography.subtitle, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

/**
 * Pieza Clima: hoy en grande y, si cabe, los próximos días. Sin permiso es un
 * botón para activarlo (lo pide solo entonces).
 */
@Composable
fun WeatherTile(repo: WeatherRepository) {
    val c = LocalColors.current
    val enable = rememberEnableWeather(repo)
    val w = repo.weather
    if (!repo.active || w == null) {
        return ShortcutTile(Icons.sun, stringResource(R.string.tile_weather), stringResource(if (repo.active) R.string.weather_loading else R.string.tap_to_enable), enable)
    }
    // Grande (ancho o alto): el pronóstico por días en una tarjeta y las próximas 4 horas en otra.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hours = w.hours.take(4)
        val wide = maxWidth > 560.dp && maxHeight > 150.dp
        val tall = !wide && maxHeight > 300.dp
        when {
            hours.isNotEmpty() && wide -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NowCard(w, Modifier.weight(1f).fillMaxHeight())
                HoursCard(hours, Modifier.weight(1f).fillMaxHeight())
            }
            hours.isNotEmpty() && tall -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NowCard(w, Modifier.weight(1f).fillMaxWidth())
                HoursCard(hours, Modifier.weight(1f).fillMaxWidth())
            }
            else -> NowCard(w, Modifier.fillMaxSize())
        }
    }
}

/** Hoy en grande, el lugar y, si cabe, los próximos días. */
@Composable
private fun NowCard(w: Weather, modifier: Modifier) {
    val c = LocalColors.current
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterStart) {
            val big = maxHeight > 120.dp
            val showDays = maxWidth > 340.dp && w.days.size > 1
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(iconOf(skyOf(w.code), w.day), c.accent, if (big) 56.dp else 36.dp)
                Column(Modifier.weight(1f)) {
                    Label("${w.temperature}°", Typography.title.copy(fontSize = if (big) 40.sp else 26.sp), maxLines = 1)
                    val today = w.days.firstOrNull()
                    val detail = stringResource(textOf(skyOf(w.code))) + (today?.let { " · ${it.max}° / ${it.min}°" } ?: "")
                    Label(detail, Typography.small, c.textMuted, maxLines = 1)
                    if (w.place.isNotBlank()) Label(w.place, Typography.small, c.textMuted, maxLines = 1)
                }
                if (showDays) {
                    val locale = currentLocale()
                    // Los formatos se crean una vez por idioma, no en cada recomposición.
                    val parse = remember(locale) { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
                    val weekday = remember(locale) { SimpleDateFormat("EEE", locale) }
                    for (d in w.days.drop(1).take(3)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val name = runCatching { weekday.format(parse.parse(d.date)!!) }.getOrDefault("")
                            Label(name.replaceFirstChar { it.uppercase() }, Typography.small, c.textMuted, maxLines = 1)
                            Icon(iconOf(skyOf(d.code), true), c.text, 24.dp)
                            Label("${d.max}°", Typography.small, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Las próximas horas: la hora, el cielo, la temperatura y la probabilidad de lluvia. */
@Composable
private fun HoursCard(hours: List<WeatherHour>, modifier: Modifier) {
    val c = LocalColors.current
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Label(stringResource(R.string.weather_next_hours), Typography.label, c.textMuted, 1)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                for (h in hours) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Label(h.time, Typography.small, c.textMuted, 1)
                        Icon(iconOf(skyOf(h.code), h.time.take(2).toIntOrNull()?.let { it in 6..18 } ?: true), c.accent, 32.dp)
                        Label("${h.temperature}°", Typography.subtitle, maxLines = 1)
                        Label("${h.rain}%", Typography.small, if (h.rain >= 40) c.accent else c.textMuted, 1)
                    }
                }
            }
        }
    }
}
