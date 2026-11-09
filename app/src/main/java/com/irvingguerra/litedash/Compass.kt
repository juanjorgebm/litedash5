package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.core.vehicle.Signal
import com.irvingguerra.litedash.core.vehicle.watch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brújula con el GPS: la pantalla no trae sensor magnético, así que el rumbo sale de hacia dónde se mueve el carro
 * ([TripRepository.bearing], válido a más de 3 km/h). Detenido se queda con el último rumbo (guardado) y lo dice.
 */
@Composable
fun CompassTile(d: Dependencies) {
    val context = LocalContext.current
    val c = LocalColors.current
    KeepTripRunning(d.trip)
    val prefs = remember { context.applicationContext.getSharedPreferences("compass", Context.MODE_PRIVATE) }
    var last by remember { mutableStateOf<Float?>(prefs.getFloat("heading", -1f).takeIf { it >= 0f }) }
    val live = d.trip.bearing
    LaunchedEffect(live) {
        if (live != null) {
            last = live
            prefs.edit().putFloat("heading", live).apply()
        }
    }
    val kmh = d.bus.watch(Signal.SPEED)
    val moving = live != null && (kmh ?: 0f) > 3f
    val heading = live ?: last
    val letters = listOf(R.string.compass_n, R.string.compass_e, R.string.compass_s, R.string.compass_w).map { stringResource(it) }
    val names = listOf(R.string.compass_n, R.string.compass_e, R.string.compass_s, R.string.compass_w)
    val status = stringResource(
        when {
            moving -> R.string.compass_moving
            heading != null -> R.string.compass_stopped
            d.trip.position == null -> R.string.compass_waiting
            else -> R.string.compass_move
        },
    )
    val ink = if (moving) c.text else c.textMuted
    Card(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
            val side = minOf(maxWidth, maxHeight)
            Box(Modifier.size(side), contentAlignment = Alignment.Center) {
                // El disco gira al revés del rumbo: el norte queda donde está de verdad.
                Box(Modifier.fillMaxSize().rotate(-(heading ?: 0f)), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val r = size.minDimension / 2f
                        drawCircle(c.line, r, style = Stroke(2.dp.toPx()))
                        for (i in 0 until 36) {
                            val a = (i * 10 * PI / 180).toFloat()
                            val len = if (i % 9 == 0) 14.dp.toPx() else if (i % 3 == 0) 8.dp.toPx() else 4.dp.toPx()
                            val from = Offset(center.x + (r - len) * sin(a), center.y - (r - len) * cos(a))
                            val to = Offset(center.x + r * sin(a), center.y - r * cos(a))
                            drawLine(if (i == 0) c.accent else c.textMuted, from, to, strokeWidth = 2.dp.toPx())
                        }
                    }
                    names.forEachIndexed { i, res ->
                        val align = when (i) { 0 -> Alignment.TopCenter; 1 -> Alignment.CenterEnd; 2 -> Alignment.BottomCenter; else -> Alignment.CenterStart }
                        Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = align) {
                            Label(stringResource(res), Typography.subtitle, if (i == 0) c.accent else c.text, 1)
                        }
                    }
                }
                // Aguja fija: apunta hacia donde va el carro.
                Canvas(Modifier.fillMaxSize()) {
                    val p = Path().apply {
                        moveTo(center.x, center.y - size.minDimension * 0.30f)
                        lineTo(center.x - 9.dp.toPx(), center.y + 6.dp.toPx())
                        lineTo(center.x + 9.dp.toPx(), center.y + 6.dp.toPx())
                        close()
                    }
                    drawPath(p, if (moving) c.accent else c.textMuted)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = side / 5), horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = heading
                    if (h != null) {
                        Label(sector(h, letters), Typography.title.copy(fontSize = if (side > 160.dp) 34.sp else 24.sp), ink, 1)
                        Label("${h.roundToInt()}°", Typography.small, c.textMuted, 1)
                    }
                    if (side > 130.dp) Label(status, Typography.small, c.textMuted, 2)
                }
            }
        }
    }
}

/** El punto cardinal más cercano a [h] grados (N, NE, E, SE, S, SO, O, NO); [names] = N, E, S, O. */
private fun sector(h: Float, names: List<String>): String = when (((h + 22.5f) / 45f).toInt() % 8) {
    0 -> names[0]
    1 -> names[0] + names[1]
    2 -> names[1]
    3 -> names[2] + names[1]
    4 -> names[2]
    5 -> names[2] + names[3]
    6 -> names[3]
    else -> names[0] + names[3]
}
