package com.irvingguerra.litedash.feature.player

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.feature.music.Drives
import java.util.concurrent.Executors

/** Una canción de la memoria USB o una estación de radio: lo que el reproductor sabe tocar. */
sealed interface Playable {
    val title: String
    val subtitle: String
    val uri: Uri
    /** Clave estable para recordar "lo último que sonó". */
    val key: String
}

/** De dónde viene lo que suena: la memoria USB o la radio por internet. */
enum class PlayerSource { USB, RADIO }

/** Lo que el reproductor está haciendo, como estado de Compose (lo lee la biblioteca). */
class PlayerState(
    val source: PlayerSource?,
    val queue: List<Playable>,
    val index: Int,
    val playing: Boolean,
    /** Conectando o cargando (la radio tarda unos segundos en arrancar). */
    val buffering: Boolean,
    val error: String?,
) {
    val current: Playable? get() = queue.getOrNull(index)

    companion object {
        val IDLE = PlayerState(null, emptyList(), -1, playing = false, buffering = false, error = null)
    }
}

/** Lado máximo de la carátula que se manda a la sesión (la pieza la muestra chica). */
private const val ART_SIDE = 512

/** Si en este tiempo no empieza a sonar, se da por perdido (la radio puede no contestar). */
private const val LOAD_TIMEOUT_MS = 25_000L

/**
 * El reproductor propio de LiteDash: toca las canciones de la memoria USB y las estaciones
 * de radio por internet con [MediaPlayer], publica una sesión de medios (así la pieza Música
 * y la vista en grande lo ven como a cualquier app, y el volante lo controla) y pide el foco
 * de audio como manda Android. Mientras suena, [PlaybackService] lo mantiene en primer plano
 * con su aviso. Uno solo por proceso; no hace nada hasta que algo pide [play].
 * Guarda el contexto de la aplicación (vive lo mismo que el proceso): no es una fuga.
 */
@SuppressLint("StaticFieldLeak")
object Player {
    var state by mutableStateOf(PlayerState.IDLE)
        private set

    /** Sube con cada [play] o [stop]: quien prepara algo en segundo plano sabe si ya pidieron otra cosa. */
    var requests = 0
        private set

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    // Leer carátulas y abrir archivos fuera del hilo principal, de uno en uno.
    private val io = Executors.newSingleThreadExecutor { Thread(it, "player-io") }
    private var player: MediaPlayer? = null
    /** Radio AAC en vivo (lo que MediaPlayer no sabe arrancar); null si suena por [player]. */
    private var aac: AacStream? = null
    private var session: MediaSession? = null
    private var focus: AudioFocusRequest? = null
    private var ducked = false
    /** Se pausó por una llamada o un aviso: al terminar, sigue solo. */
    private var resumeAfterFocus = false
    /** Qué versión de la cola va cargándose (una carga vieja no debe pisar a la nueva). */
    private var load = 0
    private var usbWatcher: BroadcastReceiver? = null
    /** Canciones sin carátula incrustada: no se vuelve a abrir el archivo cada vez que suenan. */
    private val noArt = HashSet<String>()

    /** Llamar una vez (lo hace el app al arrancar): solo guarda el contexto. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Suena [queue] desde [index]; si ya es lo mismo, solo sigue. */
    fun play(source: PlayerSource, queue: List<Playable>, index: Int) {
        val item = queue.getOrNull(index) ?: return
        requests++
        if (state.current?.key == item.key && state.queue == queue) return resume()
        state = PlayerState(source, queue, index, playing = false, buffering = true, error = null)
        PlayerStore(app).rememberLast(source, item.key)
        ensureSession()
        start(item)
    }

    fun resume() {
        val item = state.current ?: return
        // La radio en pausa se desconecta: play la vuelve a abrir (igual si se canceló la carga o falló).
        val p = player ?: run {
            update(playing = false, buffering = true)
            return start(item)
        }
        // Todavía cargando: arrancará solo al estar listo.
        if (state.buffering) return
        if (!requestFocus()) return
        p.start()
        update(playing = true)
    }

