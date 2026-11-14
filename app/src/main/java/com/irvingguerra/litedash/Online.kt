package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Página de Google que contesta 204 si hay internet de verdad (no solo una red conectada). */
private const val CHECK_URL = "https://www.gstatic.com/generate_204"

/**
 * ¿Falta internet? Se prueba con una petición real (una red Wi‑Fi puede estar conectada y sin salida) cada minuto, y de
 * inmediato cuando cambia la red; se dice "sí" tras dos fallas seguidas para no avisar por un tropiezo. Solo corre mientras
 * el inicio se ve.
 */
@Composable
fun rememberOffline(): Boolean {
    val context = LocalContext.current
    var offline by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { tick++ }
        }
        @Suppress("DEPRECATION")
        runCatching { ContextCompat.registerReceiver(context, receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION), ContextCompat.RECEIVER_EXPORTED) }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(tick) {
        var failures = 0
        while (true) {
            val ok = withContext(Dispatchers.IO) {
                runCatching { Https.get(context, CHECK_URL).disconnect(); true }.getOrDefault(false)
            }
            failures = if (ok) 0 else failures + 1
            offline = failures >= 2
            delay(if (ok) 60_000L else 10_000L)
        }
    }
    return offline
}

/** Aviso del inicio: "Sin internet"; tocarlo abre el ajuste de Wi‑Fi. */
@Composable
fun OfflineNotice(onClick: () -> Unit) {
    val c = LocalColors.current
    Label(
        stringResource(R.string.offline_notice),
        Typography.body,
        c.warning,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.warning.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
