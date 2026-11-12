package cr.juegos.carro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Colores del tema grafito / ámbar. */
object C {
    const val BG = 0xFF171B21.toInt()
    const val PANEL = 0xFF1F242B.toInt()
    const val CARD = 0xFF343B45.toInt()
    const val CARD_HI = 0xFF434B57.toInt()
    const val LINE = 0xFF4E5765.toInt()
    const val AMBER = 0xFFFFB547.toInt()
    const val AMBER_DARK = 0xFF9A6A22.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xFFA9B2BF.toInt()
    const val DARK = 0xFF1A1D22.toInt()
    const val BLUE = 0xFF5CC8FF.toInt()
    const val RED = 0xFFEF5B5B.toInt()
    const val GREEN = 0xFF4CD08A.toInt()
    val PLAYER = intArrayOf(AMBER, BLUE)

    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(s: Int) = (((a shr s) and 255) * (1 - u) + ((b shr s) and 255) * u).toInt() shl s
        return ch(24) or ch(16) or ch(8) or ch(0)
    }
}

object Fonts {
    var bold: Typeface = Typeface.DEFAULT
    var black: Typeface = Typeface.DEFAULT

    fun init(ctx: Context) {
        // La fuente negrita del sistema del carro no trae tildes: usamos una fuente propia.
        bold = try { Typeface.createFromAsset(ctx.assets, "fonts/Nunito-Bold.ttf") } catch (e: Exception) { Typeface.DEFAULT }
        black = try { Typeface.createFromAsset(ctx.assets, "fonts/Nunito-Black.ttf") } catch (e: Exception) { bold }
    }
}

/** Ayudas de dibujo compartidas. */
object G {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val t = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    val r = RectF()
    val path = Path()

    fun fill(color: Int): Paint { p.style = Paint.Style.FILL; p.color = color; return p }
    fun stroke(color: Int, width: Float): Paint { s.color = color; s.strokeWidth = width; return s }

    fun rr(c: Canvas, l: Float, tp: Float, rt: Float, b: Float, rad: Float, color: Int) {
        r.set(l, tp, rt, b); c.drawRoundRect(r, rad, rad, fill(color))
    }

    fun rr(c: Canvas, rect: RectF, rad: Float, color: Int) = c.drawRoundRect(rect, rad, rad, fill(color))

    fun rrLine(c: Canvas, rect: RectF, rad: Float, color: Int, width: Float) =
        c.drawRoundRect(rect, rad, rad, stroke(color, width))

    fun circle(c: Canvas, x: Float, y: Float, rad: Float, color: Int) = c.drawCircle(x, y, rad, fill(color))

    fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        r.set(cx - rx, cy - ry, cx + rx, cy + ry); c.drawOval(r, fill(color))
    }

    /** Dibuja texto centrado verticalmente en [y]. */
    fun text(c: Canvas, str: String, x: Float, y: Float, size: Float, color: Int,
             align: Paint.Align = Paint.Align.CENTER, font: Typeface = Fonts.bold) {
        t.typeface = font; t.textSize = size; t.color = color; t.textAlign = align
        val fm = t.fontMetrics
        c.drawText(str, x, y - (fm.ascent + fm.descent) / 2, t)
    }

    fun width(str: String, size: Float, font: Typeface = Fonts.bold): Float {
        t.typeface = font; t.textSize = size; return t.measureText(str)
    }

    fun fit(str: String, maxW: Float, size: Float, font: Typeface = Fonts.bold): Float {
        val w = width(str, size, font)
        return if (w > maxW && w > 0) size * maxW / w else size
    }

    fun textFit(c: Canvas, str: String, x: Float, y: Float, size: Float, maxW: Float, color: Int,
                align: Paint.Align = Paint.Align.CENTER, font: Typeface = Fonts.bold) =
        text(c, str, x, y, fit(str, maxW, size, font), color, align, font)

    /** Varias líneas centradas alrededor de [cy]. */
    fun lines(c: Canvas, str: String, x: Float, cy: Float, size: Float, maxW: Float, color: Int,
              align: Paint.Align = Paint.Align.CENTER, font: Typeface = Fonts.bold, gap: Float = 1.35f) {
        val ls = str.split("\n")
        var sz = size
        for (l in ls) sz = min(sz, fit(l, maxW, size, font))
        val lh = sz * gap
        var y = cy - lh * (ls.size - 1) / 2
        for (l in ls) { text(c, l, x, y, sz, color, align, font); y += lh }
    }

    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun timeExact(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    fun ease(t: Float): Float { val u = t.coerceIn(0f, 1f); return 1 - (1 - u) * (1 - u) * (1 - u) }
    fun back(t: Float): Float { val u = t.coerceIn(0f, 1f); val k = 1.7f; return 1 + (k + 1) * (u - 1) * (u - 1) * (u - 1) + k * (u - 1) * (u - 1) }
}

