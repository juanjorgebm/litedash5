package com.irvingguerra.litedash.feature.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Logos de las emisoras ya bajados (reducidos), para no pedirlos otra vez al volver a pintar la lista. */
internal object Logos {
    private val cache = LruCache<String, Bitmap>(80)
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Llamarla fuera del hilo principal; null si no hay logo o no se pudo leer. */
    fun load(context: Context, url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        val bytes = Radio.fetchBytes(context, url, maxBytes = 512 * 1024)
        val bitmap = bytes?.let { decode(it) }
        if (bitmap == null) failed += url else cache.put(url, bitmap)
        return bitmap
    }

    private fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

@Composable
internal fun rememberLogo(url: String?): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(null, url) {
        value = if (url == null) null else withContext(Dispatchers.IO) { Logos.load(context, url) }
    }
}

/** El logo de una emisora en un cuadro redondeado; sin logo, el ícono de radio. */
@Composable
internal fun LogoImage(url: String?, size: Dp, fallback: ImageVector, modifier: Modifier = Modifier, radius: Dp = 10.dp) {
    val c = LocalColors.current
    val bitmap by rememberLogo(url)
    Box(modifier.size(size).clip(RoundedCornerShape(radius)).background(c.surfaceHigh), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().padding(size / 16), contentScale = ContentScale.Fit)
        else Icon(fallback, c.textMuted, size * 0.45f)
    }
}

/** El color de fondo que sale del logo: el promedio de lo que no es transparente, oscurecido para que el texto se lea. */
private fun tintOf(b: Bitmap): Color? {
    val small = Bitmap.createScaledBitmap(b, 16, 16, true)
    var r = 0L; var g = 0L; var bl = 0L; var n = 0
    for (x in 0 until 16) for (y in 0 until 16) {
        val p = small.getPixel(x, y)
        if (android.graphics.Color.alpha(p) > 128) {
            r += android.graphics.Color.red(p); g += android.graphics.Color.green(p); bl += android.graphics.Color.blue(p); n++
        }
    }
    if (n == 0) return null
    var color = Color(r / n / 255f, g / n / 255f, bl / n / 255f)
    while (color.luminance() > 0.2f) color = lerp(color, Color.Black, 0.15f)
    return color
}

internal fun playStation(context: Context, list: List<Station>, station: Station) {
    PlayerStore(context).rememberStation(station)
    Player.play(PlayerSource.RADIO, list, list.indexOf(station))
    Radio.countClick(context, station)
}

private enum class Page(val title: Int, val tab: RadioTab?) {
    NOW(R.string.radio_now_tab, null),
    FAVORITES(R.string.radio_favorites, RadioTab.FAVORITES),
    NEARBY(R.string.radio_nearby, RadioTab.NEARBY),
    EXPLORE(R.string.radio_explore, RadioTab.EXPLORE),
    SEARCH(R.string.radio_search_tab, RadioTab.SEARCH),
}

/**
 * La radio por internet a pantalla completa, al estilo de un reproductor de música: fondo con degradado del color del
 * logo, "En reproducción" con la emisora, lo que suena y sugeridas, y las listas (favoritas, cerca, explorar, buscar).
 */
@Composable
fun RadioScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val memory = RadioBrowserMemory
    val station = Player.state.current as? Station
    var showNow by remember { mutableStateOf(station != null) }
    // Cada vez que se abre, las listas empiezan en favoritas.
    LaunchedEffect(Unit) { memory.tab = RadioTab.FAVORITES }
    val page = if (showNow) Page.NOW else Page.entries.first { it.tab == memory.tab }
    val logo by rememberLogo(station?.logoUrl)
    val tint = remember(logo, c.accent) { logo?.let(::tintOf) ?: lerp(c.accent, Color.Black, 0.7f) }
    Column(
        modifier
            .background(Brush.verticalGradient(listOf(tint, c.background, c.background)))
            // Atrapa los toques para que no lleguen al inicio de abajo.
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                Page.entries.map { it to stringResource(it.title) },
                page,
                { p -> if (p.tab == null) showNow = true else { memory.tab = p.tab; showNow = false } },
            )
            Spacer(Modifier.weight(1f))
            IconButton(Icons.close, onClose, background = c.surfaceHigh)
        }
        if (page == Page.NOW) NowPage(station, Modifier.weight(1f).fillMaxWidth())
        else RadioBrowser({ showNow = true }, Modifier.weight(1f).fillMaxWidth(), showTabs = false, resetTab = false)
    }
}

