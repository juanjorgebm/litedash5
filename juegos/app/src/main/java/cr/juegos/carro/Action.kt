package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.RectF
import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ReflejosScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val WAIT = 0; private val GO = 1; private val SHOW = 2
    private val TARGET = 5
    private val TRIES = 5
    private val two = m.players == 2
    private var state = WAIT
    private var goAt = 0L
    private var nextAt = 0L
    private var msg = ""
    private var msgColor = C.WHITE
    private val score = IntArray(2)
    private val times = ArrayList<Long>()
    private val flashT = LongArray(2) { -10_000 }
    private var round = 0

    init { restart() }

    override fun restart() {
        score.fill(0); times.clear(); round = 0
        resetClock(); newRound(1200)
    }

    private fun newRound(extra: Long = 0) {
        state = WAIT; round++
        goAt = gt() + extra + 1400 + rnd.nextInt(3200)
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val txt = if (two) "Primero a $TARGET puntos" else "Intento ${min(times.size + 1, TRIES)} de $TRIES"
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.34f, right - left, C.TEXT2)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        if (!blocked) {
            if (state == WAIT && t >= goAt) { state = GO; Sound.play(Sound.GO) }
            if (state == SHOW && t >= nextAt) {
                if (gameOver()) end() else newRound()
            }
        }
        val top = barH
        val cy = (top + h) / 2
        val rad = (h - top) * 0.3f
        if (two) {
            for (i in 0..1) {
                val l = if (i == 0) 0f else w / 2; val r = if (i == 0) w / 2 else w
                val fl = (1 - (t - flashT[i]) / 400f).coerceIn(0f, 1f)
                G.rr(c, l, top, r, h, 0f, C.mix(C.alpha(C.PLAYER[i], 1f), C.BG, 0.9f - fl * 0.35f))
                val sx = if (i == 0) w * 0.17f else w * 0.83f
                G.textFit(c, name(i), sx, top + (h - top) * 0.2f, barH * 0.5f, w * 0.26f, C.PLAYER[i], font = Fonts.black)
                G.text(c, score[i].toString(), sx, top + (h - top) * 0.55f, (h - top) * 0.34f, C.WHITE, font = Fonts.black)
                G.text(c, "toca aquí", sx, top + (h - top) * 0.85f, barH * 0.3f, C.alpha(C.WHITE, 0.45f))
            }
        } else {
            G.text(c, "Toca en cualquier parte", w * 0.16f, top + (h - top) * 0.15f, barH * 0.28f, C.TEXT2)
            for ((j, ms) in times.withIndex()) {
                G.text(c, "${j + 1}.  $ms ms", w * 0.16f, top + (h - top) * (0.3f + j * 0.12f), barH * 0.36f, C.WHITE)
            }
            if (Store.has("reflejos_ms")) {
                G.text(c, "Récord", w * 0.84f, top + (h - top) * 0.4f, barH * 0.3f, C.TEXT2)
                G.text(c, "${Store.long("reflejos_ms")} ms", w * 0.84f, top + (h - top) * 0.55f, barH * 0.5f, C.AMBER, font = Fonts.black)
            }
        }
        // Círculo central
        when (state) {
            WAIT -> {
                G.circle(c, w / 2, cy, rad, C.PANEL)
                c.drawCircle(w / 2, cy, rad, G.stroke(C.RED, rad * 0.08f))
                G.text(c, "Espera...", w / 2, cy, rad * 0.32f, C.WHITE, font = Fonts.black)
                if (round == 1) G.text(c, if (two) "Toquen su lado cuando se ponga verde" else "Toca cuando se ponga verde",
                    w / 2, cy + rad * 1.18f, barH * 0.3f, C.TEXT2)
            }
            GO -> {
                val k = G.back(((t - goAt) / 180f))
                G.circle(c, w / 2, cy, rad * (0.85f + 0.15f * k), C.GREEN)
                G.text(c, "¡YA!", w / 2, cy, rad * 0.5f, C.DARK, font = Fonts.black)
            }
            else -> {
                G.circle(c, w / 2, cy, rad, C.PANEL)
                c.drawCircle(w / 2, cy, rad, G.stroke(msgColor, rad * 0.06f))
                G.lines(c, msg, w / 2, cy, rad * 0.26f, rad * 1.6f, msgColor, font = Fonts.black)
            }
        }
    }

    private fun gameOver() = if (two) score.any { it >= TARGET } else times.size >= TRIES

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || y < barH) return
        val p = if (two) (if (x < w / 2) 0 else 1) else 0
        val t = gt()
        when (state) {
            WAIT -> {
                flashT[p] = t
                Sound.play(Sound.BAD)
                if (two) {
                    score[1 - p]++
                    msg = "¡Muy pronto,\n${name(p)}!\nPunto para\n${name(1 - p)}"
                } else msg = "¡Muy pronto!\nEspera el verde"
                msgColor = C.RED
                state = SHOW; nextAt = t + 1800
            }
            GO -> {
                val ms = t - goAt
                flashT[p] = t
                Sound.play(Sound.GOOD)
                if (two) {
                    score[p]++
                    msg = "${name(p)}\n$ms ms"
                    msgColor = C.PLAYER[p]
                } else {
                    times.add(ms)
                    msg = "$ms ms"
                    msgColor = C.AMBER
                }
                state = SHOW; nextAt = t + 1500
            }
        }
    }

    private fun end() {
        state = SHOW
        if (two) {
            val wi = if (score[0] >= TARGET) 0 else 1
            finish(Result(wi, "¡Ganó ${name(wi)}!", "${name(0)}: ${score[0]}   ${name(1)}: ${score[1]}"), 200)
        } else {
            val best = times.minOrNull() ?: 0
            val avg = times.sum() / times.size.coerceAtLeast(1)
            val nb = Store.best("reflejos_ms", best, true)
            val na = Store.best("reflejos_prom", avg, true)
            finish(Result(Result.SOLO, "$avg ms de promedio", "Más rápido: $best ms" + if (nb || na) "\n¡Nuevo récord!" else ""), 200)
        }
    }
}

