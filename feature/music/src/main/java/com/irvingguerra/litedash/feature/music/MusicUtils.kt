package com.irvingguerra.litedash.feature.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat

/**
 * Una tecla multimedia (abajo y arriba) como la del volante: a [receiver] (el receptor de una
 * app) o, sin él, al sistema de audio, que la lleva a la app de música que toca.
 */
fun sendMediaKey(context: Context, code: Int, receiver: ComponentName? = null) {
    val audio = ContextCompat.getSystemService(context, AudioManager::class.java)!!
    for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
        val event = KeyEvent(action, code)
        runCatching {
            if (receiver != null) context.sendBroadcast(Intent(Intent.ACTION_MEDIA_BUTTON).setComponent(receiver).putExtra(Intent.EXTRA_KEY_EVENT, event))
            else audio.dispatchMediaKeyEvent(event)
        }
    }
}

/** Dónde va la canción, calculado con la hora: se actualiza una vez por segundo y solo mientras suena. */
@Composable
fun rememberPosition(song: Song): State<Long> = produceState(song.currentPosition(), song) {
    value = song.currentPosition()
    while (song.playing) {
        delay(1000)
        value = song.currentPosition()
    }
}
