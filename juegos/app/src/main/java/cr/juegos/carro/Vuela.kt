package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pío, el pajarito del juego de volar. */
object Pio {
    fun draw(c: Canvas, cx: Float, cy: Float, r: Float, player: Int, flap: Float, tilt: Float, dead: Boolean) {
        val body = Coco.bodyColor(player)
        c.save()
        c.rotate(tilt, cx, cy)
        // Cola
        G.path.reset()
        G.path.moveTo(cx - r * 0.8f, cy)
        G.path.lineTo(cx - r * 1.35f, cy - r * 0.45f)
        G.path.lineTo(cx - r * 1.3f, cy + r * 0.35f)
        G.path.close()
        c.drawPath(G.path, G.fill(C.mix(body, C.DARK, 0.25f)))
        // Cuerpo
        G.circle(c, cx, cy, r, body)
        G.oval(c, cx + r * 0.15f, cy + r * 0.38f, r * 0.62f, r * 0.45f, Coco.CREAM)
        // Ala
        val wy = cy + r * 0.05f - flap * r * 0.55f
        G.oval(c, cx - r * 0.25f, wy, r * 0.55f, r * 0.32f, C.mix(body, C.WHITE, 0.3f))
        // Ojo y pico
        if (dead) {
            val p = G.stroke(C.DARK, r * 0.12f)
            c.drawLine(cx + r * 0.25f, cy - r * 0.45f, cx + r * 0.55f, cy - r * 0.15f, p)
            c.drawLine(cx + r * 0.25f, cy - r * 0.15f, cx + r * 0.55f, cy - r * 0.45f, p)
        } else {
            G.circle(c, cx + r * 0.4f, cy - r * 0.3f, r * 0.28f, C.WHITE)
            G.circle(c, cx + r * 0.48f, cy - r * 0.28f, r * 0.14f, C.DARK)
            G.circle(c, cx + r * 0.52f, cy - r * 0.34f, r * 0.05f, C.WHITE)
        }
        G.path.reset()
        G.path.moveTo(cx + r * 0.8f, cy - r * 0.08f)
        G.path.lineTo(cx + r * 1.38f, cy + r * 0.12f)
        G.path.lineTo(cx + r * 0.8f, cy + r * 0.32f)
        G.path.close()
        c.drawPath(G.path, G.fill(0xFFFF7A2F.toInt()))
        c.restore()
    }
}

/** Vuela entre los troncos tocando la pantalla. Con 2 jugadores, cada uno toca su mitad. */
class VuelaScreen(app: MainActivity, m: Match) : GameScreen(app, m) {
    private val two = m.players == 2
    private val speed = when (m.level) { 0 -> 0.36f; 2 -> 0.52f; else -> 0.44f }   // alturas por segundo
    private val gap = when (m.level) { 0 -> 0.36f; 2 -> 0.27f; else -> 0.31f }
    private val SPACING = 0.82f
    private val POST_W = 0.17f
    private val GRAV = 2.7f
    private val FLAP = -0.78f
    private val R = 0.048f
    private val GROUND = 0.09f
    private val START = 3000L

    private class Bird(val p: Int) {
        var y = 0.45f; var vy = 0f; var alive = true; var score = 0; var flapT = -10_000L; var deadT = -1L
    }
    private class Post(val x: Float, val gapY: Float) { val passed = BooleanArray(2) }

    private var birds = listOf(Bird(0))
    private val posts = ArrayList<Post>()
    private var dist = 0f          // cuánto ha avanzado el mundo (en alturas)
    private var started = false
    private var lastT = 0L
    private var seed = 0L
    private var postRnd = Random()
    private var nextPostX = 0f
    private var ended = false
    private val sky = Paint()
    private var skyH = 0f

    init { restart() }

    override fun restart() {
        birds = if (two) listOf(Bird(0), Bird(1)) else listOf(Bird(0))
        posts.clear(); dist = 0f; started = two; lastT = 0L; ended = false
        seed = rnd.nextLong(); postRnd = Random(seed)
        nextPostX = 1.6f
        resetClock()
    }

    private fun H() = h - barH
    private fun birdX() = (w / H()) * 0.28f

    private fun spawn() {
        val viewW = w / H()
        while (nextPostX < dist + viewW + 1f) {
            val margin = 0.12f + gap / 2
            val gy = margin + postRnd.nextFloat() * (1f - GROUND - 2 * margin)
            posts.add(Post(nextPostX, gy))
            nextPostX += SPACING * (0.9f + postRnd.nextFloat() * 0.25f)
        }
        posts.removeAll { it.x + POST_W < dist - 1f }
    }

    private fun flap(b: Bird) {
        if (!b.alive) return
        b.vy = FLAP; b.flapT = gt()
        Sound.play(Sound.FLIP)
    }

