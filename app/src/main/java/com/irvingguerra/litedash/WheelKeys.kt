package com.irvingguerra.litedash

import android.os.Build
import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.MusicRepository
import androidx.core.content.ContextCompat

/**
 * Qué hace un botón del volante. [stored] es como se guarda (no cambiarlo).
 * NORMAL ("Como de fábrica") = lo que haga Android con esa tecla (la música va a la app que suena,
 * el volumen al sistema): así funciona en cualquier pantalla sin configurar nada.
 */
enum class WheelAction(val stored: String, val title: Int) {
    NORMAL("normal", R.string.wheel_reset),
    PLAY_PAUSE("play", R.string.wheel_play_pause),
    NEXT("next", R.string.wheel_next),
    PREVIOUS("previous", R.string.wheel_previous),
    VOLUME_UP("volume_up", R.string.wheel_volume_up),
    VOLUME_DOWN("volume_down", R.string.wheel_volume_down),
    MUTE("mute", R.string.wheel_mute),
    NAVIGATE("navigate", R.string.wheel_navigate),
    MUSIC("music", R.string.wheel_music),
    VOICE("voice", R.string.wheel_voice),
    HOME("home", R.string.wheel_home),
    NOTHING("nothing", R.string.wheel_nothing);

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.stored == value }
    }
}

/**
 * Mandos al volante con el estándar de Android: casi todas las pantallas de auto
 * convierten los botones del volante en teclas normales (siguiente, anterior,
 * play, volumen, voz…). LiteDash las recibe mientras está al frente; con otra app
 * al frente siguen yendo a Android como siempre.
 */
class WheelKeys(private val context: Context, private val prefs: Preferences) {
    /** Mientras Ajustes espera un botón: recibe la tecla en vez de hacer su acción. */
    var learning by mutableStateOf<((Int) -> Unit)?>(null)

    /** Sube con cada botón: el inicio lo usa para salir del modo estacionado. */
    var presses by mutableIntStateOf(0)
        private set

    /** La acción que el inicio debe hacer (abrir el mapa, la música…); el inicio la [consume]. */
    var request by mutableStateOf<WheelAction?>(null)
        private set

    /** Ya hecha: así no se repite si la pantalla se vuelve a crear (al cambiar de idioma). */
    fun consume() {
        request = null
    }

    /** true si LiteDash se quedó con la tecla (no se le pasa a Android). */
    fun onKey(event: KeyEvent, music: MusicRepository): Boolean {
        val code = event.keyCode
        if (code in IGNORED) return false
        learning?.let { learn ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) learn(code)
            return true
        }
        val action = prefs.wheelKeys[code]?.let(WheelAction::fromStored) ?: WheelAction.NORMAL
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) presses++
        if (action == WheelAction.NORMAL) return false
        // Una vez por botón... salvo el volumen, que sigue si lo dejas presionado.
        val volume = action == WheelAction.VOLUME_UP || action == WheelAction.VOLUME_DOWN
        if (event.action == KeyEvent.ACTION_DOWN && (volume || event.repeatCount == 0)) run(action, music)
        return true
    }

    // Solo Android 5: no hay forma de leer si la música está silenciada.
    private var mutedLegacy = false

    private fun run(action: WheelAction, music: MusicRepository) {
        val audio = ContextCompat.getSystemService(context, AudioManager::class.java)!!
        fun adjust(direction: Int) = runCatching {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        when (action) {
            // Si lo que suena es la radio (y la música no), el volante la controla a ella; sin
            // nada en la música pero con la radio en pausa, también.
            WheelAction.PLAY_PAUSE, WheelAction.NEXT, WheelAction.PREVIOUS -> {
                val radio = music.radio.song
                val lens = if (radio != null && (radio.playing && music.song?.playing != true || music.song == null)) music.radio else music.music
                when (action) {
                    WheelAction.PLAY_PAUSE -> lens.toggle()
                    WheelAction.NEXT -> lens.next()
                    else -> lens.previous()
                }
            }
            WheelAction.VOLUME_UP -> adjust(AudioManager.ADJUST_RAISE)
            WheelAction.VOLUME_DOWN -> adjust(AudioManager.ADJUST_LOWER)
            WheelAction.MUTE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) adjust(AudioManager.ADJUST_TOGGLE_MUTE) else runCatching {
                // Android 5 no conoce ADJUST_TOGGLE_MUTE: se silencia o se regresa a mano.
                @Suppress("DEPRECATION")
                audio.setStreamMute(AudioManager.STREAM_MUSIC, !mutedLegacy)
                mutedLegacy = !mutedLegacy
            }
            WheelAction.NORMAL, WheelAction.NOTHING -> Unit
            else -> request = action
        }
    }

    companion object {
        /** Teclas que nunca se aprenden ni se cambian: navegar la pantalla y "atrás". */
        val IGNORED = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_TAB,
        )

        /** Nombre amable de las teclas estándar; las demás, con su nombre de Android. */
        fun name(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> R.string.wheel_next
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> R.string.wheel_previous
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> R.string.wheel_play_pause
            KeyEvent.KEYCODE_MEDIA_PLAY -> R.string.key_play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> R.string.key_pause
            KeyEvent.KEYCODE_VOLUME_UP -> R.string.wheel_volume_up
            KeyEvent.KEYCODE_VOLUME_DOWN -> R.string.wheel_volume_down
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> R.string.wheel_mute
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH -> R.string.wheel_voice
            KeyEvent.KEYCODE_CALL -> R.string.key_call
            KeyEvent.KEYCODE_ENDCALL -> R.string.key_end_call
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> R.string.key_forward
            KeyEvent.KEYCODE_MEDIA_REWIND -> R.string.key_rewind
            KeyEvent.KEYCODE_MEDIA_STOP -> R.string.key_stop
            else -> null
        }
    }
}

