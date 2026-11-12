package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class Tournament(val names: Array<String>, val games: List<GameId>, val laps: Int) {
    val matches: List<GameId> = (1..laps).flatMap { games }
    val results = IntArray(matches.size) { PENDING }
    var tiebreak = PENDING
    var saved = false

    fun score(p: Int) = results.count { it == p } + (if (tiebreak == p) 1 else 0)
    fun next() = results.indexOfFirst { it == PENDING }
    fun mainDone() = next() < 0
    fun needsTiebreak() = mainDone() && score(0) == score(1) && tiebreak !in 0..1
    fun finished() = mainDone() && score(0) != score(1)
    fun winner() = if (!mainDone()) -1 else if (score(0) > score(1)) 0 else if (score(1) > score(0)) 1 else -1

    companion object { const val PENDING = -9 }
}

class TourSetupScreen(app: MainActivity) : Screen(app) {
    private val selected = BooleanArray(GameId.values().size)
    private var laps = Store.long("cfg_vueltas", 1).toInt().coerceIn(1, 3)

    init {
        val mask = Store.long("cfg_torneo", 0b111111110L) // todos menos el solitario
        for (i in selected.indices) selected[i] = (mask shr i) and 1L == 1L
    }

    override fun animating() = true

    override fun draw(c: Canvas, now: Long) {
        header(c, "Torneo de 2 jugadores")
        val x0 = pad * 2; val x1 = w - pad * 2
        val rowH = barH * 0.9f
        var y = barH + pad * 1.6f
        G.text(c, "Jugadores (toca para cambiar el nombre)", x0, y + barH * 0.18f, barH * 0.29f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.42f
        val nw = (x1 - x0 - pad) / 2
        for (i in 0..1) {
            val r = RectF(x0 + i * (nw + pad), y, x0 + i * (nw + pad) + nw, y + rowH)
            G.rr(c, r, rowH * 0.24f, if (ui.pressed(r)) C.CARD_HI else C.alpha(C.PLAYER[i], 0.16f))
            G.rrLine(c, r, rowH * 0.24f, C.PLAYER[i], rowH * 0.05f)
            G.textFit(c, Store.name(i), r.centerX(), r.centerY(), rowH * 0.42f, nw * 0.85f, C.PLAYER[i], font = Fonts.black)
            ui.area(r) { app.editName(i, Store.name(i)) { Store.setName(i, it) } }
        }
        y += rowH + pad * 1.4f
        G.text(c, "Juegos del torneo", x0, y + barH * 0.18f, barH * 0.29f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.42f
        val games = GameId.values()
        val gap = pad
        val gcols = if (games.size > 6) (games.size + 1) / 2 else games.size
        val grows = (games.size + gcols - 1) / gcols
        val gw = (x1 - x0 - gap * (gcols - 1)) / gcols
        val gh = min(rowH * (if (grows > 1) 0.85f else 1.25f), h * 0.2f)
        for ((i, g) in games.withIndex()) {
            val gx = x0 + (i % gcols) * (gw + gap); val gy = y + (i / gcols) * (gh + gap)
            val r = RectF(gx, gy, gx + gw, gy + gh)
            val label = if (G.width(g.title, gh * 0.3f) > gw * 0.85f) wrap2(g.title) else g.title
            ui.button(c, r, label, if (selected[i]) Btn.SELECTED else Btn.GHOST, gh * (if (grows > 1) 0.36f else 0.28f)) { selected[i] = !selected[i] }
        }
        y += grows * gh + (grows - 1) * gap + pad * 1.4f
        G.text(c, "Vueltas (cada vuelta juega una vez cada juego)", x0, y + barH * 0.18f, barH * 0.29f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.42f
        val segW = (x1 - x0) * 0.5f
        ui.segmented(c, RectF(x0, y, x0 + segW, y + rowH), listOf("1", "2", "3"), laps - 1) { laps = it + 1 }

        val count = selected.count { it } * laps
        val pr = RectF(x0 + segW + pad * 3, y, x1, y + rowH)
        ui.button(c, pr, if (count == 0) "Elige al menos un juego" else "Empezar torneo ($count partidas)", Btn.PRIMARY,
            rowH * 0.36f, enabled = count > 0) { start() }
    }

    private fun start() {
        var mask = 0L
        for (i in selected.indices) if (selected[i]) mask = mask or (1L shl i)
        Store.put("cfg_torneo", mask); Store.put("cfg_vueltas", laps.toLong())
        val games = GameId.values().filterIndexed { i, _ -> selected[i] }
        stage.replace(TourScreen(app, Tournament(Store.names(), games, laps)))
    }
}

class TourScreen(app: MainActivity, private val t: Tournament) : Screen(app) {
    private class Bit(var x: Float, var y: Float, val vx: Float, var vy: Float, val color: Int, val size: Float, var rot: Float, val vr: Float)
    private val bits = ArrayList<Bit>()
    private var lastFrame = 0L
    private var celebrated = false
    private var lastResultAt = 0L

    override fun animating() = true

    override fun onBack(): Boolean {
        if (t.finished()) return false
        app.confirm("¿Salir del torneo? Se perderá el marcador.", "Salir") { stage.pop() }
        return true
    }

    private fun launch(i: Int, game: GameId) {
        val m = Match(game, 2, game.defaultLevel(2), t.names, starter = i % 2, tournament = true) { r ->
            if (i >= 0) t.results[i] = r.winner else t.tiebreak = if (r.winner in 0..1) r.winner else Tournament.PENDING
            lastResultAt = android.os.SystemClock.uptimeMillis()
        }
        stage.push(game.create(app, m))
    }

    private fun save() {
        if (t.saved || !t.finished()) return
        t.saved = true
        val o = JSONObject()
        o.put("fecha", SimpleDateFormat("d MMM, HH:mm", Locale("es")).format(Date()))
        o.put("n0", t.names[0]); o.put("n1", t.names[1])
        o.put("s0", t.score(0)); o.put("s1", t.score(1))
        o.put("g", t.winner())
        Store.addHistory(o)
    }

    override fun draw(c: Canvas, now: Long) {
        header(c, "Torneo") { if (!onBack()) stage.pop() }
        val done = t.finished()
        if (done) { save(); if (!celebrated) { celebrated = true; burst(); Sound.play(Sound.WIN) } }

        // Marcador
        val top = barH + pad * 1.5f
        val sw = w * 0.36f
        val sh = (h - top - pad * 2)
        val cardH = (sh - pad) / 2
        for (i in 0..1) {
            val r = RectF(pad * 2, top + i * (cardH + pad), pad * 2 + sw, top + i * (cardH + pad) + cardH)
            val lead = t.score(i) > t.score(1 - i)
            G.rr(c, r, barH * 0.25f, if (lead) C.alpha(C.PLAYER[i], 0.18f) else C.CARD)
            if (done && t.winner() == i) G.rrLine(c, r, barH * 0.25f, C.PLAYER[i], barH * 0.06f)
            G.textFit(c, t.names[i], r.left + pad * 2, r.centerY(), cardH * 0.3f, sw * 0.6f, C.PLAYER[i], Paint.Align.LEFT, Fonts.black)
            G.text(c, t.score(i).toString(), r.right - pad * 2.5f, r.centerY(), cardH * 0.62f, C.WHITE, Paint.Align.RIGHT, Fonts.black)
            if (done && t.winner() == i) Icons.trophy(c, r.right - pad * 2.5f - G.width(t.score(i).toString(), cardH * 0.62f, Fonts.black) - cardH * 0.35f, r.centerY(), cardH * 0.5f, C.PLAYER[i])
        }

        // Partidas
        val lx = pad * 4 + sw; val rx = w - pad * 2
        val listTop = top
        val btnH = barH * 1.0f
        val listBottom = h - pad * 2 - btnH - pad * 1.5f - (if (done) barH * 0.9f else 0f)
        val n = t.matches.size + (if (t.tiebreak != Tournament.PENDING || t.needsTiebreak()) 1 else 0)
        val cols = when { n <= 6 -> 2; n <= 12 -> 3; else -> 4 }
        val rows = ceil(n / cols.toFloat()).toInt().coerceAtLeast(1)
        val gap = pad * 0.8f
        val cw = (rx - lx - gap * (cols - 1)) / cols
        val ch = min((listBottom - listTop - gap * (rows - 1)) / rows, barH * 1.1f)
        val next = t.next()
        for (k in 0 until n) {
            val col = k % cols; val row = k / cols
            val r = RectF(lx + col * (cw + gap), listTop + row * (ch + gap), lx + col * (cw + gap) + cw, listTop + row * (ch + gap) + ch)
            val isTb = k >= t.matches.size
            val game = if (isTb) GameId.REFLEJOS else t.matches[k]
            val res = if (isTb) t.tiebreak else t.results[k]
            val isNext = (!isTb && k == next) || (isTb && t.needsTiebreak())
            G.rr(c, r, ch * 0.22f, if (isNext) C.alpha(C.AMBER, 0.16f) else C.PANEL)
            if (isNext) G.rrLine(c, r, ch * 0.22f, C.AMBER, ch * 0.05f)
            val title = (if (isTb) "Desempate: " else "${k + 1}. ") + game.title
            G.textFit(c, title, r.left + ch * 0.25f, r.top + ch * 0.33f, ch * 0.27f, cw - ch * 0.5f, C.WHITE, Paint.Align.LEFT, Fonts.black)
            val (label, color) = when (res) {
                Tournament.PENDING -> (if (isNext) "Siguiente" else "Pendiente") to (if (isNext) C.AMBER else C.TEXT2)
                -1 -> "Empate" to C.TEXT2
                else -> "Ganó ${t.names[res]}" to C.PLAYER[res]
            }
            G.textFit(c, label, r.left + ch * 0.25f, r.top + ch * 0.7f, ch * 0.25f, cw - ch * 0.5f, color, Paint.Align.LEFT)
        }

        // Botón principal
        val br = RectF(lx, h - pad * 2 - btnH, rx, h - pad * 2)
        when {
            done -> {
                val wi = t.winner()
                val half = (br.width() - pad) / 2
                G.textFit(c, "¡${t.names[wi]} gana el torneo!", (lx + rx) / 2, br.top - barH * 0.6f, barH * 0.5f, rx - lx, C.PLAYER[wi], font = Fonts.black)
                ui.button(c, RectF(br.left, br.top, br.left + half, br.bottom), "Revancha", Btn.PRIMARY) {
                    stage.replace(TourScreen(app, Tournament(t.names, t.games, t.laps)))
                }
                ui.button(c, RectF(br.left + half + pad, br.top, br.right, br.bottom), "Menú", Btn.CARD) { stage.popToRoot() }
            }
            t.needsTiebreak() -> ui.button(c, br, "¡Empate! Jugar desempate de Reflejos", Btn.PRIMARY) { launch(-1, GameId.REFLEJOS) }
            next >= 0 -> ui.button(c, br, "Jugar: ${t.matches[next].title}", Btn.PRIMARY) { launch(next, t.matches[next]) }
        }

        drawBits(c, now)
    }

    private fun burst() {
        val rnd = Random()
        val cols = intArrayOf(C.AMBER, C.BLUE, C.GREEN, C.RED, C.WHITE)
        repeat(140) {
            val a = rnd.nextDouble() * Math.PI * 2
            val sp = (0.4f + rnd.nextFloat()) * h * 1.2f
            bits.add(Bit(w / 2, h * 0.45f, (cos(a) * sp).toFloat(), (sin(a) * sp).toFloat() - h * 0.6f,
                cols[rnd.nextInt(cols.size)], (6 + rnd.nextInt(8)) * dp, rnd.nextFloat() * 360, (rnd.nextFloat() - 0.5f) * 720))
        }
    }

    private fun drawBits(c: Canvas, now: Long) {
        if (bits.isEmpty()) { lastFrame = now; return }
        val dt = ((now - lastFrame).coerceIn(0, 50)) / 1000f
        lastFrame = now
        val it = bits.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.vy += h * 1.4f * dt
            b.x += b.vx * dt * 0.6f; b.y += b.vy * dt; b.rot += b.vr * dt
            if (b.y > h + 40 * dp) { it.remove(); continue }
            c.save(); c.rotate(b.rot, b.x, b.y)
            G.rr(c, b.x - b.size / 2, b.y - b.size * 0.3f, b.x + b.size / 2, b.y + b.size * 0.3f, b.size * 0.1f, b.color)
            c.restore()
        }
    }
}