/** Iconos sencillos de la interfaz. */
object Icons {
    fun back(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = G.stroke(color, s * 0.13f)
        G.path.reset()
        G.path.moveTo(cx + s * 0.34f, cy); G.path.lineTo(cx - s * 0.34f, cy)
        G.path.moveTo(cx - s * 0.04f, cy - s * 0.3f); G.path.lineTo(cx - s * 0.34f, cy); G.path.lineTo(cx - s * 0.04f, cy + s * 0.3f)
        c.drawPath(G.path, p)
    }

    fun pause(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val w = s * 0.17f; val h = s * 0.32f; val rad = w * 0.35f
        G.rr(c, cx - s * 0.2f - w / 2, cy - h, cx - s * 0.2f + w / 2, cy + h, rad, color)
        G.rr(c, cx + s * 0.2f - w / 2, cy - h, cx + s * 0.2f + w / 2, cy + h, rad, color)
    }

    fun sound(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, on: Boolean) {
        val x = cx - s * 0.12f
        G.path.reset()
        G.path.moveTo(x - s * 0.28f, cy - s * 0.12f); G.path.lineTo(x - s * 0.12f, cy - s * 0.12f)
        G.path.lineTo(x + s * 0.08f, cy - s * 0.3f); G.path.lineTo(x + s * 0.08f, cy + s * 0.3f)
        G.path.lineTo(x - s * 0.12f, cy + s * 0.12f); G.path.lineTo(x - s * 0.28f, cy + s * 0.12f); G.path.close()
        c.drawPath(G.path, G.fill(color))
        val p = G.stroke(color, s * 0.08f)
        if (on) {
            G.r.set(x - s * 0.05f, cy - s * 0.2f, x + s * 0.3f, cy + s * 0.2f); c.drawArc(G.r, -50f, 100f, false, p)
            G.r.set(x - s * 0.12f, cy - s * 0.36f, x + s * 0.46f, cy + s * 0.36f); c.drawArc(G.r, -50f, 100f, false, p)
        } else {
            c.drawLine(x + s * 0.2f, cy - s * 0.14f, x + s * 0.46f, cy + s * 0.14f, p)
            c.drawLine(x + s * 0.2f, cy + s * 0.14f, x + s * 0.46f, cy - s * 0.14f, p)
        }
    }

    fun undo(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = G.stroke(color, s * 0.11f)
        G.r.set(cx - s * 0.28f, cy - s * 0.24f, cx + s * 0.32f, cy + s * 0.36f)
        c.drawArc(G.r, 180f, 230f, false, p)
        G.path.reset()
        G.path.moveTo(cx - s * 0.42f, cy - s * 0.08f); G.path.lineTo(cx - s * 0.28f, cy + s * 0.1f); G.path.lineTo(cx - s * 0.1f, cy - s * 0.04f)
        c.drawPath(G.path, p)
    }

