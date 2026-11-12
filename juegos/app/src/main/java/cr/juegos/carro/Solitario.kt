package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Dibujo de cartas: palos 0 picas, 1 corazones, 2 diamantes, 3 tréboles. */
object Cards {
    const val RED = 0xFFD63B3B.toInt()
    const val BLACK = 0xFF1E232A.toInt()
    const val PAPER = 0xFFFBFAF7.toInt()
    private val RANKS = arrayOf("", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")
    private val suits: Array<Path> = Array(4) { buildSuit(it) }

    fun rank(r: Int) = RANKS[r]
    fun isRed(suit: Int) = suit == 1 || suit == 2

    private fun buildSuit(s: Int): Path {
        val p = Path()
        when (s) {
            1 -> {
                p.moveTo(0f, 0.42f)
                p.cubicTo(-0.62f, 0.02f, -0.48f, -0.52f, 0f, -0.2f)
                p.cubicTo(0.48f, -0.52f, 0.62f, 0.02f, 0f, 0.42f)
                p.close()
            }
            2 -> {
                p.moveTo(0f, -0.48f); p.lineTo(0.36f, 0f); p.lineTo(0f, 0.48f); p.lineTo(-0.36f, 0f); p.close()
            }
            0 -> {
                p.moveTo(0f, -0.46f)
                p.cubicTo(0.6f, -0.02f, 0.46f, 0.42f, 0.03f, 0.2f)
                p.lineTo(0.16f, 0.46f); p.lineTo(-0.16f, 0.46f); p.lineTo(-0.03f, 0.2f)
                p.cubicTo(-0.46f, 0.42f, -0.6f, -0.02f, 0f, -0.46f)
                p.close()
            }
            else -> {
                p.addCircle(0f, -0.22f, 0.2f, Path.Direction.CW)
                p.addCircle(-0.22f, 0.08f, 0.2f, Path.Direction.CW)
                p.addCircle(0.22f, 0.08f, 0.2f, Path.Direction.CW)
                val st = Path()
                st.moveTo(-0.05f, 0f); st.lineTo(0.05f, 0f); st.lineTo(0.17f, 0.46f); st.lineTo(-0.17f, 0.46f); st.close()
                p.addPath(st)
            }
        }
        return p
    }

    fun suit(c: Canvas, s: Int, cx: Float, cy: Float, size: Float, color: Int = if (isRed(s)) RED else BLACK) {
        c.save(); c.translate(cx, cy); c.scale(size, size)
        c.drawPath(suits[s], G.fill(color))
        c.restore()
    }

    /** Carta boca arriba con su esquina superior izquierda en (x, y). */
    fun face(c: Canvas, x: Float, y: Float, cw: Float, ch: Float, suit: Int, rank: Int, dim: Boolean = false) {
        val rad = cw * 0.09f
        G.rr(c, x, y, x + cw, y + ch, rad, if (dim) 0xFFDCD8D0.toInt() else PAPER)
        val col = if (isRed(suit)) RED else BLACK
        val ts = ch * 0.21f
        val label = rank(rank)
        val lw = G.width(label, ts, Fonts.black)
        val fit = min(1f, (cw * 0.58f) / lw)
        G.text(c, label, x + cw * 0.07f, y + ch * 0.12f, ts * fit, col, android.graphics.Paint.Align.LEFT, Fonts.black)
        suit(c, suit, x + cw * 0.8f, y + ch * 0.12f, ch * 0.15f, col)
        if (rank >= 11) {
            G.text(c, label, x + cw / 2, y + ch * 0.6f, ch * 0.4f, C.alpha(col, 0.9f), font = Fonts.black)
            suit(c, suit, x + cw * 0.78f, y + ch * 0.86f, ch * 0.14f, col)
        } else suit(c, suit, x + cw / 2, y + ch * 0.6f, ch * 0.42f, col)
    }

    fun back(c: Canvas, x: Float, y: Float, cw: Float, ch: Float) {
        val rad = cw * 0.09f
        G.rr(c, x, y, x + cw, y + ch, rad, 0xFF3A4350.toInt())
        val i = cw * 0.08f
        G.r.set(x + i, y + i, x + cw - i, y + ch - i)
        c.drawRoundRect(G.r, rad * 0.6f, rad * 0.6f, G.stroke(C.AMBER_DARK, cw * 0.03f))
        G.path.reset()
        G.path.moveTo(x + cw / 2, y + ch * 0.28f); G.path.lineTo(x + cw * 0.72f, y + ch / 2)
        G.path.lineTo(x + cw / 2, y + ch * 0.72f); G.path.lineTo(x + cw * 0.28f, y + ch / 2); G.path.close()
        c.drawPath(G.path, G.fill(C.alpha(C.AMBER, 0.45f)))
    }

    /** Carta centrada (para el menú). */
    fun drawFace(c: Canvas, cx: Float, cy: Float, ch: Float, suit: Int, rank: Int) {
        val cw = ch * 0.7f
        face(c, cx - cw / 2, cy - ch / 2, cw, ch, suit, rank)
    }
}

class SolitarioScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private class Card(val id: Int) {
        val suit get() = id / 13
        val rank get() = id % 13 + 1
        val red get() = Cards.isRed(suit)
        var up = false
    }

