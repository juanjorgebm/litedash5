package com.irvingguerra.litedash

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.SourceKind
import com.irvingguerra.litedash.feature.player.Library
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.PlayerSource
import com.irvingguerra.litedash.feature.player.PlayerStore

/** Lo que se espera a que MediaStore indexe una memoria recién conectada antes de rendirse. */
private const val SCAN_WAIT_MS = 4_000L

/**
 * Une el reproductor propio ([Player]) con la pieza de música: USB siempre (es el reproductor
 * por defecto), la radio por internet si está prendida, y qué hacer cuando el usuario toca
 * "USB" o "Radio" (seguir con lo último que sonó; si no hay nada, la vista abre la biblioteca).
 */
object OwnPlayer {
    private val main = Handler(Looper.getMainLooper())

    /** Sube con cada toque a USB (y al cancelarlo): una búsqueda vieja ya no arranca nada. */
    private var generation = 0

    fun wire(context: Context, music: MusicRepository, prefs: Preferences) {
        Player.init(context)
        music.ownRadio = prefs.internetRadio
        music.ownSourceNow = { Player.state.source?.toKind() }
        music.ownPlay = { kind, done -> play(context, kind, done) }
        music.ownRadioLast = { PlayerStore(context).lastStation()?.title }
        music.ownPause = {
            generation++
            if (Player.state.source == PlayerSource.USB && (Player.state.playing || Player.state.buffering)) Player.pause()
        }
        music.ownStop = { Player.stop() }
    }

    /** Al prender o apagar la radio por internet: la pieza se entera, y si sonaba se para. */
    fun refresh(music: MusicRepository, prefs: Preferences) {
        music.ownRadio = prefs.internetRadio
        if (!music.ownRadio && Player.state.source == PlayerSource.RADIO) Player.stop()
    }

    /** [done] en el hilo principal: true = ya suena (o va a sonar); false = no hay nada que tocar todavía. */
    private fun play(context: Context, kind: SourceKind, done: (Boolean) -> Unit) {
        val source = kind.toPlayer() ?: return done(false)
        val gen = ++generation
        if (Player.state.source == source && Player.state.current != null) {
            Player.resume()
            return done(true)
        }
        val store = PlayerStore(context)
        when (source) {
            PlayerSource.RADIO -> {
                val station = store.lastStation() ?: return done(false)
                Player.play(PlayerSource.RADIO, listOf(station), 0)
                done(true)
            }
            PlayerSource.USB -> {
                // Sin permiso: la biblioteca lo pide (y explica para qué).
                if (!Library.hasPermission(context)) return done(false)
                // Leer la memoria tarda: en un hilo (la primera vez; después queda en caché), y al
                // tener la lista se sigue con lo último que sonó. Si no hay nada, la biblioteca.
                val asked = Player.requests
                Thread({
                    var tracks = Library.find(context).second
                    // Recién conectada, Android tarda unos segundos en indexarla.
                    if (tracks.isEmpty() && gen == generation) {
                        Thread.sleep(SCAN_WAIT_MS)
                        tracks = Library.find(context).second
                    }
                    val last = store.last(PlayerSource.USB)
                    val index = tracks.indexOfFirst { it.key == last }.coerceAtLeast(0)
                    main.post {
                        // Mientras, ya pidieron otra cosa (otro toque, otra app, una estación): no se pisa.
                        if (gen != generation || Player.requests != asked) return@post done(true)
                        if (tracks.isEmpty()) return@post done(false)
                        Player.play(PlayerSource.USB, tracks, index)
                        done(true)
                    }
                }, "usb-start").start()
            }
        }
    }
}

private fun SourceKind.toPlayer(): PlayerSource? = when (this) {
    SourceKind.USB -> PlayerSource.USB
    SourceKind.RADIO -> PlayerSource.RADIO
    SourceKind.BLUETOOTH -> null
}

private fun PlayerSource.toKind(): SourceKind = if (this == PlayerSource.USB) SourceKind.USB else SourceKind.RADIO
