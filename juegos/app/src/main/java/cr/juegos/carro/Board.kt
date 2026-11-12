package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.RectF
import java.util.Random
import kotlin.math.max
import kotlin.math.min

object Marks {
    fun x(c: Canvas, cx: Float, cy: Float, s: Float, k: Float, color: Int = C.AMBER) {
        val p = G.stroke(color, s * 0.16f)
        val d = s * 0.3f * G.back(k)
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p)
        c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
    }

    fun o(c: Canvas, cx: Float, cy: Float, s: Float, k: Float, color: Int = C.BLUE) {
        c.drawCircle(cx, cy, s * 0.31f * G.back(k), G.stroke(color, s * 0.14f))
    }
}

private val LINES3 = arrayOf(
    intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), intArrayOf(6, 7, 8),
    intArrayOf(0, 3, 6), intArrayOf(1, 4, 7), intArrayOf(2, 5, 8),
    intArrayOf(0, 4, 8), intArrayOf(2, 4, 6))

class TresScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val b = IntArray(9) { -1 }
    private val placedT = LongArray(9)
    private var turn = 0
    private var starter = m.starter
    private var winLine: IntArray? = null
    private var winT = 0L
    private var aiAt = 0L
    private val ai = m.players == 1

    // Medidas
    private var bx = 0f; private var by = 0f; private var bs = 0f
    private val cards = arrayOf(RectF(), RectF())

    init { restart() }

    override fun restart() {
        b.fill(-1); winLine = null; turn = starter; resetClock(); scheduleAi()
    }

    override fun replay() { starter = 1 - starter; restart() }

    override fun size(w: Float, h: Float) {
        super.size(w, h)
        val availH = h - barH - pad * 4
        bs = min(availH, w * 0.46f)
        bx = (w - bs) / 2; by = barH + (h - barH - bs) / 2
        val side = (w - bs) / 2
        val cw = min(side - pad * 4, bs * 0.8f); val ch = min(bs * 0.78f, cw * 1.1f)
        val cy = by + bs / 2
        cards[0].set(side / 2 - cw / 2, cy - ch / 2, side / 2 + cw / 2, cy + ch / 2)
        cards[1].set(w - side / 2 - cw / 2, cy - ch / 2, w - side / 2 + cw / 2, cy + ch / 2)
    }

    private fun scheduleAi() { aiAt = if (ai && turn == 1) gt() + 650 else 0 }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        if (m.tournament) G.textFit(c, "Torneo", (left + right) / 2, barH / 2, barH * 0.3f, right - left, C.TEXT2)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        if (aiAt > 0 && !blocked && t >= aiAt) { aiAt = 0; place(1, aiMove()) }

        G.rr(c, bx, by, bx + bs, by + bs, bs * 0.06f, C.PANEL)
        val cell = bs / 3
        val p = G.stroke(C.LINE, cell * 0.06f)
        for (i in 1..2) {
            c.drawLine(bx + i * cell, by + cell * 0.18f, bx + i * cell, by + bs - cell * 0.18f, p)
            c.drawLine(bx + cell * 0.18f, by + i * cell, bx + bs - cell * 0.18f, by + i * cell, p)
        }
        for (i in 0 until 9) {
            if (b[i] < 0) continue
            val cx = bx + (i % 3 + 0.5f) * cell; val cy = by + (i / 3 + 0.5f) * cell
            val k = (t - placedT[i]) / 260f
            if (b[i] == 0) Marks.x(c, cx, cy, cell, k) else Marks.o(c, cx, cy, cell, k)
        }
        winLine?.let { wl ->
            val k = G.ease((t - winT) / 350f)
            val x0 = bx + (wl[0] % 3 + 0.5f) * cell; val y0 = by + (wl[0] / 3 + 0.5f) * cell
            val x1 = bx + (wl[2] % 3 + 0.5f) * cell; val y1 = by + (wl[2] / 3 + 0.5f) * cell
            c.drawLine(x0, y0, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, G.stroke(C.WHITE, cell * 0.08f))
        }
        for (i in 0..1) {
            val active = turn == i && winLine == null && !over
            val extra = when {
                over && winLine != null && b[winLine!![0]] == i -> "¡Ganó!"
                active -> if (ai && i == 1) "Pensando..." else "Su turno"
                else -> null
            }
            playerCard(c, cards[i], i, active, name(i), extra) { x, y, s ->
                if (i == 0) Marks.x(c, x, y, s * 1.4f, 1f) else Marks.o(c, x, y, s * 1.4f, 1f)
            }
        }
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || (ai && turn == 1)) return
        if (x < bx || x > bx + bs || y < by || y > by + bs) return
        val cell = bs / 3
        val i = ((y - by) / cell).toInt().coerceIn(0, 2) * 3 + ((x - bx) / cell).toInt().coerceIn(0, 2)
        if (b[i] < 0) place(turn, i)
    }

    private fun place(p: Int, i: Int) {
        if (i < 0 || b[i] >= 0) return
        b[i] = p; placedT[i] = gt()
        Sound.play(Sound.POP)
        val wl = LINES3.firstOrNull { l -> l.all { b[it] == p } }
        when {
            wl != null -> { winLine = wl; winT = gt(); win(p) }
            b.all { it >= 0 } -> {
                if (ai) Store.inc("tres_e${m.level}")
                finish(Result(Result.DRAW, "Empate", "Nadie logró tres en raya."))
            }
            else -> { turn = 1 - turn; scheduleAi() }
        }
    }

    private fun win(p: Int) {
        if (ai) {
            Store.inc(if (p == 0) "tres_v${m.level}" else "tres_d${m.level}")
            finish(if (p == 0) Result(0, "¡Ganaste!", "Le ganaste a la app.") else Result(1, "Ganó la app", "¡Inténtalo otra vez!"))
        } else finish(Result(p, "¡Ganó ${name(p)}!", "Tres en raya."))
    }

    // ---- Inteligencia de la app ----
    private fun free() = (0 until 9).filter { b[it] < 0 }

    private fun winningMove(p: Int): Int {
        for (i in free()) { b[i] = p; val w = LINES3.any { l -> l.all { b[it] == p } }; b[i] = -1; if (w) return i }
        return -1
    }

    private fun smart(): Int {
        winningMove(1).let { if (it >= 0) return it }
        winningMove(0).let { if (it >= 0) return it }
        if (b[4] < 0) return 4
        val corners = listOf(0, 2, 6, 8).filter { b[it] < 0 }
        if (corners.isNotEmpty()) return corners[rnd.nextInt(corners.size)]
        val f = free(); return f[rnd.nextInt(f.size)]
    }

    private fun minimax(p: Int, depth: Int): Int {
        LINES3.firstOrNull { l -> b[l[0]] >= 0 && b[l[0]] == b[l[1]] && b[l[1]] == b[l[2]] }?.let {
            return if (b[it[0]] == 1) 10 - depth else depth - 10
        }
        val f = free(); if (f.isEmpty()) return 0
        var best = if (p == 1) -100 else 100
        for (i in f) {
            b[i] = p
            val v = minimax(1 - p, depth + 1)
            b[i] = -1
            best = if (p == 1) max(best, v) else min(best, v)
        }
        return best
    }

    private fun perfect(): Int {
        var best = -1000; val opts = ArrayList<Int>()
        for (i in free()) {
            b[i] = 1; val v = minimax(0, 1); b[i] = -1
            if (v > best) { best = v; opts.clear() }
            if (v == best) opts.add(i)
        }
        return opts[rnd.nextInt(opts.size)]
    }

    private fun aiMove(): Int {
        val f = free()
        return when (m.level) {
            0 -> if (rnd.nextFloat() < 0.35f) smart() else f[rnd.nextInt(f.size)]
            1 -> if (rnd.nextFloat() < 0.8f) smart() else f[rnd.nextInt(f.size)]
            else -> perfect()
        }
    }
}

class CuatroScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val COLS = 7; private val ROWS = 6
    private val g = IntArray(COLS * ROWS) { -1 } // índice col*ROWS + fila (0 = abajo)
    private var turn = 0
    private var starter = m.starter
    private var dropCol = -1; private var dropRow = 0; private var dropP = 0; private var dropT = 0L
    private var thinking = false
    private var gen = 0
    private var winCells: List<Int>? = null
    private var lastIdx = -1
    private val ai = m.players == 1

    private var bx = 0f; private var by = 0f; private var cell = 0f
    private val cards = arrayOf(RectF(), RectF())

    init { restart() }

    override fun restart() {
        g.fill(-1); turn = starter; dropCol = -1; thinking = false; gen++; winCells = null; lastIdx = -1
        resetClock(); maybeAi()
    }

    override fun replay() { starter = 1 - starter; restart() }

    override fun size(w: Float, h: Float) {
        super.size(w, h)
        val availH = h - barH - pad * 4
        cell = min(availH / (ROWS + 0.35f), w * 0.56f / COLS)
        val bw = cell * COLS; val bh = cell * ROWS
        bx = (w - bw) / 2; by = barH + (h - barH - bh) / 2 + cell * 0.15f
        val side = (w - bw) / 2
        val cw = min(side - pad * 4, bh * 0.75f); val ch = min(bh * 0.75f, cw * 1.15f)
        val cy = by + bh / 2
        cards[0].set(side / 2 - cw / 2, cy - ch / 2, side / 2 + cw / 2, cy + ch / 2)
        cards[1].set(w - side / 2 - cw / 2, cy - ch / 2, w - side / 2 + cw / 2, cy + ch / 2)
    }

    private fun height(col: Int): Int { var r = 0; while (r < ROWS && g[col * ROWS + r] >= 0) r++; return r }

    private fun maybeAi() {
        if (!ai || turn != 1 || over) return
        thinking = true
        val myGen = gen
        val snap = g.copyOf()
        val lvl = m.level
        val seed = rnd.nextLong()
        val t0 = System.currentTimeMillis()
        Thread {
            val col = Ai4.best(snap, lvl, Random(seed))
            val wait = max(0L, 600 - (System.currentTimeMillis() - t0))
            stage.postDelayed({
                if (myGen == gen) { thinking = false; startDrop(col) }
            }, wait)
        }.start()
    }

    private fun startDrop(col: Int) {
        val r = height(col)
        if (r >= ROWS) return
        dropCol = col; dropRow = r; dropP = turn; dropT = gt()
    }

    private fun dropDur() = 140L + 50L * (ROWS - dropRow)

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        if (m.tournament) G.textFit(c, "Torneo", (left + right) / 2, barH / 2, barH * 0.3f, right - left, C.TEXT2)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        // Termina la caída
        if (dropCol >= 0 && t - dropT >= dropDur()) {
            val idx = dropCol * ROWS + dropRow
            g[idx] = dropP; lastIdx = idx
            dropCol = -1
            Sound.play(Sound.DROP)
            afterMove(dropP, idx)
        }
        val bw = cell * COLS; val bh = cell * ROWS
        // Ficha cayendo (detrás del tablero no hace falta: se dibuja encima con agujeros)
        G.rr(c, bx - cell * 0.12f, by - cell * 0.12f, bx + bw + cell * 0.12f, by + bh + cell * 0.12f, cell * 0.3f, C.CARD)
        for (col in 0 until COLS) for (r in 0 until ROWS) {
            val cx = bx + (col + 0.5f) * cell; val cy = by + bh - (r + 0.5f) * cell
            val v = g[col * ROWS + r]
            G.circle(c, cx, cy, cell * 0.4f, if (v < 0) C.BG else C.PLAYER[v])
            if (v >= 0) G.circle(c, cx, cy, cell * 0.27f, C.mix(C.PLAYER[v], C.WHITE, 0.18f))
        }
        if (dropCol >= 0) {
            val k = ((t - dropT).toFloat() / dropDur()).coerceIn(0f, 1f)
            val y0 = by - cell * 0.5f
            val y1 = by + bh - (dropRow + 0.5f) * cell
            val cy = y0 + (y1 - y0) * k * k
            val cx = bx + (dropCol + 0.5f) * cell
            G.circle(c, cx, cy, cell * 0.4f, C.PLAYER[dropP])
            G.circle(c, cx, cy, cell * 0.27f, C.mix(C.PLAYER[dropP], C.WHITE, 0.18f))
        }
        winCells?.let { cells ->
            val pulse = (kotlin.math.sin(t / 120.0) * 0.5 + 0.5).toFloat()
            for (idx in cells) {
                val cx = bx + (idx / ROWS + 0.5f) * cell; val cy = by + bh - (idx % ROWS + 0.5f) * cell
                c.drawCircle(cx, cy, cell * 0.43f, G.stroke(C.alpha(C.WHITE, 0.5f + 0.5f * pulse), cell * 0.08f))
            }
        }
        if (lastIdx >= 0 && winCells == null) {
            val cx = bx + (lastIdx / ROWS + 0.5f) * cell; val cy = by + bh - (lastIdx % ROWS + 0.5f) * cell
            G.circle(c, cx, cy, cell * 0.07f, C.alpha(C.DARK, 0.6f))
        }
        for (i in 0..1) {
            val active = turn == i && !over
            val extra = when {
                over && winCells != null && g[winCells!![0]] == i -> "¡Ganó!"
                active -> if (ai && i == 1) "Pensando..." else "Su turno"
                else -> null
            }
            playerCard(c, cards[i], i, active, name(i), extra) { x, y, s ->
                G.circle(c, x, y, s * 0.55f, C.PLAYER[i]); G.circle(c, x, y, s * 0.37f, C.mix(C.PLAYER[i], C.WHITE, 0.18f))
            }
        }
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || dropCol >= 0 || thinking || (ai && turn == 1)) return
        val bw = cell * COLS
        if (x < bx - cell * 0.3f || x > bx + bw + cell * 0.3f || y < barH) return
        val col = ((x - bx) / cell).toInt().coerceIn(0, COLS - 1)
        if (height(col) >= ROWS) { Sound.play(Sound.BAD); return }
        startDrop(col)
    }

    private fun afterMove(p: Int, idx: Int) {
        val win = Ai4.winLine(g, idx / ROWS, idx % ROWS, p)
        when {
            win != null -> {
                winCells = win
                if (ai) {
                    Store.inc(if (p == 0) "cuatro_v${m.level}" else "cuatro_d${m.level}")
                    finish(if (p == 0) Result(0, "¡Ganaste!", "Le ganaste a la app.") else Result(1, "Ganó la app", "¡Inténtalo otra vez!"), 1300)
                } else finish(Result(p, "¡Ganó ${name(p)}!", "Cuatro en línea."), 1300)
            }
            g.all { it >= 0 } -> {
                if (ai) Store.inc("cuatro_e${m.level}")
                finish(Result(Result.DRAW, "Empate", "El tablero se llenó."))
            }
            else -> { turn = 1 - turn; maybeAi() }
        }
    }
}