    private class Snap(val piles: List<IntArray>, val ups: BooleanArray, val moves: Int)
    private class Anim(val fx: Float, val fy: Float, val start: Long)

    private val STOCK = 0
    private val WASTE = 1
    private val F0 = 2           // 2..5 bases
    private val T0 = 6           // 6..12 columnas
    private val LIMIT = 5 * 60_000L

    private val deck = Array(52) { Card(it) }
    private val piles = Array(13) { ArrayList<Card>() }
    private val undo = ArrayList<Snap>()
    private val anims = HashMap<Card, Anim>()
    private val drawN = if (m.level == 1) 3 else 1
    private val two = m.players == 2
    private var seed = 0L
    private var moves = 0
    private var won = false
    private var autoRun = false
    private var autoNext = 0L

    // Turnos en 2 jugadores
    private var turn = 0
    private var intro = false
    private val turnDone = BooleanArray(2)
    private val played = BooleanArray(2)
    private val turnTime = LongArray(2)
    private val turnCards = IntArray(2)
    private var turnStartGt = 0L

    // Selección y arrastre
    private var selPile = -1; private var selIdx = -1
    private var dragId = -1
    private var dragPile = -1; private var dragIdx = -1
    private var downX = 0f; private var downY = 0f; private var curX = 0f; private var curY = 0f
    private var dragging = false
    private var grabDx = 0f; private var grabDy = 0f

    // Medidas
    private var cw = 0f; private var ch = 0f; private var slot = 0f; private var left = 0f; private var top = 0f; private var bottom = 0f; private var gapY = 0f

    init { restart() }

    override fun restart() {
        seed = rnd.nextLong()
        turn = m.starter; turnDone.fill(false); played.fill(false); turnTime.fill(0); turnCards.fill(0)
        deal()
        resetClock()
        intro = two
        turnStartGt = 0
    }

    private fun deal() {
        val order = (0 until 52).shuffled(Random(seed))
        for (p in piles) p.clear()
        for (cd in deck) cd.up = false
        var k = 0
        for (col in 0 until 7) for (r in 0..col) {
            val cd = deck[order[k++]]; cd.up = r == col; piles[T0 + col].add(cd)
        }
        while (k < 52) piles[STOCK].add(deck[order[k++]])
        undo.clear(); anims.clear(); moves = 0; won = false; autoRun = false
        selPile = -1; dragId = -1; dragging = false
    }