    private fun update(t: Long) {
        val dt = ((t - lastT) / 1000f).coerceIn(0f, 0.05f)
        lastT = t
        if (blocked || ended) return
        if (two && t < START) return
        if (!started) return
        if (birds.none { it.alive }) return
        var left = dt
        while (left > 0f) {
            val s = min(left, 1 / 120f); left -= s
            dist += speed * s
            spawn()
            val bx = dist + birdX()
            for (b in birds) {
                if (!b.alive) {
                    if (b.y < 1f - GROUND - R) { b.vy = min(b.vy + GRAV * s, 1.4f); b.y = min(b.y + b.vy * s, 1f - GROUND - R) }
                    continue
                }
                b.vy = min(b.vy + GRAV * s, 1.3f)
                b.y += b.vy * s
                if (b.y < R) { b.y = R; b.vy = 0f }
                var hit = b.y > 1f - GROUND - R
                for (p in posts) {
                    if (bx + R * 0.85f < p.x || bx - R * 0.85f > p.x + POST_W) {
                        if (!p.passed[b.p] && bx > p.x + POST_W) { p.passed[b.p] = true; b.score++; Sound.play(Sound.COIN) }
                        continue
                    }
                    if (b.y - R * 0.85f < p.gapY - gap / 2 || b.y + R * 0.85f > p.gapY + gap / 2) hit = true
                }
                if (hit) { b.alive = false; b.deadT = t; b.vy = -0.3f; Sound.play(Sound.BONK) }
            }
        }
        if (birds.none { it.alive }) end()
    }

    private fun end() {
        ended = true
        if (two) {
            val a = birds[0]; val b = birds[1]
            val d = "${name(0)}: ${a.score}   ${name(1)}: ${b.score}"
            // Gana quien pasó más troncos; si empatan, quien duró más.
            val win = when {
                a.score != b.score -> if (a.score > b.score) 0 else 1
                a.deadT != b.deadT -> if (a.deadT > b.deadT) 0 else 1
                else -> -1
            }
            finish(if (win < 0) Result(Result.DRAW, "Empate", d) else Result(win, "¡Ganó ${name(win)}!", d), 1000)
        } else {
            val s = birds[0].score
            val nr = s > 0 && Store.best("vuela_${m.level}", s.toLong(), false)
            val word = if (s == 1) "tronco" else "troncos"
            finish(Result(Result.SOLO, "Pasaste $s $word", if (nr) "¡Nuevo récord!" else "Récord: ${Store.long("vuela_${m.level}")}"), 1000)
        }
    }

    override fun drawHud(c: Canvas, left: Float, right: Float) {
        val txt = if (two) "${name(0)}: ${birds[0].score}     ${name(1)}: ${birds[1].score}"
        else "Récord: ${Store.long("vuela_${m.level}")}"
        G.textFit(c, txt, (left + right) / 2, barH / 2, barH * 0.36f, right - left, C.WHITE, font = Fonts.black)
    }