    fun pause() {
        resumeAfterFocus = false
        if (state.buffering || aac != null) {
            // Pausar mientras carga (o una radio en vivo) = cortar; play la vuelve a abrir.
            load++
            releaseAudio()
            update(playing = false, buffering = false)
            return
        }
        player?.takeIf { it.isPlaying }?.pause()
        update(playing = false)
    }

    private fun releaseAudio() {
        player?.release()
        player = null
        aac?.stop()
        aac = null
    }

    fun toggle() = if (state.playing) pause() else resume()

    fun next() = skip(1)
    fun previous() = skip(-1)

    private fun skip(by: Int) {
        val s = state
        if (s.queue.isEmpty()) return
        val i = (s.index + by).mod(s.queue.size)
        state = PlayerState(s.source, s.queue, i, playing = false, buffering = true, error = null)
        PlayerStore(app).rememberLast(s.source!!, s.queue[i].key)
        start(s.queue[i])
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        // Mientras carga, MediaPlayer aún no deja adelantar.
        if (state.current !is Track || state.buffering) return
        p.seekTo(ms.toInt())
        // La sesión se entera ya (la barra se dibuja con la posición y la hora que publica).
        publishState()
    }

    /** Para del todo: suelta el audio, la sesión y el aviso. */
    fun stop() {
        requests++
        load++
        releaseAudio()
        abandonFocus()
        session?.isActive = false
        session?.release()
        session = null
        unwatchUsb()
        state = PlayerState.IDLE
        PlaybackService.stop(app)
    }

    private fun start(item: Playable) {
        val myLoad = ++load
        releaseAudio()
        if (item is Track) watchUsb() else unwatchUsb()
        publishMetadata(item, null)
        PlaybackService.update(app)
        // Si en un rato no suena, se avisa (la radio puede no contestar nunca).
        main.postDelayed({ if (myLoad == load && state.buffering) fail(app.getString(R.string.player_timeout)) }, LOAD_TIMEOUT_MS)
        io.execute { val art = artworkOf(item); main.post { if (myLoad == load) publishMetadata(item, art) } }
        if (item is Station && item.isAac) {
            startAac(item, myLoad)
            return
        }
        startMediaPlayer(item, myLoad)
    }

    /** Radio AAC en vivo con el decodificador propio; si el flujo no es AAC, MediaPlayer. */
    private fun startAac(item: Station, myLoad: Int) {
        if (!requestFocus()) return update(playing = false, buffering = false)
        aac = AacStream(app, item.uri.toString(), object : AacStream.Listener {
            override fun onStarted() { main.post { if (myLoad == load) update(playing = true, buffering = false) } }
            override fun onNotAac() { main.post { if (myLoad == load) { aac = null; startMediaPlayer(item, myLoad) } } }
            override fun onError(message: String) { main.post { if (myLoad == load) { Log.w("LiteDash", "Radio AAC: $message"); fail(app.getString(R.string.player_cannot_play)) } } }
        }).also { it.start() }
    }

