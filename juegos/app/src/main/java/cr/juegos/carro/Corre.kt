package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Coco, el personaje del juego de correr: una bolita con orejas y bufanda. */
object Coco {
    const val CREAM = 0xFFFFE6C0.toInt()

    fun bodyColor(player: Int) = if (player == 1) 0xFF6FCBFF.toInt() else 0xFFFFB547.toInt()
    fun scarfColor(player: Int) = if (player == 1) 0xFFFF8A3D.toInt() else 0xFF3D8BFF.toInt()

    /** [cx], [bottom]: centro de los pies. [s]: tamaño de una casilla. */
    fun draw(c: Canvas, cx: Float, bottom: Float, s: Float, phase: Float, air: Boolean, player: Int, t: Long, hurt: Boolean = false) {
        val body = bodyColor(player)
        val dark = C.mix(body, C.DARK, 0.45f)
        val cy = bottom - s * 0.5f
        // Bufanda que ondea hacia atrás
        val wave = sin(t / 70.0).toFloat()
        G.path.reset()
        G.path.moveTo(cx - s * 0.2f, cy + s * 0.02f)
        G.path.quadTo(cx - s * 0.55f, cy - s * 0.05f + wave * s * 0.06f, cx - s * 0.78f, cy - s * 0.12f + wave * s * 0.12f)
        G.path.lineTo(cx - s * 0.74f, cy + s * 0.06f + wave * s * 0.1f)
        G.path.quadTo(cx - s * 0.5f, cy + s * 0.12f, cx - s * 0.2f, cy + s * 0.16f)
        G.path.close()
        c.drawPath(G.path, G.fill(scarfColor(player)))
        // Pies
        if (air) {
            G.oval(c, cx - s * 0.16f, bottom - s * 0.12f, s * 0.15f, s * 0.1f, dark)
            G.oval(c, cx + s * 0.18f, bottom - s * 0.08f, s * 0.15f, s * 0.1f, dark)
        } else {
            val a = sin(phase.toDouble()).toFloat()
            G.oval(c, cx - s * 0.14f + a * s * 0.14f, bottom - s * 0.08f - max(0f, a) * s * 0.07f, s * 0.16f, s * 0.09f, dark)
            G.oval(c, cx + s * 0.14f - a * s * 0.14f, bottom - s * 0.08f - max(0f, -a) * s * 0.07f, s * 0.16f, s * 0.09f, dark)
        }
        // Orejas
        for (sgn in floatArrayOf(-1f, 1f)) {
            G.path.reset()
            G.path.moveTo(cx + sgn * s * 0.08f, cy - s * 0.3f)
            G.path.lineTo(cx + sgn * s * 0.3f, cy - s * 0.66f)
            G.path.lineTo(cx + sgn * s * 0.36f, cy - s * 0.2f)
            G.path.close()
            c.drawPath(G.path, G.fill(body))
            G.path.reset()
            G.path.moveTo(cx + sgn * s * 0.17f, cy - s * 0.32f)
            G.path.lineTo(cx + sgn * s * 0.29f, cy - s * 0.54f)
            G.path.lineTo(cx + sgn * s * 0.31f, cy - s * 0.28f)
            G.path.close()
            c.drawPath(G.path, G.fill(0xFFFF8FA3.toInt()))
        }
        // Cuerpo
        G.oval(c, cx, cy, s * 0.42f, s * 0.4f, body)
        G.oval(c, cx + s * 0.08f, cy + s * 0.12f, s * 0.24f, s * 0.2f, CREAM)
        // Bufanda alrededor del cuello
        G.r.set(cx - s * 0.36f, cy + s * 0.0f, cx + s * 0.36f, cy + s * 0.14f)
        c.drawRoundRect(G.r, s * 0.07f, s * 0.07f, G.fill(scarfColor(player)))
        // Cara (mira a la derecha)
        if (hurt) {
            val p = G.stroke(C.DARK, s * 0.05f)
            for (ex in floatArrayOf(0.06f, 0.26f)) {
                c.drawLine(cx + s * (ex - 0.06f), cy - s * 0.2f, cx + s * (ex + 0.06f), cy - s * 0.08f, p)
                c.drawLine(cx + s * (ex - 0.06f), cy - s * 0.08f, cx + s * (ex + 0.06f), cy - s * 0.2f, p)
            }
        } else {
            for (ex in floatArrayOf(0.06f, 0.26f)) {
                G.oval(c, cx + s * ex, cy - s * 0.14f, s * 0.08f, s * 0.1f, C.WHITE)
                G.circle(c, cx + s * (ex + 0.03f), cy - s * 0.12f, s * 0.05f, C.DARK)
                G.circle(c, cx + s * (ex + 0.045f), cy - s * 0.15f, s * 0.018f, C.WHITE)
            }
        }
        G.circle(c, cx + s * 0.36f, cy - s * 0.02f, s * 0.05f, C.alpha(0xFFFF6F8A.toInt(), 0.7f))
        G.circle(c, cx + s * 0.39f, cy - s * 0.1f, s * 0.035f, C.DARK)
    }

    fun coin(c: Canvas, cx: Float, cy: Float, r: Float, t: Long, offset: Float = 0f) {
        val k = abs(cos(t / 260.0 + offset)).toFloat().coerceAtLeast(0.15f)
        G.oval(c, cx, cy, r * k, r, 0xFFE0A21A.toInt())
        G.oval(c, cx, cy, r * k * 0.72f, r * 0.72f, 0xFFFFD34D.toInt())
        if (k > 0.5f) G.oval(c, cx, cy, r * k * 0.16f, r * 0.42f, 0xFFFFF1B8.toInt())
    }

    fun heart(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        G.circle(c, cx - s * 0.25f, cy - s * 0.12f, s * 0.28f, color)
        G.circle(c, cx + s * 0.25f, cy - s * 0.12f, s * 0.28f, color)
        G.path.reset()
        G.path.moveTo(cx - s * 0.51f, cy - s * 0.03f)
        G.path.lineTo(cx, cy + s * 0.5f)
        G.path.lineTo(cx + s * 0.51f, cy - s * 0.03f)
        G.path.close()
        c.drawPath(G.path, G.fill(color))
    }
}

