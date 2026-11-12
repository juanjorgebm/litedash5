package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Dibujos originales para las cartas de Parejas. */
object Pics {
    const val FACE = 0xFFF4EFE6.toInt()
    const val COUNT = 12
    private val TINTS = intArrayOf(
        0xFFFFE1DE.toInt(), 0xFFFFE4CC.toInt(), 0xFFDDF3DF.toInt(), 0xFFFFF2C2.toInt(),
        0xFFDDE6FA.toInt(), 0xFFD7F0FA.toInt(), 0xFFE2F1D3.toInt(), 0xFFF1DDF7.toInt(),
        0xFFFCDDEB.toInt(), 0xFFDDF5E4.toInt(), 0xFFFFEBC9.toInt(), 0xFFEFE3D6.toInt())

    fun draw(c: Canvas, id: Int, cx: Float, cy: Float, s: Float) {
        G.circle(c, cx, cy, s * 0.47f, TINTS[id % COUNT])
        when (id % COUNT) {
            0 -> car(c, cx, cy, s)
            1 -> volcano(c, cx, cy, s)
            2 -> palm(c, cx, cy, s)
            3 -> sun(c, cx, cy, s)
            4 -> moon(c, cx, cy, s)
            5 -> fish(c, cx, cy, s)
            6 -> turtle(c, cx, cy, s)
            7 -> butterfly(c, cx, cy, s)
            8 -> flower(c, cx, cy, s)
            9 -> frog(c, cx, cy, s)
            10 -> toucan(c, cx, cy, s)
            else -> coffee(c, cx, cy, s)
        }
    }

    private fun car(c: Canvas, cx: Float, cy: Float, s: Float) {
        val red = 0xFFE5484D.toInt()
        G.rr(c, cx - s * 0.2f, cy - s * 0.2f, cx + s * 0.16f, cy, s * 0.07f, red)
        G.rr(c, cx - s * 0.16f, cy - s * 0.16f, cx - s * 0.03f, cy - s * 0.02f, s * 0.02f, 0xFFBFE6FF.toInt())
        G.rr(c, cx, cy - s * 0.16f, cx + s * 0.12f, cy - s * 0.02f, s * 0.02f, 0xFFBFE6FF.toInt())
        G.rr(c, cx - s * 0.36f, cy - s * 0.06f, cx + s * 0.36f, cy + s * 0.13f, s * 0.07f, red)
        G.circle(c, cx + s * 0.31f, cy, s * 0.035f, 0xFFFFE08A.toInt())
        for (x in floatArrayOf(-0.21f, 0.21f)) {
            G.circle(c, cx + s * x, cy + s * 0.13f, s * 0.09f, 0xFF2A2E35.toInt())
            G.circle(c, cx + s * x, cy + s * 0.13f, s * 0.04f, 0xFFB8C0CC.toInt())
        }
    }

    private fun volcano(c: Canvas, cx: Float, cy: Float, s: Float) {
        for ((i, k) in floatArrayOf(0f, 1f, 2f).withIndex()) G.circle(c, cx + s * (0.02f + 0.06f * i), cy - s * (0.22f + 0.08f * k), s * (0.06f + 0.02f * i), 0xFFB5B9C0.toInt())
        G.path.reset()
        G.path.moveTo(cx - s * 0.38f, cy + s * 0.3f); G.path.lineTo(cx - s * 0.09f, cy - s * 0.15f)
        G.path.lineTo(cx + s * 0.09f, cy - s * 0.15f); G.path.lineTo(cx + s * 0.38f, cy + s * 0.3f); G.path.close()
        c.drawPath(G.path, G.fill(0xFF6E8A4E.toInt()))
        G.path.reset()
        G.path.moveTo(cx - s * 0.09f, cy - s * 0.15f); G.path.lineTo(cx + s * 0.09f, cy - s * 0.15f)
        G.path.lineTo(cx + s * 0.05f, cy - s * 0.04f); G.path.lineTo(cx, cy - s * 0.09f); G.path.lineTo(cx - s * 0.05f, cy - s * 0.02f); G.path.close()
        c.drawPath(G.path, G.fill(0xFFF26B2D.toInt()))
    }