    private fun startMediaPlayer(item: Playable, myLoad: Int) {
        val p = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            setOnPreparedListener {
                if (myLoad != load) return@setOnPreparedListener
                if (requestFocus()) {
                    it.start()
                    update(playing = true, buffering = false)
                } else {
                    update(playing = false, buffering = false)
                }
            }
            setOnCompletionListener { if (myLoad == load && state.current is Track) next() }
            setOnSeekCompleteListener { if (myLoad == load) publishState() }
            setOnErrorListener { _, what, extra ->
                if (myLoad == load) { Log.w("LiteDash", "MediaPlayer: $what/$extra ${item.uri}"); fail(app.getString(R.string.player_cannot_play)) }
                true
            }
            setOnInfoListener { _, what, _ ->
                if (myLoad == load) when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> update(buffering = true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> update(buffering = false)
                }
                false
            }
        }
        player = p
        io.execute {
            try {
                p.setDataSource(app, item.uri)
                p.prepareAsync()
            } catch (x: Exception) {
                main.post { if (myLoad == load) { Log.w("LiteDash", "MediaPlayer: ${x.message}"); fail(app.getString(R.string.player_cannot_play)) } }
            }
        }
    }

    /** No se pudo: se suelta el audio (play lo vuelve a intentar) y se avisa. */
    private fun fail(message: String) {
        load++
        releaseAudio()
        state = PlayerState(state.source, state.queue, state.index, playing = false, buffering = false, error = message)
        publishState()
        PlaybackService.update(app)
    }

    private fun update(playing: Boolean = state.playing, buffering: Boolean = state.buffering) {
        state = PlayerState(state.source, state.queue, state.index, playing, buffering, null)
        publishState()
        PlaybackService.update(app)
    }

    // --- Sesión de medios: lo que ven la pieza, el volante y el aviso. ---

    private fun ensureSession() {
        if (session != null) return
        session = MediaSession(app, "LiteDash").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = resume()
                override fun onPause() = pause()
                override fun onSkipToNext() = next()
                override fun onSkipToPrevious() = previous()
                override fun onSeekTo(pos: Long) = seekTo(pos)
                override fun onStop() = stop()
            })
            val open = app.packageManager.getLaunchIntentForPackage(app.packageName)
            if (open != null) setSessionActivity(PendingIntent.getActivity(app, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            isActive = true
        }
    }

    internal val sessionToken get() = session?.sessionToken

    private fun publishMetadata(item: Playable, art: Bitmap?) {
        val duration = (item as? Track)?.durationMs ?: -1L
        session?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.subtitle)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, (item as? Track)?.album ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                .build(),
        )
        publishState()
    }

    private fun publishState() {
        val s = state
        val p = player
        val track = s.current is Track
        val status = when {
            s.error != null -> PlaybackState.STATE_ERROR
            s.buffering -> PlaybackState.STATE_BUFFERING
            s.playing -> PlaybackState.STATE_PLAYING
            s.current != null -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_NONE
        }
        val position = if (track && p != null && !s.buffering) runCatching { p.currentPosition.toLong() }.getOrDefault(0L) else 0L
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (track) actions = actions or PlaybackState.ACTION_SEEK_TO
        session?.setPlaybackState(
            PlaybackState.Builder().setActions(actions).setState(status, position, if (s.playing) 1f else 0f)
                .apply { if (s.error != null) setErrorMessage(s.error) }.build(),
        )
    }

    /** Carátula del archivo (etiqueta incrustada) o el logo de la estación, reducida a [ART_SIDE]. */
    private fun artworkOf(item: Playable): Bitmap? = runCatching {
        if (item.key in noArt) return null
        val bytes = when (item) {
            is Track -> MediaMetadataRetriever().let { r -> try { r.setDataSource(app, item.uri); r.embeddedPicture } finally { r.release() } }
            is Station -> item.logoUrl?.let { Radio.fetchBytes(app, it, maxBytes = 512 * 1024) }
        } ?: return null.also { noArt += item.key }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > ART_SIDE * 2 || bounds.outHeight / sample > ART_SIDE * 2) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    // --- Foco de audio: pausar con llamadas o avisos y bajar el volumen con la navegación. ---

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                val wasPlaying = state.playing
                pause()
                resumeAfterFocus = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { player?.setVolume(0.2f, 0.2f); aac?.setVolume(0.2f); ducked = true }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (ducked) { player?.setVolume(1f, 1f); aac?.setVolume(1f); ducked = false }
                if (resumeAfterFocus) { resumeAfterFocus = false; resume() }
            }
        }
    }

    private fun requestFocus(): Boolean {
        val audio = ContextCompat.getSystemService(app, AudioManager::class.java) ?: return true
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setOnAudioFocusChangeListener(focusListener, main)
                .build().also { focus = it }
            audio.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val audio = ContextCompat.getSystemService(app, AudioManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focus?.let { audio.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") audio.abandonAudioFocus(focusListener)
    }

    // --- Si sacan la memoria USB mientras suena, se para (sin ella no hay archivos). ---

    private fun watchUsb() {
        if (usbWatcher != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Drives.changed()
                if (i.action != Intent.ACTION_MEDIA_MOUNTED && state.current is Track) stop()
            }
        }
        ContextCompat.registerReceiver(app, receiver, Drives.mediaFilter(), ContextCompat.RECEIVER_EXPORTED)
        usbWatcher = receiver
    }

    private fun unwatchUsb() {
        usbWatcher?.let { runCatching { app.unregisterReceiver(it) } }
        usbWatcher = null
    }
}