/** Juego de correr y saltar con tres mundos. */
class CorreScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private companion object {
        const val ROWS = 9
        const val GR = 7            // fila donde empieza el suelo
        const val NONE = 99
        const val GRAV = 55f
        const val JUMP1 = -16.5f
        const val JUMP2 = -14f
        const val CUT = -8f
        const val MAXFALL = 22f
        const val PW = 0.35f        // mitad del ancho del jugador
        const val PH = 0.9f
        const val START = 2000L
        const val HEARTS = 3
        val WORLDS = listOf("Pradera", "Playa", "Volcán", "Nieve", "Nubes", "Castillo")
        /** Peso de cada tramo (0..8) en los mundos 4 a 6. */
        val WEIGHTS = arrayOf(intArrayOf(2, 3, 2, 2, 3, 2, 2, 1, 1), intArrayOf(1, 4, 1, 4, 1, 2, 1, 1, 2), intArrayOf(1, 3, 2, 2, 3, 2, 3, 1, 2))
    }

    private class Pal(val skyTop: Int, val skyBot: Int, val far: Int, val near: Int, val top: Int, val dirt: Int, val dirtDark: Int, val block: Int, val blockDark: Int)

    private val pal = when (m.level) {
        1 -> Pal(0xFF46A9E0.toInt(), 0xFFFFE2B0.toInt(), 0xFF2E8BC0.toInt(), 0xFF3F9E5A.toInt(), 0xFFF7D98F.toInt(), 0xFFE2B76B.toInt(), 0xFFC79A4F.toInt(), 0xFFB07A42.toInt(), 0xFF7E5428.toInt())
        3 -> Pal(0xFF8DBDE3.toInt(), 0xFFEAF4FB.toInt(), 0xFF9DB3C9.toInt(), 0xFFE3EEF7.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F95A8.toInt(), 0xFF667B8E.toInt(), 0xFFBFE6F5.toInt(), 0xFF7FBCD6.toInt())
        4 -> Pal(0xFF7FC2F5.toInt(), 0xFFFFE6F0.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFE4ECF6.toInt(), 0xFFC9D6E6.toInt(), 0xFFFFC94D.toInt(), 0xFFD6961A.toInt())
        5 -> Pal(0xFF16171E.toInt(), 0xFF2E2833.toInt(), 0xFF2A2A33.toInt(), 0xFF22222A.toInt(), 0xFF9AA0AB.toInt(), 0xFF5E626C.toInt(), 0xFF484B54.toInt(), 0xFF8A6A4A.toInt(), 0xFF5E4630.toInt())
        2 -> Pal(0xFF1E0E1A.toInt(), 0xFF7A2E1E.toInt(), 0xFF3A1C1E.toInt(), 0xFF2A1416.toInt(), 0xFFE8743B.toInt(), 0xFF4D3434.toInt(), 0xFF3A2626.toInt(), 0xFF6A6A76.toInt(), 0xFF45454E.toInt())
        else -> Pal(0xFF5EBBF5.toInt(), 0xFFC8EEFF.toInt(), 0xFF8FD18A.toInt(), 0xFF5DB55A.toInt(), 0xFF5BCB4E.toInt(), 0xFF96612F.toInt(), 0xFF744A22.toInt(), 0xFFD9893B.toInt(), 0xFFA05E22.toInt())
    }
    private val speed = when (m.level) { 1 -> 6.6f; 2 -> 7.2f; 3 -> 7.4f; 4 -> 7.6f; 5 -> 7.8f; else -> 6f }
    private val length = when (m.level) { 1 -> 230; 2 -> 260; 3 -> 270; 4 -> 280; 5 -> 300; else -> 200 }

    // Mapa
    private var top = IntArray(0)
    private var plat = IntArray(0)
    private var spike = BooleanArray(0)
    private var flagX = 0f

    private class Coin(val x: Float, val y: Float) { var taken = -1L }
    private class Enemy(val bird: Boolean, var x: Float, var y: Float, val baseY: Float) {
        var vx = -1.3f; var deadT = -1L; var active = false
    }
    private class Spark(val x: Float, val y: Float, val t: Long, val color: Int)

    private val coins = ArrayList<Coin>()
    private val enemies = ArrayList<Enemy>()
    private val sparks = ArrayList<Spark>()

    // Jugador
    private var px = 0f; private var py = 0f; private var vy = 0f
    private var grounded = false
    private var jumps = 0
    private var lastGround = 0L
    private var bufT = -10_000L
    private var holding = 0
    private var hearts = HEARTS
    private var hurtT = -10_000L
    private var safeX = 2f
    private var phase = 0f
    private var collected = 0
    private var finishT = -1L
    private var deadT = -1L
    private var runEnd = -1L
    private var lastT = 0L
    private var t0 = 0L

    // Turnos
    private val two = m.players == 2
    private var turn = m.starter
    private var runsDone = 0
    private var intro = false
    private class Stat(val finished: Boolean, val coins: Int, val time: Long, val progress: Float)
    private val stats = arrayOfNulls<Stat>(2)
    private var ended = false

    private val sky = Paint()
    private var skyH = 0f
    private val tmp = RectF()

    init { restart() }

    override fun restart() {
        turn = m.starter; runsDone = 0; stats[0] = null; stats[1] = null; ended = false
        resetClock()
        newRun()
        intro = two
    }

    private fun newRun() {
        build()
        px = 3.5f; py = GR.toFloat(); vy = 0f; grounded = true; jumps = 0
        hearts = HEARTS; hurtT = -10_000L; safeX = px; collected = 0
        finishT = -1L; deadT = -1L; runEnd = -1L; holding = 0; bufT = -10_000L
        t0 = gt(); lastT = 0L; sparks.clear()
    }

    // ---------------- Construcción del recorrido ----------------

    private fun build() {
        val rnd = Random(4242L + m.level * 77L)
        val n = length + 40
        top = IntArray(n) { GR }; plat = IntArray(n) { -1 }; spike = BooleanArray(n)
        coins.clear(); enemies.clear()
        val d = m.level
        var x = 12
        var last = -1
        while (x < length - 14) {
            val prog = x.toFloat() / length
            var k: Int
            if (d <= 2) {
                k = rnd.nextInt(8)
                if (k == last) k = rnd.nextInt(8)
                if (k == 6 && d == 0) k = 0
            } else {
                val wt = WEIGHTS[d - 3]
                fun roll(): Int { var r = rnd.nextInt(wt.sum()); for (i in wt.indices) { r -= wt[i]; if (r < 0) return i }; return 0 }
                k = roll(); if (k == last) k = roll()
            }
            last = k
            when (k) {
                0 -> { // llano con monedas
                    val len = 4 + rnd.nextInt(3)
                    for (i in 1 until len - 1) coins.add(Coin(x + i + 0.5f, GR - 0.6f))
                    x += len
                }
                1 -> { // hueco con arco de monedas
                    x += 2
                    val gw = 2 + rnd.nextInt(if (d == 0) 2 else if (d >= 3 && prog > 0.35f || prog > 0.5f) 3 else 2)
                    for (i in 0 until gw) top[x + i] = NONE
                    val arcN = gw + 2
                    for (i in 0 until arcN) {
                        val u = (i + 0.5f) / arcN
                        coins.add(Coin(x - 1 + i + 0.5f, GR - 1.2f - 1.6f * 4 * u * (1 - u)))
                    }
                    x += gw + 2
                }
                2 -> { // escalones
                    x += 1
                    val h1 = 1 + rnd.nextInt(2)
                    val l1 = 3 + rnd.nextInt(2)
                    for (i in 0 until l1) top[x + i] = GR - h1
                    for (i in 1 until l1) coins.add(Coin(x + i + 0.5f, GR - h1 - 0.6f))
                    x += l1
                    if (h1 == 1 && rnd.nextBoolean()) {
                        for (i in 0 until 3) top[x + i] = GR - 2
                        coins.add(Coin(x + 1.5f, GR - 2.6f))
                        x += 3
                    }
                    if (d > 0 && rnd.nextBoolean()) { top[x] = NONE; top[x + 1] = NONE; x += 2 }
                    x += 2
                }
                3 -> { // plataforma flotante (sobre un hueco en mundos 2 y 3)
                    x += 2
                    val pl = 3 + rnd.nextInt(3)
                    val row = if (d >= 4 && rnd.nextInt(3) == 0) GR - 3 else GR - 2
                    for (i in 0 until pl) {
                        plat[x + i] = row
                        coins.add(Coin(x + i + 0.5f, row - 0.6f))
                        if (d > 0) top[x + i] = NONE
                    }
                    if (d == 0) for (i in 0 until pl) if (i % 2 == 0) coins.add(Coin(x + i + 0.5f, GR - 0.6f))
                    x += pl + 2
                }
                4 -> { // escarabajos
                    val len = 7
                    enemies.add(Enemy(false, x + 4.5f, GR.toFloat(), GR.toFloat()))
                    if (d >= 3 || d > 0 && prog > 0.3f) enemies.add(Enemy(false, x + 6.2f, GR.toFloat(), GR.toFloat()))
                    if (d >= 5 && prog > 0.3f) enemies.add(Enemy(false, x + 2.9f, GR.toFloat(), GR.toFloat()))
                    coins.add(Coin(x + 4.5f, GR - 2.2f))
                    x += len
                }
                5 -> { // pájaro
                    val low = rnd.nextInt(3) != 0
                    val y = if (low) GR - 0.55f else GR - 2.6f
                    enemies.add(Enemy(true, x + 6.5f, y, y))
                    if (low) coins.add(Coin(x + 6.5f, GR - 2.6f)) else for (i in 4..8) coins.add(Coin(x + i + 0.5f, GR - 0.6f))
                    x += 8
                }
                6 -> { // pinchos
                    x += 3
                    val sl = 1 + rnd.nextInt(if (d >= 3 && prog > 0.4f) 3 else if (prog > 0.5f || d >= 3) 2 else 1)
                    for (i in 0 until sl) spike[x + i] = true
                    coins.add(Coin(x + sl / 2f, GR - 2.4f))
                    x += sl + 3
                }
                8 -> { // dos huecos con un pilar en medio
                    x += 2
                    top[x] = NONE; top[x + 1] = NONE
                    top[x + 2] = GR - 1
                    top[x + 3] = NONE; top[x + 4] = NONE
                    for (i in 0..4) coins.add(Coin(x + i + 0.5f, GR - 2.2f - (if (i == 2) 0.6f else 0f)))
                    x += 7
                }
                else -> { // fila alta de monedas
                    for (i in 2..5) coins.add(Coin(x + i + 0.5f, GR - 2.7f))
                    x += 7
                }
            }
        }
        flagX = length - 6f
        coins.removeAll { it.x > flagX - 1 }
    }

    // ---------------- Física ----------------

    private fun topAt(tx: Int): Int = if (tx < 0 || tx >= top.size) GR else top[tx]
    private fun wall(tx: Int, ty: Int) = ty >= topAt(tx) && topAt(tx) != NONE
    private fun platAt(tx: Int) = if (tx < 0 || tx >= plat.size) -1 else plat[tx]

    private fun jump(t: Long) {
        if (grounded || t - lastGround < 110) {
            vy = JUMP1; jumps = 1; grounded = false; lastGround = -10_000L
            Sound.play(Sound.JUMP)
        } else if (jumps < 2) {
            vy = JUMP2; jumps = 2
            Sound.play(Sound.JUMP)
            sparks.add(Spark(px, py, t, C.WHITE))
        } else bufT = t
    }

    private fun step(dt: Float, t: Long) {
        val running = deadT < 0 && (finishT < 0 || px < flagX + 4)
        // Horizontal
        if (running) {
            px += speed * dt
            phase += speed * dt * 3.2f
            val col = floor(px + PW).toInt()
            val r0 = floor(py - PH + 0.01f).toInt(); val r1 = floor(py - 0.01f).toInt()
            for (r in r0..r1) if (wall(col, r)) { px = col - PW - 0.001f; break }
        }
        // Vertical
        val prev = py
        vy = min(vy + GRAV * dt, MAXFALL)
        py += vy * dt
        val wasGrounded = grounded
        grounded = false
        val c0 = floor(px - PW + 0.02f).toInt(); val c1 = floor(px + PW - 0.02f).toInt()
        if (vy >= 0) {
            val row = floor(py).toInt()
            if (prev <= row + 0.02f) {
                for (cc in c0..c1) {
                    if (wall(cc, row) || platAt(cc) == row) {
                        py = row.toFloat(); vy = 0f; grounded = true; jumps = 0; lastGround = t
                        if (topAt(cc) == row && platAt(cc) < 0 || platAt(cc) == row) safeX = px
                        break
                    }
                }
            }
        } else {
            val row = floor(py - PH).toInt()
            for (cc in c0..c1) if (wall(cc, row)) { py = row + 1 + PH; vy = 0f; break }
        }
        if (grounded && !wasGrounded && t - bufT < 140) { bufT = -10_000L; jump(t) }
        // Caer a un hueco
        if (py > ROWS + 1.5f && deadT < 0) {
            hurt(t, true)
            if (deadT < 0) {
                var sx = floor(safeX).toInt()
                while (sx > 0 && (topAt(sx) == NONE || topAt(sx - 1) == NONE)) sx--
                px = sx + 0.5f
                val tp = topAt(sx); py = if (platAt(sx) >= 0 && (tp == NONE || platAt(sx) < tp)) platAt(sx).toFloat() else tp.toFloat()
                vy = 0f
            }
        }
        // Monedas
        val cx = px; val cy = py - PH / 2
        for (co in coins) {
            if (co.taken >= 0 || abs(co.x - cx) > 0.8f) continue
            if (abs(co.y - cy) < 0.8f) {
                co.taken = t; collected++
                Sound.play(Sound.COIN)
                sparks.add(Spark(co.x, co.y, t, 0xFFFFD34D.toInt()))
            }
        }
        // Pinchos
        if (deadT < 0) {
            for (cc in c0..c1) if (cc in spike.indices && spike[cc] && py > GR - 0.45f && py <= GR + 0.05f) {
                hurt(t, false); if (deadT < 0) vy = -12f; break
            }
        }
        // Enemigos
        val visible = w / tile() + 2
        for (e in enemies) {
            if (e.deadT >= 0) continue
            if (!e.active && e.x - px < visible * 0.75f) e.active = true
            if (!e.active) continue
            if (e.bird) {
                e.x -= 1.6f * dt
                e.y = e.baseY + sin((t - t0) / 320.0 + e.x).toFloat() * 0.35f
            } else {
                val nx = e.x + e.vx * dt
                val ahead = floor(nx + (if (e.vx < 0) -0.4f else 0.4f)).toInt()
                if (topAt(ahead) != GR || spike.getOrElse(ahead) { false }) e.vx = -e.vx else e.x = nx
            }
            if (deadT >= 0) continue
            val ew = 0.4f; val eh = if (e.bird) 0.55f else 0.6f
            val eTop = if (e.bird) e.y - eh / 2 else e.y - eh
            val eBot = if (e.bird) e.y + eh / 2 else e.y
            if (px + PW > e.x - ew && px - PW < e.x + ew && py > eTop && py - PH < eBot) {
                if (vy > 0 && py - eTop < 0.5f) {
                    e.deadT = t; vy = if (holding > 0) -15f else -12f; jumps = 1
                    Sound.play(Sound.BONK)
                    sparks.add(Spark(e.x, eTop, t, C.WHITE))
                } else hurt(t, false)
            }
        }
        enemies.removeAll { it.deadT >= 0 && t - it.deadT > 600 || it.x < px - 30 }
        // Meta
        if (finishT < 0 && deadT < 0 && px >= flagX) {
            finishT = t; Sound.play(Sound.WIN)
        }
    }

    private fun hurt(t: Long, fell: Boolean) {
        if (!fell && t - hurtT < 1500) return
        hearts--; hurtT = t
        Sound.play(Sound.BAD)
        if (hearts <= 0) { deadT = t; vy = -12f }
    }

    // ---------------- Bucle ----------------

    private fun tile() = (h - barH) / ROWS

    private fun runTime(): Long = if (finishT >= 0) finishT - START else gt() - t0 - START

    private fun update() {
        if (intro || blocked) { lastT = gt() - t0; return }
        val t = gt() - t0
        if (t < START) { lastT = t; return }
        var dt = ((t - max(lastT, START)) / 1000f).coerceIn(0f, 0.05f)
        lastT = t
        while (dt > 0f) {
            val s = min(dt, 1 / 120f)
            step(s, t)
            dt -= s
        }
        sparks.removeAll { t - it.t > 500 }
        if (runEnd < 0 && (finishT >= 0 && t - finishT > 1300 || deadT >= 0 && t - deadT > 1300)) {
            runEnd = t; endRun()
        }
    }

    private fun endRun() {
        val st = Stat(finishT >= 0, collected, if (finishT >= 0) finishT - START else 0, (px / flagX).coerceIn(0f, 1f))
        stats[turn] = st
        runsDone++
        if (two && runsDone < 2) {
            turn = 1 - turn
            newRun(); intro = true
            return
        }
        ended = true
        if (two) decide() else solo(st)
    }

    private fun mon(n: Int) = if (n == 1) "1 moneda" else "$n monedas"

    private fun solo(st: Stat) {
        val l = m.level
        val newCoins = Store.best("corre_m$l", st.coins.toLong(), false)
        if (st.finished) {
            Store.inc("corre_metas")
            val newTime = Store.best("corre_t$l", st.time, true)
            val rec = if (newTime || newCoins) "¡Nuevo récord!" else "Récord: ${Store.long("corre_m$l")} monedas, ${G.timeExact(Store.long("corre_t$l"))}"
            finish(Result(Result.SOLO, "¡Llegaste a la meta!", "${mon(st.coins)} en ${G.timeExact(st.time)}\n$rec"), 300)
        } else {
            val pct = (st.progress * 100).toInt()
            finish(Result(Result.SOLO, "Se acabaron las vidas", "Llegaste al $pct % del camino con ${mon(st.coins)}\n¡Inténtalo otra vez!"), 300)
        }
    }

    private fun decide() {
        val a = stats[0]!!; val b = stats[1]!!
        fun line(i: Int, s: Stat) = "${name(i)}: " + (if (s.finished) "meta, ${mon(s.coins)}, ${G.timeExact(s.time)}" else "${(s.progress * 100).toInt()} %, ${mon(s.coins)}")
        val cmp = when {
            a.finished != b.finished -> if (a.finished) 1 else -1
            a.finished -> if (a.coins != b.coins) a.coins - b.coins else (b.time - a.time).toInt()
            else -> if (abs(a.progress - b.progress) > 0.005f) (if (a.progress > b.progress) 1 else -1) else a.coins - b.coins
        }
        val d = line(0, a) + "\n" + line(1, b)
        when {
            cmp > 0 -> finish(Result(0, "¡Ganó ${name(0)}!", d), 300)
            cmp < 0 -> finish(Result(1, "¡Ganó ${name(1)}!", d), 300)
            else -> finish(Result(Result.DRAW, "Empate", d), 300)
        }
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        holding++
        if (intro || blocked || ended) return
        val t = gt() - t0
        if (t < START || deadT >= 0 || finishT >= 0) return
        jump(t)
    }

    override fun touchUp(id: Int, x: Float, y: Float) {
        holding = (holding - 1).coerceAtLeast(0)
        if (holding == 0 && vy < CUT && deadT < 0) vy = CUT
    }

    override fun touchCancel() { holding = 0 }

    // ---------------- Dibujo ----------------

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val tm = runTime().coerceAtLeast(0)
        val who = if (two) "${name(turn)}   " else ""
        val txt = "$who${WORLDS[m.level]}   Tiempo ${G.time(tm)}"
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.38f, right - left, if (two) C.PLAYER[turn] else C.WHITE, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        update()
        val t = gt() - t0
        val T = tile()
        val oy = barH
        val visible = w / T
        val camX = max(0f, px - visible * 0.3f)
        fun sx(x: Float) = (x - camX) * T
        fun sy(y: Float) = oy + y * T

        // Cielo
        if (skyH != h) {
            skyH = h
            sky.shader = LinearGradient(0f, oy, 0f, h, pal.skyTop, pal.skyBot, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, oy, w, h, sky)
        drawBackground(c, camX, T, oy, t)

        // Suelo
        val x0 = floor(camX).toInt() - 1
        val x1 = (camX + visible).toInt() + 1
        for (x in x0..x1) {
            val tp = topAt(x)
            val l = sx(x.toFloat()); val r = sx(x + 1f) + 1f
            if (tp == NONE) {
                if (m.level == 4) continue
                if (m.level == 2 || m.level == 5) {
                    val wave = sin(t / 300.0 + x).toFloat() * T * 0.06f
                    c.drawRect(l, sy(ROWS - 0.55f) + wave, r, h, G.fill(0xFFFF6A1F.toInt()))
                    c.drawRect(l, sy(ROWS - 0.55f) + wave, r, sy(ROWS - 0.42f) + wave, G.fill(0xFFFFC23D.toInt()))
                } else {
                    val icy = m.level == 3
                    c.drawRect(l, sy(ROWS - 0.5f), r, h, G.fill(if (icy) 0xFF4F87AD.toInt() else 0xFF2E8BC0.toInt()))
                    c.drawRect(l, sy(ROWS - 0.5f), r, sy(ROWS - 0.38f), G.fill(if (icy) 0xFFD6EEFA.toInt() else 0xFF8FD3F0.toInt()))
                }
                continue
            }
            c.drawRect(l, sy(tp.toFloat()), r, h, G.fill(pal.dirt))
            if (m.level == 5) {
                val ln = G.stroke(pal.dirtDark, T * 0.05f)
                for (yy in tp until ROWS) {
                    c.drawLine(l, sy(yy + 1f), r, sy(yy + 1f), ln)
                    val off = if (yy % 2 == 0) 0f else 0.5f
                    c.drawLine(l + T * off, sy(yy.toFloat()), l + T * off, sy(yy + 1f), ln)
                }
            } else for (yy in tp until ROWS) {
                val hsh = (x * 73 + yy * 31) and 7
                if (hsh < 3) G.circle(c, l + T * (0.25f + hsh * 0.2f), sy(yy + 0.55f), T * 0.07f, pal.dirtDark)
            }
            c.drawRect(l, sy(tp.toFloat()), r, sy(tp + 0.22f), G.fill(pal.top))
            if (m.level == 4) for (j in 0..2) G.circle(c, l + T * (0.17f + j * 0.33f), sy(tp + 0.08f), T * 0.2f, pal.top)
            if (m.level == 3 && (x * 37) % 5 == 0) {
                G.path.reset(); G.path.moveTo(l + T * 0.3f, sy(tp + 0.2f)); G.path.lineTo(l + T * 0.4f, sy(tp + 0.55f)); G.path.lineTo(l + T * 0.5f, sy(tp + 0.2f)); G.path.close()
                c.drawPath(G.path, G.fill(0xFFD6EEFA.toInt()))
            }
            if (topAt(x - 1) > tp) G.circle(c, l + T * 0.06f, sy(tp + 0.11f), T * 0.11f, pal.top)
            if (topAt(x + 1) > tp) G.circle(c, r - T * 0.06f, sy(tp + 0.11f), T * 0.11f, pal.top)
            if (x in spike.indices && spike[x]) drawSpikes(c, l, sy(tp.toFloat()), T)
        }
        // Plataformas
        for (x in x0..x1) {
            val pr = platAt(x)
            if (pr < 0) continue
            tmp.set(sx(x.toFloat()) + T * 0.03f, sy(pr.toFloat()), sx(x + 1f) - T * 0.03f, sy(pr + 1f) - T * 0.05f)
            G.rr(c, tmp, T * 0.12f, pal.blockDark)
            tmp.inset(T * 0.08f, T * 0.08f)
            G.rr(c, tmp, T * 0.08f, pal.block)
            c.drawLine(tmp.left, tmp.centerY(), tmp.right, tmp.centerY(), G.stroke(pal.blockDark, T * 0.04f))
        }
        // Bandera
        if (flagX < camX + visible + 2) drawFlag(c, sx(flagX + 0.5f), sy(GR.toFloat()), T, t)
        // Monedas
        for ((i, co) in coins.withIndex()) {
            if (co.taken >= 0 || co.x < camX - 1 || co.x > camX + visible + 1) continue
            Coco.coin(c, sx(co.x), sy(co.y), T * 0.28f, now, i * 0.4f)
        }
        // Enemigos
        for (e in enemies) {
            if (e.x < camX - 2 || e.x > camX + visible + 2) continue
            if (e.bird && m.level == 5) drawBat(c, sx(e.x), sy(e.y), T, now, e.deadT >= 0)
            else if (e.bird) drawBird(c, sx(e.x), sy(e.y), T, now, e.deadT >= 0)
            else drawBeetle(c, sx(e.x), sy(e.y), T, now, e.deadT >= 0)
        }
        // Chispas
        for (s in sparks) {
            val k = (t - s.t) / 500f
            for (j in 0 until 5) {
                val a = j * 1.2566f
                Icons.star(c, sx(s.x) + cos(a) * k * T * 0.7f, sy(s.y) + sin(a) * k * T * 0.7f, T * 0.1f * (1 - k), C.alpha(s.color, 1 - k))
            }
        }
        // Jugador
        val blink = deadT < 0 && t - hurtT < 1500 && (t / 90) % 2 == 0L
        if (!blink) {
            val hx = sx(px); val hy = sy(py)
            if (deadT >= 0) {
                c.save(); c.rotate(((t - deadT) * 0.4f).coerceAtMost(180f), hx, hy - T * 0.5f)
                Coco.draw(c, hx, hy, T * 1.12f, phase, true, turn, now, hurt = true)
                c.restore()
            } else Coco.draw(c, hx, hy, T * 1.12f, phase, !grounded, turn, now, hurt = t - hurtT < 400)
        }

        drawStatus(c, T, oy, t)
        if (!intro && t < START + 500) countdown(c, t, w / 2, (barH + h) / 2, (h - barH) * 0.28f, START)
        if (finishT >= 0 && runEnd < 0) G.text(c, "¡Meta!", w / 2, oy + (h - oy) * 0.35f, barH * 1.1f, C.AMBER, font = Fonts.black)
        if (intro) drawIntro(c)
    }

    private fun drawStatus(c: Canvas, T: Float, oy: Float, t: Long) {
        val hs = T * 0.45f
        val y = oy + T * 0.55f
        G.rr(c, pad, oy + T * 0.12f, pad + hs * 1.25f * HEARTS + hs * 0.6f, oy + T * 0.98f, T * 0.3f, C.alpha(C.BG, 0.45f))
        for (i in 0 until HEARTS) Coco.heart(c, pad + hs * 0.9f + i * hs * 1.25f, y, hs, if (i < hearts) C.RED else C.alpha(C.WHITE, 0.3f))
        // Monedas
        val txt = "$collected"
        val tw = G.width(txt, T * 0.55f, Fonts.black)
        val bw = tw + T * 1.2f
        G.rr(c, w - pad - bw, oy + T * 0.12f, w - pad, oy + T * 0.98f, T * 0.3f, C.alpha(C.BG, 0.45f))
        Coco.coin(c, w - pad - bw + T * 0.45f, y, T * 0.24f, 0)
        G.text(c, txt, w - pad - T * 0.25f, y, T * 0.55f, C.WHITE, Paint.Align.RIGHT, Fonts.black)
        // Progreso
        val pw = w * 0.3f; val pl = (w - pw) / 2
        val k = (px / flagX).coerceIn(0f, 1f)
        G.rr(c, pl, y - T * 0.08f, pl + pw, y + T * 0.08f, T * 0.08f, C.alpha(C.WHITE, 0.35f))
        G.rr(c, pl, y - T * 0.08f, pl + pw * k, y + T * 0.08f, T * 0.08f, Coco.bodyColor(turn))
        G.circle(c, pl + pw * k, y, T * 0.17f, Coco.bodyColor(turn))
        Icons.star(c, pl + pw + T * 0.3f, y, T * 0.22f, C.WHITE)
    }

    private fun drawIntro(c: Canvas) {
        G.rr(c, 0f, barH, w, h, 0f, C.alpha(C.BG, 0.88f))
        val first = runsDone == 0
        G.text(c, if (first) "Primero corre" else "Ahora le toca a", w / 2, h * 0.3f, barH * 0.42f, C.TEXT2)
        G.textFit(c, name(turn), w / 2, h * 0.44f, barH * 0.9f, w * 0.7f, C.PLAYER[turn], font = Fonts.black)
        G.text(c, "Los dos corren el mismo camino", w / 2, h * 0.57f, barH * 0.32f, C.WHITE)
        Coco.draw(c, w * 0.22f, h * 0.62f, barH * 1.6f, 0f, false, turn, gt())
        val bw = w * 0.3f; val bh = barH
        ui.button(c, RectF(w / 2 - bw / 2, h * 0.68f, w / 2 + bw / 2, h * 0.68f + bh), "Empezar", Btn.PRIMARY) {
            intro = false; t0 = gt(); lastT = 0L
        }
    }

    private fun drawBackground(c: Canvas, camX: Float, T: Float, oy: Float, t: Long) {
        val gy = oy + GR * T
        when (m.level) {
            1 -> { // Playa: mar, sol y palmeras
                G.circle(c, w * 0.8f, oy + T * 1.8f, T * 1.1f, 0xFFFFF0B5.toInt())
                c.drawRect(0f, gy - T * 1.6f, w, gy, G.fill(pal.far))
                c.drawRect(0f, gy - T * 1.6f, w, gy - T * 1.45f, G.fill(0xFF8FD3F0.toInt()))
                val off = camX * T * 0.4f
                val gap = T * 9
                var x = -(off % gap) - gap
                while (x < w + gap) { palm(c, x + T * 3, gy, T); x += gap }
            }
            2 -> { // Volcán: montañas y brasas
                val off = camX * T * 0.15f
                val gap = T * 16
                var x = -(off % gap) - gap
                while (x < w + gap) {
                    G.path.reset()
                    G.path.moveTo(x, gy); G.path.lineTo(x + T * 5.5f, oy + T * 2.2f); G.path.lineTo(x + T * 7f, oy + T * 2.2f)
                    G.path.lineTo(x + T * 13f, gy); G.path.close()
                    c.drawPath(G.path, G.fill(pal.far))
                    G.oval(c, x + T * 6.25f, oy + T * 2.2f, T * 0.75f, T * 0.2f, 0xFFFF6A1F.toInt())
                    x += gap
                }
                for (i in 0 until 14) {
                    val ex = ((i * 211 + t * 0.02f * (1 + i % 3) - camX * T * 0.3f) % w + w) % w
                    val ey = h - ((t * 0.05f * (1 + i % 4) + i * 97) % (h - oy))
                    G.circle(c, ex, ey, T * 0.05f, C.alpha(0xFFFFA040.toInt(), 0.7f))
                }
            }
            3 -> { // Nieve: montañas con nieve y copos
                val off = camX * T * 0.15f
                val gap = T * 12
                var x = -(off % gap) - gap
                while (x < w + gap) {
                    G.path.reset(); G.path.moveTo(x, gy); G.path.lineTo(x + T * 5f, oy + T * 1.6f); G.path.lineTo(x + T * 10f, gy); G.path.close()
                    c.drawPath(G.path, G.fill(pal.far))
                    G.path.reset(); G.path.moveTo(x + T * 3.6f, oy + T * 2.6f); G.path.lineTo(x + T * 5f, oy + T * 1.6f); G.path.lineTo(x + T * 6.4f, oy + T * 2.6f)
                    G.path.lineTo(x + T * 5.6f, oy + T * 2.3f); G.path.lineTo(x + T * 5f, oy + T * 2.7f); G.path.lineTo(x + T * 4.4f, oy + T * 2.3f); G.path.close()
                    c.drawPath(G.path, G.fill(C.WHITE))
                    x += gap
                }
                for (i in 0 until 40) {
                    val fx = ((i * 157 + sin(t / 900.0 + i).toFloat() * T * 0.6f - camX * T * 0.5f) % w + w) % w
                    val fy = oy + ((t * 0.03f * (1 + i % 3) + i * 71) % (h - oy))
                    G.circle(c, fx, fy, T * (0.04f + (i % 3) * 0.02f), C.alpha(C.WHITE, 0.85f))
                }
            }
            4 -> { // Nubes: arcoíris y nubes grandes
                val off = camX * T * 0.08f
                val rx = w * 0.7f - off % (w * 1.6f) + w * 0.4f
                val cols = intArrayOf(0x66FF6B6B, 0x66FFB547, 0x66FFE15A, 0x664CD08A, 0x665CC8FF, 0x669B7BFF)
                for ((j, col) in cols.withIndex()) {
                    G.r.set(rx - T * (5f - j * 0.35f), oy + T * (1.2f + j * 0.35f), rx + T * (5f - j * 0.35f), oy + T * (11f - j * 0.35f))
                    c.drawArc(G.r, 180f, 180f, false, G.stroke(col, T * 0.36f))
                }
                for (layer in 0..1) {
                    val lo = camX * T * (0.12f + layer * 0.15f)
                    val cg = T * (9 + layer * 4)
                    var x = -(lo % cg) - cg
                    var i = 0
                    while (x < w + cg) {
                        val cy = oy + T * (if (layer == 0) 1.5f + (i % 2) * 1.2f else 5.6f)
                        val col = if (layer == 0) 0xCCFFFFFF.toInt() else 0xFFFFFFFF.toInt()
                        G.oval(c, x + T * 2, cy, T * 1.6f, T * 0.6f, col)
                        G.oval(c, x + T * 2.8f, cy - T * 0.45f, T * 1f, T * 0.7f, col)
                        G.oval(c, x + T * 1.3f, cy - T * 0.25f, T * 0.7f, T * 0.5f, col)
                        x += cg; i++
                    }
                }
            }
            5 -> { // Castillo: muro de piedra, ventanas y antorchas
                val off = camX * T * 0.4f
                val bw = T * 1.2f; val bh = T * 0.6f
                val ln = G.stroke(0xFF1C1C23.toInt(), T * 0.04f)
                var yy = oy; var row = 0
                while (yy < gy) {
                    c.drawLine(0f, yy, w, yy, ln)
                    var xx = -(off % bw) - bw + (if (row % 2 == 0) 0f else bw / 2)
                    while (xx < w) { c.drawLine(xx, yy, xx, yy + bh, ln); xx += bw }
                    yy += bh; row++
                }
                val gap = T * 7
                var x = -(off % gap) - gap
                while (x < w + gap) {
                    val wx = x + T * 2
                    G.r.set(wx - T * 0.55f, oy + T * 1.6f, wx + T * 0.55f, oy + T * 4f)
                    c.drawRoundRect(G.r, T * 0.55f, T * 0.55f, G.fill(0xFF0D0E14.toInt()))
                    G.circle(c, wx + T * 0.2f, oy + T * 2.3f, T * 0.06f, 0xFFFFF3C4.toInt())
                    val tx = x + T * 5.5f; val ty = oy + T * 3.4f
                    c.drawRect(tx - T * 0.08f, ty, tx + T * 0.08f, ty + T * 0.7f, G.fill(0xFF6B4A2A.toInt()))
                    val fl = (sin(t / 60.0 + x).toFloat() * 0.5f + 0.5f)
                    G.circle(c, tx, ty - T * 0.15f, T * (0.7f + 0.15f * fl), C.alpha(0xFFFFA040.toInt(), 0.12f))
                    G.oval(c, tx, ty - T * 0.12f, T * 0.16f, T * (0.24f + 0.06f * fl), 0xFFFF7A1F.toInt())
                    G.oval(c, tx, ty - T * 0.08f, T * 0.08f, T * 0.13f, 0xFFFFD34D.toInt())
                    x += gap
                }
            }
            else -> { // Pradera: nubes y colinas
                val coff = camX * T * 0.1f
                val cg = T * 11
                var x = -(coff % cg) - cg
                var i = 0
                while (x < w + cg) {
                    val cy = oy + T * (1.2f + (i % 2) * 0.9f)
                    G.oval(c, x + T * 2, cy, T * 1.1f, T * 0.45f, 0xF2FFFFFF.toInt())
                    G.oval(c, x + T * 2.6f, cy - T * 0.3f, T * 0.7f, T * 0.5f, 0xF2FFFFFF.toInt())
                    x += cg; i++
                }
            }
        }
        if (m.level == 0 || m.level == 2 || m.level == 3) {
            c.save(); c.clipRect(0f, oy, w, gy + T * 0.3f)
            val off = camX * T * 0.35f
            val gap = T * 8
            var x = -(off % gap) - gap
            while (x < w + gap) {
                G.oval(c, x + T * 4, gy + T * 0.3f, T * 4.2f, T * 2.1f, pal.near)
                x += gap
            }
            c.restore()
        }
    }

    private fun palm(c: Canvas, x: Float, gy: Float, T: Float) {
        val trunk = G.stroke(0xFF8A6239.toInt(), T * 0.22f)
        G.path.reset(); G.path.moveTo(x, gy); G.path.quadTo(x + T * 0.5f, gy - T * 1.6f, x + T * 0.2f, gy - T * 3f)
        c.drawPath(G.path, trunk)
        val leaf = G.stroke(pal.near, T * 0.2f)
        val tx = x + T * 0.2f; val ty = gy - T * 3f
        for (a in floatArrayOf(-2.6f, -2.0f, -1.2f, -0.5f, 0.1f)) {
            G.path.reset(); G.path.moveTo(tx, ty)
            G.path.quadTo(tx + cos(a) * T * 0.9f, ty + sin(a) * T * 0.9f - T * 0.3f, tx + cos(a) * T * 1.5f, ty + sin(a) * T * 0.6f + T * 0.4f)
            c.drawPath(G.path, leaf)
        }
    }

    private fun drawSpikes(c: Canvas, l: Float, gy: Float, T: Float) {
        for (i in 0 until 3) {
            val bx = l + T * (0.05f + i * 0.3f)
            G.path.reset()
            G.path.moveTo(bx, gy); G.path.lineTo(bx + T * 0.15f, gy - T * 0.5f); G.path.lineTo(bx + T * 0.3f, gy); G.path.close()
            c.drawPath(G.path, G.fill(0xFFC9D1DB.toInt()))
            G.path.reset()
            G.path.moveTo(bx + T * 0.15f, gy); G.path.lineTo(bx + T * 0.15f, gy - T * 0.5f); G.path.lineTo(bx + T * 0.3f, gy); G.path.close()
            c.drawPath(G.path, G.fill(0xFF8E98A6.toInt()))
        }
    }

    private fun drawFlag(c: Canvas, x: Float, gy: Float, T: Float, t: Long) {
        c.drawLine(x, gy, x, gy - T * 4.2f, G.stroke(0xFFE8ECF1.toInt(), T * 0.12f))
        G.circle(c, x, gy - T * 4.25f, T * 0.15f, C.AMBER)
        val wave = sin(t / 180.0).toFloat() * T * 0.12f
        G.path.reset()
        G.path.moveTo(x, gy - T * 4.1f)
        G.path.quadTo(x + T * 0.8f, gy - T * 4.1f + wave, x + T * 1.6f, gy - T * 3.85f)
        G.path.quadTo(x + T * 0.8f, gy - T * 3.3f - wave, x, gy - T * 3.3f)
        G.path.close()
        c.drawPath(G.path, G.fill(C.GREEN))
        Icons.star(c, x + T * 0.65f, gy - T * 3.72f, T * 0.2f, C.WHITE)
        G.rr(c, x - T * 0.4f, gy - T * 0.3f, x + T * 0.4f, gy, T * 0.08f, pal.blockDark)
    }

    private fun drawBeetle(c: Canvas, cx: Float, bottom: Float, T: Float, now: Long, dead: Boolean) {
        val shell = 0xFF8E44AD.toInt()
        if (dead) {
            G.oval(c, cx, bottom - T * 0.1f, T * 0.45f, T * 0.1f, shell)
            return
        }
        val a = sin(now / 80.0).toFloat()
        val leg = G.stroke(C.DARK, T * 0.06f)
        for (i in -1..1) {
            val lx = cx + i * T * 0.22f
            c.drawLine(lx, bottom - T * 0.18f, lx + (if (i == 0) -a else a) * T * 0.08f, bottom, leg)
        }
        G.r.set(cx - T * 0.42f, bottom - T * 0.68f, cx + T * 0.42f, bottom - T * 0.06f)
        c.drawArc(G.r, 180f, 180f, true, G.fill(shell))
        c.drawRect(cx - T * 0.42f, bottom - T * 0.38f, cx + T * 0.42f, bottom - T * 0.14f, G.fill(shell))
        c.drawLine(cx, bottom - T * 0.66f, cx, bottom - T * 0.2f, G.stroke(C.mix(shell, C.DARK, 0.4f), T * 0.04f))
        G.circle(c, cx + T * 0.2f, bottom - T * 0.48f, T * 0.07f, 0xFFD7A6EB.toInt())
        G.circle(c, cx + T * 0.12f, bottom - T * 0.3f, T * 0.05f, 0xFFD7A6EB.toInt())
        // Cabeza mirando a la izquierda
        G.circle(c, cx - T * 0.42f, bottom - T * 0.3f, T * 0.2f, C.DARK)
        G.circle(c, cx - T * 0.5f, bottom - T * 0.35f, T * 0.07f, C.WHITE)
        G.circle(c, cx - T * 0.53f, bottom - T * 0.35f, T * 0.035f, C.DARK)
    }

    private fun drawBat(c: Canvas, cx: Float, cy: Float, T: Float, now: Long, dead: Boolean) {
        val body = 0xFF5B3A78.toInt()
        if (dead) { G.oval(c, cx, cy, T * 0.3f, T * 0.12f, C.alpha(body, 0.6f)); return }
        val flap = sin(now / 60.0).toFloat()
        for (sgn in floatArrayOf(-1f, 1f)) {
            G.path.reset()
            G.path.moveTo(cx, cy - T * 0.05f)
            G.path.lineTo(cx + sgn * T * 0.55f, cy - T * 0.05f - flap * T * 0.3f)
            G.path.lineTo(cx + sgn * T * 0.45f, cy + T * 0.12f)
            G.path.lineTo(cx + sgn * T * 0.3f, cy + T * 0.05f)
            G.path.lineTo(cx + sgn * T * 0.18f, cy + T * 0.14f)
            G.path.close()
            c.drawPath(G.path, G.fill(C.mix(body, C.DARK, 0.3f)))
        }
        G.circle(c, cx, cy, T * 0.22f, body)
        for (sgn in floatArrayOf(-1f, 1f)) {
            G.path.reset(); G.path.moveTo(cx + sgn * T * 0.06f, cy - T * 0.18f); G.path.lineTo(cx + sgn * T * 0.16f, cy - T * 0.34f); G.path.lineTo(cx + sgn * T * 0.2f, cy - T * 0.12f); G.path.close()
            c.drawPath(G.path, G.fill(body))
        }
        G.circle(c, cx - T * 0.08f, cy - T * 0.04f, T * 0.06f, 0xFFFFE15A.toInt())
        G.circle(c, cx + T * 0.08f, cy - T * 0.04f, T * 0.06f, 0xFFFFE15A.toInt())
    }

    private fun drawBird(c: Canvas, cx: Float, cy: Float, T: Float, now: Long, dead: Boolean) {
        val body = 0xFFE84A5F.toInt()
        if (dead) {
            G.oval(c, cx, cy, T * 0.35f, T * 0.15f, C.alpha(body, 0.6f))
            return
        }
        val flap = sin(now / 70.0).toFloat()
        G.path.reset()
        G.path.moveTo(cx - T * 0.05f, cy - T * 0.05f)
        G.path.lineTo(cx + T * 0.25f, cy - T * 0.05f - flap * T * 0.4f)
        G.path.lineTo(cx + T * 0.32f, cy + T * 0.05f)
        G.path.close()
        c.drawPath(G.path, G.fill(C.mix(body, C.DARK, 0.3f)))
        G.oval(c, cx, cy, T * 0.36f, T * 0.27f, body)
        G.path.reset()
        G.path.moveTo(cx - T * 0.32f, cy - T * 0.06f); G.path.lineTo(cx - T * 0.56f, cy + T * 0.02f); G.path.lineTo(cx - T * 0.3f, cy + T * 0.08f); G.path.close()
        c.drawPath(G.path, G.fill(0xFFFFB547.toInt()))
        G.circle(c, cx - T * 0.16f, cy - T * 0.08f, T * 0.08f, C.WHITE)
        G.circle(c, cx - T * 0.19f, cy - T * 0.08f, T * 0.04f, C.DARK)
        G.path.reset()
        G.path.moveTo(cx + T * 0.3f, cy); G.path.lineTo(cx + T * 0.52f, cy - T * 0.12f); G.path.lineTo(cx + T * 0.48f, cy + T * 0.08f); G.path.close()
        c.drawPath(G.path, G.fill(body))
    }
}