    fun restart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = G.stroke(color, s * 0.11f)
        G.r.set(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f)
        c.drawArc(G.r, -60f, 300f, false, p)
        val ax = cx + s * 0.3f * cos(Math.toRadians(-60.0)).toFloat()
        val ay = cy + s * 0.3f * sin(Math.toRadians(-60.0)).toFloat()
        G.path.reset()
        G.path.moveTo(ax - s * 0.2f, ay - s * 0.04f); G.path.lineTo(ax + s * 0.02f, ay); G.path.lineTo(ax + s * 0.02f, ay - s * 0.22f)
        c.drawPath(G.path, p)
    }

    fun trophy(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        G.path.reset()
        G.path.moveTo(cx - s * 0.28f, cy - s * 0.36f); G.path.lineTo(cx + s * 0.28f, cy - s * 0.36f)
        G.path.lineTo(cx + s * 0.24f, cy - s * 0.05f)
        G.path.quadTo(cx + s * 0.18f, cy + s * 0.12f, cx + s * 0.06f, cy + s * 0.14f)
        G.path.lineTo(cx + s * 0.06f, cy + s * 0.26f); G.path.lineTo(cx + s * 0.2f, cy + s * 0.26f)
        G.path.lineTo(cx + s * 0.2f, cy + s * 0.38f); G.path.lineTo(cx - s * 0.2f, cy + s * 0.38f)
        G.path.lineTo(cx - s * 0.2f, cy + s * 0.26f); G.path.lineTo(cx - s * 0.06f, cy + s * 0.26f)
        G.path.lineTo(cx - s * 0.06f, cy + s * 0.14f)
        G.path.quadTo(cx - s * 0.18f, cy + s * 0.12f, cx - s * 0.24f, cy - s * 0.05f)
        G.path.close()
        c.drawPath(G.path, G.fill(color))
        val p = G.stroke(color, s * 0.07f)
        G.r.set(cx - s * 0.44f, cy - s * 0.3f, cx - s * 0.16f, cy + s * 0.02f); c.drawArc(G.r, 90f, 180f, false, p)
        G.r.set(cx + s * 0.16f, cy - s * 0.3f, cx + s * 0.44f, cy + s * 0.02f); c.drawArc(G.r, -90f, 180f, false, p)
    }

    fun star(c: Canvas, cx: Float, cy: Float, rOut: Float, color: Int, rot: Float = 0f) {
        G.path.reset()
        for (i in 0 until 10) {
            val rad = if (i % 2 == 0) rOut else rOut * 0.45f
            val a = Math.toRadians((i * 36.0) - 90.0 + rot).toFloat()
            val x = cx + rad * cos(a); val y = cy + rad * sin(a)
            if (i == 0) G.path.moveTo(x, y) else G.path.lineTo(x, y)
        }
        G.path.close()
        c.drawPath(G.path, G.fill(color))
    }

    fun list(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = G.stroke(color, s * 0.1f)
        for (i in -1..1) {
            val y = cy + i * s * 0.24f
            c.drawLine(cx - s * 0.1f, y, cx + s * 0.36f, y, p)
            G.circle(c, cx - s * 0.3f, y, s * 0.07f, color)
        }
    }
}

enum class Btn { CARD, PRIMARY, GHOST, SELECTED, DANGER }

/**
 * Botones en modo inmediato: cada cuadro se registran las zonas tocables mientras se dibujan,
 * y el toque se resuelve contra lo último que se dibujó.
 */
class Ui(private val view: View) {
    private class Hit(val r: RectF, val action: () -> Unit)

    private val hits = ArrayList<Hit>()
    private var dx = 0f; private var dy = 0f; private var px = 0f; private var py = 0f
    var pressing = false; private set

    fun begin() = hits.clear()
    fun reset() { hits.clear(); pressing = false }
    fun hitAt(x: Float, y: Float) = hits.any { it.r.contains(x, y) }
    fun down(x: Float, y: Float) { dx = x; dy = y; px = x; py = y; pressing = true }
    fun move(x: Float, y: Float) { px = x; py = y }
    fun cancel() { pressing = false }

