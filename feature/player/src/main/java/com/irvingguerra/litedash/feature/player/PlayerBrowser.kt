package com.irvingguerra.litedash.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.music.Drive
import com.irvingguerra.litedash.feature.music.Drives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * La biblioteca del reproductor propio, dentro de la música en grande: la memoria USB
 * (carpetas, artistas, álbumes) o la radio por internet (cerca de ti, favoritas, buscar).
 * Al tocar algo, suena y se avisa con [onPicked] (la vista regresa a "lo que suena").
 */
@Composable
fun PlayerBrowser(source: PlayerSource, onPicked: () -> Unit, modifier: Modifier = Modifier) {
    when (source) {
        PlayerSource.USB -> LibraryBrowser(onPicked, modifier)
        PlayerSource.RADIO -> RadioBrowser(onPicked, modifier)
    }
}

// --- Memoria USB ---

private enum class Group(val title: Int) { FOLDERS(R.string.library_folders), ARTISTS(R.string.library_artists), ALBUMS(R.string.library_albums), ALL(R.string.library_all) }

/** Dónde se quedó la biblioteca USB: sobrevive a cerrarla y abrirla. */
private object LibraryBrowserMemory {
    var group by mutableStateOf(Group.FOLDERS)
        private set

    // Dentro de un grupo (una carpeta, un artista…): sus canciones; null = la lista de grupos.
    var inside by mutableStateOf<String?>(null)

    /** Ya se pidieron los permisos solos en este arranque. */
    var asked = false

    /** Cambiar de grupo regresa a su lista. */
    fun pick(g: Group) {
        if (g == group) return
        group = g
        inside = null
    }
}

@Composable
private fun LibraryBrowser(onPicked: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    var permission by remember { mutableStateOf(Library.hasPermission(context)) }
    // Leer la música y, desde Android 13, mostrar el aviso del reproductor.
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permission = it[Library.permission] == true }
    val askAll = { ask.launch((listOf(Library.permission) + Library.noticePermission).toTypedArray()) }
    // La primera vez que se usa USB se piden solos (una vez por arranque; después, con el botón).
    LaunchedEffect(Unit) {
        if (!permission && !LibraryBrowserMemory.asked) {
            LibraryBrowserMemory.asked = true
            askAll()
        }
    }
    // Al conectar o sacar una memoria (o al terminar de indexarla) se vuelve a leer.
    var mounts by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Drives.changed()
                mounts++
            }
        }
        ContextCompat.registerReceiver(context, receiver, Drives.mediaFilter(scanner = true), ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    // Elegir la carpeta de la música con el selector de Android: sirve aunque la pantalla no deje
    // leer la memoria directo (pasaba en una AC8227L con Android 13: "no tiene música").
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            Library.setFolder(context, uri)
            mounts++
        }
    }
    val folderButton: @Composable () -> Unit = {
        Button(stringResource(R.string.library_pick_folder), { runCatching { pickFolder.launch(null) }.onFailure { android.widget.Toast.makeText(context, R.string.library_no_picker, android.widget.Toast.LENGTH_LONG).show() } })
    }
    if (!permission) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(stringResource(R.string.library_no_permission), Typography.body, c.textMuted)
            Button(stringResource(R.string.library_grant), askAll, primary = true)
        }
        return
    }
    // null = leyendo; la memoria (o carpeta) con música y sus canciones.
    val loaded by produceState<Pair<Drive?, List<Track>>?>(null, mounts) {
        value = null
        value = withContext(Dispatchers.IO) { Library.find(context) }
    }
    val (drive, tracks) = loaded ?: return Label(stringResource(R.string.library_loading), Typography.body, c.textMuted, modifier = modifier)
    if (drive == null || tracks.isEmpty()) {
        return Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (drive == null) Icon(Icons.usb, c.textMuted, 40.dp)
            // Memoria vista pero sin canciones leídas: casi siempre es la pantalla que no deja leerla.
            Label(if (drive == null) stringResource(R.string.library_no_drive) else stringResource(R.string.library_unreadable, drive.name), Typography.body, c.textMuted)
            if (drive == null) Label(stringResource(R.string.library_pick_folder_help), Typography.small, c.textMuted)
            folderButton()
        }
    }

    val memory = LibraryBrowserMemory
    val group = memory.group
    val groups = remember(tracks, group) {
        when (group) {
            Group.FOLDERS -> tracks.groupBy { it.folder }
            Group.ARTISTS -> tracks.groupBy { it.artist }
            Group.ALBUMS -> tracks.groupBy { it.album }
            Group.ALL -> mapOf("" to tracks)
        }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val playing = Player.state.current?.key
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(Group.entries.map { it to stringResource(it.title) }, group, { memory.pick(it) })
            Spacer(Modifier.weight(1f))
            Label(drive.name, Typography.small, c.textMuted, 1)
            folderButton()
        }
        Spacer(Modifier.height(10.dp))
        // Si la memoria cambió y el grupo recordado ya no está, a la lista.
        val shown = memory.inside?.takeIf { it in groups }
        if (group == Group.ALL || shown != null) {
            val list = if (group == Group.ALL) tracks else groups[shown].orEmpty()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (shown != null) IconButton(Icons.back, { memory.inside = null }, size = 44.dp, background = c.surfaceHigh)
                        Label(groupName(group, shown ?: ""), Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
                        Button(stringResource(R.string.library_play_all), { Player.play(PlayerSource.USB, list, 0); onPicked() })
                        Button(stringResource(R.string.library_shuffle), { Player.play(PlayerSource.USB, list.shuffled(), 0); onPicked() })
                    }
                }
                items(list, key = { it.key }) { t ->
                    BrowserRow(
                        Icons.music, t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "),
                        selected = t.key == playing,
                    ) { Player.play(PlayerSource.USB, list, list.indexOf(t)); onPicked() }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(groups.keys.toList(), key = { it }) { name ->
                    val icon = if (group == Group.FOLDERS) Icons.folder else Icons.music
                    BrowserRow(icon, groupName(group, name), (groups[name]?.size ?: 0).let { n -> pluralStringResource(R.plurals.library_songs, n, n) }, selected = false) { memory.inside = name }
                }
            }
        }
    }
}

