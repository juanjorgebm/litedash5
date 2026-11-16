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
import androidx.compose.ui.graphics.asImageBitmap
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

    /** Ajuste a mano de la sincronía de esta canción (ms; positivo = la letra va más adelante). */
    var offset by mutableStateOf(0L)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var loading: String? = null

    private fun sync(context: Context) = context.getSharedPreferences("letras_sincronia", Context.MODE_PRIVATE)

    /** Corre la letra [by] ms para la canción de ahora y lo recuerda. */
    fun shift(context: Context, by: Long) {
        val key = forSong ?: return
        offset = (offset + by).coerceIn(-30_000L, 30_000L)
        sync(context).edit().putLong(key, offset).apply()
    }

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
        offset = 0L
        status = context.getString(R.string.lyrics_searching)
        val app = context.applicationContext
        val track = Player.state.current as? Track
        Thread {
            val result = runCatching { find(app, song, track) }
            main.post {
                if (loading != key) return@post
                loading = null
                forSong = key
                offset = sync(app).getLong(key, 0L)
                val found = result.getOrNull().orEmpty()
                lines = found
                status = when {
                    found.isNotEmpty() -> ""
                    result.isFailure -> app.getString(R.string.lyrics_offline) + "\n" + (result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName } ?: "")
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
        val (rawArtist, rawTitle) = names(song, if (sameSong) track else null)
        val dir = File(context.filesDir, "letras2").apply { mkdirs() }
        File(dir, md5("$rawArtist|$rawTitle".lowercase()) + ".lrc").takeIf { it.isFile }?.let { return parse(it.readText()) }
        // Con los datos reales de iTunes (el archivo suele traer "Aquí_Abajo(256k)" y sin artista: así LRCLIB no la halla).
        val real = runCatching { SongInfo.lookup(context, rawArtist, rawTitle, song.durationMs) }.getOrNull()
        val artist = real?.optString("artist")?.takeIf { it.isNotBlank() } ?: rawArtist
        val title = real?.optString("title")?.takeIf { it.isNotBlank() } ?: rawTitle
        val album = real?.optString("album")?.takeIf { it.isNotBlank() } ?: track?.album.takeIf { sameSong }
        val cache = File(dir, md5("$rawArtist|$rawTitle".lowercase()) + ".lrc")
        // 3) LRCLIB; si falla o no la tiene, lyrics.ovh (solo el texto, sin tiempos).
        var error: Throwable? = null
        val text = runCatching { lrclib(context, artist, title, album, song.durationMs) }.onFailure { error = it }.getOrNull()
            ?: runCatching { lyricsOvh(context, artist, title) }.onFailure { if (error == null) error = it }.getOrNull()
            ?: error?.let { throw it } ?: return emptyList()
        runCatching { cache.writeText(text) }
        return parse(text)
    }

    /** Artista y título limpios; si el archivo no trae datos, se sacan del nombre ("Artista - Canción.mp3"). */
    fun names(song: Song, track: Track?): Pair<String, String> = names(song.title, song.artist, track)

    fun names(songTitle: String, songArtist: String, track: Track?): Pair<String, String> {
        var artist = songArtist.trim()
        var title = songTitle.trim()
        val file = track?.file?.substringBeforeLast('.')
        val unknown = artist.isBlank() || artist.equals("<unknown>", true) || artist.contains("desconocido", true)
        if (unknown && title.contains(" - ")) {
            artist = title.substringBefore(" - ").trim(); title = title.substringAfter(" - ").trim()
        } else if (unknown && file != null && file.contains(" - ")) {
            artist = file.substringBefore(" - ").trim(); title = file.substringAfter(" - ").trim()
        }
        // Sin guiones bajos, sin "(256k)" ni "[320 kbps]", sin números de pista ni "(Official Video)" y parecidos.
        artist = artist.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        title = title.replace('_', ' ').replace(Regex("""\s*[(\[]\s*\d{2,3}\s*k(bps)?\s*[)\]]""", RegexOption.IGNORE_CASE), "").replace(Regex("\\s+"), " ").trim()
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
        var bestDiff = 0.0
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            if (o.optString("syncedLyrics").isBlank() && o.optString("plainLyrics").isBlank()) continue
            val diff = if (durationMs > 0) Math.abs(o.optDouble("duration", 0.0) - durationMs / 1000.0) else 0.0
            val score = diff + if (o.optString("syncedLyrics").isBlank() || o.optString("syncedLyrics") == "null") 30 else 0
            if (score < bestScore) { bestScore = score; best = o; bestDiff = diff }
        }
        val o = best ?: return null
        // Otra versión de la canción (más larga o más corta) corre todas las líneas: entonces solo el texto, sin tiempos.
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" && (durationMs <= 0 || bestDiff <= 3.0) }
        return synced ?: o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
    }

    /** Respaldo: lyrics.ovh (otro servicio libre, con otro certificado). */
    private fun lyricsOvh(context: Context, artist: String, title: String): String? {
        if (artist.isBlank() || title.isBlank()) return null
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val raw = get(context, "https://api.lyrics.ovh/v1/${enc(artist)}/${enc(title)}") ?: return null
        return JSONObject(raw).optString("lyrics").replace("\r\n", "\n").trim().takeIf { it.isNotBlank() }
    }

    /** Con [Https.open]: en Android 5 a 7.1 suma las raíces nuevas que la pantalla no trae (si no, el https truena). */
    private fun get(context: Context, url: String): String? {
        val connection = Https.open(context, url)
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "LiteDash car launcher")
            val code = connection.responseCode
            // 400 = faltó algo en la búsqueda (artista vacío), 404 = no la tienen: no es falta de internet.
            if (code == 404 || code == 400) return null
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
    // Con el reproductor de LiteDash se le pregunta al reproductor (exacto); con otra app, se calcula con el reloj.
    fun now() = (if (song.title == (Player.state.current as? Track)?.title) Player.position() else null) ?: song.currentPosition()
    value = now()
    while (song.playing) {
        delay(200)
        value = now()
    }
}

