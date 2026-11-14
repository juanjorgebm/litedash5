package com.irvingguerra.litedash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Las fotos del widget "Fotos". Google ya no deja que una app lea la biblioteca de Google Fotos, así que salen de dos
 * lados: un álbum compartido por enlace (su página pública trae las direcciones de las fotos; se bajan a la pantalla
 * y se ven sin internet) y una carpeta "Fotos" en la USB.
 */
object PhotoAlbum {
    private const val MAX_ALBUM = 120
    private const val REFRESH_MS = 6 * 60 * 60 * 1000L

    /** Las fotos del álbum en su página: direcciones largas de lh3.googleusercontent.com (las cortas son avatares). */
    private val PHOTO = Regex("""https://lh3\.googleusercontent\.com/(?:pw/)?[A-Za-z0-9_\-]{60,}""")
    private val IMAGE_FILE = Regex("""(?i).+\.(jpe?g|png|webp)""")
    private val main = Handler(Looper.getMainLooper())

    /** Sube al cambiar las fotos disponibles (para volver a listarlas). */
    var version by mutableIntStateOf(0)
        private set
    var syncing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("photos", Context.MODE_PRIVATE)

    /** Cuánto se queda cada foto, en segundos (lo elige el usuario). */
    val CHOICES = listOf(10, 30, 60, 120, 300, 600)
    var seconds by mutableIntStateOf(0)
        private set

    fun seconds(context: Context): Int {
        if (seconds == 0) seconds = prefs(context).getInt("seconds", 60)
        return seconds
    }

    fun setSeconds(context: Context, value: Int) {
        seconds = value
        prefs(context).edit().putInt("seconds", value).apply()
    }
    private fun dir(context: Context) = File(context.filesDir, "album").apply { mkdirs() }

    fun link(context: Context): String = prefs(context).getString("link", "").orEmpty()

    fun setLink(context: Context, link: String) {
        prefs(context).edit().putString("link", link.trim()).putLong("at", 0L).apply()
    }

    /** Todas las fotos que hay: las del álbum ya bajadas y las de la carpeta Fotos de la USB. */
    fun files(context: Context): List<File> {
        val found = ArrayList<File>()
        dir(context).listFiles()?.filterTo(found) { it.isFile && it.length() > 0 && !it.name.endsWith(".tmp") }
        val roots = ArrayList<File>()
        File("/storage/usbotg").listFiles()?.let { roots += it }
        File("/storage").listFiles()?.let { roots += it }
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { roots += it }
        for (root in roots) for (name in listOf("Fotos", "fotos", "FOTOS")) {
            val folder = File(root, name)
            if (!folder.isDirectory) continue
            runCatching { folder.listFiles()?.filterTo(found) { it.isFile && IMAGE_FILE.matches(it.name) } }
            if (found.size > 600) break
        }
        return found.distinctBy { it.absolutePath }
    }

    /** Vuelve a leer el álbum si hay enlace y ya pasó un rato desde la última vez. */
    fun refreshIfOld(context: Context) {
        if (link(context).isBlank()) return
        if (System.currentTimeMillis() - prefs(context).getLong("at", 0L) in 0..REFRESH_MS) return
        sync(context)
    }

