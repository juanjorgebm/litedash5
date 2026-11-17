package com.irvingguerra.litedash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/** Avisos del sistema para LiteDash: escucha solo mientras se ve (con otra app al frente, nada). */
private fun Context.listen(receiver: BroadcastReceiver, action: String) =
    ContextCompat.registerReceiver(this, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)

/** Volumen de la música en el dock: botones − / + y el nivel. */
@Composable
fun Volume(buttonSize: Dp, vertical: Boolean = false) {
    val context = LocalContext.current
    val audio = remember { ContextCompat.getSystemService(context, AudioManager::class.java)!! }
    var level by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    LifecycleStartEffect(audio) {
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
        }
        context.listen(receiver, VOLUME_CHANGED)
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    fun adjust(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        level = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }
    // En la barra lateral: + arriba, el nivel y − abajo.
    if (vertical) return Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(Icons.plus, { adjust(AudioManager.ADJUST_RAISE) }, size = buttonSize)
        Label(level.toString(), Typography.subtitle, maxLines = 1)
        IconButton(Icons.minus, { adjust(AudioManager.ADJUST_LOWER) }, size = buttonSize)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.minus, { adjust(AudioManager.ADJUST_LOWER) }, size = buttonSize)
        Row(Modifier.widthIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Label(level.toString(), Typography.subtitle, maxLines = 1)
        }
        IconButton(Icons.plus, { adjust(AudioManager.ADJUST_RAISE) }, size = buttonSize)
        Spacer(Modifier.width(4.dp))
    }
}

/** El sistema lo manda al cambiar cualquier volumen; no está en el SDK público. */
private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"

/**
 * Wi-Fi o datos, audio Bluetooth conectado y ubicación encendida, en la barra.
 * Escucha al sistema solo mientras se ve (nada de revisar cada rato) y sin
 * permisos: el Bluetooth se nota por el audio conectado (tu celular, el estéreo).
 */
@Composable
fun StatusIcons() {
    val c = LocalColors.current
    val context = LocalContext.current
    var network by remember { mutableStateOf<Boolean?>(null) } // true Wi-Fi, false datos, null nada
    var bluetooth by remember { mutableStateOf(false) }
    var location by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) {
        val connectivity = ContextCompat.getSystemService(context, ConnectivityManager::class.java)!!
        // Siempre se lee la red activa: Android 6 y 7 no avisan cómo está al registrarse (solo
        // cambios) y, escuchando cualquier red, perder una no quiere decir quedarse sin internet.
        fun check(lost: Network? = null) {
            // Al perder una red, Android 6 puede seguir dándola como activa un instante.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                // Android 5 no tiene activeNetwork: se lee el tipo de la red activa a la antigua.
                @Suppress("DEPRECATION")
                val info = runCatching { connectivity.activeNetworkInfo?.takeIf { it.isConnected } }.getOrNull()
                @Suppress("DEPRECATION")
                network = when (info?.type) {
                    ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET -> true
                    ConnectivityManager.TYPE_MOBILE -> false
                    else -> null
                }
                return
            }
            val caps = runCatching { connectivity.activeNetwork?.takeIf { it != lost }?.let(connectivity::getNetworkCapabilities) }.getOrNull()
            network = when {
                caps == null -> null
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
                else -> null
            }
        }
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = check()
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) = check()
            override fun onLost(n: Network) = check(lost = n)
        }
        runCatching {
            // Android 6 no tiene "la red de ahora": se escucha cualquier red con internet.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) connectivity.registerDefaultNetworkCallback(netCallback)
            else connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), netCallback)
        }
        check()

        val audio = ContextCompat.getSystemService(context, AudioManager::class.java)!!
        fun checkAudio() {
            bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                // Android 5: sin lista de salidas de audio; basta con saber si A2DP o SCO están activos.
                @Suppress("DEPRECATION")
                (audio.isBluetoothA2dpOn || audio.isBluetoothScoOn)
            }
        }
        val audioCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkAudio()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkAudio()
        } else null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioCallback != null) audio.registerAudioDeviceCallback(audioCallback, null)
        // Android 5 no avisa de cambios de salida: se vuelve a leer cuando cambia el Bluetooth de audio.
        val a2dpReceiver = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = checkAudio()
        } else null
        a2dpReceiver?.let { context.listen(it, "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") }
        checkAudio()

        val locations = ContextCompat.getSystemService(context, LocationManager::class.java)!!
        fun checkLocation() {
            location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) locations.isLocationEnabled
            else locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
        val locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = checkLocation()
        }
        context.listen(locationReceiver, LocationManager.PROVIDERS_CHANGED_ACTION)
        checkLocation()

        onStopOrDispose {
            runCatching { connectivity.unregisterNetworkCallback(netCallback) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioCallback != null) audio.unregisterAudioDeviceCallback(audioCallback)
            a2dpReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            context.unregisterReceiver(locationReceiver)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (bluetooth) Icon(Icons.bluetooth, c.textMuted, 20.dp)
        if (location) Icon(Icons.location, c.textMuted, 20.dp)
        when (network) {
            true -> Icon(Icons.wifi, c.textMuted, 20.dp)
            false -> Icon(Icons.signal, c.textMuted, 20.dp)
            null -> Unit
        }
    }
}