@Composable
private fun groupName(group: Group, name: String): String = when {
    group == Group.ALL -> stringResource(R.string.library_all)
    name.isNotBlank() -> name
    group == Group.FOLDERS -> stringResource(R.string.library_root)
    else -> stringResource(R.string.library_unknown)
}

// --- Radio por internet ---

// Favoritas primero: es la lista principal y con la que se abre siempre.
internal enum class RadioTab(val title: Int) { FAVORITES(R.string.radio_favorites), NEARBY(R.string.radio_nearby), EXPLORE(R.string.radio_explore), SEARCH(R.string.radio_search_tab) }

/** Pestaña y búsqueda de la radio: al cerrar y abrir la biblioteca sigue donde estaba. */
internal object RadioBrowserMemory {
    var tab by mutableStateOf(RadioTab.FAVORITES)
    var query by mutableStateOf("")
    /** Explorar: false = países, true = géneros; y lo que se abrió (null = la lista). */
    var genres by mutableStateOf(false)
    var opened by mutableStateOf<Radio.Category?>(null)
}

@Composable
internal fun RadioBrowser(onPicked: () -> Unit, modifier: Modifier, showTabs: Boolean = true, resetTab: Boolean = true) {
    val c = LocalColors.current
    val context = LocalContext.current
    val store = remember { PlayerStore(context) }
    val memory = RadioBrowserMemory
    val tab = memory.tab
    val query = memory.query
    // Cada vez que se abre la radio se empieza en favoritas.
    LaunchedEffect(Unit) { if (resetTab) memory.tab = RadioTab.FAVORITES }
    val favorites = RadioFavorites.list(context)
    val here = remember { Radio.here(context) }
    // null = buscando; la lista (y si salió por ubicación o por país).
    // Si los servidores no contestan, la última lista que sí llegó (guardada).
    val nearby by produceState<List<Station>?>(null) {
        value = withContext(Dispatchers.IO) {
            val fresh = if (here != null) Radio.nearby(context, here) else Radio.country(context)
            if (fresh.isNotEmpty()) { store.setNearbyCache(fresh); fresh } else store.nearbyCache()
        }
    }
    // La primera vez, las favoritas vienen llenas con las más escuchadas de Costa Rica.
    LaunchedEffect(Unit) {
        if (store.favoritesSeeded) return@LaunchedEffect
        if (favorites.isNotEmpty()) { store.favoritesSeeded = true; return@LaunchedEffect }
        val top = withContext(Dispatchers.IO) { Radio.country(context) }.take(SEED_FAVORITES)
        if (top.isEmpty()) return@LaunchedEffect // sin red: se intenta la próxima vez
        store.setFavorites(top)
        RadioFavorites.refresh()
        store.favoritesSeeded = true
    }
    val found by produceState<List<Station>?>(null, query) {
        value = null
        if (query.length < 2) return@produceState
        value = withContext(Dispatchers.IO) { Radio.byName(context, query.trim()) }
    }
    // Buscar sin escribir nada: las más populares ahora (país y mundo), por clics de las últimas 24 h.
    val top by produceState<List<Station>?>(null, tab, query.length < 2) {
        value = null
        if (tab != RadioTab.SEARCH || query.length >= 2) return@produceState
        value = withContext(Dispatchers.IO) {
            val a = Radio.country(context).take(25)
            val b = Radio.world(context).take(25)
            (a + b).distinctBy { it.id }.sortedByDescending { it.clicks }
        }
    }
    val (workingTop, checkingTop) = rememberWorking(top)
    // Explorar: la lista de países o de géneros y, ya abierto uno, sus estaciones.
    val categories by produceState<List<Radio.Category>?>(null, tab, memory.genres) {
        value = null
        if (tab != RadioTab.EXPLORE) return@produceState
        value = withContext(Dispatchers.IO) { if (memory.genres) Radio.tags(context) else Radio.countries(context) }
    }
    val opened = memory.opened
    val exploring by produceState<List<Station>?>(null, opened) {
        value = null
        if (opened == null) return@produceState
        value = withContext(Dispatchers.IO) { if (opened.genre) Radio.byTag(context, opened.code) else Radio.byCountry(context, opened.code) }
    }
    // Solo las que suenan ahora: se prueban de 10 en 10 y van saliendo en la lista.
    val (workingNearby, checkingNearby) = rememberWorking(nearby)
    val (workingFound, checkingFound) = rememberWorking(found)
    val (workingExploring, checkingExploring) = rememberWorking(exploring)
    val playing = Player.state.current?.key
    fun toggleFavorite(s: Station) {
        val added = RadioFavorites.toggle(context, s)
        Toast.makeText(context, context.getString(if (added) R.string.radio_fav_added else R.string.radio_fav_removed, s.name), Toast.LENGTH_SHORT).show()
    }
    fun play(list: List<Station>, s: Station) {
        store.rememberStation(s)
        Player.play(PlayerSource.RADIO, list, list.indexOf(s))
        Radio.countClick(context, s)
        onPicked()
    }
    Column(modifier) {
        if (showTabs) {
            Segmented(RadioTab.entries.map { it to stringResource(it.title) }, tab, { memory.tab = it })
            Spacer(Modifier.height(10.dp))
        }
        val (list, empty) = when (tab) {
            RadioTab.NEARBY -> workingNearby to stringResource(if (nearby == null || checkingNearby) R.string.radio_checking else R.string.radio_none)
            RadioTab.FAVORITES -> favorites to stringResource(R.string.radio_no_favorites)
            RadioTab.EXPLORE -> workingExploring to stringResource(if (exploring == null) R.string.radio_loading else if (checkingExploring) R.string.radio_checking else R.string.radio_none)
            RadioTab.SEARCH -> (if (query.length < 2) workingTop else workingFound) to stringResource(if (query.length < 2) R.string.radio_checking else if (found == null) R.string.radio_loading else if (checkingFound) R.string.radio_checking else R.string.radio_none)
        }
        val checking = when (tab) { RadioTab.NEARBY -> checkingNearby; RadioTab.EXPLORE -> checkingExploring; RadioTab.SEARCH -> if (query.length < 2) checkingTop else checkingFound; else -> false }
        if (tab == RadioTab.EXPLORE) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (opened == null) {
                    Segmented(listOf(false to stringResource(R.string.radio_countries), true to stringResource(R.string.radio_genres)), memory.genres, { memory.genres = it })
                } else {
                    Button(stringResource(R.string.radio_back), { memory.opened = null })
                    Label(opened.name, Typography.subtitle, maxLines = 1)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (opened == null) {
                val cats = categories ?: return Label(stringResource(R.string.radio_loading), Typography.body, c.textMuted)
                if (cats.isEmpty()) return Label(stringResource(R.string.radio_none), Typography.body, c.textMuted)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(cats, key = { it.code }) { g ->
                        BrowserRow(Icons.radio, g.name, stringResource(R.string.radio_count, g.count), selected = false) { memory.opened = g }
                    }
                }
                return
            }
        }
        if (tab == RadioTab.SEARCH) {
            TextInput(query, { memory.query = it }, stringResource(R.string.radio_search_hint), Modifier.fillMaxWidth(), icon = true)
            Spacer(Modifier.height(8.dp))
        }
        if (tab == RadioTab.NEARBY && here == null && !nearby.isNullOrEmpty()) {
            Label(stringResource(R.string.radio_no_location), Typography.small, c.textMuted)
            Spacer(Modifier.height(6.dp))
        }
        if (tab == RadioTab.SEARCH && query.length < 2 && !list.isNullOrEmpty()) {
            Label(stringResource(R.string.radio_popular), Typography.subtitle, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        }
        if (list.isNullOrEmpty()) return Label(empty, Typography.body, c.textMuted)
        if (checking) {
            Label(stringResource(R.string.radio_checking_n, list.size), Typography.small, c.textMuted)
            Spacer(Modifier.height(6.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s ->
                val km = if (here != null) s.distanceKm(here.latitude, here.longitude) else null
                val detail = listOfNotNull(s.subtitle, km?.let { stringResource(R.string.radio_km, it.toInt()) }, s.clicks.takeIf { it > 0 }?.let { stringResource(R.string.radio_clicks, it) }).joinToString(" · ")
                BrowserRow(Icons.radio, s.name, detail, selected = s.key == playing, logo = s.logoUrl, trailing = {
                    val fav = favorites.any { it.id == s.id }
                    IconButton(if (fav) Icons.starFilled else Icons.star, { toggleFavorite(s) }, size = 44.dp, color = if (fav) c.accent else c.textMuted)
                }, onLongClick = { toggleFavorite(s) }) { play(list, s) }
            }
        }
    }
}

/**
 * De [source] (lo que dio el directorio) deja solo las estaciones que suenan ahora: las prueba de 10 en 10, en orden de
 * popularidad, y la lista va creciendo; para al juntar [WORKING_MAX]. El segundo valor es si sigue comprobando.
 */
@Composable
internal fun rememberWorking(source: List<Station>?): Pair<List<Station>?, Boolean> {
    val context = LocalContext.current
    val state by produceState<Pair<List<Station>?, Boolean>>(null to false, source) {
        if (source == null) { value = null to false; return@produceState }
        val ok = ArrayList<Station>()
        value = emptyList<Station>() to true
        withContext(Dispatchers.IO) {
            for (batch in source.take(WORKING_SCAN).chunked(10)) {
                if (ok.size >= WORKING_MAX) break
                val checked = coroutineScope { batch.map { st -> async { st to Radio.isAlive(context, st) } }.awaitAll() }
                ok += checked.filter { it.second }.map { it.first }
                value = ok.toList() to true
            }
        }
        value = ok.toList() to false
    }
    return state
}

private const val WORKING_SCAN = 100
private const val WORKING_MAX = 40

/** Un renglón de la lista: ícono, título, detalle y, si lo hay, algo a la derecha (la estrella). */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun BrowserRow(icon: ImageVector, title: String, detail: String, selected: Boolean, logo: String? = null, trailing: (@Composable () -> Unit)? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radius.inner))
            .background(if (selected) c.surfaceHigh else c.surface)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (logo != null) {
            LogoImage(logo, 48.dp, icon)
        } else {
            Icon(icon, if (selected) c.accent else c.textMuted, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, Typography.body, if (selected) c.accent else c.text, 1)
            if (detail.isNotBlank()) Label(detail, Typography.small, c.textMuted, 1)
        }
        if (trailing != null) Box { trailing() }
    }
}