@Composable
private fun NowPage(station: Station?, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    if (station == null) {
        Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.radio, c.textMuted, 64.dp)
            Spacer(Modifier.height(16.dp))
            Label(stringResource(R.string.radio_nothing), Typography.subtitle.copy(textAlign = TextAlign.Center), c.textMuted, 3)
        }
        return
    }
    val state = Player.state
    // Lo que dice el flujo (canción, género): se vuelve a pedir cada 30 s mientras se ve.
    val info by produceState<Radio.NowInfo?>(null, station.id) {
        while (true) {
            withContext(Dispatchers.IO) { Radio.now(context, station) }?.let { value = it }
            delay(30_000)
        }
    }
    val song = info?.song?.takeIf { !it.equals(station.name, ignoreCase = true) }
    val favorite = RadioFavorites.isFavorite(context, station.id)
    BoxWithConstraints(modifier) {
        val side = (maxHeight - 140.dp).coerceIn(140.dp, 330.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val shape = RoundedCornerShape(24.dp)
                Box(Modifier.shadow(24.dp, shape)) { LogoImage(station.logoUrl, side, Icons.radio, radius = 24.dp) }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    val format = listOf(station.codec.uppercase(), (info?.bitrate ?: station.bitrateKbps).takeIf { it > 0 }?.let { "$it kbps" }).filter { !it.isNullOrBlank() }.joinToString(" · ")
                    Label(listOf(stringResource(R.string.radio_live), format).filter { it.isNotBlank() }.joinToString("  ·  "), Typography.label, c.accent, 1)
                    Spacer(Modifier.height(6.dp))
                    Label(station.name, Typography.title.copy(fontSize = 38.sp), maxLines = 2)
                    Spacer(Modifier.height(6.dp))
                    when {
                        state.error != null -> Label(state.error, Typography.subtitle, c.danger, 2)
                        state.buffering -> Label(stringResource(R.string.radio_connecting), Typography.subtitle, c.textMuted, 1)
                        else -> Label(song ?: stringResource(R.string.radio_no_song), Typography.subtitle.copy(fontSize = 24.sp), if (song != null) c.text else c.textMuted, 2)
                    }
                    Spacer(Modifier.height(6.dp))
                    val genre = info?.genre ?: station.tags
                    val place = station.state.ifBlank { station.country }
                    Label(listOf(genre, place, station.clicks.takeIf { it > 0 }?.let { context.getString(R.string.radio_clicks, it) }).filter { !it.isNullOrBlank() }.joinToString(" · "), Typography.body, c.textMuted, 1)
                    info?.description?.takeIf { !it.equals(station.name, true) }?.let {
                        Spacer(Modifier.height(2.dp))
                        Label(it, Typography.small, c.textMuted, 2)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        IconButton(Icons.previous, { Player.previous() }, size = 68.dp, background = c.surfaceHigh)
                        IconButton(if (state.playing || state.buffering) Icons.pause else Icons.play, { Player.toggle() }, size = 92.dp, background = c.primary, color = c.onPrimary)
                        IconButton(Icons.next, { Player.next() }, size = 68.dp, background = c.surfaceHigh)
                        Spacer(Modifier.width(8.dp))
                        IconButton(if (favorite) Icons.starFilled else Icons.star, { RadioFavorites.toggle(context, station) }, size = 68.dp, color = if (favorite) c.accent else c.textMuted)
                    }
                }
            }
            Suggested(station, Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
}

/** Fila de emisoras parecidas (mismo género y país) y favoritas, cada una con lo que suena ahora. */
@Composable
private fun Suggested(station: Station, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val similar by produceState<List<Station>?>(null, station.id) {
        value = withContext(Dispatchers.IO) { Radio.similar(context, station) }
    }
    val favorites = RadioFavorites.list(context)
    // Las favoritas se leen de nuevo en cada repintado (lista nueva cada vez): se compara por ids, no por lista, o se reiniciaría la comprobación sin parar.
    val favoriteIds = favorites.joinToString(",") { it.id }
    val candidates = remember(similar, favoriteIds, station.id) {
        (similar.orEmpty().take(18) + favorites).distinctBy { it.id }.filter { it.id != station.id }.take(24)
    }
    val (working, _) = rememberWorking(candidates)
    val list = working.orEmpty().take(12)
    if (list.isEmpty()) return
    Column(modifier) {
        Label(stringResource(R.string.radio_suggested), Typography.label, c.textMuted, 1, Modifier.padding(start = 4.dp, bottom = 8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.id }) { s -> SuggestedCard(s) { playStation(context, list, s) } }
        }
    }
}

@Composable
private fun SuggestedCard(station: Station, onClick: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val song by produceState<String?>(null, station.id) {
        value = withContext(Dispatchers.IO) { Radio.now(context, station)?.song }?.takeIf { !it.equals(station.name, true) }
    }
    Row(
        Modifier.width(300.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.3f)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoImage(station.logoUrl, 64.dp, Icons.radio)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(station.name, Typography.body, c.text, 1)
            Label(song ?: station.subtitle, Typography.small, c.textMuted, 1)
        }
    }
}
