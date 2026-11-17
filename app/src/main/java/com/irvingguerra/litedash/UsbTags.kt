package com.irvingguerra.litedash

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.irvingguerra.litedash.feature.player.Library
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.Track
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.concurrent.Executors

/**
 * Los datos reales de las canciones de la USB. Cuando suena una canción con datos pobres ("Aquí_Abajo(256k)", sin
 * artista), se busca en iTunes; si de verdad es esa (el nombre coincide y dura lo mismo), se muestra con su título,
 * artista, álbum y carátula, se recuerda en la pantalla y se escriben las etiquetas ID3 en el MP3 de la USB (cuando
 * ya no está sonando, para no tocar el archivo abierto).
 */
object UsbTags {
    class Fix(val title: String, val artist: String, val album: String, val year: String, val genre: String, val written: Boolean, val art: String = "")

    /** Cómo va la revisión de toda la USB (botón "Actualizar datos" del buscador). */
    class Progress(val done: Int, val total: Int, val found: Int, val written: Int, val failed: Int, val running: Boolean)

    var progress by mutableStateOf<Progress?>(null)
        private set
    @Volatile private var cancel = false

    private val io = Executors.newSingleThreadExecutor { Thread(it, "usb-tags").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var fixes: MutableMap<String, Fix>? = null
    private val tried = HashSet<String>()

    /** Lo corregido de cada canción ("usb:carpeta/archivo"), para la biblioteca y el buscador. */
    fun all(context: Context): Map<String, Fix> = load(context).toMap()

    private fun store(context: Context) = File(context.filesDir, "usb_etiquetas.json")

    @Synchronized
    private fun load(context: Context): MutableMap<String, Fix> = fixes ?: HashMap<String, Fix>().also { map ->
        runCatching {
            val o = JSONObject(store(context).readText())
            for (k in o.keys()) o.optJSONObject(k)?.let { f ->
                map[k] = Fix(f.optString("title"), f.optString("artist"), f.optString("album"), f.optString("year"), f.optString("genre"), f.optBoolean("written"), f.optString("art"))
            }
        }
        fixes = map
    }

    @Synchronized
    private fun save(context: Context) {
        val o = JSONObject()
        for ((k, f) in load(context)) o.put(k, JSONObject().put("title", f.title).put("artist", f.artist).put("album", f.album).put("year", f.year).put("genre", f.genre).put("written", f.written).put("art", f.art))
        runCatching { store(context).writeText(o.toString()) }
    }

    /** Al arrancar: la biblioteca muestra lo ya corregido aunque la USB no se haya podido escribir. */
    fun wire(context: Context) {
        val app = context.applicationContext
        Library.overrides = { t -> load(app)[t.key]?.let { t.copy(title = it.title, artist = it.artist, album = it.album) } ?: t }
    }

    /** Cada vez que cambia lo que suena: busca sus datos y escribe lo pendiente de las que ya no suenan. */
    fun check(context: Context, track: Track?) {
        val app = context.applicationContext
        io.execute {
            flush(app, track?.key)
            if (track == null) return@execute
            val fixed = load(app)[track.key]
            // Ya corregida: solo vuelve a poner la carátula (el archivo aún no la trae si no se pudo escribir).
            if (fixed == null && !tried.add(track.key)) return@execute
            runCatching { lookup(app, track, fixed) }.onFailure { Log.w("LiteDash", "Etiquetas: ${it.message}") }
        }
    }

    private fun lookup(context: Context, track: Track, fixed: Fix?) {
        val (artist, title) = Lyrics.names(track.title, track.artist, track)
        val o = SongInfo.lookup(context, artist, title, track.durationMs) ?: return
        if (fixed == null && !matches(track, o)) return
        val fix = fixed ?: Fix(
            o.optString("title").ifBlank { track.title }, o.optString("artist").ifBlank { track.artist }, o.optString("album").ifBlank { track.album },
            o.optString("year"), o.optString("genre"), written = false, art = o.optString("art"),
        ).also {
            synchronized(this) { load(context)[track.key] = it }
            save(context)
        }
        val pic = SongInfo.picture(context, o)
        val art = pic?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it.path) }.getOrNull() }
        main.post {
            Library.retag(track.key, fix.title, fix.artist, fix.album)
            Player.retag(track.key, fix.title, fix.artist, fix.album, art)
        }
    }

    /** ¿Es de verdad esa canción? Las palabras del título real están en el nombre del archivo y dura lo mismo. */
    private fun matches(track: Track, o: JSONObject): Boolean {
        val have = words(track.file + " " + track.title + " " + track.artist)
        val real = words(o.optString("title").replace(Regex("""\s*[(\[].*?[)\]]"""), ""))
        if (real.isEmpty() || !have.containsAll(real)) return false
        val ms = o.optLong("ms")
        return track.durationMs <= 0 || ms <= 0 || Math.abs(ms - track.durationMs) <= 6_000
    }

    private fun words(s: String): Set<String> =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toSet()

    /** Escribe las etiquetas pendientes de las canciones que no están sonando. */
    private fun flush(context: Context, playing: String?) {
        val tracks = runCatching { Library.find(context).second }.getOrNull() ?: return
        val pending = load(context).filter { !it.value.written && it.key != playing }
        for ((key, fix) in pending) {
            val track = tracks.firstOrNull { it.key == key } ?: continue
            writeOne(context, track, fix)
        }
    }

    /** Escribe las etiquetas de [track] en la USB; true si quedaron escritas. */
    private fun writeOne(context: Context, track: Track, fix: Fix): Boolean {
        val path = pathOf(context, track.uri) ?: return false
        if (!path.endsWith(".mp3", ignoreCase = true)) { mark(context, track.key, fix); return false }
        val file = File(path)
        val o = if (fix.art.isNotBlank()) JSONObject().put("art", fix.art) else runCatching { SongInfo.lookup(context, fix.artist, fix.title, track.durationMs) }.getOrNull()
        val jpeg = o?.let { runCatching { SongInfo.picture(context, it) }.getOrNull() }?.takeIf { it.isFile && it.length() < 1_500_000 }?.readBytes()
        val ok = runCatching { Id3.write(file, fix, jpeg) }.onFailure { Log.w("LiteDash", "No se pudo escribir ${file.name}: ${it.message}") }.isSuccess
        // Si la USB no deja escribir no se reintenta cada vez: lo corregido ya se ve igual en LiteDash.
        mark(context, track.key, fix)
        if (ok) runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) }
        return ok
    }

    /**
     * Revisa toda la USB: busca los datos reales de cada canción que no los tenga y escribe sus etiquetas (menos la
     * que está sonando, que se escribe al cambiar). Va despacio con iTunes (deja de contestar si se le pide mucho).
     */
    fun scanAll(context: Context) {
        if (progress?.running == true) return
        val app = context.applicationContext
        cancel = false
        progress = Progress(0, 0, 0, 0, 0, running = true)
        io.execute {
            val tracks = runCatching { Library.find(app).second }.getOrNull().orEmpty()
            var found = 0; var written = 0; var failed = 0
            fun report(i: Int, running: Boolean) { val p = Progress(i, tracks.size, found, written, failed, running); main.post { progress = p } }
            report(0, true)
            for ((i, track) in tracks.withIndex()) {
                if (cancel) break
                var fix = load(app)[track.key]
                if (fix == null) {
                    val (artist, title) = Lyrics.names(track.title, track.artist, track)
                    val cached = SongInfo.isCached(app, artist, title)
                    val o = runCatching { SongInfo.lookup(app, artist, title, track.durationMs) }.getOrNull()
                    if (o != null && matches(track, o)) {
                        fix = Fix(
                            o.optString("title").ifBlank { track.title }, o.optString("artist").ifBlank { track.artist }, o.optString("album").ifBlank { track.album },
                            o.optString("year"), o.optString("genre"), written = false, art = o.optString("art"),
                        )
                        synchronized(this) { load(app)[track.key] = fix }
                        save(app)
                        runCatching { SongInfo.picture(app, o) }
                        val f = fix
                        main.post { Library.retag(track.key, f.title, f.artist, f.album); Player.retag(track.key, f.title, f.artist, f.album, null) }
                    }
                    // iTunes acepta unas 20 búsquedas por minuto.
                    if (!cached) Thread.sleep(3_000)
                }
                if (fix != null) {
                    found++
                    if (!fix.written && track.key != Player.state.current?.key) { if (writeOne(app, track, fix)) written++ else failed++ }
                }
                report(i + 1, true)
            }
            report(tracks.size, false)
        }
    }

    fun stopScan() { cancel = true }

    private fun mark(context: Context, key: String, fix: Fix) {
        synchronized(this) { load(context)[key] = Fix(fix.title, fix.artist, fix.album, fix.year, fix.genre, written = true) }
        save(context)
    }

    private fun pathOf(context: Context, uri: Uri): String? = when (uri.scheme) {
        "file" -> uri.path
        "content" -> runCatching {
            @Suppress("DEPRECATION")
            context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        else -> null
    }
}

