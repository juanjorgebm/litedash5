package com.irvingguerra.litedash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MyCar
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import java.text.SimpleDateFormat
import java.util.Locale

/** Colores del cielo (arriba, medio, horizonte) según el clima y si es de día. */
private fun skyColors(sky: Sky?, day: Boolean): List<Color> = if (!day) when (sky) {
    Sky.CLOUDY, Sky.FOG, Sky.RAIN, Sky.STORM -> listOf(Color(0xFF10141B), Color(0xFF1B222D), Color(0xFF2B3340))
    else -> listOf(Color(0xFF0A1120), Color(0xFF16233A), Color(0xFF2A3A55))
} else when (sky) {
    Sky.STORM -> listOf(Color(0xFF23282F), Color(0xFF3A404A), Color(0xFF4F5560))
    Sky.RAIN -> listOf(Color(0xFF3A424D), Color(0xFF525C69), Color(0xFF6B7380))
    Sky.FOG -> listOf(Color(0xFF6E7681), Color(0xFF8D939B), Color(0xFFA7ABB0))
    Sky.SNOW -> listOf(Color(0xFF8995A3), Color(0xFFAEB8C3), Color(0xFFD2D8DE))
    Sky.CLOUDY, Sky.PARTLY -> listOf(Color(0xFF56606E), Color(0xFF7B8592), Color(0xFF9A9A98))
    else -> listOf(Color(0xFF3F6A9A), Color(0xFF8FA3B8), Color(0xFFD1B98A))
}

/**
 * El Geely Okavango (foto sin fondo) sobre un cielo que cambia con el clima de Palmares y, a la derecha,
 * la temperatura y los próximos días. Llena su pieza; un toque la agranda.
 */
@Composable
fun OkavangoScene(d: Dependencies, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val weather = d.weather.weather.takeIf { d.weather.enabled }
    val sky = weather?.let { skyOf(it.code) }
    val day = weather?.day ?: true
    val colors = skyColors(sky, day)
    val shape = RoundedCornerShape(Radius.card)
    BoxWithConstraints(
        modifier
            .shadow(8.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    0f to colors[0], 0.38f to colors[1], 0.66f to colors[2],
                    0.66f to Color(0xFF22272E), 1f to Color(0xFF0F1216),
                ),
            )
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f))), shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    ) {
        val wide = maxWidth > 560.dp && maxHeight > 220.dp
        val panelWidth = if (wide) (maxWidth * 0.24f).coerceIn(190.dp, 260.dp) else 0.dp
        val areaWidth = maxWidth - panelWidth
        // Sol (o luna) y brillo del horizonte.
        Canvas(Modifier.fillMaxSize()) {
            val glow = if (day && (sky == null || sky == Sky.CLEAR || sky == Sky.PARTLY)) Color(0xFFFFC46E) else Color(0xFFB8C6DD)
            drawCircle(
                Brush.radialGradient(listOf(glow.copy(alpha = if (day) 0.5f else 0.22f), Color.Transparent), center = Offset(areaWidth.toPx() * 0.8f, size.height * 0.22f), radius = size.height * 0.5f),
                radius = size.height * 0.5f,
                center = Offset(areaWidth.toPx() * 0.8f, size.height * 0.22f),
            )
            drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Transparent), endY = size.height * 0.35f), size = Size(size.width, size.height * 0.35f))
        }
        Column(Modifier.padding(start = 28.dp, top = 22.dp)) {
            Label(d.prefs.carName ?: stringResource(R.string.my_okavango), Typography.title.copy(fontSize = 34.sp), Color.White)
            Box(Modifier.padding(top = 8.dp).width(72.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
        }
        // El auto, con su sombra en el piso, centrado en el espacio que deja el clima.
        Box(Modifier.width(areaWidth).fillMaxHeight().padding(start = 12.dp, end = 12.dp, top = 52.dp, bottom = 14.dp), contentAlignment = Alignment.BottomCenter) {
            Canvas(Modifier.fillMaxWidth(0.9f).height(34.dp).align(Alignment.BottomCenter)) {
                drawOval(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)), size = size)
            }
            Image(
                painterResource(R.drawable.okavango),
                contentDescription = MyCar.MODEL,
                modifier = Modifier.fillMaxSize().padding(bottom = 10.dp),
                contentScale = ContentScale.Fit,
                alignment = Alignment.BottomCenter,
            )
        }
        if (wide) WeatherPanel(weather, Modifier.align(Alignment.CenterEnd).padding(end = 18.dp, top = 18.dp, bottom = 18.dp).width(panelWidth - 18.dp).fillMaxHeight())
    }
}

/** Temperatura, cómo está el cielo y los tres días que siguen. */
@Composable
private fun WeatherPanel(weather: Weather?, modifier: Modifier) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(22.dp)
    val locale = currentLocale()
    val dayName = remember(locale) { SimpleDateFormat("EEE", locale) }
    val parse = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC1E242D), Color(0xD10E1115))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))), shape)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Label(MyCar.PLACE.substringBefore(','), Typography.body, c.accent, 1)
        if (weather == null) {
            Label(stringResource(R.string.weather_unknown), Typography.subtitle, c.textMuted)
            return@Column
        }
        val sky = skyOf(weather.code)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(iconOf(sky, weather.day), c.accent, 40.dp)
            Label("${weather.temperature}°", Typography.clock.copy(fontSize = 52.sp), Color.White, 1)
        }
        Label(stringResource(textOf(sky)), Typography.subtitle, Color.White, 2)
        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        for (day in weather.days.drop(1).take(3)) {
            val name = runCatching { dayName.format(parse.parse(day.date)!!) }.getOrDefault("")
                .replaceFirstChar { it.uppercase() }.trimEnd('.')
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(name, Typography.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), Color.White, 1, Modifier.width(52.dp))
                Icon(iconOf(skyOf(day.code), true), c.accent, 22.dp)
                Label("  ${day.max}°/${day.min}°", Typography.body, c.textMuted, 1)
            }
        }
    }
}
