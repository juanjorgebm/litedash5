package com.irvingguerra.litedash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * El clima del Okavango en grande, del lugar donde estás: a un lado las próximas 4 horas
 * (hora, cielo, temperatura y lluvia) y al otro los días que siguen.
 */
@Composable
fun WeatherFullScreen(d: Dependencies, close: () -> Unit) {
    val c = LocalColors.current
    val w = d.weather.weather.takeIf { d.weather.enabled }
    PanelScaffold(stringResource(R.string.tile_weather), close) {
        if (w == null) {
            Label(stringResource(R.string.weather_unknown), Typography.subtitle, c.textMuted)
            return@PanelScaffold
        }
        val locale = currentLocale()
        val parse = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        val weekday = remember(locale) { SimpleDateFormat("EEEE", locale) }
        Column(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(iconOf(skyOf(w.code), w.day), c.accent, 64.dp)
                Label("${w.temperature}°", Typography.title.copy(fontSize = 56.sp), maxLines = 1)
                Column {
                    Label(stringResource(textOf(skyOf(w.code))), Typography.subtitle, maxLines = 1)
                    Label(w.place.ifBlank { stringResource(R.string.my_okavango) }, Typography.body, c.textMuted, 1)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
                        Label(stringResource(R.string.weather_next_hours), Typography.label, c.textMuted, 1)
                        Spacer(Modifier.height(12.dp))
                        for (h in w.hours.take(4)) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Label(h.time, Typography.subtitle, c.textMuted, 1, Modifier.width(72.dp))
                                val night = h.time.take(2).toIntOrNull()?.let { it !in 6..18 } ?: false
                                Icon(iconOf(skyOf(h.code), !night), c.accent, 32.dp)
                                Label("  ${h.temperature}°", Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
                                Label("${h.rain}%", Typography.body, if (h.rain >= 40) c.accent else c.textMuted, 1)
                            }
                        }
                        if (w.hours.isEmpty()) Label(stringResource(R.string.weather_loading), Typography.body, c.textMuted)
                    }
                }
                Card(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) {
                        for (day in w.days.take(5)) {
                            val name = runCatching { weekday.format(parse.parse(day.date)!!) }.getOrDefault("").replaceFirstChar { it.uppercase() }
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Label(name, Typography.subtitle.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, modifier = Modifier.weight(1f))
                                Icon(iconOf(skyOf(day.code), true), c.accent, 32.dp)
                                Label("  ${day.max}° / ${day.min}°", Typography.subtitle, c.textMuted, 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
