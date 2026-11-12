package cr.juegos.carro

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.sin

enum class GameId(val title: String, val desc: String) {
    SOLITARIO("Solitario",
        "Ordena las cartas por palo, del As al Rey.\nToca una carta para moverla sola, o arrástrala.\nCon 2 jugadores juegan la misma partida por turnos,\n5 minutos cada uno. Gana quien termine más rápido."),
    TRES("Tres en raya",
        "Haz una línea de tres antes que el otro.\nJuega contra la app o con tu acompañante."),
    REFLEJOS("Reflejos",
        "Cuando el círculo se ponga verde, toca lo más rápido posible.\nSi tocas antes de tiempo, pierdes el punto.\nCon 2 jugadores cada uno toca su mitad de la pantalla.\nGana quien llegue primero a 5 puntos."),
    PAREJAS("Parejas",
        "Voltea dos cartas y encuentra las que son iguales.\nCon 2 jugadores, quien acierta vuelve a jugar\ny gana quien junte más parejas."),
    CUATRO("Cuatro en línea",
        "Deja caer fichas por turnos y alinea cuatro:\nen horizontal, en vertical o en diagonal.\nJuega contra la app o con tu acompañante."),
    TOPO("Golpea al topo",
        "Toca a los animalitos cuando salgan de los huecos.\nEl dorado vale 3 puntos. ¡No toques al erizo!\nCon 2 jugadores cada uno tiene su lado de la pantalla.");

    fun levels(players: Int): List<String> = when (this) {
        SOLITARIO -> listOf("Robar 1", "Robar 3")
        TRES -> if (players == 1) listOf("Fácil", "Normal", "Imposible") else emptyList()
        CUATRO -> if (players == 1) listOf("Fácil", "Normal", "Difícil") else emptyList()
        PAREJAS -> listOf("12 cartas", "18 cartas", "24 cartas")
        REFLEJOS -> emptyList()
        TOPO -> listOf("Tranquilo", "Normal", "Rápido")
    }

    fun levelLabel() = when (this) {
        SOLITARIO -> "Modo"; PAREJAS -> "Tablero"; TOPO -> "Velocidad"; else -> "Dificultad"
    }

    fun defaultLevel(players: Int) = when (this) {
        SOLITARIO -> 0
        else -> if (levels(players).size >= 2) 1 else 0
    }

    fun create(app: MainActivity, m: Match): GameScreen = when (this) {
        SOLITARIO -> SolitarioScreen(app, m)
        TRES -> TresScreen(app, m)
        REFLEJOS -> ReflejosScreen(app, m)
        PAREJAS -> ParejasScreen(app, m)
        CUATRO -> CuatroScreen(app, m)
        TOPO -> TopoScreen(app, m)
    }
}

