package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.NotificationService

/**
 * Guía de la navegación del carro (Amap / `com.neusoft.ecarx.naviac`). Esa app manda por broadcast
 * público `AUTONAVI_STANDARD_BROADCAST_SEND` la calle siguiente, el giro, las distancias y el límite de
 * velocidad (el ExtraService de Neusoft lo recibe igual, sin permisos). Aquí solo se lee y se muestra.
 */
private const val ACTION_NAV = "AUTONAVI_STANDARD_BROADCAST_SEND"

private data class NavInfo(
    val road: String, val next: String, val segMeters: Int, val routeMeters: Int, val routeSeconds: Int,
    val icon: Int, val limit: Int, val camera: Int,
)

/** Giro del ícono de Amap, en grados (0 = recto, 90 = derecha); null si no se conoce. */
private fun turnDegrees(icon: Int): Float? = when (icon) {
    2 -> -90f; 3 -> 90f; 4 -> -45f; 5 -> 45f; 6 -> -135f; 7 -> 135f; 8 -> 180f; 9 -> 0f
    else -> null
}

private fun turnName(icon: Int): Int = when (icon) {
    2 -> R.string.nav_left; 3 -> R.string.nav_right; 4 -> R.string.nav_slight_left; 5 -> R.string.nav_slight_right
    6 -> R.string.nav_sharp_left; 7 -> R.string.nav_sharp_right; 8 -> R.string.nav_uturn; 9 -> R.string.nav_straight
    11, 12 -> R.string.nav_roundabout; 10, 15 -> R.string.nav_arrive
    else -> R.string.nav_continue
}

private fun distance(m: Int) = if (m >= 1000) "%.1f km".format(m / 1000f) else "$m m"

private fun minutes(s: Int) = if (s >= 3600) "${s / 3600} h ${(s % 3600) / 60} min" else "${maxOf(1, s / 60)} min"

/** Pieza "Navegación del carro": nada que ver hasta que la navegación del carro arranque una ruta. */
@Composable
fun CarNavTile(d: Dependencies, onOpen: () -> Unit) {
    val context = LocalContext.current
    val c = LocalColors.current
    var info by remember { mutableStateOf<NavInfo?>(null) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                runCatching {
                    when (i.getIntExtra("KEY_TYPE", -1)) {
                        10001 -> info = NavInfo(
                            road = i.getStringExtra("CUR_ROAD_NAME").orEmpty(),
                            next = i.getStringExtra("NEXT_ROAD_NAME").orEmpty(),
                            segMeters = i.getIntExtra("SEG_REMAIN_DIS", 0),
                            routeMeters = i.getIntExtra("ROUTE_REMAIN_DIS", 0),
                            routeSeconds = i.getIntExtra("ROUTE_REMAIN_TIME", 0),
                            icon = i.getIntExtra("ICON", -1),
                            limit = i.getIntExtra("LIMITED_SPEED", 0),
                            camera = i.getIntExtra("CAMERA_SPEED", 0),
                        )
                        // Estado: 9 y 11 = terminó la guía (real o simulada).
                        10019 -> if (i.getIntExtra("EXTRA_STATE", -1) in listOf(9, 11)) info = null
                    }
                }
            }
        }
        val ok = runCatching { ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(ACTION_NAV), ContextCompat.RECEIVER_EXPORTED) }.isSuccess
        onDispose { if (ok) runCatching { context.applicationContext.unregisterReceiver(receiver) } }
    }
    // Sin ruta de la navegación del carro: la vuelta que dice la notificación de Waze o Google Maps (si se dio acceso a notificaciones).
    val repo = d.navigation
    DisposableEffect(repo) {
        repo.use { NotificationService.active() }
        onDispose { repo.release() }
    }
    val now = info
    val phoneTurn = repo.instruction
    Card(Modifier.fillMaxSize(), onClick = onOpen) {
        if (now == null && phoneTurn != null) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) { Turn(phoneTurn, large = true) }
        } else if (now == null) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(Radius.card)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.navigate, c.accent, 26.dp)
                }
                Label(stringResource(R.string.nav_idle), Typography.title, maxLines = 1, modifier = Modifier.padding(top = 12.dp))
                Label(stringResource(R.string.nav_idle_help), Typography.small, c.textMuted, maxLines = 2)
                if (!d.music.hasPermission()) Label(
                    stringResource(R.string.nav_need_notifications), Typography.small, c.accent, maxLines = 2,
                    modifier = Modifier.padding(top = 6.dp).clickable { d.music.requestPermission() },
                )
            }
        } else {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(72.dp).clip(RoundedCornerShape(Radius.card)).background(c.accent), contentAlignment = Alignment.Center) {
                        Box(Modifier.rotate(turnDegrees(now.icon) ?: 0f)) { Icon(Icons.navigate, c.onPrimary, 36.dp) }
                    }
                    Column(Modifier.padding(start = 16.dp)) {
                        Label(distance(now.segMeters), Typography.title, maxLines = 1)
                        Label(stringResource(turnName(now.icon)), Typography.body, maxLines = 1)
                        if (now.next.isNotBlank()) Label(now.next, Typography.body, c.textMuted, maxLines = 2)
                    }
                }
                Column {
                    if (now.road.isNotBlank()) Label(now.road, Typography.small, c.textMuted, maxLines = 1)
                    Label(
                        stringResource(R.string.nav_remaining, distance(now.routeMeters), minutes(now.routeSeconds)),
                        Typography.subtitle, maxLines = 1,
                    )
                    val limit = if (now.camera > 0) now.camera else now.limit
                    if (limit > 0) Label(stringResource(R.string.nav_limit, limit), Typography.label, c.accent, maxLines = 1)
                }
            }
        }
    }
}
