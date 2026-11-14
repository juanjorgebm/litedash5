package cr.juegos.carro

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

class MainActivity : Activity() {
    lateinit var stage: Stage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        Store.init(this)
        Sound.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stage = Stage(this)
        setContentView(stage)
        stage.push(MenuScreen(this))
        immersive()
        Updater.check(this) { stage.invalidate() }
    }

    @Suppress("DEPRECATION")
    fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onPause() {
        super.onPause()
        stage.top?.onAppPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!stage.back()) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        Sound.release()
        super.onDestroy()
    }

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    fun confirm(message: String, yes: String, onYes: () -> Unit) {
        dialog().setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes(); stage.invalidate() }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { immersive() }
            .show()
    }

    fun editName(index: Int, current: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(current)
            setSelectAllOnFocus(true)
            setSingleLine(true)
            textSize = 24f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            filters = arrayOf(InputFilter.LengthFilter(14))
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val d = dialog().setTitle("Nombre del jugador ${index + 1}")
            .setView(box)
            .setPositiveButton("Listo") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) onDone(name)
                stage.invalidate()
            }
            .setNegativeButton("Cancelar", null)
            .setOnDismissListener { immersive() }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }
}

/** Vista única donde se dibujan todas las pantallas. */
class Stage(val app: MainActivity) : View(app) {
    private val stack = ArrayList<Screen>()
    val top: Screen? get() = stack.lastOrNull()
    val ui = Ui(this)
    private var uiPointer = -1

    fun push(s: Screen) {
        top?.onHide()
        stack.add(s)
        if (width > 0) s.size(width.toFloat(), height.toFloat())
        ui.reset(); uiPointer = -1
        s.onShow()
        invalidate()
    }

    fun pop() {
        if (stack.size <= 1) return
        stack.removeAt(stack.size - 1).onHide()
        ui.reset(); uiPointer = -1
        top?.onShow()
        invalidate()
    }

    fun popToRoot() { while (stack.size > 1) pop() }

    fun replace(s: Screen) {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1).onHide()
        push(s)
    }

    fun back(): Boolean {
        val t = top ?: return false
        if (t.onBack()) { invalidate(); return true }
        if (stack.size > 1) { pop(); return true }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        for (s in stack) s.size(w.toFloat(), h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(C.BG)
        val s = top ?: return
        ui.begin()
        s.draw(c, SystemClock.uptimeMillis())
        if (s.animating()) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = top ?: return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.x; val y = e.y
                if (ui.hitAt(x, y)) { uiPointer = e.getPointerId(0); ui.down(x, y) }
                else { uiPointer = -1; s.touchDown(e.getPointerId(0), x, y) }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                s.touchDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == uiPointer) ui.move(e.getX(i), e.getY(i)) else s.touchMove(id, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (id == uiPointer) { uiPointer = -1; ui.up(e.getX(i), e.getY(i)) }
                else if (top === s) s.touchUp(id, e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> { ui.cancel(); uiPointer = -1; s.touchCancel() }
        }
        invalidate()
        return true
    }
}

abstract class Screen(val app: MainActivity) {
    var w = 0f; var h = 0f
    val dp: Float = app.resources.displayMetrics.density
    val ui: Ui get() = app.stage.ui
    val stage: Stage get() = app.stage
    val barH: Float get() = (h * 0.135f).coerceIn(50 * dp, 96 * dp)
    val pad: Float get() = barH * 0.16f

    open fun size(w: Float, h: Float) { this.w = w; this.h = h }
    abstract fun draw(c: Canvas, now: Long)
    open fun animating() = false
    open fun touchDown(id: Int, x: Float, y: Float) {}
    open fun touchMove(id: Int, x: Float, y: Float) {}
    open fun touchUp(id: Int, x: Float, y: Float) {}
    open fun touchCancel() {}
    open fun onBack(): Boolean = false
    open fun onShow() {}
    open fun onHide() {}
    open fun onAppPause() {}
    fun invalidate() = stage.invalidate()

