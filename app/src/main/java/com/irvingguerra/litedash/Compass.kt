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
import androidx.compose.foundation.layout.Box
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
    var heading by remember { mutableStateOf<Float?>(null) }
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
        var current: Float? = null
        var lastNs = 0L
        fun wrap(d: Float) = ((d % 360f) + 540f) % 360f - 180f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val k = if (haveUp) 0.05f else 1f
                        for (i in 0..2) up[i] += k * (e.values[i] - up[i])
                        haveUp = true
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val h = current
                        val dt = if (lastNs == 0L) 0f else (e.timestamp - lastNs) / 1e9f
                        lastNs = e.timestamp
                        if (h == null || !haveUp || dt <= 0f || dt > 0.5f) return
                        val norm = kotlin.math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
                        if (norm < 1f) return
                        val yawRate = (e.values[0] * up[0] + e.values[1] * up[1] + e.values[2] * up[2]) / norm // rad/s, antihorario +
                        // Solo se cuenta si el giro es de verdad (más de ~1°/s): menos desvío estando detenido.
                        if (kotlin.math.abs(yawRate) < 0.017f) return
                        current = (h - yawRate * dt * 180f / PI.toFloat() + 360f) % 360f
                        heading = current
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
                    val h = current
                    // Primer aviso: se toma tal cual; después, se corrige un poco hacia él (el giroscopio se desvía con el tiempo).
                    current = if (h == null) nav else (h + 0.3f * wrap(nav - h) + 360f) % 360f
                    heading = current
                }
            }
        }
        val ok = runCatching { ContextCompat.registerReceiver(app, receiver, IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"), ContextCompat.RECEIVER_EXPORTED) }.isSuccess
        onDispose {
            runCatching { sm?.unregisterListener(listener) }
            if (ok) runCatching { app.unregisterReceiver(receiver) }
        }
    }
    val names = listOf(R.string.compass_n, R.string.compass_e, R.string.compass_s, R.string.compass_w)
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
                    drawPath(p, c.accent)
                }
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = side / 4), horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(
                        heading?.let { "${it.roundToInt()}°" } ?: stringResource(R.string.compass_none),
                        Typography.title, maxLines = 1,
                    )
                }
            }
        }
    }
}