    private fun palm(c: Canvas, cx: Float, cy: Float, s: Float) {
        G.oval(c, cx, cy + s * 0.32f, s * 0.26f, s * 0.07f, 0xFFF0D59A.toInt())
        G.path.reset()
        G.path.moveTo(cx - s * 0.02f, cy + s * 0.32f); G.path.quadTo(cx - s * 0.02f, cy, cx + s * 0.08f, cy - s * 0.16f)
        c.drawPath(G.path, G.stroke(0xFF8A5A33.toInt(), s * 0.07f))
        val leaf = 0xFF3FA65B.toInt()
        for (a in intArrayOf(-160, -120, -60, -20, 20)) {
            val rad = Math.toRadians(a.toDouble())
            val ex = cx + s * 0.08f + s * 0.27f * cos(rad).toFloat(); val ey = cy - s * 0.16f + s * 0.2f * sin(rad).toFloat() + s * 0.06f
            G.path.reset(); G.path.moveTo(cx + s * 0.08f, cy - s * 0.16f)
            G.path.quadTo((cx + s * 0.08f + ex) / 2, ey - s * 0.12f, ex, ey)
            c.drawPath(G.path, G.stroke(leaf, s * 0.07f))
        }
        G.circle(c, cx + s * 0.05f, cy - s * 0.12f, s * 0.035f, 0xFF7A4B2A.toInt())
        G.circle(c, cx + s * 0.11f, cy - s * 0.11f, s * 0.035f, 0xFF7A4B2A.toInt())
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, s: Float) {
        val col = 0xFFFFB020.toInt()
        val p = G.stroke(col, s * 0.05f)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            c.drawLine(cx + s * 0.25f * cos(a).toFloat(), cy + s * 0.25f * sin(a).toFloat(), cx + s * 0.36f * cos(a).toFloat(), cy + s * 0.36f * sin(a).toFloat(), p)
        }
        G.circle(c, cx, cy, s * 0.19f, col)
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, s: Float) {
        G.circle(c, cx - s * 0.03f, cy, s * 0.26f, 0xFF3F5AA8.toInt())
        G.circle(c, cx + s * 0.09f, cy - s * 0.07f, s * 0.22f, TINTS[4])
        Icons.star(c, cx + s * 0.22f, cy + s * 0.16f, s * 0.07f, 0xFFFFC23D.toInt())
        Icons.star(c, cx + s * 0.27f, cy - s * 0.2f, s * 0.05f, 0xFFFFC23D.toInt())
    }

    private fun fish(c: Canvas, cx: Float, cy: Float, s: Float) {
        val col = 0xFFFF8A3D.toInt()
        G.path.reset()
        G.path.moveTo(cx + s * 0.12f, cy); G.path.lineTo(cx + s * 0.36f, cy - s * 0.15f); G.path.lineTo(cx + s * 0.36f, cy + s * 0.15f); G.path.close()
        c.drawPath(G.path, G.fill(col))
        G.oval(c, cx - s * 0.05f, cy, s * 0.24f, s * 0.15f, col)
        G.path.reset()
        G.path.moveTo(cx - s * 0.06f, cy - s * 0.14f); G.path.lineTo(cx + s * 0.08f, cy - s * 0.24f); G.path.lineTo(cx + s * 0.1f, cy - s * 0.1f); G.path.close()
        c.drawPath(G.path, G.fill(0xFFE5672A.toInt()))
        G.circle(c, cx - s * 0.16f, cy - s * 0.03f, s * 0.04f, C.WHITE)
        G.circle(c, cx - s * 0.16f, cy - s * 0.03f, s * 0.02f, C.DARK)
    }

    private fun turtle(c: Canvas, cx: Float, cy: Float, s: Float) {
        val skin = 0xFF8BC34A.toInt(); val shell = 0xFF2E8B57.toInt()
        for (x in floatArrayOf(-0.2f, 0.16f)) G.oval(c, cx + s * x, cy + s * 0.12f, s * 0.07f, s * 0.05f, skin)
        G.circle(c, cx + s * 0.3f, cy - s * 0.02f, s * 0.08f, skin)
        G.circle(c, cx + s * 0.33f, cy - s * 0.04f, s * 0.015f, C.DARK)
        G.r.set(cx - s * 0.3f, cy - s * 0.22f, cx + s * 0.24f, cy + s * 0.2f)
        c.drawArc(G.r, 180f, 180f, true, G.fill(shell))
        G.rr(c, cx - s * 0.32f, cy - s * 0.02f, cx + s * 0.26f, cy + s * 0.05f, s * 0.03f, 0xFF246B44.toInt())
        G.circle(c, cx - s * 0.03f, cy - s * 0.1f, s * 0.06f, 0xFF4FB477.toInt())
        G.circle(c, cx - s * 0.17f, cy - s * 0.05f, s * 0.04f, 0xFF4FB477.toInt())
        G.circle(c, cx + s * 0.11f, cy - s * 0.05f, s * 0.04f, 0xFF4FB477.toInt())
    }

    private fun butterfly(c: Canvas, cx: Float, cy: Float, s: Float) {
        val a = 0xFF9B5DE5.toInt(); val b = 0xFFF15BB5.toInt()
        G.oval(c, cx - s * 0.17f, cy - s * 0.1f, s * 0.15f, s * 0.13f, a)
        G.oval(c, cx + s * 0.17f, cy - s * 0.1f, s * 0.15f, s * 0.13f, a)
        G.oval(c, cx - s * 0.13f, cy + s * 0.12f, s * 0.1f, s * 0.1f, b)
        G.oval(c, cx + s * 0.13f, cy + s * 0.12f, s * 0.1f, s * 0.1f, b)
        G.circle(c, cx - s * 0.17f, cy - s * 0.1f, s * 0.05f, 0xFFFFD166.toInt())
        G.circle(c, cx + s * 0.17f, cy - s * 0.1f, s * 0.05f, 0xFFFFD166.toInt())
        G.rr(c, cx - s * 0.03f, cy - s * 0.2f, cx + s * 0.03f, cy + s * 0.24f, s * 0.03f, 0xFF3A2E3F.toInt())
        val p = G.stroke(0xFF3A2E3F.toInt(), s * 0.02f)
        c.drawLine(cx, cy - s * 0.19f, cx - s * 0.08f, cy - s * 0.31f, p)
        c.drawLine(cx, cy - s * 0.19f, cx + s * 0.08f, cy - s * 0.31f, p)
    }

    private fun flower(c: Canvas, cx: Float, cy: Float, s: Float) {
        c.drawLine(cx, cy, cx, cy + s * 0.36f, G.stroke(0xFF3FA65B.toInt(), s * 0.05f))
        G.oval(c, cx + s * 0.09f, cy + s * 0.24f, s * 0.09f, s * 0.04f, 0xFF3FA65B.toInt())
        for (i in 0 until 6) {
            val a = Math.toRadians(i * 60.0)
            G.circle(c, cx + s * 0.14f * cos(a).toFloat(), cy - s * 0.06f + s * 0.14f * sin(a).toFloat(), s * 0.1f, 0xFFFF6FA5.toInt())
        }
        G.circle(c, cx, cy - s * 0.06f, s * 0.09f, 0xFFFFC93C.toInt())
    }

    private fun frog(c: Canvas, cx: Float, cy: Float, s: Float) {
        val g = 0xFF43B05C.toInt()
        G.circle(c, cx - s * 0.15f, cy - s * 0.15f, s * 0.11f, g)
        G.circle(c, cx + s * 0.15f, cy - s * 0.15f, s * 0.11f, g)
        G.oval(c, cx, cy + s * 0.04f, s * 0.32f, s * 0.24f, g)
        for (x in floatArrayOf(-0.15f, 0.15f)) {
            G.circle(c, cx + s * x, cy - s * 0.15f, s * 0.07f, C.WHITE)
            G.circle(c, cx + s * x, cy - s * 0.14f, s * 0.035f, C.DARK)
        }
        G.r.set(cx - s * 0.17f, cy - s * 0.04f, cx + s * 0.17f, cy + s * 0.14f)
        c.drawArc(G.r, 20f, 140f, false, G.stroke(0xFF1F6B33.toInt(), s * 0.03f))
        G.circle(c, cx - s * 0.2f, cy + s * 0.06f, s * 0.035f, 0xFFFF8FA3.toInt())
        G.circle(c, cx + s * 0.2f, cy + s * 0.06f, s * 0.035f, 0xFFFF8FA3.toInt())
    }

    private fun toucan(c: Canvas, cx: Float, cy: Float, s: Float) {
        G.oval(c, cx - s * 0.08f, cy + s * 0.06f, s * 0.17f, s * 0.26f, 0xFF22262C.toInt())
        G.oval(c, cx - s * 0.02f, cy - s * 0.06f, s * 0.09f, s * 0.11f, 0xFFFFF4D6.toInt())
        G.path.reset()
        G.path.moveTo(cx + s * 0.02f, cy - s * 0.16f)
        G.path.quadTo(cx + s * 0.3f, cy - s * 0.2f, cx + s * 0.38f, cy - s * 0.02f)
        G.path.quadTo(cx + s * 0.22f, cy - s * 0.04f, cx + s * 0.04f, cy - s * 0.02f)
        G.path.close()
        c.drawPath(G.path, G.fill(0xFFFF9F1C.toInt()))
        G.circle(c, cx + s * 0.36f, cy - s * 0.04f, s * 0.03f, 0xFFE63946.toInt())
        G.circle(c, cx - s * 0.04f, cy - s * 0.13f, s * 0.035f, 0xFF7BD389.toInt())
        G.circle(c, cx - s * 0.04f, cy - s * 0.13f, s * 0.017f, C.DARK)
        c.drawLine(cx - s * 0.1f, cy + s * 0.3f, cx - s * 0.1f, cy + s * 0.36f, G.stroke(0xFF5B6470.toInt(), s * 0.03f))
    }

    private fun coffee(c: Canvas, cx: Float, cy: Float, s: Float) {
        val cup = C.WHITE
        G.oval(c, cx, cy + s * 0.26f, s * 0.3f, s * 0.06f, 0xFFD7CCC8.toInt())
        c.drawCircle(cx + s * 0.2f, cy + s * 0.05f, s * 0.08f, G.stroke(0xFF8D6E63.toInt(), s * 0.04f))
        G.rr(c, cx - s * 0.22f, cy - s * 0.08f, cx + s * 0.2f, cy + s * 0.24f, s * 0.08f, cup)
        G.r.set(cx - s * 0.22f, cy - s * 0.08f, cx + s * 0.2f, cy + s * 0.24f)
        c.drawRoundRect(G.r, s * 0.08f, s * 0.08f, G.stroke(0xFF8D6E63.toInt(), s * 0.03f))
        G.oval(c, cx - s * 0.01f, cy - s * 0.05f, s * 0.17f, s * 0.035f, 0xFF6D4C41.toInt())
        val p = G.stroke(0xFFB0A49C.toInt(), s * 0.03f)
        for (x in floatArrayOf(-0.08f, 0.06f)) {
            G.path.reset(); G.path.moveTo(cx + s * x, cy - s * 0.14f)
            G.path.quadTo(cx + s * (x - 0.05f), cy - s * 0.22f, cx + s * x, cy - s * 0.28f)
            G.path.quadTo(cx + s * (x + 0.05f), cy - s * 0.34f, cx + s * x, cy - s * 0.4f)
            c.drawPath(G.path, p)
        }
    }
}

class ParejasScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val cols = if (m.level >= 2) 8 else 6
    private val rows = if (m.level == 0) 2 else 3
    private val n = cols * rows
    private val pairs = n / 2
    private val icon = IntArray(n)
    private val state = IntArray(n)    // 0 oculta, 1 visible, 2 encontrada
    private val owner = IntArray(n)
    private val flipT = LongArray(n) { -10_000 }
    private val open = ArrayList<Int>()
    private var hideAt = 0L
    private var turn = 0
    private val score = IntArray(2)
    private var attempts = 0
    private var found = 0
    private var endTime = 0L

    private var cw = 0f; private var ch = 0f; private var gx = 0f; private var gy = 0f; private var gap = 0f

    init { restart() }

    override fun restart() {
        val ids = (0 until Pics.COUNT).shuffled(rnd).take(pairs)
        val deck = (ids + ids).shuffled(rnd)
        for (i in 0 until n) { icon[i] = deck[i]; state[i] = 0; owner[i] = -1; flipT[i] = -10_000 }
        open.clear(); hideAt = 0; turn = m.starter; score.fill(0); attempts = 0; found = 0; endTime = 0
        resetClock()
    }

    override fun size(w: Float, h: Float) {
        super.size(w, h)
        val ax = pad * 2; val ay = barH + pad * 1.5f
        val aw = w - pad * 4; val ah = h - ay - pad * 1.5f
        gap = min(aw, ah) * 0.025f
        val cellW = (aw - gap * (cols - 1)) / cols
        val cellH = (ah - gap * (rows - 1)) / rows
        cw = min(cellW, cellH * 1.25f); ch = min(cellH, cw * 1.3f)
        val tw = cols * cw + (cols - 1) * gap; val th = rows * ch + (rows - 1) * gap
        gx = ax + (aw - tw) / 2; gy = ay + (ah - th) / 2
    }

    private fun rect(i: Int, out: RectF): RectF {
        val col = i % cols; val row = i / cols
        out.set(gx + col * (cw + gap), gy + row * (ch + gap), gx + col * (cw + gap) + cw, gy + row * (ch + gap) + ch)
        return out
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val cy = barH / 2
        if (m.players == 1) {
            val t = if (endTime > 0) endTime else gt()
            G.textFit(c, "Tiempo ${G.timeExact(t)}     Intentos $attempts     Parejas $found/$pairs", (left + right) / 2, cy, barH * 0.34f, right - left, C.WHITE)
        } else {
            val mid = (left + right) / 2
            for (i in 0..1) {
                val active = turn == i && !over
                val r = RectF(if (i == 0) left else mid + barH * 0.1f, barH * 0.16f, if (i == 0) mid - barH * 0.1f else right, barH * 0.84f)
                G.rr(c, r, r.height() * 0.3f, if (active) C.alpha(C.PLAYER[i], 0.2f) else C.CARD)
                if (active) G.rrLine(c, r, r.height() * 0.3f, C.PLAYER[i], r.height() * 0.06f)
                G.textFit(c, "${name(i)}  ${score[i]}", r.centerX(), r.centerY(), r.height() * 0.5f, r.width() * 0.88f, if (active) C.PLAYER[i] else C.WHITE, font = Fonts.black)
            }
        }
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        if (open.size == 2 && t >= hideAt) hideOpen()
        val r = RectF()
        for (i in 0 until n) {
            rect(i, r)
            val k = ((t - flipT[i]) / 240f).coerceIn(0f, 1f)
            val faceNow = state[i] >= 1
            val showFace = if (k < 0.5f) !faceNow else faceNow
            val sx = abs(cos(k * Math.PI)).toFloat().coerceAtLeast(0.04f)
            c.save()
            c.scale(sx, 1f, r.centerX(), r.centerY())
            val rad = cw * 0.1f
            if (showFace) {
                G.rr(c, r, rad, if (state[i] == 2 && k >= 1f) C.mix(Pics.FACE, C.BG, 0.25f) else Pics.FACE)
                Pics.draw(c, icon[i], r.centerX(), r.centerY(), min(cw, ch) * 0.86f)
                if (state[i] == 2 && owner[i] >= 0 && m.players == 2) G.rrLine(c, r, rad, C.PLAYER[owner[i]], cw * 0.05f)
            } else {
                G.rr(c, r, rad, C.CARD)
                val inset = cw * 0.08f
                G.r.set(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)
                c.drawRoundRect(G.r, rad * 0.7f, rad * 0.7f, G.stroke(C.AMBER_DARK, cw * 0.025f))
                Icons.star(c, r.centerX(), r.centerY(), min(cw, ch) * 0.16f, C.alpha(C.AMBER, 0.55f))
            }
            c.restore()
        }
    }

    private fun hideOpen() {
        for (i in open) { state[i] = 0; flipT[i] = gt() }
        open.clear()
        if (m.players == 2) turn = 1 - turn
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked) return
        val r = RectF()
        val i = (0 until n).firstOrNull { rect(it, r).contains(x, y) } ?: return
        if (state[i] != 0) return
        if (open.size == 2) hideOpen()
        state[i] = 1; flipT[i] = gt(); open.add(i)
        Sound.play(Sound.FLIP)
        if (open.size == 2) {
            attempts++
            val a = open[0]; val b = open[1]
            if (icon[a] == icon[b]) {
                state[a] = 2; state[b] = 2; owner[a] = turn; owner[b] = turn
                score[turn]++; found++
                open.clear()
                Sound.play(Sound.GOOD)
                if (found == pairs) end()
            } else hideAt = gt() + 1000
        }
    }

    private fun end() {
        endTime = gt()
        if (m.players == 1) {
            val newT = Store.best("parejas_t${m.level}", endTime, true)
            val newI = Store.best("parejas_i${m.level}", attempts.toLong(), true)
            val extra = if (newT || newI) "\n¡Nuevo récord!" else ""
            finish(Result(Result.SOLO, "¡Completado!", "Tiempo: ${G.timeExact(endTime)}\nIntentos: $attempts$extra"), 700)
        } else {
            val d = "${name(0)}: ${score[0]}   ${name(1)}: ${score[1]}"
            when {
                score[0] > score[1] -> finish(Result(0, "¡Ganó ${name(0)}!", d), 700)
                score[1] > score[0] -> finish(Result(1, "¡Ganó ${name(1)}!", d), 700)
                else -> finish(Result(Result.DRAW, "Empate", d), 700)
            }
        }
    }
}