/** Dibujos de cada juego para el menú. */
object GameArt {
    fun draw(c: Canvas, g: GameId, cx: Float, cy: Float, s: Float, t: Long) {
        when (g) {
            GameId.SOLITARIO -> {
                c.save(); c.rotate(-12f, cx, cy + s * 0.3f)
                Cards.drawFace(c, cx - s * 0.12f, cy - s * 0.02f, s * 0.42f, 0, 1)
                c.restore()
                c.save(); c.rotate(10f, cx, cy + s * 0.3f)
                Cards.drawFace(c, cx + s * 0.14f, cy, s * 0.42f, 1, 13)
                c.restore()
            }
            GameId.TRES -> {
                val cell = s * 0.24f
                val p = G.stroke(C.LINE, s * 0.035f)
                for (i in 1..2) {
                    c.drawLine(cx - cell * 1.5f + i * cell, cy - cell * 1.5f, cx - cell * 1.5f + i * cell, cy + cell * 1.5f, p)
                    c.drawLine(cx - cell * 1.5f, cy - cell * 1.5f + i * cell, cx + cell * 1.5f, cy - cell * 1.5f + i * cell, p)
                }
                Marks.x(c, cx - cell, cy - cell, cell * 0.8f, 1f)
                Marks.o(c, cx, cy, cell * 0.8f, 1f)
                Marks.x(c, cx + cell, cy + cell, cell * 0.8f, 1f)
                Marks.o(c, cx + cell, cy - cell, cell * 0.8f, 1f)
            }
            GameId.REFLEJOS -> {
                val k = (sin(t / 400.0) * 0.5 + 0.5).toFloat()
                G.circle(c, cx, cy, s * 0.38f, C.alpha(C.GREEN, 0.18f + 0.12f * k))
                G.circle(c, cx, cy, s * 0.28f, C.GREEN)
                G.path.reset()
                G.path.moveTo(cx + s * 0.04f, cy - s * 0.2f); G.path.lineTo(cx - s * 0.1f, cy + s * 0.03f)
                G.path.lineTo(cx, cy + s * 0.03f); G.path.lineTo(cx - s * 0.04f, cy + s * 0.2f)
                G.path.lineTo(cx + s * 0.11f, cy - s * 0.04f); G.path.lineTo(cx + s * 0.01f, cy - s * 0.04f); G.path.close()
                c.drawPath(G.path, G.fill(C.DARK))
            }
            GameId.PAREJAS -> {
                val cw = s * 0.36f; val ch = cw * 1.25f
                G.rr(c, cx - cw - s * 0.03f, cy - ch / 2, cx - s * 0.03f, cy + ch / 2, cw * 0.12f, Pics.FACE)
                Pics.draw(c, 7, cx - cw / 2 - s * 0.03f, cy, cw * 0.8f)
                G.rr(c, cx + s * 0.03f, cy - ch / 2, cx + cw + s * 0.03f, cy + ch / 2, cw * 0.12f, Pics.FACE)
                Pics.draw(c, 7, cx + cw / 2 + s * 0.03f, cy, cw * 0.8f)
            }
            GameId.CUATRO -> {
                val cell = s * 0.17f
                G.rr(c, cx - cell * 2.2f, cy - cell * 1.7f, cx + cell * 2.2f, cy + cell * 1.7f, cell * 0.3f, C.CARD_HI)
                val pattern = arrayOf("....", ".ab.", "abba")
                for (r in 0 until 3) for (col in 0 until 4) {
                    val ch = pattern[r][col]
                    val x = cx - cell * 1.5f + col * cell; val y = cy - cell + r * cell
                    G.circle(c, x, y, cell * 0.4f, when (ch) { 'a' -> C.AMBER; 'b' -> C.BLUE; else -> C.BG })
                }
            }
            GameId.TOPO -> {
                G.oval(c, cx, cy + s * 0.24f, s * 0.38f, s * 0.11f, C.DARK)
                c.save(); c.clipRect(cx - s, cy - s, cx + s, cy + s * 0.24f)
                Critters.draw(c, 0, cx, cy + s * 0.02f, s * 0.26f, false, t)
                c.restore()
            }
        }
    }
}

class MenuScreen(app: MainActivity) : Screen(app) {
    override fun animating() = true

