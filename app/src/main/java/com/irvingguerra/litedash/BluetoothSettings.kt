// Ajustes → Apps → Música → "Vincular celular": el Bluetooth de Android de la pantalla, sin
// app externa. Emparejar (o quitar) el celular, hacerse visible y buscar celulares cerca.
package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.SourceKind

/**
 * Vincular (o quitar) el celular por el Bluetooth de Android de la pantalla. En pantallas con
 * chip propio (sin el Bluetooth de Android), se abre su propia app. Todo inline, sin ventanas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BluetoothSettings(d: Dependencies) {
    val context = LocalContext.current
    val c = LocalColors.current
    val adapter = remember { ContextCompat.getSystemService(context, BluetoothManager::class.java)?.adapter }

    KeepBluetoothRow(d)
    // Sin el Bluetooth de Android (o sin el perfil de música AVRCP): vincula desde su propia app.
    if (adapter == null || !d.music.bluetoothBuiltIn) {
        Label(stringResource(R.string.bt_own_pair), Typography.body, c.textMuted)
        if (d.music.sourceApps[SourceKind.BLUETOOTH] != null) {
            Button(stringResource(R.string.bt_open_app), { d.music.openBluetoothApp() })
        }
        return
    }

    var on by remember { mutableStateOf(adapter.isEnabled) }
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<BluetoothDevice>() }

    LifecycleStartEffect(adapter) {
        on = adapter.isEnabled
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, i: Intent) {
                when (i.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> on = adapter.isEnabled
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = IntentCompat.getParcelableExtra(i, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        if (dev != null && found.none { it.address == dev.address }) found.add(dev)
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // Avisos del sistema: el receptor debe aceptar avisos de fuera de la app.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onStopOrDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    if (!on) {
        val askEnable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
        Label(stringResource(R.string.bluetooth_off), Typography.body, c.danger)
        Button(stringResource(R.string.bluetooth_turn_on), { askEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }, primary = true)
        return
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val granted = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    if (!granted) {
        Label(stringResource(R.string.bt_permission_help), Typography.small, c.textMuted)
        Button(stringResource(R.string.bt_permission), { ask.launch(permissions.toTypedArray()) }, primary = true)
        return
    }

    // El celular conectado (se ve si está dado el permiso de ver su nombre).
    val connected = d.music.bluetoothDevice
    if (connected != null) Label(stringResource(R.string.bt_connected, connected), Typography.body)
    else Label(stringResource(R.string.bt_not_connected), Typography.body, c.textMuted)

    // Cómo se llama esta pantalla, para buscarla desde el celular.
    val myName = remember(granted) { adapterName(adapter) }
    if (myName.isNotEmpty()) Label(stringResource(R.string.bt_screen_name, myName), Typography.small, c.textMuted)

    // Hacerse visible 5 minutos para que el celular la encuentre.
    val discover = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    Button(stringResource(R.string.bt_discover), {
        runCatching {
            discover.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300))
        }
    })

    // Buscar celulares cerca y emparejar tocando uno.
    Button(stringResource(if (scanning) R.string.bt_searching else R.string.bt_search), {
        if (scanning) return@Button
        found.clear()
        scanning = true
        startDiscovery(adapter)
    })
    if (found.isNotEmpty()) {
        Label(stringResource(R.string.bt_found), Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (dev in found) Button(deviceLabel(dev), { bond(dev) })
        }
    }

    // Celulares ya guardados, con "Olvidar" para volver a empezar.
    val bonded = remember(granted, on) { mutableStateListOf<BluetoothDevice>().apply { addAll(bondedDevices(adapter)) } }
    if (bonded.isNotEmpty()) {
        Label(stringResource(R.string.bt_paired), Typography.label, c.textMuted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (dev in bonded) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Label(deviceLabel(dev), Typography.body, maxLines = 1)
                    Button(stringResource(R.string.bt_forget), { forget(dev); bonded.remove(dev) })
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun adapterName(adapter: BluetoothAdapter): String = runCatching { adapter.name }.getOrNull().orEmpty()

@SuppressLint("MissingPermission")
private fun deviceLabel(dev: BluetoothDevice): String =
    runCatching { dev.name?.takeIf { it.isNotBlank() } ?: dev.address }.getOrDefault(dev.address)

@SuppressLint("MissingPermission")
private fun bondedDevices(adapter: BluetoothAdapter): List<BluetoothDevice> =
    runCatching { adapter.bondedDevices.toList() }.getOrDefault(emptyList())

@SuppressLint("MissingPermission")
private fun startDiscovery(adapter: BluetoothAdapter): Boolean =
    runCatching { adapter.startDiscovery() }.getOrDefault(false)

@SuppressLint("MissingPermission")
private fun bond(dev: BluetoothDevice) = runCatching { dev.createBond() }

/** Quitar el vínculo guardado: `removeBond` no es público, va por reflexión. */
private fun forget(dev: BluetoothDevice) = runCatching { dev.javaClass.getMethod("removeBond").invoke(dev) }

/** Opción: mantener el Bluetooth del carro activo y reconectando solo al encender. */
@Composable
private fun KeepBluetoothRow(d: Dependencies) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<String?>(null) }
    com.irvingguerra.litedash.core.ui.SwitchRow(stringResource(R.string.bt_keep), d.prefs.keepBluetooth, { d.prefs.keepBluetooth = it }, stringResource(R.string.bt_keep_help))
    if (d.prefs.keepBluetooth) {
        Button(stringResource(R.string.bt_keep_now), { CarBluetoothKeeper.ensure(context) { result = it } })
        result?.let { Label(stringResource(R.string.bt_keep_result, it), Typography.label, LocalColors.current.textMuted) }
    }
}
