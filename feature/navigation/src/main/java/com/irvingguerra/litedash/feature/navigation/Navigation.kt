package com.irvingguerra.litedash.feature.navigation

import android.os.Build
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lo que dice el aviso de la app de navegación: la siguiente vuelta y la llegada. */
@Immutable
data class TurnInstruction(
    val key: String,
    val pkg: String,
    /** Normalmente la distancia a la vuelta ("300 m"). */
    val title: String,
    /** Normalmente la maniobra ("Gira a la derecha hacia Reforma"). */
    val text: String,
    /** Llegada, tiempo o distancia restante, si la app la pone. */
    val detail: String,
    /** La flecha de la maniobra que dibuja la app. */
    val arrow: Bitmap?,
)

/**
 * Lee la siguiente vuelta del aviso que Waze o Google Maps muestran mientras
 * navegan, para tenerla en el tablero sin abrir el mapa. Solo mira avisos de
 * [packages] y solo mientras algo la usa ([use]/[release]); no guarda nada.
 */
class NavigationRepository(private val context: Context, private val packages: Set<String>) {
    var instruction by mutableStateOf<TurnInstruction?>(null)
        private set

    private var users = 0

    /** [active] son los avisos que ya están en pantalla al empezar a escuchar. */
    fun use(active: () -> List<StatusBarNotification>) {
        users++
        if (users == 1) active().forEach(::posted)
    }

    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) instruction = null
    }

    fun posted(notification: StatusBarNotification) {
        if (users == 0 || notification.packageName !in packages) return
        val n = notification.notification
        // Waze y Maps navegan con un aviso fijo (o de categoría navegación); los demás avisos no cuentan.
        // CATEGORY_NAVIGATION (API 28) es solo un texto: en Android viejo la categoría llega vacía.
        @SuppressLint("InlinedApi")
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0 || n.category == Notification.CATEGORY_NAVIGATION
        if (!ongoing && notification.packageName != TEST_PACKAGE) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val detail = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        // Waze y Maps repiten el aviso a cada rato con lo mismo: si no cambió, ni se carga
        // la flecha (es un bitmap) ni se avisa a la pantalla.
        val current = instruction
        if (current != null && current.key == notification.key && current.title == title && current.text == text && current.detail == detail) return
        instruction = TurnInstruction(
            key = notification.key,
            pkg = notification.packageName,
            title = title,
            text = text,
            detail = detail,
            arrow = arrow(n),
        )
    }

    fun removed(notification: StatusBarNotification) {
        if (instruction?.key == notification.key) instruction = null
    }

    private fun arrow(n: Notification): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 5: el ícono grande es un Bitmap suelto (no hay Icon).
            @Suppress("DEPRECATION")
            return n.largeIcon
        }
        val d = n.getLargeIcon()?.loadDrawable(context) ?: return null
        if (d is BitmapDrawable) return d.bitmap
        val side = (48 * context.resources.displayMetrics.density).toInt()
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also {
            d.setBounds(0, 0, side, side)
            d.draw(Canvas(it))
        }
    }.getOrNull()

    companion object {
        /** Los avisos de `adb shell cmd notification post` (para probar sin manejar). */
        const val TEST_PACKAGE = "com.android.shell"
    }
}