    override fun drawGame(c: Canvas, now: Long) {
        val t = gt()
        update(t)
        val Hh = H(); val oy = barH
        fun sx(x: Float) = (x - dist) * Hh
        fun sy(y: Float) = oy + y * Hh
        // Cielo y fondo
        if (skyH != h) { skyH = h; sky.shader = LinearGradient(0f, oy, 0f, h, 0xFF63B8F2.toInt(), 0xFFFFE3C2.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, oy, w, h, sky)
        val cg = Hh * 1.3f
        var x = -((dist * Hh * 0.15f) % cg) - cg
        var i = 0
        while (x < w + cg) {
            val cy = oy + Hh * (0.14f + (i % 3) * 0.1f)
            G.oval(c, x + cg * 0.3f, cy, Hh * 0.16f, Hh * 0.06f, 0xE6FFFFFF.toInt())
            G.oval(c, x + cg * 0.36f, cy - Hh * 0.04f, Hh * 0.09f, Hh * 0.06f, 0xE6FFFFFF.toInt())
            x += cg; i++
        }
        val hg = Hh * 0.9f
        x = -((dist * Hh * 0.4f) % hg) - hg
        while (x < w + hg) { G.oval(c, x + hg / 2, sy(1f - GROUND) + Hh * 0.05f, hg * 0.6f, Hh * 0.2f, 0xFF7CC97A.toInt()); x += hg }
        // Troncos
        for (p in posts) {
            val l = sx(p.x); val r = sx(p.x + POST_W)
            if (r < 0 || l > w) continue
            drawLog(c, l, r, oy, sy(p.gapY - gap / 2), true)
            drawLog(c, l, r, sy(p.gapY + gap / 2), sy(1f - GROUND), false)
        }
        // Suelo
        c.drawRect(0f, sy(1f - GROUND), w, h, G.fill(0xFF96612F.toInt()))
        c.drawRect(0f, sy(1f - GROUND), w, sy(1f - GROUND) + Hh * 0.025f, G.fill(0xFF5BCB4E.toInt()))
        val st = Hh * 0.12f
        x = -((dist * Hh) % st)
        while (x < w) { c.drawLine(x, sy(1f - GROUND) + Hh * 0.04f, x + st * 0.4f, h, G.stroke(0xFF865427.toInt(), Hh * 0.012f)); x += st }
        // Pájaros
        val bxs = birdX() * Hh
        for (b in birds) {
            val hover = if (!started || (two && t < START)) sin(t / 180.0).toFloat() * 0.015f else 0f
            if (!started || (two && t < START)) b.y = 0.45f + hover
            val k = ((t - b.flapT) / 160f).coerceIn(0f, 1f)
            val flapA = if (b.alive) (if (k < 1f) 1 - k else sin(t / 70.0).toFloat() * 0.25f) else 0f
            val tilt = if (!b.alive) 70f else (b.vy * 45f).coerceIn(-25f, 70f)
            val off = if (two) (if (b.p == 0) -0.03f else 0.03f) * Hh else 0f
            Pio.draw(c, bxs + off, sy(b.y), R * Hh * 1.15f, b.p, flapA, tilt, !b.alive)
        }
        // Puntos grandes
        if (!two) {
            G.text(c, "${birds[0].score}", w / 2, oy + Hh * 0.12f, Hh * 0.13f, C.alpha(C.DARK, 0.25f), font = Fonts.black)
            G.text(c, "${birds[0].score}", w / 2 - Hh * 0.006f, oy + Hh * 0.115f, Hh * 0.13f, C.WHITE, font = Fonts.black)
        }
        // Indicaciones
        if (!two && !started) {
            G.rr(c, w / 2 - Hh * 0.55f, oy + Hh * 0.58f, w / 2 + Hh * 0.55f, oy + Hh * 0.74f, Hh * 0.08f, C.alpha(C.BG, 0.7f))
            G.text(c, "Toca la pantalla para aletear", w / 2, oy + Hh * 0.66f, Hh * 0.055f, C.WHITE, font = Fonts.black)
        }
        if (two && t < START + 600) {
            c.drawLine(w / 2, oy, w / 2, sy(1f - GROUND), G.stroke(C.alpha(C.WHITE, 0.6f), Hh * 0.008f))
            G.textFit(c, "${name(0)}: toca aquí", w * 0.25f, oy + Hh * 0.75f, Hh * 0.06f, w * 0.4f, C.PLAYER[0], font = Fonts.black)
            G.textFit(c, "${name(1)}: toca aquí", w * 0.75f, oy + Hh * 0.75f, Hh * 0.06f, w * 0.4f, C.PLAYER[1], font = Fonts.black)
            countdown(c, t, w / 2, oy + Hh * 0.4f, Hh * 0.22f, START)
        }
    }

    private fun drawLog(c: Canvas, l: Float, r: Float, top: Float, bottom: Float, capBottom: Boolean) {
        if (bottom <= top) return
        val wd = r - l
        c.drawRect(l, top, r, bottom, G.fill(0xFFA0683A.toInt()))
        c.drawRect(l + wd * 0.12f, top, l + wd * 0.22f, bottom, G.fill(0xFFB57C4A.toInt()))
        c.drawRect(r - wd * 0.2f, top, r, bottom, G.fill(0xFF85532B.toInt()))
        // Corte del tronco con anillos
        val cy = if (capBottom) bottom else top
        val capH = wd * 0.28f
        G.oval(c, (l + r) / 2, cy, wd * 0.6f, capH, 0xFF85532B.toInt())
        G.oval(c, (l + r) / 2, cy, wd * 0.55f, capH * 0.82f, 0xFFE9C48E.toInt())
        G.r.set((l + r) / 2 - wd * 0.36f, cy - capH * 0.52f, (l + r) / 2 + wd * 0.36f, cy + capH * 0.52f)
        c.drawOval(G.r, G.stroke(0xFFC99B62.toInt(), wd * 0.04f))
        G.r.set((l + r) / 2 - wd * 0.18f, cy - capH * 0.26f, (l + r) / 2 + wd * 0.18f, cy + capH * 0.26f)
        c.drawOval(G.r, G.stroke(0xFFC99B62.toInt(), wd * 0.04f))
        // Hojita
        val ly = if (capBottom) bottom - wd * 0.6f else top + wd * 0.6f
        G.oval(c, r + wd * 0.05f, ly, wd * 0.16f, wd * 0.08f, 0xFF5BCB4E.toInt())
    }

    override fun touchDown(id: Int, x: Float, y: Float) {
        if (blocked || ended || y < barH) return
        val t = gt()
        if (two) {
            if (t < START) return
            flap(birds[if (x < w / 2) 0 else 1])
        } else {
            if (!started) { started = true; lastT = t }
            flap(birds[0])
        }
    }
}