    override fun size(w: Float, h: Float) {
        super.size(w, h)
        top = barH + pad
        bottom = h - pad
        val availH = bottom - top
        val maxW = w - pad * 2
        cw = min(maxW / 10.4f * 0.9f, availH * 0.39f / 1.4f)
        ch = cw * 1.4f
        slot = cw / 0.9f
        left = (w - slot * 10.4f) / 2
        gapY = min(pad, (availH - 2 * ch) * 0.3f).coerceAtLeast(pad * 0.3f)
    }

    private fun colX(i: Int): Float = when {
        i == 0 -> left + (slot - cw) / 2
        i in 1..7 -> left + (i + 0.2f) * slot + (slot - cw) / 2
        else -> left + (i + 0.4f) * slot + (slot - cw) / 2
    }

    private fun pileX(p: Int) = when (p) {
        STOCK, WASTE -> colX(0)
        in F0 until F0 + 4 -> colX(8 + (p - F0) % 2)
        else -> colX(1 + p - T0)
    }

    private fun pileY(p: Int) = when (p) {
        STOCK -> top
        WASTE -> top + ch + gapY
        in F0 until F0 + 4 -> if (p - F0 < 2) top else top + ch + gapY
        else -> top
    }

    /** Separación vertical de una columna: boca abajo y boca arriba. */
    private fun offsets(p: Int): Pair<Float, Float> {
        val pile = piles[p]
        val down = pile.count { !it.up }; val upN = pile.size - down
        val avail = bottom - top - ch
        var fd = ch * 0.11f
        var fu = ch * 0.27f
        val need = down * fd + max(0, upN - 1) * fu
        if (need > avail) {
            fd = min(fd, max(ch * 0.05f, (avail * 0.3f) / max(1, down)))
            fu = if (upN > 1) ((avail - down * fd) / (upN - 1)).coerceAtLeast(ch * 0.1f) else fu
        }
        return fd to fu
    }

    private fun cardPos(p: Int, i: Int): Pair<Float, Float> {
        val x = pileX(p); val y = pileY(p)
        return when {
            p >= T0 -> {
                val (fd, fu) = offsets(p)
                var yy = y
                for (k in 0 until i) yy += if (piles[p][k].up) fu else fd
                x to yy
            }
            p == WASTE -> {
                val n = piles[p].size
                val vis = min(drawN, n)
                val k = i - (n - vis)
                x to (y + if (k > 0) k * ch * 0.2f else 0f)
            }
            else -> x to y
        }
    }

    private fun snapshot() {
        undo.add(Snap(piles.map { pile -> IntArray(pile.size) { pile[it].id } }, BooleanArray(52) { deck[it].up }, moves))
        if (undo.size > 300) undo.removeAt(0)
    }

    private fun doUndo() {
        if (undo.isEmpty() || autoRun || won) return
        val s = undo.removeAt(undo.size - 1)
        for ((i, ids) in s.piles.withIndex()) { piles[i].clear(); ids.forEach { piles[i].add(deck[it]) } }
        for (i in 0 until 52) deck[i].up = s.ups[i]
        moves = s.moves; anims.clear(); selPile = -1
    }

    private fun startAnims(cards: List<Card>) {
        val now = SystemClock.uptimeMillis()
        for (cd in cards) {
            val (p, i) = locate(cd)
            val (x, y) = currentPos(cd, p, i)
            anims[cd] = Anim(x, y, now)
        }
    }

    private fun locate(cd: Card): Pair<Int, Int> {
        for ((p, pile) in piles.withIndex()) { val i = pile.indexOf(cd); if (i >= 0) return p to i }
        return 0 to 0
    }

    private fun currentPos(cd: Card, p: Int, i: Int): Pair<Float, Float> {
        val (tx, ty) = cardPos(p, i)
        val a = anims[cd] ?: return tx to ty
        val k = G.ease((SystemClock.uptimeMillis() - a.start) / 200f)
        return (a.fx + (tx - a.fx) * k) to (a.fy + (ty - a.fy) * k)
    }

    private fun canFoundation(cd: Card, f: Int): Boolean {
        val pile = piles[f]
        return if (pile.isEmpty()) cd.rank == 1 else pile.last().suit == cd.suit && pile.last().rank == cd.rank - 1
    }