/** Búsqueda para Cuatro en línea. */
object Ai4 {
    private const val C7 = 7
    private const val R6 = 6
    private val ORDER = intArrayOf(3, 2, 4, 1, 5, 0, 6)

    private fun at(b: IntArray, c: Int, r: Int) = if (c in 0 until C7 && r in 0 until R6) b[c * R6 + r] else -2

    fun winLine(b: IntArray, c: Int, r: Int, p: Int): List<Int>? {
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
        for (d in dirs) {
            val cells = arrayListOf(c * R6 + r)
            var k = 1
            while (at(b, c + d[0] * k, r + d[1] * k) == p) { cells.add((c + d[0] * k) * R6 + r + d[1] * k); k++ }
            k = 1
            while (at(b, c - d[0] * k, r - d[1] * k) == p) { cells.add((c - d[0] * k) * R6 + r - d[1] * k); k++ }
            if (cells.size >= 4) return cells
        }
        return null
    }

    private fun wins(b: IntArray, c: Int, r: Int, p: Int): Boolean {
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
        for (d in dirs) {
            var n = 1; var k = 1
            while (at(b, c + d[0] * k, r + d[1] * k) == p) { n++; k++ }
            k = 1
            while (at(b, c - d[0] * k, r - d[1] * k) == p) { n++; k++ }
            if (n >= 4) return true
        }
        return false
    }

