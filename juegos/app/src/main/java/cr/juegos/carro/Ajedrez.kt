package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ======================= Reglas =======================

object Chess {
    const val P = 1; const val N = 2; const val B = 3; const val R = 4; const val Q = 5; const val K = 6
    const val F_EP = 1; const val F_CASTLE = 2; const val F_DOUBLE = 4
    val VAL = intArrayOf(0, 100, 320, 330, 500, 900, 0)
    val KNIGHT = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2), intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
    val KING = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
    val ROOK_D = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    val BISHOP_D = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))

    fun from(m: Int) = m and 63
    fun to(m: Int) = (m shr 6) and 63
    fun promo(m: Int) = (m shr 12) and 7
    fun flag(m: Int) = (m shr 16) and 7
    fun move(from: Int, to: Int, promo: Int = 0, flag: Int = 0) = from or (to shl 6) or (promo shl 12) or (flag shl 16)
}

/** Posición: casillas 0..63 (fila*8+columna, fila 0 = la de las blancas). Piezas >0 blancas, <0 negras. */
class Pos(
    val b: IntArray = IntArray(64),
    var side: Int = 1,
    var castle: Int = 15,   // 1 corto blancas, 2 largo blancas, 4 corto negras, 8 largo negras
    var ep: Int = -1,
    var half: Int = 0,
) {
    companion object {
        fun start(): Pos {
            val p = Pos()
            val back = intArrayOf(Chess.R, Chess.N, Chess.B, Chess.Q, Chess.K, Chess.B, Chess.N, Chess.R)
            for (f in 0..7) {
                p.b[f] = back[f]; p.b[8 + f] = Chess.P
                p.b[48 + f] = -Chess.P; p.b[56 + f] = -back[f]
            }
            return p
        }
    }

    fun copy() = Pos(b.copyOf(), side, castle, ep, half)

    fun king(s: Int): Int { val k = Chess.K * s; for (i in 0..63) if (b[i] == k) return i; return -1 }

    fun attacked(sq: Int, by: Int): Boolean {
        if (sq < 0) return false
        val f = sq % 8; val r = sq / 8
        val pr = r - by
        if (pr in 0..7) {
            if (f > 0 && b[pr * 8 + f - 1] == Chess.P * by) return true
            if (f < 7 && b[pr * 8 + f + 1] == Chess.P * by) return true
        }
        for (d in Chess.KNIGHT) { val nf = f + d[0]; val nr = r + d[1]; if (nf in 0..7 && nr in 0..7 && b[nr * 8 + nf] == Chess.N * by) return true }
        for (d in Chess.KING) { val nf = f + d[0]; val nr = r + d[1]; if (nf in 0..7 && nr in 0..7 && b[nr * 8 + nf] == Chess.K * by) return true }
        for (d in Chess.ROOK_D) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val v = b[nr * 8 + nf]
                if (v != 0) { if (v == Chess.R * by || v == Chess.Q * by) return true; break }
                nf += d[0]; nr += d[1]
            }
        }
        for (d in Chess.BISHOP_D) {
            var nf = f + d[0]; var nr = r + d[1]
            while (nf in 0..7 && nr in 0..7) {
                val v = b[nr * 8 + nf]
                if (v != 0) { if (v == Chess.B * by || v == Chess.Q * by) return true; break }
                nf += d[0]; nr += d[1]
            }
        }
        return false
    }

    fun inCheck(s: Int = side) = attacked(king(s), -s)

    private fun addPawn(out: MutableList<Int>, from: Int, to: Int, flag: Int) {
        val r = to / 8
        if (r == 7 || r == 0) for (p in intArrayOf(Chess.Q, Chess.N, Chess.R, Chess.B)) out.add(Chess.move(from, to, p, flag))
        else out.add(Chess.move(from, to, 0, flag))
    }

    fun pseudo(out: MutableList<Int>, capsOnly: Boolean = false) {
        val s = side
        for (sq in 0..63) {
            val v = b[sq] * s
            if (v <= 0) continue
            val f = sq % 8; val r = sq / 8
            when (v) {
                Chess.P -> {
                    val r1 = r + s
                    if (r1 in 0..7) {
                        val promoRank = r1 == 7 || r1 == 0
                        if (b[r1 * 8 + f] == 0 && (!capsOnly || promoRank)) {
                            addPawn(out, sq, r1 * 8 + f, 0)
                            val startRank = if (s == 1) 1 else 6
                            if (!capsOnly && r == startRank && b[(r + 2 * s) * 8 + f] == 0) out.add(Chess.move(sq, (r + 2 * s) * 8 + f, 0, Chess.F_DOUBLE))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val t = r1 * 8 + nf
                            if (b[t] * s < 0) addPawn(out, sq, t, 0)
                            else if (t == ep) out.add(Chess.move(sq, t, 0, Chess.F_EP))
                        }
                    }
                }
                Chess.N, Chess.K -> {
                    for (d in if (v == Chess.N) Chess.KNIGHT else Chess.KING) {
                        val nf = f + d[0]; val nr = r + d[1]
                        if (nf !in 0..7 || nr !in 0..7) continue
                        val t = nr * 8 + nf
                        if (b[t] * s > 0) continue
                        if (capsOnly && b[t] == 0) continue
                        out.add(Chess.move(sq, t))
                    }
                    if (v == Chess.K && !capsOnly) castling(out, sq)
                }
                else -> {
                    val dirs = when (v) { Chess.B -> Chess.BISHOP_D; Chess.R -> Chess.ROOK_D; else -> Chess.ROOK_D + Chess.BISHOP_D }
                    for (d in dirs) {
                        var nf = f + d[0]; var nr = r + d[1]
                        while (nf in 0..7 && nr in 0..7) {
                            val t = nr * 8 + nf
                            if (b[t] * s > 0) break
                            if (b[t] == 0) { if (!capsOnly) out.add(Chess.move(sq, t)) }
                            else { out.add(Chess.move(sq, t)); break }
                            nf += d[0]; nr += d[1]
                        }
                    }
                }
            }
        }
    }

    private fun castling(out: MutableList<Int>, k: Int) {
        val s = side
        val base = if (s == 1) 0 else 56
        if (k != base + 4) return
        val (shortBit, longBit) = if (s == 1) 1 to 2 else 4 to 8
        if (castle and shortBit != 0 && b[base + 5] == 0 && b[base + 6] == 0 && b[base + 7] == Chess.R * s &&
            !attacked(base + 4, -s) && !attacked(base + 5, -s) && !attacked(base + 6, -s)) out.add(Chess.move(k, base + 6, 0, Chess.F_CASTLE))
        if (castle and longBit != 0 && b[base + 1] == 0 && b[base + 2] == 0 && b[base + 3] == 0 && b[base] == Chess.R * s &&
            !attacked(base + 4, -s) && !attacked(base + 3, -s) && !attacked(base + 2, -s)) out.add(Chess.move(k, base + 2, 0, Chess.F_CASTLE))
    }

    fun make(m: Int): Pos {
        val n = copy()
        val from = Chess.from(m); val to = Chess.to(m); val pr = Chess.promo(m); val fl = Chess.flag(m)
        val p = n.b[from]; val cap = n.b[to]
        n.b[to] = if (pr > 0) pr * side else p
        n.b[from] = 0
        if (fl == Chess.F_EP) n.b[to - 8 * side] = 0
        if (fl == Chess.F_CASTLE) {
            if (to > from) { n.b[from + 1] = n.b[from + 3]; n.b[from + 3] = 0 } else { n.b[from - 1] = n.b[from - 4]; n.b[from - 4] = 0 }
        }
        n.ep = if (fl == Chess.F_DOUBLE) from + 8 * side else -1
        if (abs(p) == Chess.K) n.castle = n.castle and (if (side == 1) 3 else 12).inv()
        for (sq in intArrayOf(from, to)) when (sq) { 0 -> n.castle = n.castle and 2.inv(); 7 -> n.castle = n.castle and 1.inv(); 56 -> n.castle = n.castle and 8.inv(); 63 -> n.castle = n.castle and 4.inv() }
        n.half = if (abs(p) == Chess.P || cap != 0 || fl == Chess.F_EP) 0 else half + 1
        n.side = -side
        return n
    }

    fun isLegal(m: Int): Boolean { val n = make(m); return !n.attacked(n.king(side), -side) }

    fun legal(): List<Int> { val l = ArrayList<Int>(48); pseudo(l); return l.filter { isLegal(it) } }

    fun key(): String = b.joinToString(",") + "|$side|$castle|$ep"

    /** Sin material para dar mate (rey solo, o rey y una pieza menor). */
    fun insufficient(): Boolean {
        var minors = 0
        for (v in b) when (abs(v)) { Chess.P, Chess.R, Chess.Q -> return false; Chess.N, Chess.B -> minors++ }
        return minors <= 1
    }

    fun captured(isCapture: Int): Boolean = b[Chess.to(isCapture)] != 0 || Chess.flag(isCapture) == Chess.F_EP
}