    private fun canTableau(cd: Card, t: Int): Boolean {
        val pile = piles[t]
        if (pile.isEmpty()) return cd.rank == 13
        val top = pile.last()
        return top.up && top.red != cd.red && top.rank == cd.rank + 1
    }

    private fun movable(p: Int, i: Int): Boolean {
        val pile = piles[p]
        if (i < 0 || i >= pile.size || !pile[i].up) return false
        return when {
            p == WASTE || p in F0 until F0 + 4 -> i == pile.size - 1
            p >= T0 -> true
            else -> false
        }
    }

    private fun canMove(p: Int, i: Int, dest: Int): Boolean {
        if (dest == p || !movable(p, i)) return false
        val cd = piles[p][i]
        return when {
            dest in F0 until F0 + 4 -> i == piles[p].size - 1 && canFoundation(cd, dest)
            dest >= T0 -> canTableau(cd, dest)
            else -> false
        }
    }

    private fun move(p: Int, i: Int, dest: Int) {
        snapshot()
        val moving = piles[p].subList(i, piles[p].size).toList()
        startAnims(moving)
        repeat(moving.size) { piles[p].removeAt(piles[p].size - 1) }
        piles[dest].addAll(moving)
        if (p >= T0 && piles[p].isNotEmpty() && !piles[p].last().up) piles[p].last().up = true
        moves++
        selPile = -1
        Sound.play(if (dest in F0 until F0 + 4) Sound.GOOD else Sound.POP)
        checkWin()
    }

    private fun bestDest(p: Int, i: Int): Int {
        val cd = piles[p][i]
        if (i == piles[p].size - 1 && p !in F0 until F0 + 4) {
            for (f in F0 until F0 + 4) if (canFoundation(cd, f)) return f
        }
        // Un Rey que ya está al fondo de su columna no se mueve a otra vacía.
        val kingAtBottom = p >= T0 && i == 0 && cd.rank == 13
        for (t in T0 until T0 + 7) if (t != p && piles[t].isNotEmpty() && canTableau(cd, t)) return t
        if (!kingAtBottom) for (t in T0 until T0 + 7) if (t != p && piles[t].isEmpty() && canTableau(cd, t)) return t
        return -1
    }

    private fun tapStock() {
        snapshot()
        val st = piles[STOCK]
        if (st.isNotEmpty()) {
            val n = min(drawN, st.size)
            val drawn = ArrayList<Card>()
            repeat(n) { val cd = st.removeAt(st.size - 1); cd.up = true; drawn.add(cd) }
            startAnimsFrom(drawn, pileX(STOCK), pileY(STOCK))
            piles[WASTE].addAll(drawn)
            Sound.play(Sound.FLIP)
        } else if (piles[WASTE].isNotEmpty()) {
            val w = piles[WASTE]
            for (k in w.indices.reversed()) { w[k].up = false; st.add(w[k]) }
            w.clear()
            Sound.play(Sound.DROP)
        } else { undo.removeAt(undo.size - 1); return }
        moves++
        selPile = -1
    }

    private fun startAnimsFrom(cards: List<Card>, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (cd in cards) anims[cd] = Anim(x, y, now)
    }

    private fun foundationCount() = (F0 until F0 + 4).sumOf { piles[it].size }

    private fun canAuto() = !won && !autoRun && piles[STOCK].isEmpty() && piles[WASTE].isEmpty() &&
        (T0 until T0 + 7).all { t -> piles[t].all { it.up } }

    private fun checkWin() {
        if (foundationCount() == 52 && !won) {
            won = true; autoRun = false
            endTurn(true)
        }
    }

