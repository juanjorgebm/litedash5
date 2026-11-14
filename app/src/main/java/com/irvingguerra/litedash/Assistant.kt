package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MyCar
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * El asistente: una pregunta hablada (o escrita) va a Gemini con la clave gratis del usuario y la respuesta sale en
 * pantalla. Las apps de Gemini, ChatGPT o Claude no corren en Android 5.1 y la pantalla no trae el dictado de Google,
 * así que se graba el audio (WAV de 16 kHz) y Gemini lo entiende directo. La clave se guarda solo en la pantalla.
 */
object Assistant {
    class Message(val mine: Boolean, val text: String, val error: Boolean = false, val image: ImageBitmap? = null)

    private const val RATE = 16_000
    private const val MAX_SECONDS = 45

    /** Tras hablar, este rato de silencio manda la pregunta sola (en trozos de 100 ms). */
    private const val QUIET_CHUNKS = 18
    private const val HOST = "https://generativelanguage.googleapis.com/v1beta/models/"

    /**
     * Se prueban en orden hasta que uno conteste (si no existe para la clave o ya gastó su cupo gratis, sigue el otro).
     * Para escribir lo que se dijo va el más liviano, que contesta en un par de segundos.
     */
    private val TRANSCRIBE_MODELS = listOf("gemini-2.5-flash-lite", "gemini-flash-lite-latest", "gemini-2.5-flash")
    private val ANSWER_MODELS = listOf("gemini-2.5-flash", "gemini-flash-latest", "gemini-2.5-flash-lite")

    /** La voz de Gemini, para cuando la pantalla no trae voz propia (la del carro no tiene). */
    /** Para dibujar: los de Gemini que hacen imágenes (si la clave los tiene gratis); si no, Pollinations, que es libre. */
    private val IMAGE_MODELS = listOf("gemini-2.5-flash-image", "gemini-2.0-flash-preview-image-generation", "gemini-2.5-flash-image-preview")

    /** "Hazme una imagen de…", "dibuja…", "genera una foto…": se contesta con una imagen y no con texto. */
    private val WANTS_IMAGE = Regex(
        "(?i)(\\b(dibuj\\p{L}*|pint\\p{L}*|ilustr\\p{L}*)\\b)|(\\b(genera\\p{L}*|crea\\p{L}*|haz\\p{L}*|hac\\p{L}*|mu[eé]stra\\p{L}*|quiero|dame|manda\\p{L}*|draw|make|create|generate)\\b.{0,40}\\b(imagen|im[aá]genes|foto\\p{L}*|dibujo|ilustraci[oó]n|image|picture|drawing)\\b)",
    )

    fun wantsImage(text: String) = WANTS_IMAGE.containsMatchIn(text)

    private val VOICE_MODELS = listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-flash-tts")
    private const val VOICE_RATE = 24_000

    private val main = Handler(Looper.getMainLooper())

    val messages = mutableStateListOf<Message>()
    var busy by mutableStateOf(false)
        private set
    var recording by mutableStateOf(false)
        private set

    /** Volumen del micrófono mientras graba (0 a 1), para ver que sí escucha. */
    var level by mutableFloatStateOf(0f)
        private set

    /** Si la pantalla tiene voz para leer las respuestas. */
    var canSpeak by mutableStateOf(false)
        private set

    @Volatile private var stopAsked = false
    @Volatile private var cancelled = false
    /** Un modelo de Gemini que la clave puede usar. */
    class Model(val id: String, val label: String)

    /** Los modelos que permite la clave (los pide a Google); null mientras no se sabe. */
    var models by mutableStateOf<List<Model>?>(null)
        private set
    var modelsNote by mutableStateOf("")
        private set

    /** El modelo que eligió el usuario; null = automático (el mejor gratis que tenga la clave). */
    @Volatile var chosen: String? = null

    /** Pide a Google la lista de modelos de la clave (en otro hilo). */
    fun loadModels(key: String) {
        if (key.isBlank()) return
        modelsNote = ""
        Thread {
            val result = runCatching { fetchModels(key.trim()) }
            main.post {
                result.getOrNull()?.let { models = it }
                modelsNote = result.exceptionOrNull()?.let { "No se pudo leer la lista de modelos: ${it.message}" }.orEmpty()
            }
        }.start()
    }