// ======================= La app juega =======================

object ChessAi {
    private const val MATE = 100_000
    private var nodes = 0
    private var budget = 0
    private var stop = false

    private fun center(f: Int, r: Int) = (7 - (abs(2 * f - 7) + abs(2 * r - 7)) / 2)

    /** Evaluación desde las blancas. */
    fun eval(p: Pos): Int {
        var mat = 0
        for (v in p.b) if (abs(v) != Chess.K) mat += Chess.VAL[abs(v)]
        val endgame = mat < 2600
        var s = 0
        for (sq in 0..63) {
            val v = p.b[sq]; if (v == 0) continue
            val col = if (v > 0) 1 else -1
            val t = abs(v)
            val f = sq % 8; val r = if (col == 1) sq / 8 else 7 - sq / 8
            val c = center(f, sq / 8)
            var e = Chess.VAL[t]
            e += when (t) {
                Chess.P -> r * (if (endgame) 12 else 6) + (if (f in 3..4 && r >= 3) 12 else 0)
                Chess.N -> c * 6 - 18
                Chess.B -> c * 4 - 8
                Chess.R -> if (r == 6) 15 else 0
                Chess.Q -> c * 2
                else -> if (endgame) c * 6 else (if (r == 0) 10 else -20) - c * 3
            }
            s += e * col
        }
        return s
    }