/** Se pone una vez en el inicio: avisa a [UsbTags] cada vez que cambia la canción de la USB. */
@Composable
fun UsbTagsWatcher() {
    val context = LocalContext.current
    val track = Player.state.current as? Track
    LaunchedEffect(track?.key) { UsbTags.check(context, track) }
}

/**
 * Etiquetas ID3v2.3 de un MP3: título, artista, álbum, año, género y carátula. Las demás etiquetas que ya traía (pista,
 * letra, comentarios) se conservan. Si cabe en el espacio de la etiqueta vieja se escribe ahí mismo; si no, se arma
 * el archivo nuevo al lado y se cambia por el viejo solo cuando quedó completo.
 */
internal object Id3 {
    private val OURS = setOf("TIT2", "TPE1", "TALB", "TYER", "TDRC", "TCON", "APIC")

    fun write(file: File, fix: UsbTags.Fix, jpeg: ByteArray?) {
        val frames = ByteArrayOutputStream()
        fun text(id: String, value: String) {
            if (value.isBlank()) return
            val body = ByteArrayOutputStream().apply {
                write(1); write(0xFF); write(0xFE)
                write(value.toByteArray(Charsets.UTF_16LE)); write(0); write(0)
            }.toByteArray()
            frame(frames, id, body)
        }
        text("TIT2", fix.title)
        text("TPE1", fix.artist)
        text("TALB", fix.album)
        text("TYER", fix.year)
        text("TCON", fix.genre)
        if (jpeg != null) frame(frames, "APIC", ByteArrayOutputStream().apply {
            write(0); write("image/jpeg".toByteArray()); write(0); write(3); write(0); write(jpeg)
        }.toByteArray())

        RandomAccessFile(file, "rw").use { raf ->
            var old = 0L
            if (raf.length() >= 10) {
                val head = ByteArray(10)
                raf.readFully(head)
                if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() && head[2] == '3'.code.toByte()) {
                    val version = head[3].toInt()
                    val flags = head[5].toInt() and 0xFF
                    val size = syncsafe(head, 6)
                    old = 10L + size + if (flags and 0x10 != 0) 10 else 0
                    // Se copian las otras etiquetas solo si se pueden leer tal cual (sin desincronizar ni encabezado extendido).
                    if ((version == 3 || version == 4) && flags and 0xC0 == 0 && size in 1..8_000_000) {
                        val body = ByteArray(size)
                        raf.readFully(body)
                        keep(body, version, frames)
                    }
                }
            }
            val tag = frames.toByteArray()
            if (10 + tag.size <= old) {
                // Cabe: se escribe encima, con relleno, sin mover el audio.
                raf.seek(0)
                raf.write(header(old - 10))
                raf.write(tag)
                raf.write(ByteArray((old - 10 - tag.size).toInt()))
                return
            }
            val padding = 2048
            val tmp = File(file.parentFile, ".${file.name}.litedash")
            tmp.outputStream().buffered().use { out ->
                out.write(header((tag.size + padding).toLong()))
                out.write(tag)
                out.write(ByteArray(padding))
                raf.seek(old)
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = raf.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            raf.close()
            check(tmp.length() >= file.length()) { tmp.delete(); "copia incompleta" }
            val bak = File(file.parentFile, ".${file.name}.bak")
            check(file.renameTo(bak)) { tmp.delete(); "no se pudo renombrar" }
            if (!tmp.renameTo(file)) {
                bak.renameTo(file)
                tmp.delete()
                error("no se pudo reemplazar")
            }
            bak.delete()
        }
    }