    private fun fetchModels(key: String): List<Model> {
        val connection = URL(HOST.trimEnd('/') + "?pageSize=1000").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("x-goog-api-key", key)
            val code = connection.responseCode
            val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            check(code in 200..299) { runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()?.take(140) ?: "código $code" }
            val list = JSONObject(raw).optJSONArray("models") ?: JSONArray()
            val out = ArrayList<Model>()
            for (i in 0 until list.length()) {
                val m = list.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                if ("generateContent" !in methods) continue
                val id = m.optString("name").removePrefix("models/")
                if (!id.startsWith("gemini")) continue
                out += Model(id, m.optString("displayName").ifBlank { id })
            }
            return out
        }  finally {
            connection.disconnect()
        }
    }

    /** Ni voz, ni imágenes, ni video en vivo: los que contestan con texto. */
    private fun textModel(id: String) = listOf("tts", "image", "embedding", "live", "native-audio", "computer-use", "robotics", "aqa").none { it in id }

    /** Versión del modelo (2.5, 3…) para preferir el más nuevo; los "latest" siguen al más nuevo. */
    private fun version(id: String): Double =
        if ("latest" in id) 99.0 else Regex("gemini-(\\d+(?:\\.\\d+)?)").find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    /**
     * En qué orden probar modelos para [task]: el elegido, el que ya funcionó, y después los que la clave tiene (los
     * livianos para escribir lo que se dijo; "flash" para contestar; "pro" al final porque su cupo gratis es mínimo).
     */
    private fun ranked(key: String, task: String, defaults: List<String>): List<String> {
        if (models == null) runCatching { fetchModels(key) }.getOrNull()?.let { list -> main.post { models = list }; known = list }
        val have = (models ?: known)?.map { it.id }
        val voice = task == "voz"
        val pool = when {
            have == null -> defaults
            voice -> have.filter { "tts" in it }.ifEmpty { defaults }
            else -> have.filter { textModel(it) && "preview" !in it || it == chosen }.ifEmpty { have.filter(::textModel) }
        }
        fun rank(id: String): Double {
            val lite = "lite" in id
            val pro = "pro" in id
            val kind = when {
                pro -> 0.0
                task == "texto" && lite -> 3.0
                task == "texto" -> 2.0
                lite -> 2.0
                else -> 3.0
            }
            return kind * 1000 + version(id)
        }
        val sorted = pool.sortedByDescending(::rank)
        val first = listOfNotNull(chosen?.takeIf { !voice }, working[task])
        return (first + sorted).distinct().take(5)
    }

    @Volatile private var known: List<Model>? = null

    /** El modelo que contestó la última vez, por tarea: se prueba primero. */
    private val working = HashMap<String, String>()
    private var tts: TextToSpeech? = null
    @Volatile private var player: AudioTrack? = null
    @Volatile private var speech = 0

    /** Mientras Gemini escribe lo que se dijo (antes de la respuesta). */
    var transcribing by mutableStateOf(false)
        private set

    /** Mientras se prepara o suena la voz de la respuesta. */
    var speaking by mutableStateOf(false)
        private set

    fun clear() {
        quiet()
        messages.clear()
    }

    /** Prepara la voz de la pantalla (si tiene un motor de voz con español). */
    fun prepareVoice(context: Context) {
        if (tts != null) return
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                val ok = status == TextToSpeech.SUCCESS && runCatching {
                    val result = tts?.setLanguage(Locale.forLanguageTag("es-US")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                }.getOrDefault(false)
                main.post { canSpeak = ok }
            }
        }
    }

    /** Lee la respuesta: con la voz de la pantalla si tiene; si no, con la voz de Gemini (audio que se reproduce aquí). */
    private fun speak(key: String, text: String) {
        val clean = text.replace(Regex("[*#_`]"), "").trim()
        if (clean.isEmpty()) return
        if (canSpeak) {
            runCatching { tts?.speak(clean.take(3500), TextToSpeech.QUEUE_FLUSH, null, "litedash-asistente") }
            return
        }
        val turn = ++speech
        speaking = true
        Thread {
            val result = runCatching {
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part(clean.take(1200))))))
                    .put(
                        "generationConfig",
                        JSONObject().put("responseModalities", JSONArray().put("AUDIO")).put(
                            "speechConfig",
                            JSONObject().put("voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore"))),
                        ),
                    )
                var audio: ByteArray? = null
                call(key, "voz", ranked(key, "voz", VOICE_MODELS), { body }, stream = false, readMs = 40_000) { json ->
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) for (i in 0 until parts.length()) {
                        val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
                        if (data.isNotEmpty()) audio = Base64.decode(data, Base64.DEFAULT)
                    }
                }
                val pcm = audio ?: error("Gemini no mandó audio")
                if (turn != speech) return@runCatching
                play(pcm, turn)
            }
            main.post {
                if (turn == speech) speaking = false
                result.exceptionOrNull()?.let { if (turn == speech) messages += Message(false, "No se pudo leer en voz alta: ${it.message}", error = true) }
            }
        }.start()
    }

    /** Reproduce audio PCM de 16 bits a 24 kHz (lo que manda la voz de Gemini) por el parlante de la música. */
    @Suppress("DEPRECATION")
    private fun play(pcm: ByteArray, turn: Int) {
        val smallest = AudioTrack.getMinBufferSize(VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack(AudioManager.STREAM_MUSIC, VOICE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(smallest, 16_384), AudioTrack.MODE_STREAM)
        check(track.state == AudioTrack.STATE_INITIALIZED) { "la pantalla no dejó usar el parlante" }
        player = track
        // Medio segundo de silencio al final: si el búfer se queda sin datos, esta pantalla repite basura (el "ruido de radio").
        val sound = pcm.copyOf((pcm.size and 1.inv()) + VOICE_RATE)
        val frames = sound.size / 2
        try {
            track.play()
            var at = 0
            while (at < sound.size && turn == speech) {
                val n = track.write(sound, at, minOf(8192, sound.size - at))
                if (n <= 0) break
                at += n
            }
            // Espera a que suene todo lo escrito y corta antes de que el parlante se quede vacío.
            val limit = System.currentTimeMillis() + 5_000
            while (turn == speech && track.playbackHeadPosition < frames - VOICE_RATE / 4 && System.currentTimeMillis() < limit) Thread.sleep(50)
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.stop() }
            runCatching { track.release() }
            if (player === track) player = null
        }
    }

    /** Calla la voz. */
    fun quiet() {
        speech++
        speaking = false
        runCatching { tts?.stop() }
        runCatching { player?.pause(); player?.flush() }
    }

    /** Deja de grabar y manda lo grabado. */
    fun finishListening() {
        stopAsked = true
    }

    /** Deja de grabar sin mandar nada y calla la voz (al cerrar la pantalla). */
    fun cancel() {
        cancelled = true
        stopAsked = true
        quiet()
    }

    /** Graba la pregunta: termina sola tras un silencio, al tocar de nuevo o a los [MAX_SECONDS] segundos. */
    @SuppressLint("MissingPermission")
    fun listen(context: Context, key: String, speakAnswer: Boolean, where: String) {
        if (recording || busy || key.isBlank()) return
        val app = context.applicationContext
        quiet()
        recording = true
        stopAsked = false
        cancelled = false
        Thread {
            val pcm = ByteArrayOutputStream()
            var problem: String? = null
            var recorder: AudioRecord? = null
            var spoke = false
            try {
                val smallest = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(smallest > 0) { "la pantalla no graba a 16 kHz (código $smallest)" }
                val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(smallest, RATE * 2))
                recorder = r
                check(r.state == AudioRecord.STATE_INITIALIZED) { "la pantalla no le dio el micrófono a LiteDash" }
                r.startRecording()
                check(r.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "el micrófono está ocupado por otra app" }
                val chunk = ShortArray(RATE / 10)
                val bytes = ByteArray(chunk.size * 2)
                var floor = Double.MAX_VALUE
                var chunks = 0
                var silent = 0
                while (!stopAsked && chunks < MAX_SECONDS * 10) {
                    val n = r.read(chunk, 0, chunk.size)
                    check(n >= 0) { "el micrófono dejó de responder (código $n)" }
                    if (n == 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = chunk[i].toInt()
                        sum += v.toDouble() * v
                        bytes[i * 2] = (v and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    pcm.write(bytes, 0, n * 2)
                    val rms = Math.sqrt(sum / n)
                    chunks++
                    // El ruido de fondo se mide al empezar; hablar es pasar bien por encima de él.
                    if (chunks <= 3) floor = minOf(floor, rms)
                    val threshold = (floor * 3).coerceIn(600.0, 2500.0)
                    if (rms > threshold) { spoke = true; silent = 0 } else silent++
                    val shown = (rms / 6000.0).coerceIn(0.0, 1.0).toFloat()
                    main.post { level = shown }
                    if (spoke && silent >= QUIET_CHUNKS) break
                }
            } catch (t: Throwable) {
                problem = t.message ?: t.javaClass.simpleName
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
            }
            val audio = pcm.toByteArray()
            main.post {
                recording = false
                level = 0f
                when {
                    cancelled -> Unit
                    problem != null -> messages += Message(false, "Micrófono: $problem", error = true)
                    audio.size < RATE -> Unit // menos de medio segundo: un toque sin querer
                    !spoke && audio.size >= RATE * 2 * 3 -> messages += Message(false, "No se oyó nada por el micrófono de la pantalla. Puedes escribir la pregunta abajo.", error = true)
                    else -> send(key, wav(audio), null, speakAnswer, where)
                }
            }
        }.start()
    }

    /** Una pregunta escrita. */
    fun ask(key: String, text: String, speakAnswer: Boolean, where: String) {
        if (busy || recording || key.isBlank() || text.isBlank()) return
        quiet()
        send(key, null, text.trim(), speakAnswer, where)
    }

    /**
     * Primero se escribe en pantalla lo que se dijo (un modelo liviano, rápido) y enseguida se pide la respuesta, que
     * va apareciendo mientras Gemini la escribe.
     */
    private fun send(key: String, audio: ByteArray?, text: String?, speakAnswer: Boolean, where: String) {
        busy = true
        val history = messages.filter { !it.error && it.text.isNotBlank() }.takeLast(12).map { it.mine to it.text }
        if (text != null) messages += Message(true, text)
        else transcribing = true
        Thread {
            val clean = key.trim()
            val question = if (text != null) Result.success(text) else runCatching { transcribe(clean, audio!!) }
            val heard = question.getOrNull()?.trim().orEmpty()
            main.post {
                transcribing = false
                when {
                    question.isFailure -> {
                        messages += Message(true, "(pregunta por voz)")
                        messages += Message(false, question.exceptionOrNull()?.message ?: "Sin respuesta", error = true)
                    }
                    heard.isEmpty() -> messages += Message(false, "No te entendí. Intenta de nuevo, más cerca del micrófono.", error = true)
                    text == null -> messages += Message(true, heard)
                }
                if (question.isFailure || heard.isEmpty()) { busy = false; return@post }
                if (wantsImage(heard)) {
                    messages += Message(false, "Haciendo la imagen…")
                    val slot = messages.lastIndex
                    Thread {
                        val result = runCatching { image(clean, heard) }
                        main.post {
                            busy = false
                            if (slot >= messages.size) return@post
                            val made = result.getOrNull()
                            messages[slot] = if (made != null) Message(false, made.second, image = made.first)
                            else Message(false, result.exceptionOrNull()?.message ?: "No se pudo hacer la imagen.", error = true)
                            if (made != null && speakAnswer) speak(clean, "Listo, aquí está la imagen.")
                        }
                    }.start()
                    return@post
                }
                // La respuesta empieza vacía y se va llenando.
                messages += Message(false, "")
                val slot = messages.lastIndex
                Thread {
                    val built = StringBuilder()
                    val result = runCatching {
                        answer(clean, history, heard, where) { delta ->
                            built.append(delta)
                            val shown = built.toString()
                            main.post { if (slot < messages.size) messages[slot] = Message(false, shown) }
                        }
                    }
                    main.post {
                        busy = false
                        val final = built.toString().trim()
                        if (slot >= messages.size) return@post
                        when {
                            final.isNotEmpty() -> {
                                messages[slot] = Message(false, final)
                                if (speakAnswer) speak(clean, final)
                            }
                            else -> messages[slot] = Message(false, result.exceptionOrNull()?.message ?: "Gemini contestó vacío; pregunta de nuevo.", error = true)
                        }
                    }
                }.start()
            }
        }.start()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        fun int(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun short(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); int(36 + pcm.size); out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII)); int(16); short(1); short(1); int(RATE); int(RATE * 2); short(2); short(16)
        out.write("data".toByteArray(Charsets.US_ASCII)); int(pcm.size)
        out.write(pcm, 0, pcm.size)
        return out.toByteArray()
    }

    private fun part(text: String) = JSONObject().put("text", text)

    /** Sin "pensar" antes de contestar: en los modelos 2.5 eso es lo que hacía tardar tanto. */
    private fun quick(model: String, config: JSONObject = JSONObject()): JSONObject =
        if ("2.5" in model && "tts" !in model) config.put("thinkingConfig", JSONObject().put("thinkingBudget", 0)) else config

    /** Lo que se dijo en el audio, escrito tal cual. */
    private fun transcribe(key: String, audio: ByteArray): String {
        val wav = Base64.encodeToString(wav(audio), Base64.NO_WRAP)
        var heard = ""
        call(key, "texto", ranked(key, "texto", TRANSCRIBE_MODELS), { model ->
            JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts",
                            JSONArray()
                                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", wav)))
                                .put(part("Escribe exactamente lo que dice la persona en este audio (en español de Costa Rica), sin comillas ni nada más. Si no se oye ninguna palabra, no escribas nada.")),
                        ),
                    ),
                )
                .put("generationConfig", quick(model, JSONObject().put("temperature", 0)))
        }, stream = false, readMs = 25_000) { json -> heard = textOf(json) }
        return heard.trim().trim('"', '“', '”').trim()
    }

    /** La respuesta, que llega en pedazos por [onText] mientras Gemini la escribe. */
    private fun answer(key: String, history: List<Pair<Boolean, String>>, question: String, where: String, onText: (String) -> Unit) {
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-CR")).format(Date())
        val system = "Eres el asistente de la pantalla de un carro (${MyCar.MODEL}) en Costa Rica. El conductor puede ir manejando y la respuesta se le lee en voz alta: " +
            "responde en español, claro, cálido y breve (dos a cuatro frases, salvo que pida detalle), en texto simple sin Markdown, asteriscos, listas ni tablas. " +
            "La hora de la pantalla es $now. $where"
        val contents = JSONArray()
        for ((mine, said) in history) contents.put(JSONObject().put("role", if (mine) "user" else "model").put("parts", JSONArray().put(part(said))))
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(part(question))))
        call(key, "respuesta", ranked(key, "respuesta", ANSWER_MODELS), { model ->
            JSONObject()
                .put("system_instruction", JSONObject().put("parts", JSONArray().put(part(system))))
                .put("contents", contents)
                .put("generationConfig", quick(model))
        }, stream = true, readMs = 30_000) { json -> textOf(json).takeIf { it.isNotEmpty() }?.let(onText) }
    }

    /**
     * Hace la imagen que se pidió: primero con Gemini (si la clave tiene un modelo de imágenes con cupo) y si no, con
     * Pollinations, que es gratis y no pide clave. Devuelve la imagen y un texto corto para la burbuja.
     */
    private fun image(key: String, request: String): Pair<ImageBitmap, String> {
        var made: ByteArray? = null
        var caption = ""
        val have = (models ?: known)?.map { it.id }?.filter { "image" in it && "imagen" !in it }.orEmpty()
        val list = (working["imagen"]?.let { listOf(it) }.orEmpty() + have + IMAGE_MODELS).distinct().take(4)
        val gemini = runCatching {
            call(key, "imagen", list, { _ ->
                JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part(request + "\n(Formato horizontal, 16:9. Si escribes algo, que sea en español y muy corto.)")))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            }, stream = false, readMs = 90_000) { json ->
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return@call
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    val data = (p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data"))?.optString("data")
                    if (!data.isNullOrEmpty()) made = Base64.decode(data, Base64.DEFAULT)
                    else if (!p.optBoolean("thought", false)) caption += p.optString("text")
                }
            }
        }
        made?.let { bytes -> decode(bytes)?.let { return it to caption.trim().ifEmpty { "Listo, aquí está." } } }
        // Sin Gemini: se describe en inglés (así dibuja mejor) y se pide a Pollinations.
        val prompt = runCatching { englishPrompt(key, request) }.getOrNull()?.takeIf { it.isNotBlank() } ?: request
        val bytes = runCatching { pollinations(prompt) }
        val bitmap = bytes.getOrNull()?.let(::decode)
        if (bitmap != null) return bitmap to "Listo, aquí está."
        val why = gemini.exceptionOrNull()?.message?.lineSequence()?.firstOrNull().orEmpty()
        error("No se pudo hacer la imagen. ${bytes.exceptionOrNull()?.let { "Pollinations: ${it.javaClass.simpleName} ${it.message.orEmpty().take(80)}" }.orEmpty()} ${if (why.isNotEmpty()) "Gemini: $why" else ""}".trim())
    }

    /** Pasa el pedido a una descripción en inglés para dibujar (con el modelo de texto, que es rápido). */
    private fun englishPrompt(key: String, request: String): String {
        var out = ""
        call(key, "texto", ranked(key, "texto", TRANSCRIBE_MODELS), { model ->
            JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(part("Write a short, vivid English image-generation prompt (max 60 words, no quotes, nothing else) for this request: $request")))))
                .put("generationConfig", quick(model, JSONObject().put("temperature", 0.4)))
        }, stream = false, readMs = 20_000) { json -> out = textOf(json) }
        return out.trim().trim('"').take(400)
    }

    private fun pollinations(prompt: String): ByteArray {
        val seed = (System.currentTimeMillis() % 100_000).toInt()
        val url = "https://image.pollinations.ai/prompt/" + java.net.URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=1280&height=720&nologo=true&seed=$seed"
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 90_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "LiteDash")
            val code = connection.responseCode
            check(code in 200..299) { "código $code" }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /** Achica la imagen a un tamaño que la pantalla aguante sin quedarse sin memoria. */
    private fun decode(bytes: ByteArray): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1024 || bounds.outHeight / (sample * 2) >= 1024) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()

    private fun textOf(json: JSONObject): String {
        val found = StringBuilder()
        val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (!p.optBoolean("thought", false)) found.append(p.optString("text"))
        }
        return found.toString()
    }

    /**
     * Llama a Gemini probando los [models] en orden. Con [stream] la respuesta llega en pedazos (eventos "data:") y cada
     * uno pasa por [onJson] apenas llega; si no, llega entera. Un modelo que ya empezó a contestar no se cambia.
     */
    private fun call(key: String, task: String, models: List<String>, body: (String) -> JSONObject, stream: Boolean, readMs: Int, onJson: (JSONObject) -> Unit) {
        var last = "Sin respuesta"
        // Qué pasó con cada modelo probado, para que el error diga todo y no solo el último.
        val tried = ArrayList<String>()
        for (name in models) {
            val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
            val bytes = body(name).toString().toByteArray(Charsets.UTF_8)
            val connection = URL("$HOST$name:$action").openConnection() as HttpURLConnection
            var started = false
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = readMs
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("x-goog-api-key", key)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
                val code = connection.responseCode
                if (code in 200..299) {
                    working[task] = name
                    if (stream) {
                        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                if (!line.startsWith("data:")) continue
                                val json = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: continue
                                started = true
                                onJson(json)
                            }
                        }
                    } else {
                        val raw = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        started = true
                        onJson(JSONObject(raw))
                    }
                    return
                }
                val raw = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val detail = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(160)
                last = when (code) {
                    400, 401, 403 -> if (detail.contains("API key", ignoreCase = true) || code != 400) "La clave de Gemini no sirve: revísala en el botón Clave. $detail" else "Gemini rechazó la pregunta: $detail"
                    404 -> "El modelo $name no existe para esta clave."
                    429 -> "Se acabaron las preguntas gratis por ahora (límite de Gemini). Prueba en un rato. $detail"
                    else -> "Gemini respondió $code. $detail"
                }
                tried += "$name: " + when (code) {
                    404 -> "no está para esta clave"
                    429 -> "se acabó su cupo gratis por ahora"
                    else -> "error $code"
                }
                // Con otro modelo puede ir: no existe, está saturado o ese ya gastó su cupo gratis.
                if (code == 400 && !detail.contains("model", ignoreCase = true)) error(last)
                if (code == 401 || code == 403) error(last)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                last = if (e is java.net.SocketTimeoutException) "Gemini tardó demasiado en contestar; intenta de nuevo." else "Sin conexión con Gemini: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}"
                // Si ya estaba escribiendo la respuesta, no se empieza otra con otro modelo.
                if (started) error(last)
                tried += "$name: " + if (e is java.net.SocketTimeoutException) "tardó demasiado" else "sin conexión"
            } finally {
                connection.disconnect()
            }
        }
        if (tried.size > 1) error("Ningún modelo contestó. Toca «Modelo» para elegir otro.\n" + tried.joinToString("\n"))
        error(last)
    }

    /** Busca la clave en un archivo `gemini.txt` de la USB o de Descargas, para no teclearla en la pantalla. */
    fun keyFromFile(): String? {
        val roots = ArrayList<File>()
        File("/storage/usbotg").listFiles()?.let { roots += it }
        roots += File("/storage/usbotg")
        File("/storage").listFiles()?.let { roots += it }
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { roots += it; roots += File(it, "Download") }
        val shape = Regex("AIza[0-9A-Za-z_\\-]{30,}")
        for (root in roots) for (name in listOf("gemini.txt", "Gemini.txt", "GEMINI.TXT")) {
            val text = runCatching { File(root, name).takeIf { it.isFile && it.length() < 4096 }?.readText() }.getOrNull() ?: continue
            val found = shape.find(text)?.value ?: text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 20 && ' ' !in it }
            if (found != null) return found
        }
        return null
    }
}