    private fun score(p: Pos, m: Int): Int {
        val cap = abs(p.b[Chess.to(m)])
        return (if (cap > 0) 10 * Chess.VAL[cap] - Chess.VAL[abs(p.b[Chess.from(m)])] / 10 + 1000 else 0) + Chess.promo(m) * 300
    }

    private fun quiesce(p: Pos, alpha0: Int, beta: Int, qd: Int): Int {
        nodes++
        val stand = eval(p) * p.side
        if (stand >= beta) return beta
        var alpha = max(alpha0, stand)
        if (qd >= 4) return alpha
        val caps = ArrayList<Int>(16); p.pseudo(caps, true)
        caps.sortByDescending { score(p, it) }
        for (m in caps) {
            val n = p.make(m)
            if (n.attacked(n.king(p.side), -p.side)) continue
            val v = -quiesce(n, -beta, -alpha, qd + 1)
            if (v >= beta) return beta
            if (v > alpha) alpha = v
        }
        return alpha
    }

    private fun negamax(p: Pos, depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        nodes++
        if (nodes > budget) stop = true
        if (stop) return 0
        if (p.half >= 100) return 0
        if (depth <= 0) return quiesce(p, alpha0, beta, 0)
        val moves = p.legal().sortedByDescending { score(p, it) }
        if (moves.isEmpty()) return if (p.inCheck()) -MATE + ply else 0
        var alpha = alpha0
        var best = -MATE * 2
        for (m in moves) {
            val v = -negamax(p.make(m), depth - 1, -beta, -alpha, ply + 1)
            if (stop) return 0
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
        }
        return best
    }

    /** level 0 fácil, 1 normal, 2 difícil. */
    fun best(p: Pos, level: Int, rnd: Random): Int {
        val moves = p.legal()
        if (moves.size == 1) return moves[0]
        if (level == 0 && rnd.nextInt(100) < 35) {
            // En fácil, a veces juega una jugada cualquiera
            return moves[rnd.nextInt(moves.size)]
        }
        val maxDepth = when (level) { 0 -> 1; 1 -> 2; else -> 4 }
        val noise = when (level) { 0 -> 80; 1 -> 20; else -> 0 }
        budget = when (level) { 0 -> 20_000; 1 -> 80_000; else -> 220_000 }
        nodes = 0; stop = false
        var order = moves.sortedByDescending { score(p, it) }
        var bestMove = order[0]
        for (d in 1..maxDepth) {
            var bestHere = Int.MIN_VALUE; var moveHere = order[0]
            val scored = ArrayList<Pair<Int, Int>>()
            for (m in order) {
                val v = -negamax(p.make(m), d - 1, -MATE * 2, MATE * 2, 1) + (if (noise > 0) rnd.nextInt(noise * 2 + 1) - noise else 0)
                if (stop) break
                scored.add(m to v)
                if (v > bestHere) { bestHere = v; moveHere = m }
            }
            if (stop) { if (d == 1 && scored.isNotEmpty()) bestMove = moveHere; break }
            bestMove = moveHere
            order = scored.sortedByDescending { it.second }.map { it.first }
        }
        return bestMove
    }
}