/**
 * Lo que se sabe de la canción, como en Spotify: carátula grande, álbum, año y género. Sale de la búsqueda de iTunes
 * (libre, sin clave) con el artista y el título; se guarda en la pantalla para no volver a pedirlo.
 */
object SongInfo {
    class Info(val title: String, val artist: String, val album: String, val year: String, val genre: String, val art: androidx.compose.ui.graphics.ImageBitmap?)

    var info by mutableStateOf<Info?>(null)
        private set
    var forSong by mutableStateOf<String?>(null)
        private set
    private var loading: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun keyOf(song: Song) = "${song.artist.trim()}|${song.title.trim()}".lowercase()

    fun load(context: Context, song: Song?) {
        if (song == null || song.title.isBlank()) return
        val key = keyOf(song)
        if (key == forSong || key == loading) return
        loading = key
        info = null
        val app = context.applicationContext
        val track = (Player.state.current as? Track)?.takeIf { it.title.equals(song.title, ignoreCase = true) }
        Thread {
            val found = runCatching { find(app, song, track) }.getOrNull()
            main.post {
                if (loading != key) return@post
                loading = null
                forSong = key
                info = found
            }
        }.start()
    }

    private fun find(context: Context, song: Song, track: Track?): Info? {
        val (artist, title) = Lyrics.names(song, track)
        val o = lookup(context, artist, title, song.durationMs) ?: return null
        val pic = picture(context, o)
        val art = if (pic?.isFile == true) runCatching { android.graphics.BitmapFactory.decodeFile(pic.path)?.let { it.asImageBitmap() } }.getOrNull() else null
        return Info(o.optString("title").ifBlank { title }, o.optString("artist").ifBlank { artist }, o.optString("album"), o.optString("year"), o.optString("genre"), art)
    }

