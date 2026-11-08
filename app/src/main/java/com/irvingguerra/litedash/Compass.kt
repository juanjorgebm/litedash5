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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Brújula. El rumbo sale del sensor magnético de la pantalla (vector de rotación, o magnetómetro con
 * acelerómetro) y, si la pantalla no lo trae, del rumbo (`CAR_DIRECTION`) que manda la navegación del carro.
 * La pantalla va parada: el rumbo es hacia donde mira su parte de atrás (X y Z del sensor).
 */
@Composable
fun CompassTile() {
    val context = LocalContext.current
    val c = LocalColors.current
    var sensorHeading by remember { mutableStateOf<Float?>(null) }
    var navHeading by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val app = context.applicationContext
        val sm = ContextCompat.getSystemService(app, SensorManager::class.java)
        val rot = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val mag = sm?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gravity = FloatArray(3)
        val field = FloatArray(3)
        var haveGravity = false
        var haveField = false
        // Suaviza en círculo (359° y 1° son vecinos): se promedian seno y coseno.
        var sx = 0f
        var sy = 0f
        var started = false
        fun publish(deg: Float) {
            val r = (deg * PI / 180).toFloat()
            if (!started) { sx = cos(r); sy = sin(r); started = true }
            else { sx += 0.12f * (cos(r) - sx); sy += 0.12f * (sin(r) - sy) }
            val out = (atan2(sy, sx) * 180 / PI).toFloat()
            sensorHeading = (out + 360f) % 360f
        }
        fun fromMatrix(matrix: FloatArray) {
            val remapped = FloatArray(9)
            if (!SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)) return
            val o = FloatArray(3)
            SensorManager.getOrientation(remapped, o)
            publish((o[0] * 180 / PI).toFloat())
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        val m = FloatArray(9)
                        SensorManager.getRotationMatrixFromVector(m, e.values)
                        fromMatrix(m)
                    }
                    Sensor.TYPE_ACCELEROMETER -> { e.values.copyInto(gravity, endIndex = 3); haveGravity = true }
                    Sensor.TYPE_MAGNETIC_FIELD -> { e.values.copyInto(field, endIndex = 3); haveField = true }
                }
                if (rot == null && haveGravity && haveField && e.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    val m = FloatArray(9)
                    if (SensorManager.getRotationMatrix(m, null, gravity, field)) fromMatrix(m)
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        val sensors = if (rot != null) listOf(rot) else listOfNotNull(mag, acc).takeIf { mag != null && acc != null }.orEmpty()
        sensors.forEach { runCatching { sm?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                if (i.getIntExtra("KEY_TYPE", -1) == 10001 && i.hasExtra("CAR_DIRECTION")) {
                    navHeading = i.getIntExtra("CAR_DIRECTION", 0).toFloat()
                }
            }
        }
        val ok = runCatching { ContextCompat.registerReceiver(app, receiver, IntentFilter("AUTONAVI_STANDARD_BROADCAST_SEND"), ContextCompat.RECEIVER_EXPORTED) }.isSuccess
        onDispose {
            runCatching { sm?.unregisterListener(listener) }
            if (ok) runCatching { app.unregisterReceiver(receiver) }
        }
    }
    val heading = sensorHeading ?: navHeading
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