// ======================= Dibujo de piezas =======================

object ChessArt {
    const val WHITE_FILL = 0xFFF7EEDD.toInt()
    const val WHITE_LINE = 0xFF3A3328.toInt()
    const val BLACK_FILL = 0xFF2B2F36.toInt()
    const val BLACK_LINE = 0xFFC9CFD8.toInt()

    fun draw(c: Canvas, type: Int, white: Boolean, cx: Float, cy: Float, s: Float) {
        val fill = if (white) WHITE_FILL else BLACK_FILL
        val line = if (white) WHITE_LINE else BLACK_LINE
        val fp = G.fill(fill)
        val sp = G.stroke(line, s * 0.035f)
        fun base() {
            G.r.set(cx - s * 0.3f, cy + s * 0.26f, cx + s * 0.3f, cy + s * 0.38f)
            c.drawRoundRect(G.r, s * 0.05f, s * 0.05f, fp); c.drawRoundRect(G.r, s * 0.05f, s * 0.05f, sp)
        }
        val p = G.path
        p.reset()
        when (type) {
            Chess.P -> {
                p.moveTo(cx - s * 0.2f, cy + s * 0.27f)
                p.quadTo(cx - s * 0.18f, cy + s * 0.05f, cx - s * 0.08f, cy - s * 0.04f)
                p.lineTo(cx + s * 0.08f, cy - s * 0.04f)
                p.quadTo(cx + s * 0.18f, cy + s * 0.05f, cx + s * 0.2f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                G.oval(c, cx, cy - s * 0.04f, s * 0.14f, s * 0.045f, fill)
                G.r.set(cx - s * 0.14f, cy - s * 0.085f, cx + s * 0.14f, cy + s * 0.005f); c.drawOval(G.r, sp)
                c.drawCircle(cx, cy - s * 0.2f, s * 0.12f, fp); c.drawCircle(cx, cy - s * 0.2f, s * 0.12f, sp)
            }
            Chess.R -> {
                p.moveTo(cx - s * 0.18f, cy + s * 0.27f); p.lineTo(cx - s * 0.16f, cy - s * 0.12f)
                p.lineTo(cx - s * 0.24f, cy - s * 0.14f); p.lineTo(cx - s * 0.24f, cy - s * 0.34f)
                p.lineTo(cx - s * 0.14f, cy - s * 0.34f); p.lineTo(cx - s * 0.14f, cy - s * 0.26f)
                p.lineTo(cx - s * 0.05f, cy - s * 0.26f); p.lineTo(cx - s * 0.05f, cy - s * 0.34f)
                p.lineTo(cx + s * 0.05f, cy - s * 0.34f); p.lineTo(cx + s * 0.05f, cy - s * 0.26f)
                p.lineTo(cx + s * 0.14f, cy - s * 0.26f); p.lineTo(cx + s * 0.14f, cy - s * 0.34f)
                p.lineTo(cx + s * 0.24f, cy - s * 0.34f); p.lineTo(cx + s * 0.24f, cy - s * 0.14f)
                p.lineTo(cx + s * 0.16f, cy - s * 0.12f); p.lineTo(cx + s * 0.18f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                c.drawLine(cx - s * 0.16f, cy - s * 0.12f, cx + s * 0.16f, cy - s * 0.12f, sp)
            }
            Chess.N -> {
                p.moveTo(cx + s * 0.2f, cy + s * 0.27f)
                p.lineTo(cx + s * 0.19f, cy - s * 0.04f)
                p.quadTo(cx + s * 0.18f, cy - s * 0.32f, cx - s * 0.01f, cy - s * 0.35f)
                p.lineTo(cx - s * 0.05f, cy - s * 0.44f)
                p.lineTo(cx - s * 0.1f, cy - s * 0.32f)
                p.quadTo(cx - s * 0.24f, cy - s * 0.24f, cx - s * 0.29f, cy - s * 0.04f)
                p.lineTo(cx - s * 0.25f, cy + s * 0.03f)
                p.quadTo(cx - s * 0.16f, cy + s * 0.05f, cx - s * 0.06f, cy - s * 0.03f)
                p.quadTo(cx - s * 0.14f, cy + s * 0.12f, cx - s * 0.19f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                G.circle(c, cx - s * 0.1f, cy - s * 0.19f, s * 0.035f, line)
                G.circle(c, cx - s * 0.23f, cy - s * 0.02f, s * 0.02f, line)
            }
            Chess.B -> {
                p.moveTo(cx - s * 0.17f, cy + s * 0.27f)
                p.quadTo(cx - s * 0.12f, cy + s * 0.12f, cx - s * 0.08f, cy + s * 0.08f)
                p.quadTo(cx - s * 0.2f, cy - s * 0.04f, cx, cy - s * 0.32f)
                p.quadTo(cx + s * 0.2f, cy - s * 0.04f, cx + s * 0.08f, cy + s * 0.08f)
                p.quadTo(cx + s * 0.12f, cy + s * 0.12f, cx + s * 0.17f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                c.drawCircle(cx, cy - s * 0.37f, s * 0.05f, fp); c.drawCircle(cx, cy - s * 0.37f, s * 0.05f, sp)
                c.drawLine(cx - s * 0.02f, cy - s * 0.06f, cx + s * 0.08f, cy - s * 0.18f, sp)
                c.drawLine(cx - s * 0.1f, cy + s * 0.1f, cx + s * 0.1f, cy + s * 0.1f, sp)
            }
            Chess.Q -> {
                p.moveTo(cx - s * 0.2f, cy + s * 0.27f)
                p.lineTo(cx - s * 0.27f, cy - s * 0.22f)
                p.lineTo(cx - s * 0.13f, cy - s * 0.06f)
                p.lineTo(cx - s * 0.12f, cy - s * 0.3f)
                p.lineTo(cx - s * 0.03f, cy - s * 0.08f)
                p.lineTo(cx, cy - s * 0.34f)
                p.lineTo(cx + s * 0.03f, cy - s * 0.08f)
                p.lineTo(cx + s * 0.12f, cy - s * 0.3f)
                p.lineTo(cx + s * 0.13f, cy - s * 0.06f)
                p.lineTo(cx + s * 0.27f, cy - s * 0.22f)
                p.lineTo(cx + s * 0.2f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                for ((x, y) in listOf(-0.27f to -0.22f, -0.12f to -0.3f, 0f to -0.34f, 0.12f to -0.3f, 0.27f to -0.22f)) {
                    c.drawCircle(cx + s * x, cy + s * y - s * 0.04f, s * 0.045f, fp); c.drawCircle(cx + s * x, cy + s * y - s * 0.04f, s * 0.045f, sp)
                }
                c.drawLine(cx - s * 0.19f, cy + s * 0.14f, cx + s * 0.19f, cy + s * 0.14f, sp)
            }
            else -> { // Rey
                p.moveTo(cx - s * 0.2f, cy + s * 0.27f)
                p.lineTo(cx - s * 0.26f, cy - s * 0.1f)
                p.quadTo(cx - s * 0.13f, cy - s * 0.3f, cx, cy - s * 0.2f)
                p.quadTo(cx + s * 0.13f, cy - s * 0.3f, cx + s * 0.26f, cy - s * 0.1f)
                p.lineTo(cx + s * 0.2f, cy + s * 0.27f)
                p.close()
                c.drawPath(p, fp); c.drawPath(p, sp)
                c.drawLine(cx - s * 0.2f, cy + s * 0.12f, cx + s * 0.2f, cy + s * 0.12f, sp)
                G.r.set(cx - s * 0.04f, cy - s * 0.46f, cx + s * 0.04f, cy - s * 0.2f); c.drawRect(G.r, fp); c.drawRect(G.r, sp)
                G.r.set(cx - s * 0.11f, cy - s * 0.4f, cx + s * 0.11f, cy - s * 0.33f); c.drawRect(G.r, fp); c.drawRect(G.r, sp)
            }
        }
        base()
    }
}

// ======================= Pantalla =======================

class AjedrezScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val LIGHT = 0xFFEED9B6.toInt()
    private val DARKSQ = 0xFFB58863.toInt()
    private val ai = m.players == 1
    private var whiteP = if (ai) 0 else m.starter

    private var pos = Pos.start()
    private var legal: List<Int> = emptyList()
    private val history = ArrayList<Pos>()
    private val keys = ArrayList<String>()
    private val lastMoves = ArrayList<Int>()
    private var sel = -1
    private var promoFrom = -1; private var promoTo = -1
    private var thinking = false
    private var gen = 0
    private var animMove = -1; private var animPiece = 0; private var animT = 0L
    private var ended = false

    private var bx = 0f; private var by = 0f; private var cell = 0f
    private val cards = arrayOf(RectF(), RectF())

    init { restart() }

    override fun restart() {
        pos = Pos.start(); history.clear(); keys.clear(); lastMoves.clear()
        keys.add(pos.key()); legal = pos.legal()
        sel = -1; promoFrom = -1; thinking = false; gen++; animMove = -1; ended = false
        resetClock(); maybeAi()
    }

    override fun replay() { if (!ai) whiteP = 1 - whiteP; restart() }

    override fun size(w: Float, h: Float) {
        super.size(w, h)
        cell = min((h - barH - pad * 3) / 8f, w * 0.5f / 8f)
        val bw = cell * 8
        bx = (w - bw) / 2; by = barH + (h - barH - bw) / 2
        val side = (w - bw) / 2
        val cw = min(side - pad * 4, bw * 0.62f); val ch = min(bw * 0.8f, cw * 1.25f)
        val cy = by + bw / 2
        cards[0].set(side / 2 - cw / 2, cy - ch / 2, side / 2 + cw / 2, cy + ch / 2)
        cards[1].set(w - side / 2 - cw / 2, cy - ch / 2, w - side / 2 + cw / 2, cy + ch / 2)
    }

    /** Jugador (0/1) que mueve ahora. */
    private fun mover() = if (pos.side == 1) whiteP else 1 - whiteP
    private fun aiTurn() = ai && mover() == 1

    private fun sqX(sq: Int) = bx + (sq % 8 + 0.5f) * cell
    private fun sqY(sq: Int) = by + (7 - sq / 8 + 0.5f) * cell

    private fun maybeAi() {
        if (!aiTurn() || over || ended) return
        thinking = true
        val myGen = gen
        val snap = pos.copy()
        val lvl = m.level
        val seed = rnd.nextLong()
        val t0 = System.currentTimeMillis()
        Thread {
            val mv = ChessAi.best(snap, lvl, Random(seed))
            val wait = max(0L, 500 - (System.currentTimeMillis() - t0))
            stage.postDelayed({ if (myGen == gen) { thinking = false; play(mv) } }, wait)
        }.start()
    }

    private fun play(mv: Int) {
        val capture = pos.captured(mv)
        history.add(pos); lastMoves.add(mv)
        animPiece = pos.b[Chess.from(mv)]; animMove = mv; animT = gt()
        pos = pos.make(mv)
        keys.add(pos.key())
        legal = pos.legal()
        sel = -1
        Sound.play(if (capture) Sound.BONK else Sound.DROP)
        if (legal.isNotEmpty() && pos.inCheck()) Sound.play(Sound.BAD)
        checkEnd()
        if (!ended) maybeAi()
    }

    private fun checkEnd() {
        val winnerIfMate = 1 - mover()
        val reason = when {
            legal.isEmpty() && pos.inCheck() -> null
            legal.isEmpty() -> "Ahogado: el rey no puede moverse y no está en jaque."
            pos.insufficient() -> "No queda material para dar jaque mate."
            keys.count { it == keys.last() } >= 3 -> "La misma posición se repitió 3 veces."
            pos.half >= 100 -> "50 jugadas sin capturas ni movimientos de peón."
            else -> return
        }
        ended = true
        if (reason == null) {
            if (ai) {
                Store.inc(if (winnerIfMate == 0) "ajedrez_v${m.level}" else "ajedrez_d${m.level}")
                finish(if (winnerIfMate == 0) Result(0, "¡Ganaste!", "¡Jaque mate a la app!") else Result(1, "Ganó la app", "Jaque mate. ¡Inténtalo otra vez!"), 1500)
            } else finish(Result(winnerIfMate, "¡Ganó ${name(winnerIfMate)}!", "¡Jaque mate!"), 1500)
        } else {
            if (ai) Store.inc("ajedrez_e${m.level}")
            finish(Result(Result.DRAW, "Tablas", reason), 1200)
        }
    }

    private fun canUndo() = history.isNotEmpty() && !thinking && !ended && !m.tournament && (!ai || history.size >= 2 || !aiTurn())

    private fun undo() {
        if (!canUndo()) return
        val n = if (ai) (if (aiTurn()) 1 else 2).coerceAtMost(history.size) else 1
        repeat(n) {
            pos = history.removeAt(history.size - 1); lastMoves.removeAt(lastMoves.size - 1); keys.removeAt(keys.size - 1)
        }
        legal = pos.legal(); sel = -1; promoFrom = -1; animMove = -1; gen++
        Sound.play(Sound.TAP)
        maybeAi()
    }

    override fun drawBarButtons(c: Canvas, right: Float): Float {
        if (m.tournament) return right
        val m2 = barH * 0.15f
        val bh = barH - 2 * m2
        val ur = RectF(right - bh * 1.35f, m2, right, barH - m2)
        ui.iconButton(c, ur, Btn.CARD, { cx, cy, s, col -> Icons.undo(c, cx, cy, s, col) }, enabled = canUndo()) { undo() }
        return ur.left - m2
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val txt = when {
            m.tournament -> "Torneo"
            ai -> "Contra la app · " + m.game.levels(1).getOrElse(m.level) { "" }
            else -> "Jugada ${history.size / 2 + 1}"
        }
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.32f, right - left, C.TEXT2)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        val bw = cell * 8
        G.rr(c, bx - cell * 0.18f, by - cell * 0.18f, bx + bw + cell * 0.18f, by + bw + cell * 0.18f, cell * 0.2f, 0xFF5A3E2B.toInt())
        val last = lastMoves.lastOrNull() ?: -1
        val checkSq = if (pos.inCheck()) pos.king(pos.side) else -1
        for (sq in 0..63) {
            val f = sq % 8; val r = sq / 8
            val l = bx + f * cell; val tp = by + (7 - r) * cell
            var col = if ((f + r) % 2 == 1) LIGHT else DARKSQ
            if (last >= 0 && (sq == Chess.from(last) || sq == Chess.to(last))) col = C.mix(col, 0xFFE8C547.toInt(), 0.45f)
            if (sq == sel) col = C.mix(col, 0xFF7CC66A.toInt(), 0.6f)
            c.drawRect(l, tp, l + cell + 0.5f, tp + cell + 0.5f, G.fill(col))
            if (sq == checkSq) G.circle(c, l + cell / 2, tp + cell / 2, cell * 0.46f, C.alpha(C.RED, 0.75f))
        }
        // Coordenadas
        val cs = cell * 0.2f
        for (i in 0..7) {
            G.text(c, "abcdefgh"[i].toString(), bx + (i + 0.5f) * cell, by + bw + cell * 0.09f, cs, C.alpha(C.WHITE, 0.7f))
            G.text(c, "${i + 1}", bx - cell * 0.09f, by + (7 - i + 0.5f) * cell, cs, C.alpha(C.WHITE, 0.7f))
        }
        // Piezas
        val animK = if (animMove >= 0) ((t - animT) / 180f).coerceIn(0f, 1f) else 1f
        if (animK >= 1f) animMove = -1
        for (sq in 0..63) {
            val v = pos.b[sq]
            if (v == 0) continue
            if (animMove >= 0 && sq == Chess.to(animMove)) continue
            ChessArt.draw(c, abs(v), v > 0, sqX(sq), sqY(sq) - cell * 0.03f, cell * 1.12f)
        }
        if (animMove >= 0) {
            val fr = Chess.from(animMove); val to = Chess.to(animMove)
            val k = G.ease(animK)
            val v = pos.b[to]
            ChessArt.draw(c, abs(v), v > 0, sqX(fr) + (sqX(to) - sqX(fr)) * k, sqY(fr) + (sqY(to) - sqY(fr)) * k - cell * 0.03f, cell * 1.12f)
        }
        // Destinos posibles
        if (sel >= 0) {
            for (mv in legal) if (Chess.from(mv) == sel) {
                val to = Chess.to(mv)
                if (pos.b[to] != 0 || Chess.flag(mv) == Chess.F_EP) c.drawCircle(sqX(to), sqY(to), cell * 0.43f, G.stroke(C.alpha(0xFF1E5A2A.toInt(), 0.65f), cell * 0.07f))
                else G.circle(c, sqX(to), sqY(to), cell * 0.14f, C.alpha(0xFF1E5A2A.toInt(), 0.55f))
            }
        }
        // Tarjetas
        for (i in 0..1) {
            val white = (i == whiteP)
            val active = mover() == i && !over && !ended
            val extra = when {
                active && legal.isNotEmpty() && pos.inCheck() -> "¡Jaque!"
                active -> if (ai && i == 1) "Pensando..." else "Su turno"
                else -> if (white) "Blancas" else "Negras"
            }
            playerCard(c, cards[i], i, active, name(i), extra) { x, y, s -> ChessArt.draw(c, Chess.K, white, x, y + s * 0.1f, s * 1.5f) }
            drawCaptured(c, cards[i], white)
        }
        if (promoFrom >= 0) drawPromo(c)
    }

    /** Piezas que este jugador le ha comido al otro. */
    private fun drawCaptured(c: Canvas, r: RectF, white: Boolean) {
        val start = intArrayOf(0, 8, 2, 2, 2, 1, 1)
        val count = IntArray(7)
        val enemy = if (white) -1 else 1
        for (v in pos.b) if (v * enemy > 0) count[abs(v)]++
        val list = ArrayList<Int>()
        for (tp in intArrayOf(Chess.Q, Chess.R, Chess.B, Chess.N, Chess.P)) repeat(max(0, start[tp] - count[tp])) { list.add(tp) }
        if (list.isEmpty()) return
        val s = min(r.width() / 6f, barH * 0.6f)
        val perRow = ((r.width() - s * 0.4f) / (s * 0.62f)).toInt().coerceAtLeast(1)
        for ((i, tp) in list.withIndex()) {
            val row = i / perRow; val col = i % perRow
            ChessArt.draw(c, tp, !white, r.left + s * 0.55f + col * s * 0.62f, r.bottom + s * 0.7f + row * s * 0.95f, s)
        }
    }

    private fun promoRects(): List<RectF> {
        val sz = cell * 1.25f
        val total = sz * 4 + pad * 3
        val x0 = bx + cell * 4 - total / 2; val y0 = by + cell * 4 - sz / 2
        return (0..3).map { RectF(x0 + it * (sz + pad), y0, x0 + it * (sz + pad) + sz, y0 + sz) }
    }

    private val promoTypes = intArrayOf(Chess.Q, Chess.R, Chess.B, Chess.N)

    private fun drawPromo(c: Canvas) {
        val rs = promoRects()
        val bw = cell * 8
        G.rr(c, bx, by, bx + bw, by + bw, 0f, C.alpha(C.BG, 0.6f))
        G.text(c, "¿En qué se convierte el peón?", bx + bw / 2, rs[0].top - cell * 0.45f, cell * 0.36f, C.WHITE, font = Fonts.black)
        for ((i, r) in rs.withIndex()) {
            G.rr(c, r, cell * 0.2f, if (ui.pressed(r)) C.CARD_HI else C.CARD)
            ChessArt.draw(c, promoTypes[i], pos.side == 1, r.centerX(), r.centerY(), r.width() * 0.9f)
        }
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || ended || thinking || aiTurn() || animMove >= 0) return
        if (promoFrom >= 0) {
            val rs = promoRects()
            for ((i, r) in rs.withIndex()) if (r.contains(x, y)) {
                val mv = legal.firstOrNull { Chess.from(it) == promoFrom && Chess.to(it) == promoTo && Chess.promo(it) == promoTypes[i] }
                promoFrom = -1
                if (mv != null) play(mv)
                return
            }
            promoFrom = -1; sel = -1
            return
        }
        val bw = cell * 8
        if (x < bx || x >= bx + bw || y < by || y >= by + bw) { sel = -1; return }
        val f = ((x - bx) / cell).toInt().coerceIn(0, 7)
        val r = 7 - ((y - by) / cell).toInt().coerceIn(0, 7)
        val sq = r * 8 + f
        if (sel >= 0) {
            val moves = legal.filter { Chess.from(it) == sel && Chess.to(it) == sq }
            if (moves.isNotEmpty()) {
                if (moves.size > 1) { promoFrom = sel; promoTo = sq; Sound.play(Sound.TAP) } else play(moves[0])
                return
            }
        }
        if (pos.b[sq] * pos.side > 0) {
            sel = if (sel == sq) -1 else sq
            Sound.play(Sound.TAP)
            if (sel >= 0 && legal.none { Chess.from(it) == sel }) Sound.play(Sound.BAD)
        } else {
            if (sel >= 0) Sound.play(Sound.BAD)
            sel = -1
        }
    }
}