    private fun idOf(artist: String, title: String) = MessageDigest.getInstance("MD5").digest("$artist|$title".lowercase().toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Los datos reales en iTunes (title, artist, album, year, genre, art, ms), guardados en la pantalla; null si no
     * está. Lee la red: fuera del hilo principal.
     */
    fun lookup(context: Context, artist: String, title: String, durationMs: Long): JSONObject? {
        val dir = File(context.filesDir, "canciones").apply { mkdirs() }
        val json = File(dir, idOf(artist, title) + ".json")
        if (json.isFile) return runCatching { JSONObject(json.readText()) }.getOrNull()
        val term = URLEncoder.encode("$artist $title".trim(), "UTF-8")
        val connection = Https.open(context, "https://itunes.apple.com/search?media=music&entity=song&limit=5&term=$term")
        val results = try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "LiteDash car launcher")
            JSONObject(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).optJSONArray("results") ?: JSONArray()
        } finally {
            connection.disconnect()
        }
        // La que dura parecido, si se sabe la duración.
        var best: JSONObject? = null
        var bestDiff = Long.MAX_VALUE
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val diff = if (durationMs > 0) Math.abs(r.optLong("trackTimeMillis") - durationMs) else i.toLong()
            if (diff < bestDiff) { bestDiff = diff; best = r }
        }
        val r = best ?: return null
        val out = JSONObject()
            .put("title", r.optString("trackName"))
            .put("artist", r.optString("artistName"))
            .put("album", r.optString("collectionName"))
            .put("year", r.optString("releaseDate").take(4))
            .put("genre", r.optString("primaryGenreName"))
            .put("art", r.optString("artworkUrl100").replace("100x100", "600x600"))
            .put("ms", r.optLong("trackTimeMillis"))
            .put("track", r.optInt("trackNumber"))
        runCatching { json.writeText(out.toString()) }
        return out
    }

    /** La carátula de [o] bajada a la pantalla (la primera vez la baja); null si no hay. */
    fun picture(context: Context, o: JSONObject): File? {
        val url = o.optString("art").takeIf { it.startsWith("http") } ?: return null
        val pic = File(File(context.filesDir, "canciones").apply { mkdirs() }, idOf(url, "art") + ".jpg")
        if (!pic.isFile) runCatching {
            val connection = Https.open(context, url)
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                val bytes = connection.inputStream.use { it.readBytes() }
                File(pic.path + ".part").apply { writeBytes(bytes) }.renameTo(pic)
            } finally {
                connection.disconnect()
            }
        }
        return pic.takeIf { it.isFile }
    }
}