/** El widget: un micrófono que abre el asistente y empieza a escuchar. */
@Composable
fun AssistantTile(onOpen: () -> Unit) {
    val c = LocalColors.current
    Card(Modifier.fillMaxSize(), onClick = onOpen) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
            val side = (minOf(maxWidth, maxHeight) * 0.5f).coerceAtMost(72.dp)
            val tall = maxHeight > 90.dp
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.mic, c.accent, side)
                if (tall) {
                    Spacer(Modifier.height(8.dp))
                    Label(stringResource(R.string.tile_assistant), Typography.small.copy(fontWeight = FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** El asistente en grande: la conversación a un lado y el micrófono al otro; abajo se puede escribir. */
@Composable
fun AssistantScreen(d: Dependencies, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val prefs = d.prefs
    val key = prefs.assistantKey.orEmpty()
    var editing by remember { mutableStateOf(key.isBlank()) }
    var draft by remember { mutableStateOf(key) }
    var note by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    Assistant.chosen = prefs.assistantModel
    LaunchedEffect(key) { if (key.isNotBlank() && Assistant.models == null) Assistant.loadModels(key) }
    val position = d.trip.position
    val where = if (position != null) "El carro está cerca de la latitud ${position.latitude} y longitud ${position.longitude}." else "El carro suele estar en ${MyCar.PLACE}."

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun listen() = Assistant.listen(context, prefs.assistantKey.orEmpty(), prefs.assistantSpeak, where)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() }
    fun micTapped() {
        when {
            Assistant.recording -> Assistant.finishListening()
            Assistant.busy -> Unit
            granted() -> listen()
            else -> runCatching { permission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }

    DisposableEffect(Unit) {
        Assistant.prepareVoice(context)
        onDispose { Assistant.cancel() }
    }
    // Al abrirlo ya escucha: tocar el widget y hablar.
    LaunchedEffect(Unit) { if (!editing && granted()) listen() }

    PanelScaffold(
        stringResource(R.string.tile_assistant),
        close,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(end = 10.dp)) {
                if (!editing) {
                    Button(stringResource(if (prefs.assistantSpeak) R.string.assistant_voice_on else R.string.assistant_voice_off), {
                        prefs.assistantSpeak = !prefs.assistantSpeak
                        if (!prefs.assistantSpeak) Assistant.quiet()
                    })
                    if (Assistant.messages.isNotEmpty()) Button(stringResource(R.string.assistant_clear), { Assistant.clear() })
                    Button(stringResource(R.string.assistant_model), { picking = !picking; if (Assistant.models == null) Assistant.loadModels(prefs.assistantKey.orEmpty()) }, primary = picking)
                    Button(stringResource(R.string.assistant_key), { Assistant.cancel(); draft = prefs.assistantKey.orEmpty(); note = ""; editing = true })
                }
            }
        },
    ) {
        if (editing) {
            Label(stringResource(R.string.assistant_key_help), Typography.body, c.textMuted, 8)
            Spacer(Modifier.height(14.dp))
            TextInput(draft, { draft = it.trim().take(120) }, stringResource(R.string.assistant_key_hint), Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(stringResource(R.string.assistant_key_save), {
                    if (draft.length >= 20) { prefs.assistantKey = draft; editing = false; note = ""; Assistant.loadModels(draft) } else note = context.getString(R.string.assistant_key_short)
                }, primary = true)
                Button(stringResource(R.string.assistant_key_file), {
                    val found = runCatching { Assistant.keyFromFile() }.getOrNull()
                    if (found != null) { draft = found; note = context.getString(R.string.assistant_key_found) } else note = context.getString(R.string.assistant_key_missing)
                })
                if (prefs.assistantKey.orEmpty().isNotBlank()) Button(stringResource(R.string.assistant_key_cancel), { editing = false; note = "" })
            }
            if (note.isNotEmpty()) Label(note, Typography.body, c.accent, 3, Modifier.padding(top = 12.dp))
            return@PanelScaffold
        }
        if (picking) {
            ModelPicker(prefs.assistantModel) { id ->
                prefs.assistantModel = id
                Assistant.chosen = id
                picking = false
            }
            return@PanelScaffold
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val list = rememberLazyListState()
            val count = Assistant.messages.size
            // Baja sola mientras llega la respuesta (cada pedazo cambia el largo del último mensaje).
            val tail = Assistant.messages.lastOrNull()?.text?.length ?: 0
            LaunchedEffect(count, tail, Assistant.transcribing) { if (count > 0) list.scrollToItem(count - 1 + if (Assistant.transcribing) 1 else 0, 100_000) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (count == 0 && !Assistant.busy) Label(stringResource(R.string.assistant_empty), Typography.subtitle, c.textMuted, 4, Modifier.align(Alignment.Center))
                LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Assistant.messages) { m -> Bubble(m) }
                    if (Assistant.transcribing) item { Label(stringResource(R.string.assistant_hearing), Typography.body, c.accent, 1, Modifier.padding(10.dp)) }
                }
            }
            Column(Modifier.width(200.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val recording = Assistant.recording
                val alert = Color(0xFFFF3B30)
                // El aro crece con el volumen: se ve que el micrófono sí oye.
                Box(
                    Modifier.size(150.dp).clip(CircleShape)
                        .background(if (recording) alert.copy(alpha = 0.18f + 0.5f * Assistant.level) else Color.Transparent)
                        .clickable { micTapped() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(112.dp).clip(CircleShape).background(if (recording) alert else c.primary).border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.mic, if (recording) Color.White else c.onPrimary, 56.dp) }
                }
                Spacer(Modifier.height(10.dp))
                Label(
                    stringResource(
                        when {
                            recording -> R.string.assistant_listening
                            Assistant.transcribing -> R.string.assistant_hearing
                            Assistant.busy -> R.string.assistant_thinking
                            Assistant.speaking -> R.string.assistant_speaking
                            else -> R.string.assistant_tap
                        },
                    ),
                    Typography.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 2,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput(typed, { typed = it }, stringResource(R.string.assistant_type), Modifier.weight(1f))
            Button(stringResource(R.string.assistant_send), {
                if (typed.isNotBlank() && !Assistant.busy && !Assistant.recording) {
                    Assistant.ask(prefs.assistantKey.orEmpty(), typed, prefs.assistantSpeak, where)
                    typed = ""
                }
            }, primary = true)
        }
    }
}

/** Elegir el modelo: automático o uno de los que permite la clave (con lo que conviene de cada tipo). */
@Composable
private fun ModelPicker(current: String?, pick: (String?) -> Unit) {
    val c = LocalColors.current
    val list = Assistant.models
    Label(stringResource(R.string.assistant_model_help), Typography.body, c.textMuted, 4)
    Spacer(Modifier.height(10.dp))
    if (list == null) {
        Label(Assistant.modelsNote.ifEmpty { stringResource(R.string.places_loading) }, Typography.body, c.textMuted, 3)
        return
    }
    val shown = list.filter { m -> listOf("tts", "image", "embedding", "live", "native-audio", "computer-use", "robotics", "aqa").none { it in m.id } }
        .sortedWith(compareBy<Assistant.Model>({ "pro" in it.id }, { "lite" !in it.id }).thenByDescending { it.id })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ModelRow(stringResource(R.string.assistant_model_auto), stringResource(R.string.assistant_model_auto_help), current == null) { pick(null) } }
        items(shown) { m ->
            val hint = when {
                "pro" in m.id -> R.string.assistant_model_pro
                "lite" in m.id -> R.string.assistant_model_lite
                else -> R.string.assistant_model_flash
            }
            ModelRow(m.label, m.id + " · " + stringResource(hint), current == m.id) { pick(m.id) }
        }
    }
}

@Composable
private fun ModelRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) c.primary else c.surface).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Label(title, Typography.subtitle, if (selected) c.onPrimary else c.text, 1)
        Label(detail, Typography.small, if (selected) c.onPrimary else c.textMuted, 2)
    }
}

@Composable
private fun Bubble(m: Assistant.Message) {
    val c = LocalColors.current
    // Como un chat: lo que dices, a la izquierda y con el color de acento; lo que contesta, a la derecha.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.Start else Arrangement.End) {
        Box(
            Modifier.fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(topStart = if (m.mine) 4.dp else 18.dp, topEnd = if (m.mine) 18.dp else 4.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                .background(
                    when {
                        m.mine -> c.primary
                        m.error -> c.surface
                        else -> c.surfaceHigh
                    },
                )
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            m.image?.let { picture ->
                Image(
                    picture,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit,
                )
            }
            Label(
                m.text.ifEmpty { "…" },
                Typography.subtitle,
                when {
                    m.mine -> c.onPrimary
                    m.error -> c.danger
                    else -> c.text
                },
            )
            }
        }
    }
}
