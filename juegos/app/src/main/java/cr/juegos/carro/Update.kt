package cr.juegos.carro

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/** Una entrada de novedades: versión, fecha y líneas de cambios. */
class Novedad(val code: Int, val date: String, val lines: List<String>)

/** Busca, descarga e instala versiones nuevas publicadas en la release "juegos-carro" de GitHub. */
object Updater {
    private const val BASE = "https://github.com/juanjorgebm/litedash5/releases/download/juegos-carro/"
    const val APK_NAME = "juegos-carro.apk"

    enum class State { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, ERROR, DOWNLOADING, READY }

    @Volatile var state = State.IDLE; private set
    @Volatile var latestCode = 0; private set
    @Volatile var latestName = ""; private set
    @Volatile var notes: List<Novedad> = emptyList(); private set
    @Volatile var progress = 0f; private set
    @Volatile var error = ""; private set
    private val main = Handler(Looper.getMainLooper())

    fun currentCode(ctx: Context): Int = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }

    val hasUpdate get() = state == State.AVAILABLE || state == State.DOWNLOADING || state == State.READY

    /** Texto de novedades: líneas "N|fecha" abren una versión, las demás son cambios. */
    fun parse(text: String): List<Novedad> {
        val out = ArrayList<Novedad>()
        var code = -1; var date = ""; val lines = ArrayList<String>()
        fun flush() { if (code >= 0) out.add(Novedad(code, date, ArrayList(lines))); lines.clear() }
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.isEmpty() || l.startsWith("#")) continue
            val m = Regex("^(\\d+)\\|(.*)$").find(l)
            if (m != null) { flush(); code = m.groupValues[1].toInt(); date = m.groupValues[2].trim() }
            else lines.add(l.removePrefix("-").trim())
        }
        flush()
        return out
    }

    /** Novedades que vienen dentro del APK instalado. */
    fun localNotes(ctx: Context): List<Novedad> = try {
        parse(ctx.assets.open("novedades.txt").bufferedReader().readText())
    } catch (e: Exception) { emptyList() }

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(6) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "JuegosCarro")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("Redirección sin destino")
                c.disconnect(); u = URL(URL(u), loc).toString()
            } else if (code == 200) return c
            else { c.disconnect(); throw Exception("Respuesta $code") }
        }
        throw Exception("Demasiadas redirecciones")
    }

    fun check(ctx: Context, onDone: () -> Unit = {}) {
        if (state == State.CHECKING || state == State.DOWNLOADING) return
        state = State.CHECKING
        val app = ctx.applicationContext
        Thread {
            try {
                val c = open(BASE + "version.json?t=" + System.currentTimeMillis())
                val js = JSONObject(c.inputStream.bufferedReader().readText()); c.disconnect()
                latestCode = js.getInt("code"); latestName = js.optString("name", "1.$latestCode")
                notes = parse(js.optString("notes", ""))
                state = if (latestCode > currentCode(app)) State.AVAILABLE else State.UP_TO_DATE
            } catch (e: Exception) {
                error = "No se pudo revisar. ¿Hay internet?"
                state = State.ERROR
            }
            main.post(onDone)
        }.start()
    }

    private fun apkFile(ctx: Context): File = File(ctx.externalCacheDir ?: ctx.cacheDir, APK_NAME)

    fun download(ctx: Context, onChange: () -> Unit) {
        if (state == State.DOWNLOADING) return
        state = State.DOWNLOADING; progress = 0f
        val app = ctx.applicationContext
        Thread {
            try {
                val f = apkFile(app)
                val c = open(BASE + APK_NAME + "?v=" + latestCode)
                val total = c.contentLength.toLong()
                var done = 0L
                val tmp = File(f.path + ".part")
                c.inputStream.use { inp: InputStream ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        var lastPost = 0L
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) progress = done.toFloat() / total
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 150) { lastPost = now; main.post(onChange) }
                        }
                    }
                }
                c.disconnect()
                if (total > 0 && done < total) throw Exception("Descarga incompleta")
                f.delete(); tmp.renameTo(f)
                @Suppress("SetWorldReadable") f.setReadable(true, false)
                progress = 1f; state = State.READY
                main.post { onChange(); install(app) }
            } catch (e: Exception) {
                error = "Se cortó la descarga. Intenta otra vez."
                state = State.ERROR
                main.post(onChange)
            }
        }.start()
    }

    fun install(ctx: Context) {
        val f = apkFile(ctx)
        if (!f.exists()) return
        val uri = if (Build.VERSION.SDK_INT >= 24) Uri.parse("content://${ctx.packageName}.apk/$APK_NAME") else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { ctx.startActivity(i) } catch (e: Exception) { error = "No se pudo abrir el instalador"; state = State.ERROR }
    }

    /** Ícono de flecha hacia abajo sobre una bandeja. */
    fun icon(c: Canvas, cx: Float, cy: Float, s: Float, col: Int) {
        val p = G.stroke(col, s * 0.13f)
        c.drawLine(cx, cy - s * 0.38f, cx, cy + s * 0.12f, p)
        c.drawLine(cx - s * 0.22f, cy - s * 0.08f, cx, cy + s * 0.14f, p)
        c.drawLine(cx + s * 0.22f, cy - s * 0.08f, cx, cy + s * 0.14f, p)
        c.drawLine(cx - s * 0.36f, cy + s * 0.18f, cx - s * 0.36f, cy + s * 0.36f, p)
        c.drawLine(cx - s * 0.36f, cy + s * 0.36f, cx + s * 0.36f, cy + s * 0.36f, p)
        c.drawLine(cx + s * 0.36f, cy + s * 0.36f, cx + s * 0.36f, cy + s * 0.18f, p)
    }
}