    override fun draw(c: Canvas, now: Long) {
        // Encabezado
        val top = barH * 1.15f
        G.text(c, "Juegos", pad * 2, top * 0.42f, barH * 0.62f, C.WHITE, Paint.Align.LEFT, Fonts.black)
        val tw = G.width("Juegos", barH * 0.62f, Fonts.black)
        G.text(c, "para la espera", pad * 2 + tw + barH * 0.25f, top * 0.46f, barH * 0.3f, C.TEXT2, Paint.Align.LEFT)

        val bh = barH * 0.78f
        val by = top * 0.42f - bh / 2
        var x = w - pad * 2
        val sr = RectF(x - bh * 1.3f, by, x, by + bh); soundButton(c, sr); x = sr.left - pad
        val rw = bh * 3.1f
        val rr = RectF(x - rw, by, x, by + bh)
        ui.iconButton(c, rr, Btn.CARD, { cx, cy, s, col -> Icons.list(c, cx, cy, s, col) }, "Récords") { stage.push(RecordsScreen(app)) }
        x = rr.left - pad
        val tr = RectF(x - rw * 1.05f, by, x, by + bh)
        ui.iconButton(c, tr, Btn.PRIMARY, { cx, cy, s, col -> Icons.trophy(c, cx, cy, s, col) }, "Torneo") { stage.push(TourSetupScreen(app)) }

        // Tarjetas de juegos
        val games = GameId.values()
        val wide = w / h >= 2.05f
        val cols = if (wide) 6 else 3
        val rows = if (wide) 1 else 2
        val gx = pad * 2; val gy = top
        val gw = w - pad * 4; val gh = h - top - pad * 2
        val gap = pad * 1.2f
        val cw = (gw - gap * (cols - 1)) / cols
        val ch = (gh - gap * (rows - 1)) / rows
        for ((i, g) in games.withIndex()) {
            val col = i % cols; val row = i / cols
            val r = RectF(gx + col * (cw + gap), gy + row * (ch + gap), gx + col * (cw + gap) + cw, gy + row * (ch + gap) + ch)
            val pressed = ui.pressed(r)
            val rad = min(cw, ch) * 0.1f
            G.rr(c, r, rad, if (pressed) C.CARD_HI else C.CARD)
            val artS = min(cw * 0.95f, ch * 0.62f)
            GameArt.draw(c, g, r.centerX(), r.top + ch * 0.42f, artS, now)
            val label = if (G.width(g.title, barH * 0.36f, Fonts.black) > cw * 0.88f) wrap2(g.title) else g.title
            G.lines(c, label, r.centerX(), r.bottom - ch * 0.15f, barH * 0.36f, cw * 0.88f, C.WHITE, font = Fonts.black, gap = 1.15f)
            ui.area(r) { stage.push(SetupScreen(app, g)) }
        }
    }
}

/** Pantalla para elegir 1 o 2 jugadores, la dificultad y los nombres. */
class SetupScreen(app: MainActivity, val game: GameId) : Screen(app) {
    private var players = Store.long("cfg_jug_${game.name}", 1).toInt()
    private var level = levelFor(players)

    private fun levelFor(p: Int) = Store.long("cfg_niv_${game.name}_$p", game.defaultLevel(p).toLong()).toInt()
        .coerceIn(0, (game.levels(p).size - 1).coerceAtLeast(0))

    override fun animating() = true