    /** Las etiquetas viejas que no son las que se escriben, pasadas a ID3v2.3. */
    private fun keep(body: ByteArray, version: Int, out: ByteArrayOutputStream) {
        var i = 0
        while (i + 10 <= body.size) {
            if (body[i].toInt() == 0) break
            val id = String(body, i, 4, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val size = if (version == 4) syncsafe(body, i + 4) else ((body[i + 4].toInt() and 0xFF) shl 24) or ((body[i + 5].toInt() and 0xFF) shl 16) or ((body[i + 6].toInt() and 0xFF) shl 8) or (body[i + 7].toInt() and 0xFF)
            if (size <= 0 || i + 10 + size > body.size) break
            val flags = ((body[i + 8].toInt() and 0xFF) shl 8) or (body[i + 9].toInt() and 0xFF)
            val data = body.copyOfRange(i + 10, i + 10 + size)
            i += 10 + size
            if (id in OURS) continue
            if (version == 4) {
                // De la 2.4 solo lo sencillo: sin banderas y sin texto en UTF-8 (la 2.3 no lo tiene).
                if (flags != 0 || id.length != 4) continue
                if ((id.startsWith("T") || id == "COMM" || id == "USLT") && data.isNotEmpty() && data[0].toInt() == 3) continue
            }
            frame(out, id, data, if (version == 3) flags else 0)
        }
    }

    private fun frame(out: ByteArrayOutputStream, id: String, body: ByteArray, flags: Int = 0) {
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = body.size
        out.write(n ushr 24); out.write(n ushr 16 and 0xFF); out.write(n ushr 8 and 0xFF); out.write(n and 0xFF)
        out.write(flags ushr 8 and 0xFF); out.write(flags and 0xFF)
        out.write(body)
    }

    private fun header(size: Long): ByteArray {
        val n = size.toInt()
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (n ushr 21 and 0x7F).toByte(), (n ushr 14 and 0x7F).toByte(), (n ushr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    }

    private fun syncsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
}
