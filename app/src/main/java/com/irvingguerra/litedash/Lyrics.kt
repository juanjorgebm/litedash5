package com.irvingguerra.litedash

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Https
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.MusicRepository
import com.irvingguerra.litedash.feature.music.Song
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.Track
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * La letra de lo que suena, como karaoke. Sale de un archivo .lrc junto a la canción en la USB (mismo nombre) o, si
 * no hay, de LRCLIB (servicio libre de letras con tiempos). La que se baja se guarda en la pantalla para verla sin
 * internet la próxima vez.
 */
object Lyrics {
    /** Una línea; [ms] = cuándo se canta (null si la letra no trae tiempos). */
    class Line(val ms: Long?, val text: String)

    var lines by mutableStateOf<List<Line>>(emptyList())
        private set
    var status by mutableStateOf("")
        private set
    /** Para qué canción es lo que hay ("artista|título"). */
    var forSong by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var loading: String? = null

    private fun keyOf(song: Song) = "${song.artist.trim()}|${song.title.trim()}".lowercase()

    /** Busca la letra de [song] si todavía no está (en otro hilo). */
    fun load(context: Context, song: Song?) {
        if (song == null || song.title.isBlank()) {
            forSong = null; lines = emptyList(); status = ""
            return
        }
        val key = keyOf(song)
        if (key == forSong || key == loading) return
        loading = key
        lines = emptyList()
        status = context.getString(R.string.lyrics_searching)
        val app = context.applicationContext
        val track = Player.state.current as? Track
        Thread {
            val result = runCatching { find(app, song, track) }
            main.post {
                if (loading != key) return@post
                loading = null
                forSong = key
                val found = result.getOrNull().orEmpty()
                lines = found
                status = when {
                    found.isNotEmpty() -> ""
                    result.isFailure -> app.getString(R.string.lyrics_offline)
                    else -> app.getString(R.string.lyrics_none)
                }
            }
        }.start()
    }

    private fun find(context: Context, song: Song, track: Track?): List<Line> {
        // 1) Un .lrc junto a la canción en la USB.
        val sameSong = track != null && track.title.equals(song.title, ignoreCase = true)
        if (sameSong) pathOf(context, track!!.uri)?.let { path ->
            val lrc = File(path.substringBeforeLast('.') + ".lrc")
            if (lrc.isFile) parse(lrc.readText()).takeIf { it.isNotEmpty() }?.let { return it }
        }
        // 2) La que ya se bajó antes.
        val (artist, title) = names(song, if (sameSong) track else null)
        val cache = File(File(context.filesDir, "letras").apply { mkdirs() }, md5("$artist|$title".lowercase()) + ".lrc")
        if (cache.isFile) return parse(cache.readText())
        // 3) LRCLIB.
        val text = lrclib(context, artist, title, track?.album.takeIf { sameSong }, song.durationMs) ?: return emptyList()
        runCatching { cache.writeText(text) }
        return parse(text)
    }