    private fun endTurn(completed: Boolean) {
        val elapsed = gt() - turnStartGt
        if (!two) {
            Store.inc("sol_ganadas")
            val nr = Store.best("sol_t${m.level}", elapsed, true)
            finish(Result(Result.SOLO, "¡Lo lograste!", "Tiempo: ${G.timeExact(elapsed)}\nMovimientos: $moves" + if (nr) "\n¡Nuevo récord!" else ""), 900)
            return
        }
        turnDone[turn] = completed
        played[turn] = true
        turnTime[turn] = min(elapsed, LIMIT)
        turnCards[turn] = foundationCount()
        val other = 1 - turn
        if (!played[other]) {
            // Pasa al otro jugador con la misma partida.
            turn = other
            deal()
            intro = true
            Sound.play(Sound.TICK)
        } else decide()
    }

    private fun describe(i: Int) = if (turnDone[i]) "${name(i)}: terminó en ${G.timeExact(turnTime[i])}"
        else "${name(i)}: ${turnCards[i]} cartas en ${G.timeExact(turnTime[i])}"

    private fun decide() {
        val d = describe(0) + "\n" + describe(1)
        val winner = when {
            turnDone[0] && turnDone[1] -> if (turnTime[0] == turnTime[1]) -1 else if (turnTime[0] < turnTime[1]) 0 else 1
            turnDone[0] -> 0
            turnDone[1] -> 1
            turnCards[0] != turnCards[1] -> if (turnCards[0] > turnCards[1]) 0 else 1
            else -> -1
        }
        finish(if (winner < 0) Result(Result.DRAW, "Empate", d) else Result(winner, "¡Ganó ${name(winner)}!", d), 700)
    }

    override fun drawBarButtons(c: Canvas, right: Float): Float {
        if (intro) return right
        val m2 = barH * 0.15f
        val bh = barH - 2 * m2
        var x = right
        val ur = RectF(x - bh * 1.35f, m2, x, barH - m2)
        ui.iconButton(c, ur, Btn.CARD, { cx, cy, s, col -> Icons.undo(c, cx, cy, s, col) }, enabled = undo.isNotEmpty() && !won && !autoRun) { doUndo() }
        x = ur.left - m2
        if (canAuto()) {
            val ar = RectF(x - bh * 3f, m2, x, barH - m2)
            ui.button(c, ar, "Terminar", Btn.PRIMARY) { autoRun = true; autoNext = 0 }
            x = ar.left - m2
        } else if (two && !won) {
            val ar = RectF(x - bh * 3f, m2, x, barH - m2)
            ui.button(c, ar, "Rendirse", Btn.CARD) {
                app.confirm("¿Terminar tu turno con ${foundationCount()} cartas en las bases?", "Terminar") { endTurn(false) }
            }
            x = ar.left - m2
        }
        return x
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        if (intro) return
        val elapsed = gt() - turnStartGt
        val txt = if (two) "${name(turn)}   ${G.time(LIMIT - elapsed)}   Bases ${foundationCount()}/52"
        else "${G.timeExact(elapsed)}     Movimientos $moves"
        val col = if (two) C.PLAYER[turn] else C.WHITE
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.34f, right - left, col, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        if (two && !intro && !over && !blocked && t - turnStartGt >= LIMIT) endTurn(false)
        if (autoRun && !blocked && t >= autoNext) { autoStep(); autoNext = t + 90 }

        // Huecos vacíos
        val slotCol = C.alpha(C.WHITE, 0.08f)
        for (p in 0 until 13) {
            val x = pileX(p); val y = pileY(p)
            G.r.set(x, y, x + cw, y + ch)
            c.drawRoundRect(G.r, cw * 0.09f, cw * 0.09f, G.fill(slotCol))
            if (p in F0 until F0 + 4 && piles[p].isEmpty()) G.text(c, "A", x + cw / 2, y + ch / 2, ch * 0.3f, C.alpha(C.WHITE, 0.18f), font = Fonts.black)
            if (p == STOCK && piles[p].isEmpty() && piles[WASTE].isNotEmpty()) Icons.restart(c, x + cw / 2, y + ch / 2, cw * 0.6f, C.alpha(C.WHITE, 0.4f))
        }
        // Mazo
        if (piles[STOCK].isNotEmpty()) {
            Cards.back(c, pileX(STOCK), pileY(STOCK), cw, ch)
            G.text(c, piles[STOCK].size.toString(), pileX(STOCK) + cw / 2, pileY(STOCK) + ch + gapY * 0.0f - ch * 0.08f, ch * 0.13f, C.alpha(C.WHITE, 0.7f))
        }
        val dragCards = ArrayList<Card>()
        if (dragging && dragPile >= 0) dragCards.addAll(piles[dragPile].subList(dragIdx, piles[dragPile].size))
        // Descarte, bases y columnas
        val animating = ArrayList<Pair<Card, Pair<Int, Int>>>()
        val nowMs = SystemClock.uptimeMillis()
        for (p in WASTE until 13) {
            val pile = piles[p]
            val from = when {
                p == WASTE -> max(0, pile.size - drawN - 1)
                p in F0 until F0 + 4 -> max(0, pile.size - 2)
                else -> 0
            }
            for (i in from until pile.size) {
                val cd = pile[i]
                if (cd in dragCards) continue
                val a = anims[cd]
                if (a != null && nowMs - a.start < 200) { animating.add(cd to (p to i)); continue }
                if (a != null) anims.remove(cd)
                drawCard(c, cd, p, i)
            }
        }
        for ((cd, pi) in animating) drawCard(c, cd, pi.first, pi.second)
        if (dragging) {
            for ((k, cd) in dragCards.withIndex()) {
                val x = curX - grabDx; val y = curY - grabDy + k * ch * 0.25f
                Cards.face(c, x, y, cw, ch, cd.suit, cd.rank)
            }
        }
        if (intro) drawIntro(c)
    }