    private fun open(address: String): HttpURLConnection {
        var url = address
        // Las redirecciones se siguen a mano: el enlace corto salta de un servidor a otro.
        repeat(6) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "es")
            val code = c.responseCode
            if (code !in 300..399) return c
            val next = c.getHeaderField("Location")
            c.disconnect()
            checkNotNull(next) { "redirección sin destino" }
            url = URL(URL(url), next).toString()
        }
        error("demasiadas redirecciones")
    }

    /** Lee la página del álbum compartido y baja las fotos que falten; borra las que ya no están en el álbum. */
    fun sync(context: Context) {
        if (syncing) return
        val app = context.applicationContext
        var link = link(app)
        if (link.isBlank()) return
        if (!link.startsWith("http")) link = "https://$link"
        syncing = true
        status = "Leyendo el álbum…"
        Thread {
            val result = runCatching {
                val page = open(link)
                val html = try {
                    check(page.responseCode == 200) { "el enlace respondió ${page.responseCode}" }
                    page.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                } finally {
                    page.disconnect()
                }
                val photos = PHOTO.findAll(html).map { it.value }.distinct().take(MAX_ALBUM).toList()
                check(photos.isNotEmpty()) { "no encontré fotos en ese enlace; revisa que sea el enlace de un álbum compartido" }
                val folder = dir(app)
                val wanted = HashSet<String>()
                var failed = 0
                for ((i, photo) in photos.withIndex()) {
                    val name = MessageDigest.getInstance("SHA-1").digest(photo.toByteArray()).take(10).joinToString("") { "%02x".format(it) } + ".jpg"
                    wanted += name
                    val file = File(folder, name)
                    if (file.isFile && file.length() > 0) continue
                    main.post { status = "Bajando fotos… ${i + 1} de ${photos.size}" }
                    val ok = runCatching {
                        val c = open("$photo=w1600-h900")
                        try {
                            check(c.responseCode == 200) { "foto ${c.responseCode}" }
                            val tmp = File(folder, "$name.tmp")
                            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            check(tmp.renameTo(file)) { "no se pudo guardar" }
                        } finally {
                            c.disconnect()
                        }
                    }.isSuccess
                    if (ok) main.post { version++ } else failed++
                }
                // Lo que se quitó del álbum se quita de la pantalla (solo si la lectura salió bien).
                folder.listFiles()?.forEach { if (it.name !in wanted) it.delete() }
                photos.size to failed
            }
            main.post {
                syncing = false
                val done = result.getOrNull()
                status = when {
                    done == null -> "Álbum: ${result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName}"
                    done.second > 0 -> "${done.first - done.second} fotos del álbum (${done.second} no se pudieron bajar)"
                    else -> "${done.first} fotos del álbum"
                }
                if (done != null) prefs(app).edit().putLong("at", System.currentTimeMillis()).apply()
                version++
            }
        }.start()
    }

    /** Busca el enlace en un archivo `fotos.txt` de la USB o de Descargas, para no teclearlo. */
    fun linkFromFile(): String? {
        val roots = ArrayList<File>()
        File("/storage/usbotg").listFiles()?.let { roots += it }
        File("/storage").listFiles()?.let { roots += it }
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { roots += it; roots += File(it, "Download") }
        val shape = Regex("""https?://\S+""")
        for (root in roots) for (name in listOf("fotos.txt", "Fotos.txt", "FOTOS.TXT")) {
            val text = runCatching { File(root, name).takeIf { it.isFile && it.length() < 4096 }?.readText() }.getOrNull() ?: continue
            shape.find(text)?.let { return it.value }
        }
        return null
    }

    /** La foto achicada a lo que cabe en la pantalla y derecha (las de cámara vienen giradas por EXIF). */
    fun decode(file: File, maxSide: Int): ImageBitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val turn = when (runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val upright = if (turn == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(turn) }, true)
        upright.asImageBitmap()
    }.getOrNull()
}

/** Va pasando las [files] en desorden, con un fundido entre una y otra. */
@Composable
private fun Slideshow(files: List<File>, maxSide: Int, scale: ContentScale, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    val context = LocalContext.current
    // Mientras se bajan fotos la lista cambia a cada rato: no por eso se pasa a la siguiente antes de tiempo.
    val current by rememberUpdatedState(files)
    LaunchedEffect(files.isEmpty()) {
        if (files.isEmpty()) { shown = null; return@LaunchedEffect }
        while (true) {
            for (file in current.shuffled()) {
                if (!file.isFile) continue
                val next = withContext(Dispatchers.IO) { PhotoAlbum.decode(file, maxSide) } ?: continue
                if (shown != null) fade.animateTo(0f, tween(450))
                shown = next
                fade.animateTo(1f, tween(650))
                // Se revisa cada segundo: si cambian el tiempo en el ajuste, vale de una vez.
                var waited = 0
                while (waited < PhotoAlbum.seconds(context)) { delay(1_000); waited++ }
            }
            // Si ninguna abrió (la USB se quitó), no dar vueltas sin parar.
            if (shown == null) delay(5_000)
        }
    }
    Box(modifier) {
        shown?.let { Image(it, null, Modifier.fillMaxSize().alpha(fade.value), contentScale = scale) }
    }
}

/** El widget: las fotos pasando; un toque las abre a pantalla completa. */
@Composable
fun PhotosTile(onOpen: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { PhotoAlbum.refreshIfOld(context) }
    val version = PhotoAlbum.version
    val files = remember(version) { PhotoAlbum.files(context) }
    Card(Modifier.fillMaxSize(), onClick = onOpen) {
        if (files.isEmpty()) Column(Modifier.fillMaxSize().padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.image, c.accent, 40.dp)
            Spacer(Modifier.height(8.dp))
            Label(
                if (PhotoAlbum.syncing || PhotoAlbum.status.isNotEmpty()) PhotoAlbum.status else stringResource(R.string.photos_empty),
                Typography.small.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 4,
            )
        } else Slideshow(files, 900, ContentScale.Crop, Modifier.fillMaxSize().clip(RoundedCornerShape(Radius.card)))
    }
}