/** La carátula: la de iTunes si la hay; si no, la que trae la canción; si no, una nota. */
@Composable
private fun Cover(song: Song?, info: SongInfo.Info?, size: androidx.compose.ui.unit.Dp) {
    val c = LocalColors.current
    val art = info?.art ?: song?.artwork?.let { it.asImageBitmap() }
    Box(Modifier.width(size).height(size).clip(RoundedCornerShape(if (size > 100.dp) 16.dp else 10.dp)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        if (art != null) androidx.compose.foundation.Image(art, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else com.irvingguerra.litedash.core.ui.Icon(Icons.music, c.textMuted, size * 0.4f)
    }
}

/** "Álbum · 1987 · Pop". */
private fun details(info: SongInfo.Info?) = listOfNotNull(info?.album?.takeIf { it.isNotBlank() }, info?.year?.takeIf { it.isNotBlank() }, info?.genre?.takeIf { it.isNotBlank() }).joinToString(" · ")

/**
 * La letra en una tarjeta, con la línea que se canta resaltada y centrada, y arriba la carátula, el álbum, el año y
 * el género. [full] = a pantalla completa, como en Spotify: carátula grande con los datos y los controles a la
 * izquierda y la letra grande a la derecha. Tocar una línea salta a esa parte.
 */
@Composable
fun LyricsCard(music: MusicRepository, full: Boolean, onFull: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    // Escuchar lo que suena mientras esta vista esté (a pantalla completa, la de música de atrás ya se cerró).
    DisposableEffect(music) {
        music.use()
        onDispose { music.release() }
    }
    val song = music.song
    LaunchedEffect(song?.title, song?.artist) {
        Lyrics.load(context, song)
        SongInfo.load(context, song)
    }
    val pos by rememberFinePosition(song)
    var searching by remember { mutableStateOf(false) }
    val info = if (song != null && SongInfo.forSong == SongInfo.keyOf(song)) SongInfo.info else null
    val title = info?.title ?: song?.title ?: stringResource(R.string.lyrics_title)
    val artist = info?.artist ?: song?.artist?.takeIf { it.isNotBlank() } ?: ""
    Card(modifier.fillMaxSize()) {
        if (full) Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            BoxWithConstraints(Modifier.weight(0.42f).fillMaxSize()) {
                val cover = minOf(maxWidth, maxHeight - 250.dp).coerceAtLeast(120.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(song, info, cover)
                    Spacer(Modifier.height(14.dp))
                    Label(title, Typography.title, c.text, 2, Modifier.fillMaxWidth())
                    if (artist.isNotBlank()) Label(artist, Typography.subtitle, c.accent, 1, Modifier.fillMaxWidth())
                    details(info).takeIf { it.isNotEmpty() }?.let { Label(it, Typography.body, c.textMuted, 2, Modifier.fillMaxWidth()) }
                    Spacer(Modifier.weight(1f))
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
                        IconButton(Icons.previous, { music.previous() }, size = 80.dp)
                        Spacer(Modifier.width(24.dp))
                        IconButton(if (song?.playing == true) Icons.pause else Icons.play, { music.toggle() }, size = 96.dp, background = c.primary, color = c.onPrimary)
                        Spacer(Modifier.width(24.dp))
                        IconButton(Icons.next, { music.next() }, size = 80.dp)
                    }
                }
            }
            Box(Modifier.weight(0.58f).fillMaxSize()) {
                if (searching) UsbSearch(close = { searching = false })
                else {
                    LyricsLines(music, song, pos, full = true, Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.TopEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(Icons.close, onFull, size = 64.dp)
                        // Buscar en la USB (por canción, cantante, estilo o carpeta).
                        IconButton(Icons.search, { searching = true }, size = 64.dp)
                    }
                    // Ajuste fino si la letra va adelantada o atrasada (se recuerda por canción).
                    if (Lyrics.lines.any { it.ms != null }) Row(
                        Modifier.align(Alignment.BottomEnd).clip(RoundedCornerShape(14.dp)).background(c.surfaceHigh).padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(Icons.minus, { Lyrics.shift(context, -500) }, size = 52.dp)
                        Label(
                            if (Lyrics.offset == 0L) stringResource(R.string.lyrics_sync) else "%+.1f s".format(Lyrics.offset / 1000f),
                            Typography.small, if (Lyrics.offset == 0L) c.textMuted else c.accent, 1, Modifier.padding(horizontal = 6.dp),
                        )
                        IconButton(Icons.plus, { Lyrics.shift(context, 500) }, size = 52.dp)
                    }
                }
            }
        } else Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (song != null) Cover(song, info, 64.dp)
                Column(Modifier.weight(1f)) {
                    Label(title, Typography.subtitle, c.text, 1)
                    Label(listOf(artist, details(info)).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { stringResource(R.string.lyrics_title) }, Typography.small, c.textMuted, 1)
                }
                IconButton(Icons.expand, onFull, size = 52.dp)
            }
            Spacer(Modifier.height(8.dp))
            LyricsLines(music, song, pos, full = false, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Las líneas de la letra, con la que se canta resaltada y centrada. */
@Composable
private fun LyricsLines(music: MusicRepository, song: Song?, pos: Long, full: Boolean, modifier: Modifier) {
    val c = LocalColors.current
    val lines = if (song != null && Lyrics.forSong == "${song.artist.trim()}|${song.title.trim()}".lowercase()) Lyrics.lines else emptyList()
    val timed = lines.any { it.ms != null }
    // Medio segundo antes: así la línea aparece cuando empieza a cantarse, no tarde.
    val current = if (timed) lines.indexOfLast { (it.ms ?: Long.MAX_VALUE) <= pos + Lyrics.offset + 300 } else -1
    val list = rememberLazyListState()
    BoxWithConstraints(modifier) {
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
                    val style = if (full) Typography.title.copy(fontSize = if (now) 36.sp else 28.sp) else Typography.subtitle.copy(fontSize = if (now) 26.sp else 20.sp)
                    Label(
                        line.text.ifBlank { "♪" },
                        style.copy(fontWeight = if (now) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center),
                        when {
                            now -> c.accent
                            past -> c.textMuted.copy(alpha = 0.55f)
                            else -> c.textMuted
                        },
                        3,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = line.ms != null && song.seekable) { line.ms?.let { music.seekTo((it - Lyrics.offset).coerceAtLeast(0L)) } }.padding(vertical = 2.dp),
                    )
                }
                item { Spacer(Modifier.height(if (timed) 200.dp else 20.dp)) }
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
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101318)).clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
        // Abajo más espacio: la barra del carro tapa la parte de abajo de la pantalla.
        .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 76.dp)) {
        LyricsCard(music, full = true, onFull = close)
    }
}