    private fun heights(b: IntArray) = IntArray(C7) { c -> var r = 0; while (r < R6 && b[c * R6 + r] >= 0) r++; r }

    private fun eval(b: IntArray, p: Int): Int {
        var score = 0
        for (r in 0 until R6) { if (b[3 * R6 + r] == p) score += 3 else if (b[3 * R6 + r] == 1 - p) score -= 3 }
        val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(1, -1))
        for (c in 0 until C7) for (r in 0 until R6) for (d in dirs) {
            val ec = c + d[0] * 3; val er = r + d[1] * 3
            if (ec !in 0 until C7 || er !in 0 until R6) continue
            var mine = 0; var theirs = 0
            for (k in 0..3) {
                val v = b[(c + d[0] * k) * R6 + r + d[1] * k]
                if (v == p) mine++ else if (v == 1 - p) theirs++
            }
            if (theirs == 0) score += when (mine) { 3 -> 6; 2 -> 2; else -> 0 }
            else if (mine == 0) score -= when (theirs) { 3 -> 8; 2 -> 2; else -> 0 }
        }
        return score
    }

    private fun negamax(b: IntArray, h: IntArray, depth: Int, alpha0: Int, beta: Int, p: Int): Int {
        var alpha = alpha0
        var any = false
        var best = -1_000_000
        for (c in ORDER) {
            if (h[c] >= R6) continue
            any = true
            val r = h[c]
            b[c * R6 + r] = p; h[c]++
            val v = if (wins(b, c, r, p)) 100_000 + depth
            else if (depth == 0) eval(b, p)
            else -negamax(b, h, depth - 1, -beta, -alpha, 1 - p)
            b[c * R6 + r] = -1; h[c]--
            if (v > best) best = v
            if (best > alpha) alpha = best
            if (alpha >= beta) break
        }
        return if (any) best else 0
    }

    private fun immediate(b: IntArray, h: IntArray, p: Int): Int {
        for (c in ORDER) if (h[c] < R6) {
            val r = h[c]; b[c * R6 + r] = p
            val w = wins(b, c, r, p); b[c * R6 + r] = -1
            if (w) return c
        }
        return -1
    }

    fun best(b: IntArray, level: Int, rnd: Random): Int {
        val h = heights(b)
        val valid = (0 until C7).filter { h[it] < R6 }
        if (valid.isEmpty()) return 0
        val me = 1
        if (level == 0) {
            if (rnd.nextFloat() < 0.6f) { immediate(b, h, me).let { if (it >= 0) return it } }
            if (rnd.nextFloat() < 0.4f) { immediate(b, h, 1 - me).let { if (it >= 0) return it } }
            return valid[rnd.nextInt(valid.size)]
        }
        val depth = if (level == 1) 2 else 6
        var bestV = Int.MIN_VALUE
        val opts = ArrayList<Int>()
        for (c in ORDER) {
            if (h[c] >= R6) continue
            val r = h[c]
            b[c * R6 + r] = me; h[c]++
            var v = if (wins(b, c, r, me)) 1_000_000 else -negamax(b, h, depth - 1, -1_000_000_0, 1_000_000_0, 1 - me)
            b[c * R6 + r] = -1; h[c]--
            if (level == 1) v += rnd.nextInt(7)
            if (v > bestV) { bestV = v; opts.clear() }
            if (v == bestV) opts.add(c)
        }
        return opts[rnd.nextInt(opts.size)]
    }
}