    /** Barra superior con botón de volver y título. */
    fun header(c: Canvas, title: String, onBackTap: () -> Unit = { stage.back() }): Float {
        G.rr(c, 0f, 0f, w, barH, 0f, C.PANEL)
        val m = barH * 0.15f
        val br = android.graphics.RectF(m, m, m + barH * 1.5f, barH - m)
        ui.iconButton(c, br, Btn.CARD, { x, y, s, col -> Icons.back(c, x, y, s, col) }, action = onBackTap)
        G.textFit(c, title, br.right + barH * 0.35f, barH / 2, barH * 0.42f, w * 0.45f, C.WHITE,
            android.graphics.Paint.Align.LEFT, Fonts.black)
        return br.right
    }

    fun soundButton(c: Canvas, r: android.graphics.RectF) {
        ui.iconButton(c, r, Btn.CARD, { x, y, s, col -> Icons.sound(c, x, y, s, col, Store.sound) }) {
            Store.sound = !Store.sound
        }
    }
}

class Result(val winner: Int, val title: String, val detail: String) {
    companion object {
        const val DRAW = -1
        const val SOLO = -2
    }
}

class Match(
    val game: GameId,
    val players: Int,
    val level: Int,
    val names: Array<String>,
    val starter: Int = 0,
    val tournament: Boolean = false,
    /** Grado escolar (1..6) para la Trivia. */
    val grade: Int = Store.long("cfg_grado", 4).toInt(),
    val onResult: ((Result) -> Unit)? = null,
)

/** Base de los juegos: reloj con pausa, barra superior y ventanas de pausa y resultado. */
abstract class GameScreen(app: MainActivity, val m: Match) : Screen(app) {
    private var startT = SystemClock.uptimeMillis()
    private var pausedAcc = 0L
    private var pauseT = 0L
    var paused = false; private set
    var result: Result? = null; private set
    private var resultT = 0L
    private var pending: Result? = null
    private var pendingAt = 0L
    val rnd = Random()
    val vsApp get() = m.players == 1 && (m.game == GameId.TRES || m.game == GameId.CUATRO)

    /** Hay un final en curso o ya se mostró. */
    val over get() = pending != null || result != null
    val blocked get() = paused || over

    fun gt(): Long {
        val now = SystemClock.uptimeMillis()
        return now - startT - pausedAcc - (if (paused) now - pauseT else 0)
    }

    fun resetClock() {
        startT = SystemClock.uptimeMillis(); pausedAcc = 0
        if (paused) pauseT = startT
        result = null; pending = null
    }

    fun pause() {
        if (!paused && result == null) { paused = true; pauseT = SystemClock.uptimeMillis(); invalidate() }
    }

    fun resume() {
        if (paused) { pausedAcc += SystemClock.uptimeMillis() - pauseT; paused = false; invalidate() }
    }

    fun name(i: Int) = m.names[i]

    fun finish(r: Result, delay: Long = 900) {
        if (over) return
        pending = r
        pendingAt = SystemClock.uptimeMillis() + delay
        invalidate()
    }

    abstract fun restart()
    open fun replay() = restart()
    abstract fun drawGame(c: Canvas, now: Long)

    /** Información en el centro de la barra superior (tiempo, puntos...). */
    open fun drawHud(c: Canvas, left: Float, right: Float) {}

    /** Botones extra a la derecha de la barra, antes del sonido. Devuelve el borde izquierdo usado. */
    open fun drawBarButtons(c: Canvas, right: Float): Float = right

    override fun animating() = true
    override fun onAppPause() = pause()
    override fun onBack(): Boolean {
        when {
            result != null -> return false
            paused -> resume()
            else -> pause()
        }
        return true
    }

    final override fun draw(c: Canvas, now: Long) {
        val p = pending
        if (p != null && now >= pendingAt) {
            pending = null; result = p; resultT = now
            Sound.play(if (p.winner == Result.DRAW) Sound.TICK else Sound.WIN)
        }
        drawGame(c, now)
        drawBar(c)
        if (paused) drawPause(c) else result?.let { drawResult(c, it, now) }
    }