@Composable
private fun keyName(code: Int): String =
    WheelKeys.name(code)?.let { stringResource(it) }
        ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** Ajustes → Mandos al volante: aprender un botón y elegir qué hace. */
@Composable
fun WheelSettings(keys: WheelKeys, prefs: Preferences) {
    val c = LocalColors.current
    var waiting by remember { mutableStateOf(false) }
    var learned by remember { mutableStateOf<Int?>(null) }
    // Mientras está esta pantalla y se espera un botón, la tecla se aprende en vez de hacer su acción.
    DisposableEffect(waiting) {
        keys.learning = if (waiting) { code -> learned = code; waiting = false } else null
        onDispose { keys.learning = null }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            waiting -> {
                Label(stringResource(R.string.wheel_press), Typography.subtitle, c.accent)
                Button(stringResource(R.string.cancel), { waiting = false })
            }
            learned != null -> {
                val code = learned!!
                Label(stringResource(R.string.wheel_learned, keyName(code)), Typography.subtitle)
                Label(stringResource(R.string.wheel_choose), Typography.small, c.textMuted)
                val current = prefs.wheelKeys[code]?.let(WheelAction::fromStored) ?: WheelAction.NORMAL
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (a in WheelAction.entries) {
                        Button(stringResource(a.title), {
                            prefs.wheelKeys = if (a == WheelAction.NORMAL) prefs.wheelKeys - code else prefs.wheelKeys + (code to a.stored)
                            learned = null
                        }, primary = a == current)
                    }
                }
            }
            else -> Button(stringResource(R.string.wheel_learn), { waiting = true }, primary = true)
        }
    }
}

/** La lista de botones ya configurados, con su acción y para dejarlos como de fábrica. */
@Composable
fun WheelAssigned(prefs: Preferences) {
    val c = LocalColors.current
    val list = prefs.wheelKeys.entries.sortedBy { it.key }
    if (list.isEmpty()) {
        Label(stringResource(R.string.wheel_none), Typography.small, c.textMuted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((code, stored) in list) {
            val action = WheelAction.fromStored(stored) ?: WheelAction.NORMAL
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label(keyName(code), Typography.subtitle, maxLines = 1)
                    Label(stringResource(action.title), Typography.small, c.accent, maxLines = 1)
                }
                Button(stringResource(R.string.wheel_reset), { prefs.wheelKeys = prefs.wheelKeys - code })
            }
        }
    }
}
