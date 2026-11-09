package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brújula sin sensor magnético: el rumbo (`CAR_DIRECTION`) de la navegación del carro es la referencia del norte y el
 * giroscopio (con el acelerómetro para saber cuál es "arriba") lo sigue entre avisos. Sin navegación activa no hay norte.
 */
@Composable
fun CompassTile() {
    val context = LocalContext.current
    val c = LocalColors.current
    val prefs = remember { context.applicationContext.getSharedPreferences("compass", Context.MODE_PRIVATE) }
    // El último rumbo se recuerda: el carro apagado no gira, así que al volver a encender sigue valiendo.
    var heading by remember { mutableStateOf<Float?>(prefs.getFloat("heading", -1f).takeIf { it >= 0f }) }
    var picking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val app = context.applicationContext
        val sm = ContextCompat.getSystemService(app, SensorManager::class.java)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        // La pantalla no trae brújula magnética (solo acelerómetro y giroscopio), así que el norte lo da el rumbo de la
        // navegación del carro (CAR_DIRECTION) y el giroscopio lo mantiene entre un aviso y otro: el eje de giro del carro es
        // "hacia arriba", que se saca del acelerómetro (en reposo marca hacia arriba), sin importar cómo esté montada la pantalla.
        val up = FloatArray(3)
        var haveUp = false
        var lastNs = 0L
        var lastSave = 0L
        // Cuándo el carro está detenido (el acelerómetro casi no vibra): ahí se mide el desvío propio del giroscopio.
        var accMean = 9.8f
        var accDev = 1f
        var bias = 0f
        fun wrap(d: Float) = ((d % 360f) + 540f) % 360f - 180f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val k = if (haveUp) 0.05f else 1f
                        for (i in 0..2) up[i] += k * (e.values[i] - up[i])
                        haveUp = true
                        val m = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                        accMean += 0.02f * (m - accMean)
                        accDev += 0.02f * (kotlin.math.abs(m - accMean) - accDev)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val h = heading
                        val dt = if (lastNs == 0L) 0f else (e.timestamp - lastNs) / 1e9f
                        lastNs = e.timestamp
                        if (h == null || !haveUp || dt <= 0f || dt > 0.5f) return
                        val norm = kotlin.math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
                        if (norm < 1f) return
                        val yawRate = (e.values[0] * up[0] + e.values[1] * up[1] + e.values[2] * up[2]) / norm // rad/s, antihorario +
                        // Detenido: no hay giro posible, lo que marque el giroscopio es su desvío y se resta después.
                        if (accDev < 0.03f) {
                            bias += 0.01f * (yawRate - bias)
                            return
                        }
                        val rate = yawRate - bias
                        if (kotlin.math.abs(rate) < 0.005f) return
                        val next = (h - rate * dt * 180f / PI.toFloat() + 360f) % 360f
                        heading = next
                        val now = System.currentTimeMillis()
                        if (now - lastSave > 5_000) { lastSave = now; prefs.edit().putFloat("heading", next).apply() }
                    }
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listOfNotNull(acc, gyro).forEach { runCatching { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.getIntExtra("KEY_TYPE", -1) == 10001 && i.hasExtra("CAR_DIRECTION")) {
                    val nav = i.getIntExtra("CAR_DIRECTION", 0).toFloat()
                    val h = heading
                    // Primer aviso: se toma tal cual; después, se corrige un poco hacia él (el giroscopio se desvía con el tiempo).
                    heading = if (h == null) nav else (h + 0.3f * wrap(nav - h) + 360f) % 360f
                }
            }
        }
        val ok = runCatching { ContextCompat.registerReceiver(app, receiver, IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"), ContextCompat.RECEIVER_EXPORTED) }.isSuccess
        onDispose {
            heading?.let { prefs.edit().putFloat("heading", it).apply() }
            runCatching { sm?.unregisterListener(listener) }
            if (ok) runCatching { app.unregisterReceiver(receiver) }
        }
    }
    val names = listOf(R.string.compass_n, R.string.compass_e, R.string.compass_s, R.string.compass_w)
    val letters = names.map { stringResource(it) }
    Card(Modifier.fillMaxSize().clickable { picking = !picking }) {
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
                    drawPath(p, c.accent)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = side / 4), horizontalAlignment = Alignment.CenterHorizontally) {
                    val h = heading
                    Label(
                        if (h == null) stringResource(R.string.compass_none) else "${sector(h, letters)} ${h.roundToInt()}°",
                        Typography.title, maxLines = 1,
                    )
                }
            }
            if (picking) {
                // Sin brújula magnética el norte lo fijas tú: toca hacia dónde mira el carro ahora; desde ahí el giroscopio lo sigue.
                val points = listOf(0f to listOf(0), 45f to listOf(0, 1), 90f to listOf(1), 135f to listOf(2, 1), 180f to listOf(2), 225f to listOf(2, 3), 270f to listOf(3), 315f to listOf(0, 3))
                Column(Modifier.fillMaxSize().background(c.surfaceHigh).clickable { picking = false }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Label(stringResource(R.string.compass_pick), Typography.small, c.textMuted, 2)
                    for (row in points.chunked(3)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((deg, parts) in row) {
                                Button(parts.joinToString("") { letters[it] }, {
                                    heading = deg
                                    prefs.edit().putFloat("heading", deg).apply()
                                    picking = false
                                })
                            }
                        }
                    }
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