    override fun draw(c: Canvas, now: Long) {
        header(c, game.title)
        val top = barH + pad * 2
        val leftW = w * 0.42f
        // Dibujo y descripción
        val artS = min(leftW * 0.5f, (h - top) * 0.42f)
        val art = RectF(pad * 2, top, pad * 2 + leftW, top + artS * 1.15f)
        G.rr(c, art, barH * 0.25f, C.PANEL)
        GameArt.draw(c, game, art.centerX(), art.centerY(), artS, now)
        val descTop = art.bottom + pad
        G.lines(c, game.desc, pad * 2, (descTop + h - pad * 2) / 2, barH * 0.27f, leftW, C.TEXT2, Paint.Align.LEFT, gap = 1.4f)

        // Opciones
        val rx = pad * 4 + leftW
        val rw = w - rx - pad * 2
        val rowH = barH * 0.9f
        var y = top
        G.text(c, "Jugadores", rx, y + barH * 0.2f, barH * 0.3f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.45f
        ui.segmented(c, RectF(rx, y, rx + rw, y + rowH), listOf("1 jugador", "2 jugadores"), players - 1) {
            players = it + 1; level = levelFor(players)
        }
        y += rowH + pad * 1.6f
        if (players == 2) {
            G.text(c, "Nombres (toca para cambiar)", rx, y + barH * 0.2f, barH * 0.3f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.45f
            nameButtons(c, RectF(rx, y, rx + rw, y + rowH))
            y += rowH + pad * 1.6f
        }
        val levels = game.levels(players)
        if (levels.isNotEmpty()) {
            G.text(c, game.levelLabel(), rx, y + barH * 0.2f, barH * 0.3f, C.TEXT2, Paint.Align.LEFT); y += barH * 0.45f
            ui.segmented(c, RectF(rx, y, rx + rw, y + rowH), levels, level) { level = it }
        }
        val pbh = barH * 1.05f
        val pr = RectF(w - pad * 2 - rw * 0.55f, h - pad * 2 - pbh, w - pad * 2, h - pad * 2)
        ui.button(c, pr, "Jugar", Btn.PRIMARY, pbh * 0.42f) { start() }
    }

    private fun nameButtons(c: Canvas, r: RectF) {
        val gap = pad
        val bw = (r.width() - gap) / 2
        for (i in 0..1) {
            val br = RectF(r.left + i * (bw + gap), r.top, r.left + i * (bw + gap) + bw, r.bottom)
            val pressed = ui.pressed(br)
            G.rr(c, br, br.height() * 0.24f, if (pressed) C.CARD_HI else C.alpha(C.PLAYER[i], 0.16f))
            G.rrLine(c, br, br.height() * 0.24f, C.PLAYER[i], br.height() * 0.05f)
            G.textFit(c, Store.name(i), br.centerX(), br.centerY(), br.height() * 0.38f, bw * 0.85f, C.PLAYER[i], font = Fonts.black)
            ui.area(br) { app.editName(i, Store.name(i)) { Store.setName(i, it) } }
        }
    }

    private fun start() {
        Store.put("cfg_jug_${game.name}", players.toLong())
        Store.put("cfg_niv_${game.name}_$players", level.toLong())
        val names = if (players == 2) Store.names() else arrayOf("Tú", if (game == GameId.TRES || game == GameId.CUATRO) "App" else "")
        stage.push(game.create(app, Match(game, players, level, names)))
    }
}

/** Récords guardados y torneos anteriores. */
class RecordsScreen(app: MainActivity) : Screen(app) {
    private var tab = 0

    override fun draw(c: Canvas, now: Long) {
        header(c, "Récords")
        val m = barH * 0.15f
        val tr = RectF(w * 0.42f, m, w - m * 2 - barH * 2.4f, barH - m)
        ui.segmented(c, tr, listOf("Juegos", "Torneos"), tab) { tab = it }
        val dr = RectF(w - m - barH * 2.3f, m, w - m, barH - m)
        ui.button(c, dr, "Borrar", Btn.DANGER) {
            app.confirm("¿Borrar todos los récords y el historial de torneos?", "Borrar") { Store.clearRecords() }
        }
        if (tab == 0) drawRecords(c) else drawHistory(c)
    }

    private fun lv(game: GameId, i: Int) = game.levels(1).getOrElse(i) { "" }

    private fun linesFor(g: GameId): List<String> {
        fun t(k: String) = if (Store.has(k)) G.timeExact(Store.long(k)) else "-"
        return when (g) {
            GameId.SOLITARIO -> listOf(
                "Ganadas: ${Store.long("sol_ganadas")}",
                "Mejor tiempo robando 1: ${t("sol_t0")}",
                "Mejor tiempo robando 3: ${t("sol_t1")}")
            GameId.TRES -> (0..2).map { "${lv(g, it)}: ${Store.long("tres_v$it")} ganadas, ${Store.long("tres_d$it")} perdidas" }
            GameId.CUATRO -> (0..2).map { "${lv(g, it)}: ${Store.long("cuatro_v$it")} ganadas, ${Store.long("cuatro_d$it")} perdidas" }
            GameId.REFLEJOS -> listOf(
                "Reacción más rápida: " + (if (Store.has("reflejos_ms")) "${Store.long("reflejos_ms")} ms" else "-"),
                "Mejor promedio: " + (if (Store.has("reflejos_prom")) "${Store.long("reflejos_prom")} ms" else "-"))
            GameId.PAREJAS -> (0..2).map {
                "${lv(g, it)}: ${t("parejas_t$it")}" + (if (Store.has("parejas_i$it")) ", ${Store.long("parejas_i$it")} intentos" else "")
            }
            GameId.TOPO -> (0..2).map { "${lv(g, it)}: " + (if (Store.has("topo_$it")) "${Store.long("topo_$it")} puntos" else "-") }
        }
    }

    private fun drawRecords(c: Canvas) {
        val games = GameId.values()
        val cols = 3; val rows = 2
        val top = barH + pad * 1.5f
        val gap = pad * 1.2f
        val cw = (w - pad * 4 - gap * (cols - 1)) / cols
        val ch = (h - top - pad * 2 - gap * (rows - 1)) / rows
        for ((i, g) in games.withIndex()) {
            val r = RectF(pad * 2 + (i % cols) * (cw + gap), top + (i / cols) * (ch + gap), 0f, 0f)
            r.right = r.left + cw; r.bottom = r.top + ch
            G.rr(c, r, barH * 0.2f, C.CARD)
            G.textFit(c, g.title, r.left + pad * 1.5f, r.top + ch * 0.17f, barH * 0.36f, cw * 0.9f, C.AMBER, Paint.Align.LEFT, Fonts.black)
            val ls = linesFor(g)
            val lh = (ch * 0.72f) / 3.2f
            for ((j, l) in ls.withIndex()) {
                G.textFit(c, l, r.left + pad * 1.5f, r.top + ch * 0.4f + j * lh, barH * 0.27f, cw - pad * 3, C.WHITE, Paint.Align.LEFT)
            }
        }
    }

    private fun drawHistory(c: Canvas) {
        val list = Store.history()
        val top = barH + pad * 1.5f
        if (list.isEmpty()) {
            G.text(c, "Todavía no hay torneos guardados.", w / 2, (top + h) / 2, barH * 0.36f, C.TEXT2)
            return
        }
        val rowH = (h - top - pad * 2) / 6
        for ((i, o) in list.take(6).withIndex()) {
            val r = RectF(pad * 2, top + i * rowH, w - pad * 2, top + i * rowH + rowH * 0.86f)
            G.rr(c, r, r.height() * 0.25f, C.CARD)
            val n0 = o.optString("n0"); val n1 = o.optString("n1")
            val s0 = o.optInt("s0"); val s1 = o.optInt("s1")
            val win = o.optInt("g", -1)
            G.textFit(c, o.optString("fecha"), r.left + pad * 1.5f, r.centerY(), r.height() * 0.36f, r.width() * 0.2f, C.TEXT2, Paint.Align.LEFT)
            G.textFit(c, "$n0  $s0  -  $s1  $n1", r.left + r.width() * 0.25f, r.centerY(), r.height() * 0.44f, r.width() * 0.45f, C.WHITE, Paint.Align.LEFT, Fonts.black)
            val res = when (win) { 0 -> "Ganó $n0"; 1 -> "Ganó $n1"; else -> "Empate" }
            G.textFit(c, res, r.right - pad * 1.5f, r.centerY(), r.height() * 0.4f, r.width() * 0.24f,
                if (win in 0..1) C.PLAYER[win] else C.TEXT2, Paint.Align.RIGHT)
        }
    }
}

/** Parte un texto en dos líneas por el espacio más cercano al centro. */
fun wrap2(s: String): String {
    val mid = s.length / 2
    var best = -1
    for (i in s.indices) if (s[i] == ' ' && (best < 0 || kotlin.math.abs(i - mid) < kotlin.math.abs(best - mid))) best = i
    return if (best < 0) s else s.substring(0, best) + "\n" + s.substring(best + 1)
}
