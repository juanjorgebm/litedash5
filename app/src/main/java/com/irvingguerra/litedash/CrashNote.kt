package com.irvingguerra.litedash

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File

/**
 * Si LiteDash se cierra por un error, guarda qué fue y, al volver a abrir, lo muestra unos segundos: así una foto de la
 * pantalla basta para saber por qué se reinició (en la pantalla del carro no hay otra forma de ver el error).
 */
object CrashNote {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val root = generateSequence(error) { it.cause }.last()
                val where = (root.stackTrace.firstOrNull { it.className.startsWith("com.irvingguerra") } ?: root.stackTrace.firstOrNull())
                    ?.let { "${it.fileName}:${it.lineNumber}" }.orEmpty()
                File(app.filesDir, FILE).writeText("${root.javaClass.simpleName}: ${root.message.orEmpty().take(120)} [$where] (hilo ${thread.name})")
            }
            previous?.uncaughtException(thread, error)
        }
        val note = File(app.filesDir, FILE)
        if (!note.isFile) return
        val text = runCatching { note.readText() }.getOrNull()
        note.delete()
        if (text.isNullOrBlank()) return
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { Toast.makeText(app, app.getString(R.string.crash_note, text), Toast.LENGTH_LONG).show() }
        }, 2_500)
    }
}