    private fun drawCard(c: Canvas, cd: Card, p: Int, i: Int) {
        val (x, y) = currentPos(cd, p, i)
        if (cd.up) {
            Cards.face(c, x, y, cw, ch, cd.suit, cd.rank)
            if (selPile == p && i >= selIdx) {
                G.r.set(x, y, x + cw, y + ch)
                c.drawRoundRect(G.r, cw * 0.09f, cw * 0.09f, G.stroke(C.AMBER, cw * 0.06f))
            }
        } else Cards.back(c, x, y, cw, ch)
    }

    private fun drawIntro(c: Canvas) {
        G.rr(c, 0f, barH, w, h, 0f, C.alpha(C.BG, 0.85f))
        val col = C.PLAYER[turn]
        G.text(c, "Turno de", w / 2, h * 0.32f, barH * 0.42f, C.TEXT2)
        G.textFit(c, name(turn), w / 2, h * 0.47f, barH * 0.9f, w * 0.7f, col, font = Fonts.black)
        G.text(c, "Tienes 5 minutos. Los dos juegan la misma partida.", w / 2, h * 0.6f, barH * 0.32f, C.WHITE)
        val bw = w * 0.3f; val bh = barH * 1.0f
        ui.button(c, RectF(w / 2 - bw / 2, h * 0.7f, w / 2 + bw / 2, h * 0.7f + bh), "Empezar", Btn.PRIMARY) {
            intro = false; turnStartGt = gt()
        }
    }

    private fun autoStep() {
        // Mueve a las bases la carta más baja disponible.
        var bestP = -1; var bestF = -1; var bestRank = 99
        for (p in T0 until T0 + 7) {
            val pile = piles[p]; if (pile.isEmpty()) continue
            val cd = pile.last()
            for (f in F0 until F0 + 4) if (canFoundation(cd, f) && cd.rank < bestRank) { bestP = p; bestF = f; bestRank = cd.rank }
        }
        if (bestP < 0) { autoRun = false; return }
        move(bestP, piles[bestP].size - 1, bestF)
    }

