package com.irvingguerra.litedash

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Looper
import android.os.Handler
import android.database.ContentObserver
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/*
 * Widgets para encender y apagar el Wi‑Fi y el Bluetooth con un toque. En Android 5 a 9 (Wi‑Fi)
 * y 5 a 12 (Bluetooth) LiteDash lo cambia directo; en los más nuevos Android ya no deja, así que
 * se abre el ajuste del sistema.
 */

/** Wi‑Fi: encendido, apagado o cambiando (null). */
@Composable
fun WifiToggleTile() {
    val context = LocalContext.current
    val wifi = remember { ContextCompat.getSystemService(context.applicationContext, WifiManager::class.java) }
    var on by remember { mutableStateOf(wifi?.isWifiEnabled) }
    SystemBroadcast(WifiManager.WIFI_STATE_CHANGED_ACTION) { on = wifi?.isWifiEnabled }
    ToggleTile(Icons.wifi, stringResource(R.string.tile_wifi), on, null) {
        val target = on != true
        @Suppress("DEPRECATION") // en Android 10+ no hace nada: ahí se abre el ajuste
        val done = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wifi != null &&
            runCatching { wifi.setWifiEnabled(target) }.getOrDefault(false)
        if (done) on = target else openSettings(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)
    }
}

/**
 * Bluetooth. La pantalla del Okavango (Neusoft) maneja su propio Bluetooth y el de Android siempre sale apagado: el
 * estado real está en los ajustes del sistema (se vio con el monitor de la pantalla): `com.neusoft.geely.bluetooth.state`
 * (12 = encendido, 10 = apagado), `BTPhone` y `BTAudio` ("Connected" / "Disconnected"). Se lee eso y, si no hay, Android.
 */
@SuppressLint("MissingPermission") // Android 12: si falta BLUETOOTH_CONNECT, runCatching y se abre el ajuste
@Composable
fun BluetoothToggleTile() {
    val context = LocalContext.current
    val adapter = remember { ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter }
    var car by remember { mutableStateOf(readCarBluetooth(context)) }
    var android by remember { mutableStateOf(adapter?.isEnabled) }
    SystemBroadcast(BluetoothAdapter.ACTION_STATE_CHANGED) { android = adapter?.isEnabled }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { car = readCarBluetooth(context) }
        }
        val resolver = context.contentResolver
        val registered = runCatching {
            CAR_BT_KEYS.forEach { resolver.registerContentObserver(Settings.System.getUriFor(it), false, observer) }
        }.isSuccess
        car = readCarBluetooth(context)
        onDispose { if (registered) runCatching { resolver.unregisterContentObserver(observer) } }
    }
    val on = car.on ?: android
    val state = when {
        car.phone -> stringResource(R.string.toggle_phone_connected)
        on == true -> stringResource(R.string.toggle_on)
        else -> stringResource(R.string.toggle_off)
    }
    ToggleTile(Icons.bluetooth, stringResource(R.string.tile_bluetooth), on == true || car.phone, state) {
        val target = on != true
        if (target && car.on != null) { CarBluetoothKeeper.ensure(context); return@ToggleTile }
        @Suppress("DEPRECATION") // enable()/disable(): desde Android 13 ya no se dejan usar
        val done = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && adapter != null && car.on == null &&
            runCatching { if (target) adapter.enable() else adapter.disable() }.getOrDefault(false)
        if (done) android = target else openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS)
    }
}

private val CAR_BT_KEYS = listOf("com.neusoft.geely.bluetooth.state", "BTPhone", "BTAudio")

/** Lo que dicen los ajustes de la pantalla del carro: Bluetooth encendido (null = no los trae) y celular conectado. */
private data class CarBluetooth(val on: Boolean?, val phone: Boolean)

private fun readCarBluetooth(context: Context): CarBluetooth = runCatching {
    val r = context.contentResolver
    val state = Settings.System.getString(r, "com.neusoft.geely.bluetooth.state")?.toIntOrNull()
    val phone = Settings.System.getString(r, "BTPhone").equals("Connected", ignoreCase = true) ||
        Settings.System.getString(r, "BTAudio").equals("Connected", ignoreCase = true)
    CarBluetooth(on = state?.let { it == 12 || phone }, phone = phone)
}.getOrDefault(CarBluetooth(null, false))

private fun openSettings(context: Context, action: String) {
    val tries = listOf(Intent(action), Intent(Settings.ACTION_WIRELESS_SETTINGS), Intent(Settings.ACTION_SETTINGS))
    val opened = tries.any { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
    if (!opened) Toast.makeText(context, R.string.toggle_blocked, Toast.LENGTH_LONG).show()
}

/** Escucha [action] del sistema mientras el widget se ve. */
@Composable
private fun SystemBroadcast(action: String, onChange: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(action) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onChange()
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
        onChange()
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/** Ícono grande que se ilumina encendido; debajo (o al lado) el nombre y si está encendido. */
@Composable
private fun ToggleTile(icon: ImageVector, title: String, on: Boolean?, stateText: String?, onClick: () -> Unit) {
    val c = LocalColors.current
    val state = stateText ?: stringResource(if (on == true) R.string.toggle_on else R.string.toggle_off)
    Card(Modifier.fillMaxSize(), onClick = onClick) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(14.dp)) {
            val large = maxHeight > 120.dp
            val small = isSmallScreen()
            val wide = maxWidth > 120.dp
            val circle = @Composable {
                Box(
                    Modifier.size(if (large) 64.dp else if (small) 52.dp else 44.dp).clip(CircleShape)
                        .background(if (on == true) c.accent else c.surfaceHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, if (on == true) c.onPrimary else c.textMuted, if (large) 30.dp else if (small) 26.dp else 22.dp)
                }
            }
            if (large) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    circle()
                    Column {
                        Label(title, Typography.title, maxLines = 1)
                        Label(state, Typography.small, if (on == true) c.accent else c.textMuted, maxLines = 1)
                    }
                }
            } else {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (wide) Arrangement.Start else Arrangement.Center) {
                    circle()
                    if (wide) {
                        Column(Modifier.padding(start = 14.dp)) {
                            Label(title, Typography.subtitle, maxLines = 1)
                            Label(state, Typography.small, if (on == true) c.accent else c.textMuted, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
