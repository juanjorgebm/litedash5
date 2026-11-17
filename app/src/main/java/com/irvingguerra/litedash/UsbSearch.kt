package com.irvingguerra.litedash

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.player.Library
import com.irvingguerra.litedash.feature.player.Player
import com.irvingguerra.litedash.feature.player.PlayerSource
import com.irvingguerra.litedash.feature.player.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** Cómo se agrupa la música de la USB en el buscador. */
private enum class Group(val label: Int) { SONGS(R.string.usb_search_songs), ARTISTS(R.string.usb_search_artists), GENRES(R.string.usb_search_genres), FOLDERS(R.string.usb_search_folders) }

/** Una canción con su estilo (de la USB o el que dio iTunes). */
private class Item(val track: Track, val genre: String)

/**
 * Buscar en la USB a pantalla grande: escribir parte del título, artista, álbum o carpeta, o ver la música por
 * cantante, por estilo o por carpeta. Tocar una canción la pone a sonar con las demás de esa lista detrás.
 */
@Composable
fun UsbSearch(close: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val context = LocalContext.current
    val unknownGenre = stringResource(R.string.usb_search_no_genre)
    val unknownArtist = stringResource(R.string.usb_search_no_artist)
    val root = stringResource(R.string.usb_search_root)
    val scan = UsbTags.progress
    // Al terminar de revisar la USB, la lista se vuelve a leer con los datos nuevos.
    val items by produceState<List<Item>?>(null, scan?.running) {
        value = withContext(Dispatchers.IO) { if (Library.hasPermission(context)) load(context, unknownGenre) else emptyList() }
    }
    var query by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(Group.SONGS) }
    // El cantante, estilo o carpeta abierto dentro de su grupo.
    var open by remember { mutableStateOf<String?>(null) }

    fun nameOf(i: Item, g: Group) = when (g) {
        Group.ARTISTS -> i.track.artist.ifBlank { unknownArtist }
        Group.GENRES -> i.genre
        Group.FOLDERS -> i.track.folder.ifBlank { root }
        Group.SONGS -> ""
    }

    Column(modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextInput(query, { query = it }, stringResource(R.string.usb_search_hint), Modifier.weight(1f), icon = true)
            IconButton(Icons.close, close, size = 56.dp)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (g in Group.values()) Chip(stringResource(g.label), g == group) { group = g; open = null }
        }
        Spacer(Modifier.height(10.dp))
        // Bajar los datos reales de todas las canciones (y escribirlos en los MP3), con su barra de avance.
        if (scan?.running == true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Label(stringResource(R.string.usb_scan_running, scan.done, scan.total, scan.found), Typography.small, c.text, 1)
                    Spacer(Modifier.height(6.dp))
                    val fraction = if (scan.total > 0) scan.done.toFloat() / scan.total else 0f
                    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.line)) {
                        Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(c.accent))
                    }
                }
                Button(stringResource(R.string.usb_scan_stop), { UsbTags.stopScan() })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(stringResource(R.string.usb_scan_start), { UsbTags.scanAll(context) }, primary = true)
                if (scan != null) Label(
                    stringResource(R.string.usb_scan_done, scan.found, scan.total, scan.written) + if (scan.failed > 0) " " + stringResource(R.string.usb_scan_failed, scan.failed) else "",
                    Typography.small, c.textMuted, 2, Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        val all = items
        when {
            all == null -> Label(stringResource(R.string.usb_search_loading), Typography.subtitle, c.textMuted, 2, Modifier.padding(20.dp))
            all.isEmpty() -> Label(stringResource(R.string.usb_search_empty), Typography.subtitle, c.textMuted, 3, Modifier.padding(20.dp))
            else -> {
                val words = norm(query).split(' ').filter { it.isNotBlank() }
                fun hit(text: String) = words.all { w -> norm(text).contains(w) }
                val list = remember(all, query, group, open) {
                    if (group == Group.SONGS || open != null) {
                        all.filter { (open == null || nameOf(it, group) == open) && hit("${it.track.title} ${it.track.artist} ${it.track.album} ${it.track.folder} ${it.genre}") }
                    } else emptyList()
                }
                if (group != Group.SONGS && open == null) {
                    val groups = remember(all, query, group) {
                        all.groupBy { nameOf(it, group) }.filterKeys { hit(it) }.toList().sortedBy { norm(it.first) }
                    }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(groups, key = { it.first }) { (name, songs) ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).clickable { open = name; query = "" }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (group == Group.FOLDERS) Icons.folder else Icons.music, c.textMuted, 26.dp)
                                Spacer(Modifier.padding(6.dp))
                                Label(name, Typography.subtitle, c.text, 1, Modifier.weight(1f))
                                Label(stringResource(R.string.usb_search_count, songs.size), Typography.small, c.textMuted, 1)
                            }
                        }
                    }
                } else {
                    open?.let { name ->
                        Row(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = null }.padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.back, c.accent, 24.dp)
                            Spacer(Modifier.padding(4.dp))
                            Label(name, Typography.subtitle, c.accent, 1)
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    if (list.isEmpty()) Label(stringResource(R.string.usb_search_none), Typography.subtitle, c.textMuted, 2, Modifier.padding(20.dp))
                    val current = Player.state.current?.key
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list, key = { it.track.key }) { i ->
                            val now = i.track.key == current
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (now) c.surfaceHigh else c.surface)
                                    .clickable {
                                        val queue = list.map { it.track }
                                        Player.play(PlayerSource.USB, queue, queue.indexOf(i.track).coerceAtLeast(0))
                                        close()
                                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (now) Icons.play else Icons.music, if (now) c.accent else c.textMuted, 24.dp)
                                Spacer(Modifier.padding(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Label(i.track.title, Typography.body, if (now) c.accent else c.text, 1)
                                    Label(listOf(i.track.artist, i.track.album, i.genre.takeIf { it != unknownGenre }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { i.track.folder }, Typography.small, c.textMuted, 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected) c.primary else c.surfaceHigh).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 12.dp),
    ) { Label(text, Typography.body, if (selected) c.onPrimary else c.text, 1) }
}

private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

/** Las canciones de la USB con su estilo: el que corrigió LiteDash, si no el que Android leyó del archivo. */
private fun load(context: Context, unknown: String): List<Item> {
    val tracks = Library.find(context).second
    val fixes = UsbTags.all(context)
    val byId = HashMap<Long, String>()
    runCatching {
        context.contentResolver.query(MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Genres._ID, MediaStore.Audio.Genres.NAME), null, null, null)?.use { g ->
            while (g.moveToNext()) {
                val name = g.getString(1)?.trim()?.takeIf { it.isNotBlank() } ?: continue
                runCatching {
                    context.contentResolver.query(MediaStore.Audio.Genres.Members.getContentUri("external", g.getLong(0)), arrayOf(MediaStore.Audio.Genres.Members.AUDIO_ID), null, null, null)?.use { m ->
                        while (m.moveToNext()) byId[m.getLong(0)] = name
                    }
                }
            }
        }
    }
    return tracks.map { t ->
        val id = if (t.uri.scheme == "content" && t.uri.authority == MediaStore.AUTHORITY) runCatching { ContentUris.parseId(t.uri) }.getOrDefault(-1L) else -1L
        Item(t, fixes[t.key]?.genre?.takeIf { it.isNotBlank() } ?: byId[id] ?: unknown)
    }
}