/** Entrega el APK descargado al instalador en Android 7 y posteriores. */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context!!
        val f = File(ctx.externalCacheDir ?: ctx.cacheDir, Updater.APK_NAME)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** Pantalla para ver la versión, las novedades y actualizar. */
class UpdateScreen(app: MainActivity) : Screen(app) {
    private fun wrap(text: String, maxW: Float, size: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in text.split(" ")) {
            val t = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && G.width(t, size) > maxW) { out.add(line); line = word } else line = t
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private val cur = Updater.currentCode(app)
    private val local = Updater.localNotes(app)

    init { if (Updater.state != Updater.State.DOWNLOADING && Updater.state != Updater.State.READY) Updater.check(app) { invalidate() } }

    override fun animating() = Updater.state == Updater.State.CHECKING || Updater.state == Updater.State.DOWNLOADING

    override fun draw(c: Canvas, now: Long) {
        header(c, "Actualizar")
        val st = Updater.state
        val top = barH + pad * 2
        val leftW = w * 0.36f
        // Panel izquierdo: versión y botón
        val lp = RectF(pad * 2, top, pad * 2 + leftW, h - pad * 2)
        G.rr(c, lp, barH * 0.25f, C.PANEL)
        val cx = lp.centerX()
        G.text(c, "Tienes la versión", cx, lp.top + lp.height() * 0.1f, barH * 0.3f, C.TEXT2)
        G.text(c, Updater.currentName(app).ifEmpty { "1.$cur" }, cx, lp.top + lp.height() * 0.22f, barH * 0.62f, C.WHITE, font = Fonts.black)
        val (msg, col) = when (st) {
            Updater.State.CHECKING, Updater.State.IDLE -> "Buscando versión nueva..." to C.TEXT2
            Updater.State.UP_TO_DATE -> "Ya tienes la última versión" to C.GREEN
            Updater.State.AVAILABLE -> "¡Hay una versión nueva: ${Updater.latestName}!" to C.AMBER
            Updater.State.DOWNLOADING -> "Descargando ${(Updater.progress * 100).toInt()} %" to C.AMBER
            Updater.State.READY -> "Lista para instalar" to C.GREEN
            Updater.State.ERROR -> Updater.error to C.RED
        }
        G.textFit(c, msg, cx, lp.top + lp.height() * 0.38f, barH * 0.34f, lp.width() * 0.9f, col, font = Fonts.black)
        if (st == Updater.State.DOWNLOADING) {
            val bw = lp.width() * 0.8f; val by = lp.top + lp.height() * 0.48f
            G.rr(c, cx - bw / 2, by, cx + bw / 2, by + barH * 0.16f, barH * 0.08f, C.CARD)
            G.rr(c, cx - bw / 2, by, cx - bw / 2 + bw * Updater.progress, by + barH * 0.16f, barH * 0.08f, C.AMBER)
        }
        val bh = barH * 0.95f; val bw = lp.width() * 0.84f
        val br = RectF(cx - bw / 2, lp.top + lp.height() * 0.58f, cx + bw / 2, lp.top + lp.height() * 0.58f + bh)
        when (st) {
            Updater.State.AVAILABLE -> ui.button(c, br, "Descargar e instalar", Btn.PRIMARY, bh * 0.36f) { Updater.download(app) { invalidate() } }
            Updater.State.READY -> ui.button(c, br, "Instalar", Btn.PRIMARY, bh * 0.4f) { Updater.install(app) }
            Updater.State.UP_TO_DATE, Updater.State.ERROR -> ui.button(c, br, "Buscar otra vez", Btn.CARD, bh * 0.36f) { Updater.check(app) { invalidate() } }
            else -> ui.button(c, br, "Espera...", Btn.GHOST, bh * 0.36f, enabled = false) {}
        }
        G.lines(c, "Si el carro dice «App no instalada»,\ndesinstala los Juegos una vez\ne instala de nuevo.", cx, lp.bottom - lp.height() * 0.13f,
            barH * 0.24f, lp.width() * 0.88f, C.TEXT2)

        // Panel derecho: novedades
        val rp = RectF(lp.right + pad * 2, top, w - pad * 2, h - pad * 2)
        G.rr(c, rp, barH * 0.25f, C.PANEL)
        val newer = Updater.notes.filter { it.code > cur }
        val (title, list) = if (Updater.hasUpdate && newer.isNotEmpty()) "Novedades de la versión nueva" to newer
            else "Novedades de tu versión" to (Updater.notes.filter { it.code <= cur }.ifEmpty { local })
        G.textFit(c, title, rp.left + pad * 2, rp.top + barH * 0.5f, barH * 0.38f, rp.width() - pad * 4, C.AMBER, Paint.Align.LEFT, Fonts.black)
        var y = rp.top + barH * 1.05f
        val ts = min(barH * 0.3f, rp.height() * 0.06f)
        val maxY = rp.bottom - pad * 1.5f
        loop@ for (n in list.take(4)) {
            if (y + ts * 3f > maxY) break
            G.text(c, "Versión 1.${n.code}" + if (n.date.isNotEmpty()) "  ·  ${n.date}" else "", rp.left + pad * 2, y, ts * 0.9f, C.TEXT2, Paint.Align.LEFT, Fonts.black)
            y += ts * 1.5f
            val tw = rp.right - pad * 2 - (rp.left + pad * 3.6f)
            for (l in n.lines) {
                if (y + ts > maxY) break@loop
                G.circle(c, rp.left + pad * 2.6f, y, ts * 0.16f, C.AMBER)
                for (part in wrap(l, tw, ts)) {
                    if (y + ts > maxY) break@loop
                    G.textFit(c, part, rp.left + pad * 3.6f, y, ts, tw, C.WHITE, Paint.Align.LEFT)
                    y += ts * 1.35f
                }
                y += ts * 0.1f
            }
            y += ts * 0.6f
        }
    }
}