/** Animalitos originales para Golpea al topo. kind: 0 normal, 1 dorado, 2 erizo. */
object Critters {
    fun draw(c: Canvas, kind: Int, cx: Float, cy: Float, s: Float, hit: Boolean, t: Long) {
        val body = when (kind) { 1 -> 0xFFFFC94A.toInt(); 2 -> 0xFF7D8590.toInt(); else -> 0xFFA0673F.toInt() }
        val belly = when (kind) { 1 -> 0xFFFFE9A8.toInt(); 2 -> 0xFFC9CED6.toInt(); else -> 0xFFE3B98F.toInt() }
        if (kind == 2) {
            // Púas
            G.path.reset()
            val n = 9
            for (i in 0..n) {
                val a = Math.PI + Math.PI * i / n
                val rOut = if (i % 2 == 0) s * 1.25f else s * 0.95f
                val x = cx + rOut * cos(a).toFloat(); val y = cy + rOut * sin(a).toFloat() * 1.05f
                if (i == 0) G.path.moveTo(x, y) else G.path.lineTo(x, y)
            }
            G.path.close()
            c.drawPath(G.path, G.fill(0xFF4A515B.toInt()))
        } else {
            G.circle(c, cx - s * 0.62f, cy - s * 0.72f, s * 0.24f, body)
            G.circle(c, cx + s * 0.62f, cy - s * 0.72f, s * 0.24f, body)
            G.circle(c, cx - s * 0.62f, cy - s * 0.72f, s * 0.12f, belly)
            G.circle(c, cx + s * 0.62f, cy - s * 0.72f, s * 0.12f, belly)
        }
        G.rr(c, cx - s, cy - s, cx + s, cy + s * 1.6f, s, body)
        G.oval(c, cx, cy + s * 0.55f, s * 0.6f, s * 0.6f, belly)
        if (hit) {
            val p = G.stroke(C.DARK, s * 0.12f)
            for (ex in floatArrayOf(-0.38f, 0.38f)) {
                val x = cx + s * ex; val y = cy - s * 0.2f; val d = s * 0.14f
                c.drawLine(x - d, y - d, x + d, y + d, p); c.drawLine(x - d, y + d, x + d, y - d, p)
            }
            for (i in 0 until 3) {
                val a = t / 160.0 + i * 2.1
                Icons.star(c, cx + s * 0.9f * cos(a).toFloat(), cy - s * 1.15f + s * 0.25f * sin(a).toFloat(), s * 0.2f, 0xFFFFE066.toInt())
            }
        } else {
            for (ex in floatArrayOf(-0.38f, 0.38f)) {
                G.circle(c, cx + s * ex, cy - s * 0.2f, s * 0.17f, C.WHITE)
                G.circle(c, cx + s * ex + s * 0.03f, cy - s * 0.17f, s * 0.1f, C.DARK)
                G.circle(c, cx + s * ex + s * 0.06f, cy - s * 0.22f, s * 0.035f, C.WHITE)
            }
            if (kind == 2) {
                val p = G.stroke(C.DARK, s * 0.09f)
                c.drawLine(cx - s * 0.58f, cy - s * 0.5f, cx - s * 0.22f, cy - s * 0.38f, p)
                c.drawLine(cx + s * 0.58f, cy - s * 0.5f, cx + s * 0.22f, cy - s * 0.38f, p)
            }
        }
        G.oval(c, cx, cy + s * 0.12f, s * 0.17f, s * 0.12f, if (kind == 2) C.DARK else 0xFFFF8FA3.toInt())
        if (kind == 0 || kind == 1) {
            val p = G.stroke(C.alpha(C.DARK, 0.5f), s * 0.05f)
            for (sgn in floatArrayOf(-1f, 1f)) {
                c.drawLine(cx + sgn * s * 0.3f, cy + s * 0.18f, cx + sgn * s * 0.75f, cy + s * 0.1f, p)
                c.drawLine(cx + sgn * s * 0.3f, cy + s * 0.28f, cx + sgn * s * 0.75f, cy + s * 0.32f, p)
            }
        }
        if (kind == 1) {
            val k = (sin(t / 90.0) * 0.5 + 0.5).toFloat()
            Icons.star(c, cx + s * 0.95f, cy - s * 0.95f, s * (0.18f + 0.1f * k), C.WHITE)
        }
    }
}

class TopoScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val DURATION = 45_000L
    private val START = 3_000L
    private val speed = when (m.level) { 0 -> 0.78f; 2 -> 1.3f; else -> 1f }
    private val two = m.players == 2
    private val cols = if (two) 3 else 5
    private val rows = 3

    private class Mole(val hole: Int, val kind: Int, val t0: Long, val up: Long) { var hitT = -1L }
    private class Popup(val x: Float, val y: Float, val text: String, val color: Int, val t: Long)
    private inner class Field(seed: Long, val player: Int) {
        val rnd = Random(seed)
        val moles = ArrayList<Mole>()
        val popups = ArrayList<Popup>()
        var next = START + 300
        var score = 0
        val rect = RectF()
    }

    private var fields = makeFields()
    private var ended = false

    private fun makeFields(): List<Field> {
        val seed = rnd.nextLong()
        return if (two) listOf(Field(seed, 0), Field(seed, 1)) else listOf(Field(seed, 0))
    }

    init { restart() }

    override fun restart() {
        fields = makeFields(); ended = false
        resetClock(); layoutFields()
    }

    override fun size(w: Float, h: Float) { super.size(w, h); layoutFields() }

    private fun layoutFields() {
        if (w == 0f) return
        val top = barH + pad
        if (two) {
            fields[0].rect.set(pad, top, w / 2 - pad / 2, h - pad)
            fields[1].rect.set(w / 2 + pad / 2, top, w - pad, h - pad)
        } else fields[0].rect.set(pad * 2, top, w - pad * 2, h - pad)
    }

    private fun cell(f: Field, hole: Int, out: RectF): RectF {
        val r = f.rect
        val head = if (two) r.height() * 0.16f else 0f
        val cw = r.width() / cols; val ch = (r.height() - head) / rows
        val col = hole % cols; val row = hole / cols
        out.set(r.left + col * cw, r.top + head + row * ch, r.left + (col + 1) * cw, r.top + head + (row + 1) * ch)
        return out
    }

    private fun timeLeft() = (DURATION - (gt() - START)).coerceIn(0, DURATION)

    private fun update(f: Field, t: Long) {
        val end = START + DURATION
        while (f.next <= t && f.next < end) {
            val prog = ((f.next - START).toFloat() / DURATION).coerceIn(0f, 1f)
            val interval = (950 - 520 * prog) / speed
            val up = ((1300 - 620 * prog) / speed).toLong()
            val busy = f.moles.filter { f.next < it.t0 + it.up + 200 }.map { it.hole }.toSet()
            val freeHoles = (0 until cols * rows).filter { it !in busy }
            val r = f.rnd.nextFloat()
            val pick = f.rnd.nextInt(cols * rows)
            if (freeHoles.isNotEmpty()) {
                val hole = freeHoles[pick % freeHoles.size]
                val kind = when {
                    r < 0.07f -> 1
                    r < 0.22f && prog > 0.08f -> 2
                    else -> 0
                }
                f.moles.add(Mole(hole, kind, f.next, if (kind == 1) (up * 0.7f).toLong() else up))
            }
            f.next += (interval * (0.7f + 0.6f * f.rnd.nextFloat())).toLong()
        }
        f.moles.removeAll { t > it.t0 + it.up + 300 || (it.hitT >= 0 && t > it.hitT + 600) }
        f.popups.removeAll { t > it.t + 700 }
    }

    /** Cuánto ha salido el animalito (0..1). */
    private fun rise(mo: Mole, t: Long): Float {
        val a = t - mo.t0
        if (mo.hitT >= 0) {
            val k = (t - mo.hitT) / 450f
            return if (k < 0.5f) 1f else (1 - (k - 0.5f) * 2).coerceIn(0f, 1f)
        }
        return when {
            a < 0 -> 0f
            a < 140 -> G.ease(a / 140f)
            a < mo.up -> 1f
            else -> (1 - (a - mo.up) / 160f).coerceIn(0f, 1f)
        }
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val tl = timeLeft()
        val txt = if (two) "Tiempo ${G.time(tl)}" else "Puntos ${fields[0].score}      Tiempo ${G.time(tl)}"
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.38f, right - left, if (tl in 1..5000) C.RED else C.WHITE, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        val r = RectF()
        for (f in fields) {
            if (!blocked) update(f, t)
            G.rr(c, f.rect, barH * 0.25f, 0xFF27312A.toInt())
            if (two) {
                val col = C.PLAYER[f.player]
                val head = f.rect.height() * 0.16f
                G.textFit(c, "${name(f.player)}   ${f.score}", f.rect.centerX(), f.rect.top + head * 0.55f, head * 0.6f, f.rect.width() * 0.9f, col, font = Fonts.black)
            }
            for (hole in 0 until cols * rows) {
                cell(f, hole, r)
                val hw = min(r.width() * 0.36f, r.height() * 0.55f)
                val hy = r.top + r.height() * 0.86f
                G.oval(c, r.centerX(), hy, hw, hw * 0.3f, 0xFF12171C.toInt())
                val mo = f.moles.lastOrNull { it.hole == hole }
                if (mo != null) {
                    val k = rise(mo, t)
                    if (k > 0f) {
                        val s = hw * 0.78f
                        val cy = hy + s * 1.9f - k * s * 2.6f
                        c.save()
                        c.clipRect(r.left, r.top - r.height(), r.right, hy)
                        Critters.draw(c, mo.kind, r.centerX(), cy, s, mo.hitT >= 0, t)
                        c.restore()
                    }
                }
                // Borde delantero del hueco
                G.r.set(r.centerX() - hw, hy - hw * 0.3f, r.centerX() + hw, hy + hw * 0.3f)
                c.drawArc(G.r, 0f, 180f, false, G.stroke(0xFF3C4A3F.toInt(), hw * 0.12f))
            }
            for (p in f.popups) {
                val k = (t - p.t) / 700f
                G.text(c, p.text, p.x, p.y - k * barH, barH * 0.55f, C.alpha(p.color, 1 - k), font = Fonts.black)
            }
        }
        if (countdown(c, t, w / 2, (barH + h) / 2, (h - barH) * 0.3f, START)) return
        if (!ended && !blocked && t >= START + DURATION) end()
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || ended) return
        val t = gt()
        if (t < START) return
        val f = fields.firstOrNull { it.rect.contains(x, y) } ?: return
        val r = RectF()
        for (mo in f.moles.reversed()) {
            if (mo.hitT >= 0) continue
            val k = rise(mo, t)
            if (k < 0.35f) continue
            cell(f, mo.hole, r)
            val hw = min(r.width() * 0.36f, r.height() * 0.55f)
            val hy = r.top + r.height() * 0.86f
            val s = hw * 0.78f
            val topY = hy - k * s * 2.6f - s * 0.8f
            if (x >= r.centerX() - hw * 1.15f && x <= r.centerX() + hw * 1.15f && y >= topY - s * 0.4f && y <= hy + hw * 0.4f) {
                mo.hitT = t
                val (pts, col) = when (mo.kind) { 1 -> 3 to C.AMBER; 2 -> -2 to C.RED; else -> 1 to C.WHITE }
                f.score = (f.score + pts).coerceAtLeast(0)
                f.popups.add(Popup(r.centerX(), topY, if (pts > 0) "+$pts" else "$pts", col, t))
                Sound.play(when (mo.kind) { 1 -> Sound.GOOD; 2 -> Sound.BAD; else -> Sound.BONK })
                return
            }
        }
    }

    private fun end() {
        ended = true
        if (two) {
            val a = fields[0].score; val b = fields[1].score
            val d = "${name(0)}: $a   ${name(1)}: $b"
            when {
                a > b -> finish(Result(0, "¡Ganó ${name(0)}!", d), 600)
                b > a -> finish(Result(1, "¡Ganó ${name(1)}!", d), 600)
                else -> finish(Result(Result.DRAW, "Empate", d), 600)
            }
        } else {
            val s = fields[0].score
            val nr = s > 0 && Store.best("topo_${m.level}", s.toLong(), false)
            finish(Result(Result.SOLO, "$s puntos", if (nr) "¡Nuevo récord!" else "Récord: ${Store.long("topo_${m.level}")} puntos"), 600)
        }
    }
}