    fun up(x: Float, y: Float) {
        pressing = false
        val h = hits.lastOrNull { it.r.contains(dx, dy) } ?: return
        val slop = h.r.height() * 0.3f
        if (x >= h.r.left - slop && x <= h.r.right + slop && y >= h.r.top - slop && y <= h.r.bottom + slop) {
            Sound.play(Sound.TAP)
            h.action()
            view.invalidate()
        }
    }

    fun pressed(r: RectF) = pressing && r.contains(dx, dy) && r.contains(px, py)

    /** Zona tocable invisible. */
    fun area(r: RectF, action: () -> Unit) { hits.add(Hit(RectF(r), action)) }

    private fun colors(style: Btn, enabled: Boolean): Pair<Int, Int> {
        val (bg, fg) = when (style) {
            Btn.CARD -> C.CARD to C.WHITE
            Btn.PRIMARY -> C.AMBER to C.DARK
            Btn.GHOST -> 0 to C.WHITE
            Btn.SELECTED -> C.alpha(C.AMBER, 0.18f) to C.AMBER
            Btn.DANGER -> C.alpha(C.RED, 0.2f) to C.RED
        }
        return if (enabled) bg to fg else C.alpha(C.CARD, 0.5f) to C.alpha(C.WHITE, 0.35f)
    }

    private fun frame(c: Canvas, r: RectF, style: Btn, enabled: Boolean): Int {
        val (bg, fg) = colors(style, enabled)
        val rad = min(r.height() * 0.24f, r.width() * 0.24f)
        val down = enabled && pressed(r)
        val rr = if (down) RectF(r).apply { inset(r.width() * 0.02f, r.height() * 0.04f) } else r
        if (bg != 0) G.rr(c, rr, rad, if (down) C.mix(bg, C.BG, 0.25f) else bg)
        if (style == Btn.GHOST || style == Btn.SELECTED) G.rrLine(c, rr, rad, if (style == Btn.SELECTED) C.AMBER else C.LINE, r.height() * 0.045f)
        return fg
    }

    fun button(c: Canvas, r: RectF, label: String, style: Btn = Btn.CARD, size: Float = 0f,
               enabled: Boolean = true, action: () -> Unit) {
        val fg = frame(c, r, style, enabled)
        val sz = if (size > 0) size else r.height() * 0.36f
        G.lines(c, label, r.centerX(), r.centerY(), sz, r.width() * 0.86f, fg)
        if (enabled) hits.add(Hit(RectF(r), action))
    }

    fun iconButton(c: Canvas, r: RectF, style: Btn, icon: (Float, Float, Float, Int) -> Unit,
                   label: String? = null, enabled: Boolean = true, action: () -> Unit) {
        val fg = frame(c, r, style, enabled)
        val s = r.height() * 0.62f
        if (label == null) {
            icon(r.centerX(), r.centerY(), s, fg)
        } else {
            val ts = r.height() * 0.34f
            val tw = min(G.width(label, ts), r.width() - s * 1.6f)
            val total = s * 1.15f + tw
            val x0 = r.centerX() - total / 2
            icon(x0 + s * 0.45f, r.centerY(), s, fg)
            G.textFit(c, label, x0 + s * 1.15f, r.centerY(), ts, tw, fg, Paint.Align.LEFT)
        }
        if (enabled) hits.add(Hit(RectF(r), action))
    }

    /** Fila de opciones donde una está elegida. */
    fun segmented(c: Canvas, r: RectF, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val n = options.size
        val gap = r.height() * 0.12f
        val bw = (r.width() - gap * (n - 1)) / n
        for (i in 0 until n) {
            val br = RectF(r.left + i * (bw + gap), r.top, r.left + i * (bw + gap) + bw, r.bottom)
            button(c, br, options[i], if (i == selected) Btn.SELECTED else Btn.CARD, r.height() * 0.34f) { onPick(i) }
        }
    }
}