    /** Artista y título limpios; si el archivo no trae datos, se sacan del nombre ("Artista - Canción.mp3"). */
    private fun names(song: Song, track: Track?): Pair<String, String> {
        var artist = song.artist.trim()
        var title = song.title.trim()
        val file = track?.file?.substringBeforeLast('.')
        val unknown = artist.isBlank() || artist.equals("<unknown>", true) || artist.contains("desconocido", true)
        if (unknown && title.contains(" - ")) {
            artist = title.substringBefore(" - ").trim(); title = title.substringAfter(" - ").trim()
        } else if (unknown && file != null && file.contains(" - ")) {
            artist = file.substringBefore(" - ").trim(); title = file.substringAfter(" - ").trim()
        }
        // Sin números de pista ni "(Official Video)" y parecidos.
        title = title.replace(Regex("""^\d{1,3}[\s.\-_]+"""), "").replace(Regex("""\s*[(\[](official|video|audio|lyric|letra|hd|hq|remaster)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "").trim()
        return artist to title
    }

    private fun pathOf(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        "content" -> runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        else -> null
    }

    private fun lrclib(context: Context, artist: String, title: String, album: String?, durationMs: Long): String? {
        fun get(url: String) = get(context, url)
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val seconds = if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        val albumPart = album?.takeIf { it.isNotBlank() }?.let { "&album_name=${enc(it)}" }.orEmpty()
        get("https://lrclib.net/api/get?artist_name=${enc(artist)}&track_name=${enc(title)}$albumPart$seconds")?.let { raw ->
            val o = JSONObject(raw)
            o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
            o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        // Sin coincidencia exacta: la búsqueda, prefiriendo la que trae tiempos y dura parecido.
        val list = get("https://lrclib.net/api/search?track_name=${enc(title)}" + (if (artist.isNotBlank()) "&artist_name=${enc(artist)}" else ""))?.let { JSONArray(it) } ?: return null
        var best: JSONObject? = null
        var bestScore = Double.MAX_VALUE
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("syncedLyrics").isBlank() && o.optString("plainLyrics").isBlank()) continue
            val diff = if (durationMs > 0) Math.abs(o.optDouble("duration", 0.0) - durationMs / 1000.0) else 0.0
            val score = diff + if (o.optString("syncedLyrics").isBlank() || o.optString("syncedLyrics") == "null") 30 else 0
            if (score < bestScore) { bestScore = score; best = o }
        }
        val o = best ?: return null
        return o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Con [Https.open]: en Android 5 a 7.1 suma las raíces nuevas que la pantalla no trae (si no, el https truena). */
    private fun get(context: Context, url: String): String? {
        val connection = Https.open(context, url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "LiteDash car launcher")
            val code = connection.responseCode
            if (code == 404) return null
            check(code in 200..299) { "código $code" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Lee el formato LRC ("[01:23.45] texto"); sin marcas de tiempo, cada línea sin tiempo. */
    fun parse(text: String): List<Line> {
        val out = ArrayList<Line>()
        var timed = false
        for (raw in text.lineSequence()) {
            val stamps = STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) {
                // Etiquetas como [ar:…] o [ti:…] se saltan.
                if (raw.trim().matches(Regex("""\[[a-zA-Z]+:.*]"""))) continue
                out += Line(null, raw.trim())
                continue
            }
            timed = true
            val words = raw.substring(stamps.last().range.last + 1).trim()
            for (s in stamps) {
                val (m, sec, frac) = s.destructured
                val f = when (frac.length) { 0 -> 0; 1 -> frac.toInt() * 100; 2 -> frac.toInt() * 10; else -> frac.take(3).toInt() }
                out += Line(m.toLong() * 60_000 + sec.toLong() * 1000 + f, words)
            }
        }
        val lines = if (timed) out.filter { it.ms != null }.sortedBy { it.ms } else out
        // Sin líneas vacías al principio ni al final.
        return lines.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Dónde va la canción, cuatro veces por segundo (para que la línea cambie a tiempo). */
@Composable
private fun rememberFinePosition(song: Song?) = produceState(song?.currentPosition() ?: 0L, song) {
    if (song == null) return@produceState
    value = song.currentPosition()
    while (song.playing) {
        delay(250)
        value = song.currentPosition()
    }
}

/**
 * La letra en una tarjeta, con la línea que se canta resaltada y centrada. [full] = a pantalla completa (letra más
 * grande y los controles de la música abajo). Tocar una línea salta a esa parte.
 */
@Composable
fun LyricsCard(music: MusicRepository, full: Boolean, onFull: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    // Cuenta como usuaria de la música mientras se ve: en pantalla completa la música en grande ya no está debajo y,
    // sin esto, LiteDash dejaba de escuchar qué suena (sin canción, sin letra y los botones sin efecto).
    DisposableEffect(music) {
        music.use()
        onDispose { music.release() }
    }
    val song = music.song
    LaunchedEffect(song?.title, song?.artist) { Lyrics.load(context, song) }
    val pos by rememberFinePosition(song)
    val lines = if (song != null && Lyrics.forSong == "${song.artist.trim()}|${song.title.trim()}".lowercase()) Lyrics.lines else emptyList()
    val timed = lines.any { it.ms != null }
    // Medio segundo antes: así la línea aparece cuando empieza a cantarse, no tarde.
    val current = if (timed) lines.indexOfLast { (it.ms ?: Long.MAX_VALUE) <= pos + 500 } else -1
    val list = rememberLazyListState()
    Card(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Label(song?.title ?: stringResource(R.string.lyrics_title), if (full) Typography.title else Typography.subtitle, c.text, 1)
                    Label(song?.artist?.takeIf { it.isNotBlank() } ?: stringResource(R.string.lyrics_title), Typography.small, c.textMuted, 1)
                }
                IconButton(if (full) Icons.close else Icons.expand, onFull, size = if (full) 64.dp else 52.dp)
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val middle = with(LocalDensity.current) { (maxHeight / 3).roundToPx() }
                LaunchedEffect(current, lines.size) { if (current >= 0) list.animateScrollToItem(current, -middle) }
                when {
                    // La app de música del carro no dice qué suena: la letra solo se puede con el reproductor de LiteDash.
                    song == null -> Label(stringResource(if (music.carMode) R.string.lyrics_car_app else R.string.lyrics_nothing), Typography.subtitle.copy(textAlign = TextAlign.Center), c.textMuted, 5, Modifier.align(Alignment.Center).padding(horizontal = 24.dp))
                    lines.isEmpty() -> Label(Lyrics.status.ifEmpty { stringResource(R.string.lyrics_none) }, Typography.subtitle, c.textMuted, 3, Modifier.align(Alignment.Center))
                    else -> LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(if (full) 14.dp else 8.dp)) {
                        item { Spacer(Modifier.height(if (timed) 40.dp else 0.dp)) }
                        itemsIndexed(lines) { i, line ->
                            val now = i == current
                            val past = timed && i < current
                            val style = if (full) Typography.title.copy(fontSize = if (now) 38.sp else 30.sp) else Typography.subtitle.copy(fontSize = if (now) 26.sp else 20.sp)
                            Label(
                                line.text.ifBlank { "♪" },
                                style.copy(fontWeight = if (now) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center),
                                when {
                                    now -> c.accent
                                    past -> c.textMuted.copy(alpha = 0.55f)
                                    else -> c.textMuted
                                },
                                3,
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = line.ms != null && song.seekable) { line.ms?.let { music.seekTo(it) } }.padding(vertical = 2.dp),
                            )
                        }
                        item { Spacer(Modifier.height(if (timed) 200.dp else 20.dp)) }
                    }
                }
            }
            if (full) {
                Spacer(Modifier.height(10.dp))
                if (song != null && song.durationMs > 0) {
                    val fraction = (pos.toFloat() / song.durationMs).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(clock(pos), Typography.small, c.textMuted, 1)
                        Box(Modifier.weight(1f).padding(horizontal = 12.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.line)) {
                            Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(c.primary))
                        }
                        Label(clock(song.durationMs), Typography.small, c.textMuted, 1)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(Icons.previous, { music.previous() }, size = 88.dp)
                    Spacer(Modifier.width(28.dp))
                    IconButton(if (song?.playing == true) Icons.pause else Icons.play, { music.toggle() }, size = 104.dp, background = c.primary, color = c.onPrimary)
                    Spacer(Modifier.width(28.dp))
                    IconButton(Icons.next, { music.next() }, size = 88.dp)
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun stringResource(id: Int) = androidx.compose.ui.res.stringResource(id)

/** La letra a pantalla completa con los controles. */
@Composable
fun LyricsScreen(music: MusicRepository, close: () -> Unit) {
    val c = LocalColors.current
    // Fondo opaco: si no, se ve la barra de la izquierda a través.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101318)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}.padding(16.dp)) {
        LyricsCard(music, full = true, onFull = close)
    }
}