/** Las fotos a pantalla completa, como protector: un toque cierra; el botón de la esquina abre el ajuste del álbum. */
@Composable
fun PhotosScreen(close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val version = PhotoAlbum.version
    val files = remember(version) { PhotoAlbum.files(context) }
    var setup by remember { mutableStateOf(files.isEmpty()) }
    var draft by remember { mutableStateOf(PhotoAlbum.link(context)) }
    if (setup) {
        PanelScaffold(stringResource(R.string.tile_photos), close) {
            Label(stringResource(R.string.photos_help), Typography.body, c.textMuted, 8)
            Spacer(Modifier.height(14.dp))
            TextInput(draft, { draft = it.trim().take(300) }, stringResource(R.string.photos_link_hint), Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(stringResource(if (PhotoAlbum.syncing) R.string.photos_syncing else R.string.photos_save), {
                    PhotoAlbum.setLink(context, draft)
                    PhotoAlbum.sync(context)
                }, primary = true)
                Button(stringResource(R.string.photos_link_file), {
                    runCatching { PhotoAlbum.linkFromFile() }.getOrNull()?.let { draft = it }
                })
                if (files.isNotEmpty()) Button(stringResource(R.string.photos_view, files.size), { setup = false })
            }
            if (PhotoAlbum.status.isNotEmpty()) Label(PhotoAlbum.status, Typography.body, c.accent, 4, Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            Label(stringResource(R.string.photos_time), Typography.subtitle, c.text)
            Spacer(Modifier.height(10.dp))
            val chosen = PhotoAlbum.seconds(context)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (option in PhotoAlbum.CHOICES) {
                    val text = if (option < 60) stringResource(R.string.photos_seconds, option) else stringResource(R.string.photos_minutes, option / 60)
                    Button(text, { PhotoAlbum.setSeconds(context, option) }, primary = option == chosen)
                }
            }
        }
        return
    }
    Viewer(files, close) { setup = true }
}

/**
 * Las fotos en grande: pasan solas, y con botones para ir a la anterior o la siguiente, pausar, o ver la galería en
 * pequeño y elegir una. Los botones se esconden solos; un toque en la foto los vuelve a mostrar.
 */
@Composable
private fun Viewer(files: List<File>, close: () -> Unit, openSetup: () -> Unit) {
    val context = LocalContext.current
    val order = remember(files.size) { files.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var gallery by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    fun go(step: Int) { if (order.isNotEmpty()) index = ((index + step) % order.size + order.size) % order.size; touched++ }

    // Abre la foto que toca, con un fundido.
    LaunchedEffect(index, order) {
        val file = order.getOrNull(index) ?: return@LaunchedEffect
        val next = withContext(Dispatchers.IO) { PhotoAlbum.decode(file, 1920) }
        if (shown != null) fade.animateTo(0f, tween(300))
        shown = next
        fade.animateTo(1f, tween(500))
    }
    // Pasa sola a la siguiente (se reinicia al tocar un botón).
    LaunchedEffect(index, paused, gallery, touched) {
        if (paused || gallery || order.size < 2) return@LaunchedEffect
        var waited = 0
        while (waited < PhotoAlbum.seconds(context)) { delay(1_000); waited++ }
        index = (index + 1) % order.size
    }
    // Los botones se esconden a los 6 s sin tocar nada.
    LaunchedEffect(controls, touched) { if (controls && !gallery) { delay(6_000); controls = false } }

    if (gallery) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                androidx.compose.foundation.lazy.grid.GridCells.Adaptive(220.dp),
                Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(order.size) { i ->
                    Thumb(order[i], i == index) { index = i; gallery = false; controls = true; touched++ }
                }
            }
            Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(Icons.close, { gallery = false; controls = true; touched++ }, background = Color(0x99000000), color = Color.White)
                Label(stringResource(R.string.photos_count, order.size), Typography.subtitle, Color.White, 1, Modifier.align(Alignment.CenterVertically))
            }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Color.Black).clickable(remember { MutableInteractionSource() }, indication = null) { controls = !controls; touched++ }) {
        shown?.let { Image(it, null, Modifier.fillMaxSize().alpha(fade.value), contentScale = ContentScale.Fit) }
        if (controls) {
            val dim = Color(0x99000000)
            Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(Icons.close, close, size = 72.dp, background = dim, color = Color.White)
            }
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(Icons.apps, { gallery = true }, size = 72.dp, background = dim, color = Color.White)
                IconButton(Icons.settings, openSetup, size = 72.dp, background = dim, color = Color.White)
            }
            IconButton(Icons.previous, { go(-1) }, Modifier.align(Alignment.CenterStart).padding(16.dp), size = 96.dp, background = dim, color = Color.White)
            IconButton(Icons.next, { go(1) }, Modifier.align(Alignment.CenterEnd).padding(16.dp), size = 96.dp, background = dim, color = Color.White)
            Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).clip(RoundedCornerShape(40.dp)).background(dim).padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(if (paused) Icons.play else Icons.pause, { paused = !paused; touched++ }, size = 64.dp, color = Color.White)
                if (order.isNotEmpty()) Label("${index + 1} / ${order.size}", Typography.subtitle, Color.White, 1)
            }
        }
    }
}

/** Una foto pequeña de la galería (se abre en otro hilo para no trabar la lista). */
@Composable
private fun Thumb(file: File, current: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    var picture by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) { picture = withContext(Dispatchers.IO) { PhotoAlbum.decode(file, 320) } }
    Box(
        Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(if (current) c.accent else Color(0xFF222222)).clickable(onClick = onClick).padding(if (current) 4.dp else 0.dp),
    ) {
        picture?.let { Image(it, null, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop) }
    }
}
