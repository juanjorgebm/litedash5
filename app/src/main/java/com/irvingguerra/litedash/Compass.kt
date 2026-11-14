package com.irvingguerra.litedash

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
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
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            // La brújula "acostada" y vista a unos 45°: arriba es hacia adelante, como el parabrisas. El texto va aparte, derecho.
            val textRight = maxWidth > maxHeight * 1.5f
            val flat = 0.62f // cuánto de alto se ve el disco inclinado
            val diameter = if (textRight) minOf(maxWidth * 0.58f, maxHeight / flat) else minOf(maxWidth, (maxHeight - 52.dp) / flat)
            val dial = @Composable {
                Box(Modifier.size(diameter, diameter * flat), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.requiredSize(diameter).graphicsLayer {
                            rotationX = 55f
                            cameraDistance = 16f * density
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        // El plato con grosor (una sombra debajo) para que se note el relieve.
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f * 0.96f
                            drawCircle(Color.Black.copy(alpha = 0.45f), r, center = Offset(center.x, center.y + 14.dp.toPx()))
                            drawCircle(c.surfaceHigh, r)
                            drawCircle(c.line, r, style = Stroke(3.dp.toPx()))
                        }
                        // El anillo gira al revés del rumbo: el norte queda donde está de verdad.
                        Box(Modifier.fillMaxSize().rotate(-(heading ?: 0f)), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val r = size.minDimension / 2f * 0.96f
                                for (i in 0 until 72) {
                                    val a = (i * 5 * PI / 180).toFloat()
                                    val len = if (i % 18 == 0) 20.dp.toPx() else if (i % 6 == 0) 13.dp.toPx() else 6.dp.toPx()
                                    val from = Offset(center.x + (r - len) * sin(a), center.y - (r - len) * cos(a))
                                    val to = Offset(center.x + r * sin(a), center.y - r * cos(a))
                                    drawLine(if (i == 0) c.accent else c.textMuted, from, to, strokeWidth = 2.5.dp.toPx())
                                }
                            }
                            names.forEachIndexed { i, res ->
                                val align = when (i) { 0 -> Alignment.TopCenter; 1 -> Alignment.CenterEnd; 2 -> Alignment.BottomCenter; else -> Alignment.CenterStart }
                                Box(Modifier.fillMaxSize().padding(diameter * 0.1f), contentAlignment = align) {
                                    // Cada letra se endereza con el anillo para leerse siempre de pie.
                                    Box(Modifier.rotate(heading ?: 0f)) {
                                        Label(stringResource(res), Typography.title.copy(fontSize = (diameter.value * 0.13f).coerceIn(14f, 34f).sp), if (i == 0) c.accent else c.text, 1)
                                    }
                                }
                            }
                        }
                        // Fijo: el abanico hacia adelante y la flecha del carro (adelante = arriba de la pantalla).
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            val fan = Path().apply {
                                moveTo(center.x, center.y)
                                lineTo(center.x - r * 0.28f, center.y - r * 0.9f)
                                lineTo(center.x + r * 0.28f, center.y - r * 0.9f)
                                close()
                            }
                            drawPath(fan, (if (moving) c.accent else c.textMuted).copy(alpha = 0.22f))
                            val arrow = Path().apply {
                                moveTo(center.x, center.y - r * 0.34f)
                                lineTo(center.x - r * 0.12f, center.y + r * 0.12f)
                                lineTo(center.x, center.y + r * 0.04f)
                                lineTo(center.x + r * 0.12f, center.y + r * 0.12f)
                                close()
                            }
                            drawPath(arrow, if (moving) c.accent else c.textMuted)
                            // La marca de "hacia adelante" en el borde de arriba.
                            val mark = Path().apply {
                                moveTo(center.x, center.y - r * 0.99f)
                                lineTo(center.x - r * 0.05f, center.y - r * 1.1f)
                                lineTo(center.x + r * 0.05f, center.y - r * 1.1f)
                                close()
                            }
                            drawPath(mark, c.text)
                        }
                    }
                }
            }
            val readout = @Composable {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = heading
                    if (h != null) {
                        Label(sector(h, letters), Typography.title.copy(fontSize = 34.sp), ink, 1)
                        Label("${h.roundToInt()}°", Typography.subtitle, c.textMuted, 1)
                    }
                    Label(status, Typography.small, c.textMuted, 2)
                }
            }
            if (textRight) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { dial() }
                    readout()
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                    dial()
                    readout()
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
