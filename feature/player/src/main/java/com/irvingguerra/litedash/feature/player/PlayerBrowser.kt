package com.irvingguerra.litedash.feature.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
        Button(stringResource(R.string.library_pick_folder), { runCatching { pickFolder.launch(null) } })
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

private enum class RadioTab(val title: Int) { NEARBY(R.string.radio_nearby), FAVORITES(R.string.radio_favorites), SEARCH(R.string.radio_search_tab) }

/** Pestaña y búsqueda de la radio: al cerrar y abrir la biblioteca sigue donde estaba. */
private object RadioBrowserMemory {
    var tab by mutableStateOf(RadioTab.FAVORITES)
    var query by mutableStateOf("")
}

@Composable
private fun RadioBrowser(onPicked: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val store = remember { PlayerStore(context) }
    val memory = RadioBrowserMemory
    val tab = memory.tab
    val query = memory.query
    var favorites by remember { mutableStateOf(store.favorites()) }
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
        favorites = top
        store.setFavorites(top)
        store.favoritesSeeded = true
    }
    val found by produceState<List<Station>?>(null, query) {
        value = null
        if (query.length < 2) return@produceState
        value = withContext(Dispatchers.IO) { Radio.byName(context, query.trim()) }
    }
    val playing = Player.state.current?.key
    fun toggleFavorite(s: Station) {
        favorites = if (favorites.any { it.id == s.id }) favorites.filter { it.id != s.id } else favorites + s
        store.setFavorites(favorites)
    }
    fun play(list: List<Station>, s: Station) {
        store.rememberStation(s)
        Player.play(PlayerSource.RADIO, list, list.indexOf(s))
        Radio.countClick(context, s)
        onPicked()
    }
    Column(modifier) {
        Segmented(RadioTab.entries.map { it to stringResource(it.title) }, tab, { memory.tab = it })
        Spacer(Modifier.height(10.dp))
        val (list, empty) = when (tab) {
            RadioTab.NEARBY -> nearby to stringResource(if (nearby == null) R.string.radio_loading else R.string.radio_none)
            RadioTab.FAVORITES -> favorites to stringResource(R.string.radio_no_favorites)
            RadioTab.SEARCH -> found to stringResource(if (query.length < 2) R.string.radio_search_help else if (found == null) R.string.radio_loading else R.string.radio_none)
        }
        if (tab == RadioTab.SEARCH) {
            TextInput(query, { memory.query = it }, stringResource(R.string.radio_search_hint), Modifier.fillMaxWidth(), icon = true)
            Spacer(Modifier.height(8.dp))
        }
        if (tab == RadioTab.NEARBY && here == null && !nearby.isNullOrEmpty()) {
            Label(stringResource(R.string.radio_no_location), Typography.small, c.textMuted)
            Spacer(Modifier.height(6.dp))
        }
        if (list.isNullOrEmpty()) return Label(empty, Typography.body, c.textMuted)
        LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { s ->
                val km = if (here != null) s.distanceKm(here.latitude, here.longitude) else null
                val detail = listOfNotNull(s.subtitle, km?.let { stringResource(R.string.radio_km, it.toInt()) }).joinToString(" · ")
                BrowserRow(Icons.radio, s.name, detail, selected = s.key == playing, trailing = {
                    val fav = favorites.any { it.id == s.id }
                    IconButton(if (fav) Icons.starFilled else Icons.star, { toggleFavorite(s) }, size = 44.dp, color = if (fav) c.accent else c.textMuted)
                }) { play(list, s) }
            }
        }
    }
}

/** Un renglón de la lista: ícono, título, detalle y, si lo hay, algo a la derecha (la estrella). */
@Composable
private fun BrowserRow(icon: ImageVector, title: String, detail: String, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(Radius.inner))
            .background(if (selected) c.surfaceHigh else c.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, if (selected) c.accent else c.textMuted, 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Label(title, Typography.body, if (selected) c.accent else c.text, 1)
            if (detail.isNotBlank()) Label(detail, Typography.small, c.textMuted, 1)
        }
        if (trailing != null) Box { trailing() }
    }
}