    private fun drawBar(c: Canvas) {
        G.rr(c, 0f, 0f, w, barH, 0f, C.PANEL)
        val m2 = barH * 0.15f
        val bh = barH - 2 * m2
        val pr = android.graphics.RectF(m2, m2, m2 + bh * 1.35f, barH - m2)
        ui.iconButton(c, pr, Btn.CARD, { x, y, s, col -> Icons.pause(c, x, y, s, col) }) { pause() }
        val tx = pr.right + barH * 0.3f
        G.textFit(c, m.game.title, tx, barH / 2, barH * 0.38f, w * 0.2f, C.WHITE, android.graphics.Paint.Align.LEFT, Fonts.black)
        val titleRight = tx + G.width(m.game.title, G.fit(m.game.title, w * 0.2f, barH * 0.38f, Fonts.black), Fonts.black)
        val sr = android.graphics.RectF(w - m2 - bh * 1.35f, m2, w - m2, barH - m2)
        soundButton(c, sr)
        val left = drawBarButtons(c, sr.left - m2)
        drawHud(c, titleRight + barH * 0.3f, left - barH * 0.2f)
    }

    private fun overlayPanel(c: Canvas, alpha: Float, pw: Float, ph: Float): android.graphics.RectF {
        G.rr(c, 0f, 0f, w, h, 0f, C.alpha(C.BG, 0.82f * alpha))
        val r = android.graphics.RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
        G.rr(c, r, barH * 0.3f, C.alpha(C.PANEL, alpha))
        G.rrLine(c, r, barH * 0.3f, C.alpha(C.LINE, alpha), barH * 0.03f)
        return r
    }

    private fun drawPause(c: Canvas) {
        val pw = (w * 0.5f).coerceAtMost(h * 1.3f)
        val r = overlayPanel(c, 1f, pw, h * 0.78f)
        G.text(c, "Pausa", r.centerX(), r.top + r.height() * 0.15f, barH * 0.6f, C.WHITE, font = Fonts.black)
        val bh = r.height() * 0.17f
        val bw = r.width() * 0.8f
        var y = r.top + r.height() * 0.3f
        fun btn(label: String, style: Btn, a: () -> Unit) {
            ui.button(c, android.graphics.RectF(r.centerX() - bw / 2, y, r.centerX() + bw / 2, y + bh), label, style, action = a)
            y += bh * 1.25f
        }
        btn("Continuar", Btn.PRIMARY) { resume() }
        if (!m.tournament) btn("Reiniciar", Btn.CARD) { resume(); restart() }
        btn(if (m.tournament) "Salir al torneo" else "Salir", Btn.CARD) { stage.pop() }
    }

    private fun drawResult(c: Canvas, r: Result, now: Long) {
        val a = G.ease((now - resultT) / 300f)
        val pw = (w * 0.56f).coerceAtMost(h * 1.5f)
        val ph = h * 0.8f
        val box = overlayPanel(c, a, pw, ph)
        box.offset(0f, (1 - a) * h * 0.05f)
        val color = when (r.winner) { 0, 1 -> C.PLAYER[r.winner]; else -> C.AMBER }
        if (r.winner != Result.DRAW) Icons.trophy(c, box.centerX(), box.top + box.height() * 0.15f, box.height() * 0.2f, color)
        G.textFit(c, r.title, box.centerX(), box.top + box.height() * 0.33f, barH * 0.62f, box.width() * 0.9f, color, font = Fonts.black)
        if (r.detail.isNotEmpty()) {
            G.lines(c, r.detail, box.centerX(), box.top + box.height() * 0.53f, barH * 0.32f, box.width() * 0.88f, C.WHITE)
        }
        if (a < 0.95f) return
        val bh = box.height() * 0.16f
        val y = box.bottom - bh - box.height() * 0.07f
        if (m.tournament) {
            val bw = box.width() * 0.7f
            ui.button(c, android.graphics.RectF(box.centerX() - bw / 2, y, box.centerX() + bw / 2, y + bh), "Continuar torneo", Btn.PRIMARY) {
                stage.pop(); m.onResult?.invoke(r)
            }
        } else {
            val gap = box.width() * 0.05f
            val bw = (box.width() * 0.86f - gap) / 2
            val x0 = box.centerX() - bw - gap / 2
            ui.button(c, android.graphics.RectF(x0, y, x0 + bw, y + bh), "Otra vez", Btn.PRIMARY) { replay() }
            ui.button(c, android.graphics.RectF(x0 + bw + gap, y, x0 + 2 * bw + gap, y + bh), "Salir", Btn.CARD) { stage.pop() }
        }
    }

