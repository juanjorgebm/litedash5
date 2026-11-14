package com.irvingguerra.litedash.feature.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Servicios de los ELM327 por Bluetooth LE que se conocen (se prueban primero, en orden). */
private val KNOWN_SERVICES = listOf(
    "0000fff0-0000-1000-8000-00805f9b34fb", // la mayoría de los clones (Veepeak, Konnwei…)
    "0000ffe0-0000-1000-8000-00805f9b34fb", // módulos tipo HM-10
    "000018f0-0000-1000-8000-00805f9b34fb", // Vgate y otros
    "e7810a71-73ae-499d-8c15-faa9aef0c3f2", // Vgate iCar Pro BLE
).map(UUID::fromString)

/** Servicios genéricos de todo aparato BLE (nombre, batería…): no son el puerto serie. */
private val GENERIC_SERVICES = setOf("00001800", "00001801", "0000180a", "0000180f")

/** Descriptor para pedir avisos de una característica (CCCD). */
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Cuánto cabe en un envío con el MTU de fábrica (23 − 3 de encabezado). */
private const val CHUNK = 20

/**
 * ELM327 por Bluetooth LE: no se empareja, se conecta directo. Lo que manda el
 * adaptador llega por avisos de una característica y lo que se le manda se escribe en
 * otra, de 20 en 20 bytes. Se arma como un flujo para que [Elm327] no note diferencia.
 * El constructor espera a que todo esté listo (llamarlo desde un hilo de fondo).
 */
@SuppressLint("MissingPermission") // el repositorio revisa BLUETOOTH_CONNECT antes
class BleLink(context: Context, device: BluetoothDevice) : QueuedLink() {
    override val chunkSize = CHUNK
    private val ready = CountDownLatch(1)
    private val written = Semaphore(0)
    @Volatile private var failure: String? = null
    private var writer: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closed = true
                failure = failure ?: "Se desconectó el adaptador ($status)"
                ready.countDown()
                written.release()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failure = "No se pudo leer el adaptador ($status)"
                return ready.countDown()
            }
            val pair = serialPair(g)
            if (pair == null) {
                failure = "No parece un adaptador OBD"
                return ready.countDown()
            }
            val (notify, write) = pair
            writer = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                failure = "El adaptador no deja recibir sus respuestas"
                return ready.countDown()
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            writeDescriptor(g, cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) failure = "El adaptador no aceptó mandar respuestas ($status)"
            ready.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            for (b in value) incoming.put(b)
        }

        @Deprecated("Android 12 y anteriores")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ch.value?.let { v -> for (b in v) incoming.put(b) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            written.release()
        }
    }

    // null si el Bluetooth está apagado.
    private val gatt: BluetoothGatt = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, callback))
        ?: throw IOException("El Bluetooth está apagado")

    init {
        val ok = ready.await(15, TimeUnit.SECONDS)
        if (!ok || failure != null || writer == null) {
            close()
            throw IOException(failure ?: "El adaptador no respondió por Bluetooth LE")
        }
    }

    /**
     * Manda un pedazo y espera a que el adaptador lo reciba (de uno en uno, si no se
     * pierden). GATT pide el arreglo exacto, así que aquí sí se copia el trozo.
     */
    override fun send(bytes: ByteArray, offset: Int, length: Int) {
        val chunk = bytes.copyOfRange(offset, offset + length)
        val ch = writer ?: throw IOException("Sin canal de escritura")
        // Confirmaciones tardías de un envío anterior que ya se dio por perdido.
        written.drainPermits()
        val type = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ch, chunk, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = type
            @Suppress("DEPRECATION")
            ch.value = chunk
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ch)
        }
        if (!started || !written.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("No se pudo mandar al adaptador")
    }

    override fun close() {
        closed = true
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }
}

/**
 * La característica que avisa (lo que contesta el adaptador) y la que se escribe, del
 * mismo servicio: primero los servicios conocidos, luego cualquiera que tenga las dos.
 */
private fun serialPair(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
    val services = g.services.orEmpty().filter { s -> GENERIC_SERVICES.none { s.uuid.toString().startsWith(it) } }
    val ordered = services.sortedBy { s -> KNOWN_SERVICES.indexOf(s.uuid).let { if (it < 0) Int.MAX_VALUE else it } }
    for (s in ordered) {
        val chars = s.characteristics.orEmpty()
        val notify = chars.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 }
        val write = chars.firstOrNull { it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 }
        if (notify != null && write != null) return notify to write
    }
    return null
}

@SuppressLint("MissingPermission")
private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        g.writeDescriptor(d, value)
    } else {
        @Suppress("DEPRECATION")
        d.value = value
        @Suppress("DEPRECATION")
        g.writeDescriptor(d)
    }
}
