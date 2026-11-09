package com.irvingguerra.litedash.feature.player

import android.content.Context
import android.os.Build
import com.irvingguerra.litedash.core.ui.Https
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Android 5 a 7.1 no confía en Let's Encrypt (ISRG) y MediaPlayer usa solo los certificados del sistema, así que las
 * radios https se quedaban en "Conectando…". Aquí se abre la radio con [Https] (que sí trae esos certificados) y se
 * le entrega a MediaPlayer por http en 127.0.0.1. Solo hace falta en esas versiones.
 */
class StreamProxy(private val context: Context, private val url: String) {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    @Volatile private var closed = false

    val localUrl: String get() = "http://127.0.0.1:${server.localPort}/stream"

    fun start() {
        Thread {
            while (!closed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val input = s.getInputStream()
            // Se lee (y se ignora) la petición de MediaPlayer hasta la línea en blanco.
            var last = 0
            var n = 0
            while (n < 8192) {
                val b = input.read()
                if (b < 0) return
                n++
                last = (last shl 8 or b) and 0xFFFFFFFF.toInt()
                if (last == 0x0D0A0D0A) break
            }
            val c = Https.open(context, url).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "LiteDash")
            }
            try {
                val out = s.getOutputStream()
                if (c.responseCode !in 200..299) { out.write("HTTP/1.0 502 Bad Gateway\r\n\r\n".toByteArray()); return }
                out.write("HTTP/1.0 200 OK\r\nContent-Type: ${c.contentType ?: "audio/mpeg"}\r\nConnection: close\r\n\r\n".toByteArray())
                val buffer = ByteArray(8192)
                c.inputStream.use { body ->
                    while (!closed) {
                        val r = body.read(buffer)
                        if (r < 0) break
                        out.write(buffer, 0, r)
                        out.flush()
                    }
                }
            } finally {
                c.disconnect()
            }
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
    }

    companion object {
        fun needed(url: String) = Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1 && url.startsWith("https://", ignoreCase = true)
    }
}