    /** Cuenta regresiva 3, 2, 1, ¡Ya! Devuelve true mientras sigue. */
    fun countdown(c: Canvas, t: Long, cx: Float, cy: Float, size: Float, total: Long = 3000): Boolean {
        if (t >= total + 500) return false
        val label: String
        val k: Float
        if (t < total) {
            val n = ((total - t + 999) / 1000).toInt()
            label = n.toString(); k = ((total - t) % 1000) / 1000f
        } else { label = "¡Ya!"; k = 1 - (t - total) / 500f }
        G.circle(c, cx, cy, size * 0.75f, C.alpha(C.BG, 0.75f))
        G.text(c, label, cx, cy, size * (0.7f + 0.3f * k), C.alpha(C.AMBER, 0.4f + 0.6f * k), font = Fonts.black)
        return true
    }

    /** Tarjeta de jugador para juegos por turnos. */
    fun playerCard(c: Canvas, r: android.graphics.RectF, i: Int, active: Boolean, label: String, extra: String? = null,
                   mark: ((Float, Float, Float) -> Unit)? = null) {
        val col = C.PLAYER[i]
        G.rr(c, r, r.width() * 0.1f, if (active) C.alpha(col, 0.16f) else C.PANEL)
        if (active) G.rrLine(c, r, r.width() * 0.1f, col, barH * 0.06f)
        val cx = r.centerX()
        mark?.invoke(cx, r.top + r.height() * 0.3f, r.height() * 0.28f)
        G.textFit(c, label, cx, r.top + r.height() * 0.6f, barH * 0.4f, r.width() * 0.86f, C.WHITE, font = Fonts.black)
        if (extra != null) G.textFit(c, extra, cx, r.top + r.height() * 0.78f, barH * 0.32f, r.width() * 0.86f, if (active) col else C.TEXT2)
    }
}

