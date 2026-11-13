package com.irvingguerra.litedash

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.irvingguerra.litedash.core.ui.Radius
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
    class Message(val mine: Boolean, val text: String, val error: Boolean = false)

    private const val RATE = 16_000
    private const val MAX_SECONDS = 45

    /** Tras hablar, este rato de silencio manda la pregunta sola (en trozos de 100 ms). */
    private const val QUIET_CHUNKS = 18
    private const val HOST = "https://generativelanguage.googleapis.com/v1beta/models/"

    /** Se prueban en orden: el nombre "latest" sigue al modelo gratis vigente; los otros, por si no existe. */
    private val MODELS = listOf("gemini-flash-latest", "gemini-2.5-flash", "gemini-2.0-flash")

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
    private var model: String? = null
    private var tts: TextToSpeech? = null

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

    private fun speak(text: String) {
        if (!canSpeak) return
        runCatching { tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "litedash-asistente") }
    }

    /** Calla la voz. */
    fun quiet() {
        runCatching { tts?.stop() }
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

    private fun send(key: String, audio: ByteArray?, text: String?, speakAnswer: Boolean, where: String) {
        busy = true
        val history = messages.filter { !it.error }.takeLast(12).map { it.mine to it.text }
        if (text != null) messages += Message(true, text)
        Thread {
            val result = runCatching { request(key.trim(), history, audio, text, where) }
            main.post {
                busy = false
                val reply = result.getOrNull()
                if (reply == null) {
                    if (audio != null) messages += Message(true, "(pregunta por voz)")
                    messages += Message(false, result.exceptionOrNull()?.message ?: "Sin respuesta", error = true)
                    return@post
                }
                var answer = reply.trim()
                if (audio != null) {
                    // Con audio se le pide que primero escriba lo que entendió, para verlo en pantalla.
                    val first = answer.lineSequence().firstOrNull().orEmpty()
                    val heard = if (first.startsWith("Tú:", ignoreCase = true)) first.substring(3).trim() else ""
                    if (heard.isNotEmpty()) answer = answer.substringAfter('\n', "").trim()
                    messages += Message(true, heard.ifEmpty { "(pregunta por voz)" })
                }
                if (answer.isEmpty()) answer = "No entendí la pregunta."
                messages += Message(false, answer)
                if (speakAnswer) speak(answer)
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

    private fun request(key: String, history: List<Pair<Boolean, String>>, audio: ByteArray?, text: String?, where: String): String {
        val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm", Locale.forLanguageTag("es-CR")).format(Date())
        val system = "Eres el asistente de la pantalla de un carro (${MyCar.MODEL}) en Costa Rica. El conductor puede ir manejando: " +
            "responde en español, claro y breve (pocas frases, salvo que pida detalle), en texto simple sin Markdown, asteriscos ni tablas. " +
            "La hora de la pantalla es $now. $where " +
            "Si la pregunta llega en audio, la primera línea de tu respuesta debe ser exactamente \"Tú: \" seguido de lo que entendiste, y después la respuesta en las líneas siguientes."
        val contents = JSONArray()
        for ((mine, said) in history) contents.put(JSONObject().put("role", if (mine) "user" else "model").put("parts", JSONArray().put(part(said))))
        val parts = JSONArray()
        if (audio != null) {
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
            parts.put(part("Responde la pregunta de este audio."))
        } else parts.put(part(text.orEmpty()))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(part(system))))
            .put("contents", contents)
            .toString().toByteArray(Charsets.UTF_8)

        var last = "Sin respuesta"
        val order = listOfNotNull(model) + MODELS.filter { it != model }
        for (name in order) {
            val connection = URL("$HOST$name:generateContent").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 90_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("x-goog-api-key", key)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                val raw = (if (code in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val json = runCatching { JSONObject(raw) }.getOrNull()
                if (code in 200..299) {
                    val found = StringBuilder()
                    val answerParts = json?.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    if (answerParts != null) for (i in 0 until answerParts.length()) {
                        val p = answerParts.optJSONObject(i) ?: continue
                        if (!p.optBoolean("thought", false)) found.append(p.optString("text"))
                    }
                    if (found.isNotBlank()) {
                        model = name
                        return found.toString()
                    }
                    val blocked = json?.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    error(if (blocked.isNotEmpty()) "Gemini no quiso responder ($blocked)." else "Gemini contestó vacío; pregunta de nuevo.")
                }
                val detail = json?.optJSONObject("error")?.optString("message").orEmpty().take(160)
                last = when (code) {
                    400, 401, 403 -> if (detail.contains("API key", ignoreCase = true) || code != 400) "La clave de Gemini no sirve: revísala en el botón Clave. $detail" else "Gemini rechazó la pregunta: $detail"
                    404 -> "El modelo $name no existe para esta clave."
                    429 -> "Se acabaron las preguntas gratis por ahora (límite de Gemini). Prueba en un rato. $detail"
                    else -> "Gemini respondió $code. $detail"
                }
                // Con otro modelo puede ir: no existe, está saturado o ese ya gastó su cupo gratis.
                if (code != 404 && code != 429 && code < 500) error(last)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Sin conexión con Gemini: ${e.javaClass.simpleName} ${e.message.orEmpty().take(100)}")
            } finally {
                connection.disconnect()
            }
        }
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
                    if (Assistant.canSpeak) Button(stringResource(if (prefs.assistantSpeak) R.string.assistant_voice_on else R.string.assistant_voice_off), {
                        prefs.assistantSpeak = !prefs.assistantSpeak
                        if (!prefs.assistantSpeak) Assistant.quiet()
                    })
                    if (Assistant.messages.isNotEmpty()) Button(stringResource(R.string.assistant_clear), { Assistant.clear() })
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
                    if (draft.length >= 20) { prefs.assistantKey = draft; editing = false; note = "" } else note = context.getString(R.string.assistant_key_short)
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
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val list = rememberLazyListState()
            val count = Assistant.messages.size
            LaunchedEffect(count, Assistant.busy) { if (count > 0) list.scrollToItem(count - 1 + if (Assistant.busy) 1 else 0) }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (count == 0 && !Assistant.busy) Label(stringResource(R.string.assistant_empty), Typography.subtitle, c.textMuted, 4, Modifier.align(Alignment.Center))
                LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Assistant.messages) { m -> Bubble(m) }
                    if (Assistant.busy) item { Label(stringResource(R.string.assistant_thinking), Typography.body, c.accent, 1, Modifier.padding(10.dp)) }
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
                            Assistant.busy -> R.string.assistant_thinking
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

@Composable
private fun Bubble(m: Assistant.Message) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.fillMaxWidth(if (m.mine) 0.8f else 1f).clip(RoundedCornerShape(Radius.inner)).background(if (m.mine) c.surfaceHigh else c.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Label(
                m.text,
                if (m.mine) Typography.body else Typography.subtitle,
                when {
                    m.error -> c.danger
                    m.mine -> c.textMuted
                    else -> c.text
                },
            )
        }
    }
}