    // ---- Toques ----
    private fun hit(x: Float, y: Float): Pair<Int, Int> {
        // Devuelve (pila, índice); índice -1 si es la pila vacía.
        for (p in 0 until 13) {
            val px = pileX(p)
            if (x < px || x > px + cw) continue
            val pile = piles[p]
            if (p >= T0) {
                if (pile.isEmpty()) { if (y >= pileY(p) && y <= pileY(p) + ch) return p to -1; continue }
                for (i in pile.indices.reversed()) {
                    val (_, cy) = cardPos(p, i)
                    if (y >= cy && y <= cy + ch) return p to i
                }
            } else {
                if (p == WASTE) {
                    if (pile.isEmpty()) { if (y >= pileY(p) && y <= pileY(p) + ch) return p to -1; continue }
                    val (_, ty) = cardPos(p, pile.size - 1)
                    if (y >= pileY(p) && y <= ty + ch) return p to (pile.size - 1)
                } else {
                    val py = pileY(p)
                    if (y >= py && y <= py + ch) return p to (pile.size - 1)
                }
            }
        }
        return -1 to -1
    }

    /** Pila de destino bajo un punto (las columnas cuentan en toda su altura). */
    private fun dropTarget(x: Float, y: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (p in F0 until 13) {
            val px = pileX(p) + cw / 2
            val py = if (p >= T0) min(y, cardPos(p, max(0, piles[p].size - 1)).second + ch / 2) else pileY(p) + ch / 2
            if (p >= T0 && y < top) continue
            val d = abs(px - x) + abs(py - y) * 0.6f
            if (abs(px - x) < slot * 0.75f && d < bestD) { bestD = d; best = p }
        }
        return best
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || intro || autoRun || won) return
        if (dragId != -1) return
        dragId = id; downX = x; downY = y; curX = x; curY = y; dragging = false
        val (p, i) = hit(x, y)
        dragPile = -1
        if (p >= 0 && i >= 0 && movable(p, i)) {
            dragPile = p; dragIdx = i
            val (cx, cy) = cardPos(p, i)
            grabDx = x - cx; grabDy = y - cy
        }
    }

    override fun touchMove(id: Int, x: Float, y: Float) {
        if (id != dragId) return
        curX = x; curY = y
        if (!dragging && dragPile >= 0 && (abs(x - downX) + abs(y - downY)) > 14 * dp) { dragging = true; selPile = -1 }
    }

    override fun touchUp(id: Int, x: Float, y: Float) {
        if (id != dragId) return
        dragId = -1
        if (blocked || intro) { dragging = false; return }
        if (dragging) {
            dragging = false
            val cx = x - grabDx + cw / 2; val cy = y - grabDy + ch * 0.3f
            val dest = dropTarget(cx, cy)
            if (dest >= 0 && canMove(dragPile, dragIdx, dest)) {
                val moving = piles[dragPile].subList(dragIdx, piles[dragPile].size).toList()
                // Así la animación sale desde donde se soltaron, no desde la pila original.
                startAnimsFrom(moving, x - grabDx, y - grabDy)
                move(dragPile, dragIdx, dest)
            } else {
                val moving = piles[dragPile].subList(dragIdx, piles[dragPile].size).toList()
                startAnimsFrom(moving, x - grabDx, y - grabDy)
                Sound.play(Sound.BAD)
            }
            return
        }
        tap(x, y)
    }

    override fun touchCancel() { dragId = -1; dragging = false }

    private fun tap(x: Float, y: Float) {
        val (p, i) = hit(x, y)
        if (p == STOCK) { tapStock(); return }
        // Hay una carta elegida: intentar ponerla aquí.
        if (selPile >= 0 && p >= 0 && p != selPile) {
            if (canMove(selPile, selIdx, p)) { move(selPile, selIdx, p); return }
        }
        if (p < 0 || i < 0) { selPile = -1; return }
        if (!movable(p, i)) { selPile = -1; return }
        if (selPile == p && selIdx == i) { selPile = -1; return }
        val dest = bestDest(p, i)
        if (dest >= 0) move(p, i, dest)
        else { selPile = p; selIdx = i; Sound.play(Sound.TAP) }
    }
}