/** Récords, nombres y torneos guardados en el dispositivo. */
object Store {
    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) { sp = ctx.getSharedPreferences("juegos", Context.MODE_PRIVATE) }

    fun long(k: String, def: Long = 0L) = sp.getLong(k, def)
    fun put(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    fun inc(k: String) = put(k, long(k) + 1)
    fun has(k: String) = sp.contains(k)

    /** Guarda [v] si mejora el récord. Devuelve true si es nuevo récord. */
    fun best(k: String, v: Long, lowerIsBetter: Boolean): Boolean {
        val old = sp.getLong(k, -1)
        val better = old < 0 || (if (lowerIsBetter) v < old else v > old)
        if (better) put(k, v)
        return better
    }

    var sound: Boolean
        get() = sp.getBoolean("sonido", true)
        set(v) { sp.edit().putBoolean("sonido", v).apply() }

    fun name(i: Int): String = sp.getString("nombre$i", null) ?: "Jugador ${i + 1}"
    fun setName(i: Int, s: String) = sp.edit().putString("nombre$i", s).apply()
    fun names() = arrayOf(name(0), name(1))

    fun history(): List<JSONObject> {
        val arr = try { JSONArray(sp.getString("torneos", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    fun addHistory(o: JSONObject) {
        val list = listOf(o) + history().take(19)
        val arr = JSONArray(); list.forEach { arr.put(it) }
        sp.edit().putString("torneos", arr.toString()).apply()
    }

    fun clearRecords() {
        val keep = setOf("sonido", "nombre0", "nombre1")
        val e = sp.edit()
        for (k in sp.all.keys) if (k !in keep && !k.startsWith("cfg_")) e.remove(k)
        e.apply()
    }
}

/** Sonidos sintetizados al arrancar (no hay archivos de audio). */
object Sound {
    const val TAP = 0; const val POP = 1; const val GOOD = 2; const val BAD = 3; const val WIN = 4
    const val TICK = 5; const val GO = 6; const val BONK = 7; const val FLIP = 8; const val DROP = 9
    const val JUMP = 10; const val COIN = 11
    private const val COUNT = 12
    private const val SR = 22050
    private var pool: SoundPool? = null
    private val ids = IntArray(COUNT)

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()
        pool = p
        for (i in 0 until COUNT) {
            try {
                val f = File(ctx.cacheDir, "sonido$i.wav")
                f.writeBytes(wav(gen(i)))
                ids[i] = p.load(f.path, 1)
            } catch (e: Exception) { ids[i] = 0 }
        }
    }

    fun play(i: Int) {
        if (!Store.sound) return
        val id = ids[i]
        if (id != 0) pool?.play(id, 0.8f, 0.8f, 1, 0, 1f)
    }

    fun release() { pool?.release(); pool = null }

    private fun sweep(f0: Double, f1: Double, dur: Double, vol: Double = 0.5, square: Boolean = false, noise: Double = 0.0): DoubleArray {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        var ph = 0.0
        val rnd = Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = f0 + (f1 - f0) * (i.toDouble() / n)
            ph += 2 * PI * f / SR
            var v = sin(ph)
            if (square) v = if (v >= 0) 0.6 else -0.6
            if (noise > 0) v = v * (1 - noise) + (rnd.nextDouble() * 2 - 1) * noise
            val env = (t / 0.004).coerceAtMost(1.0) * (1 - i.toDouble() / n).pow(1.6)
            out[i] = v * env * vol
        }
        return out
    }

    private fun cat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
        return out
    }

    private fun gen(i: Int): DoubleArray = when (i) {
        TAP -> sweep(1500.0, 1300.0, 0.035, 0.35)
        POP -> sweep(480.0, 1150.0, 0.08, 0.55)
        GOOD -> cat(sweep(660.0, 660.0, 0.08), sweep(990.0, 990.0, 0.16))
        BAD -> sweep(230.0, 140.0, 0.24, 0.45, square = true)
        WIN -> cat(sweep(523.0, 523.0, 0.11), sweep(659.0, 659.0, 0.11), sweep(784.0, 784.0, 0.11), sweep(1047.0, 1047.0, 0.4))
        TICK -> sweep(880.0, 880.0, 0.09, 0.45)
        GO -> sweep(1320.0, 1320.0, 0.28, 0.5)
        BONK -> sweep(320.0, 90.0, 0.13, 0.8, noise = 0.25)
        FLIP -> sweep(900.0, 1700.0, 0.05, 0.3, noise = 0.3)
        JUMP -> sweep(320.0, 760.0, 0.12, 0.4, square = true)
        COIN -> cat(sweep(988.0, 988.0, 0.05, 0.35, square = true), sweep(1319.0, 1319.0, 0.14, 0.35, square = true))
        else -> sweep(760.0, 240.0, 0.1, 0.55)
    }

    private fun wav(data: DoubleArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        val dataLen = data.size * 2
        fun i32(v: Int) { bytes.write(v and 255); bytes.write((v shr 8) and 255); bytes.write((v shr 16) and 255); bytes.write((v shr 24) and 255) }
        fun i16(v: Int) { bytes.write(v and 255); bytes.write((v shr 8) and 255) }
        bytes.write("RIFF".toByteArray()); i32(36 + dataLen); bytes.write("WAVE".toByteArray())
        bytes.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(SR); i32(SR * 2); i16(2); i16(16)
        bytes.write("data".toByteArray()); i32(dataLen)
        for (d in data) i16((d.coerceIn(-1.0, 1.0) * 32767).toInt())
        return bytes.toByteArray()
    }
}
