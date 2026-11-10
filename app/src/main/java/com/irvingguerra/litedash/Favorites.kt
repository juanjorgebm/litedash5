package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.IconButton
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


/** Cuántos favoritos caben en la pieza (dos columnas por dos filas). */
private const val MAX_FAVORITES = 6

private fun hasContacts(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Un contacto del celular conectado. */
private class Contact(val name: String, val number: String, val photo: ImageBitmap?, val starred: Boolean)

/** Lo que se encontró: los contactos del celular y, para depurar, qué se probó y qué contestó cada fuente. */
private class Loaded(val all: List<Contact>, val diag: String) {
    /** Los que marcaste con estrella en LiteDash (o que el celular manda como favoritos). */
    fun favorites(chosen: Set<String>): List<Contact> = all.filter { it.starred || digits(it.number) in chosen }.distinctBy { digits(it.number) }.take(MAX_FAVORITES)
}

private fun digits(number: String) = number.filter { it.isDigit() }

/** En minúsculas y sin tildes, para buscar. */
private fun plain(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

/** Los favoritos que marcas tú (la app del carro no tiene favoritos): los números, guardados en las preferencias. */
private object FavContacts {
    var version by mutableIntStateOf(0)
    private fun prefs(context: Context) = context.getSharedPreferences("favcontacts", Context.MODE_PRIVATE)
    fun get(context: Context): Set<String> = runCatching { prefs(context).getStringSet("numbers", emptySet())!!.toSet() }.getOrDefault(emptySet())
    fun toggle(context: Context, number: String) {
        val key = digits(number)
        val now = get(context).toMutableSet()
        if (!now.remove(key)) now += key
        prefs(context).edit().putStringSet("numbers", now).apply()
        version++
    }
}

/**
 * Los contactos del celular conectado por Bluetooth: la agenda que baja [CarPhonebook] y, si la pantalla los
 * tuviera, los contactos de Android que vienen de una cuenta. Los locales sin cuenta no se usan. Solo en IO.
 */
private fun loadContacts(context: Context): Loaded {
    val log = ArrayList<String>()
    val out = ArrayList<Contact>()
    val resolver = context.contentResolver
    // 1) La agenda que LiteDash bajó del celular con el servicio Bluetooth de la pantalla.
    for (e in CarPhonebook.cached(context)) out += Contact(e.name, e.number, null, false)
    log += "Agenda del celular: ${CarPhonebook.status.ifBlank { "${out.size} contactos guardados" }}"
    // 2) Los contactos de Android que vienen de una cuenta (celular sincronizado).
    runCatching {
        val synced = HashSet<Long>()
        val accounts = LinkedHashSet<String>()
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
            "${ContactsContract.RawContacts.DELETED} = 0", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val type = c.getString(1)
                accounts += type ?: "local"
                if (!type.isNullOrBlank()) synced += c.getLong(0)
            }
        }
        log += "Contactos Android: ${accounts.joinToString().ifBlank { "vacío" }}"
        if (synced.isNotEmpty()) {
            val seen = HashSet<Long>()
            resolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER, Phone.STARRED, Phone.PHOTO_THUMBNAIL_URI),
                null, null, "${Phone.IS_PRIMARY} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val number = c.getString(2).orEmpty()
                    if (id !in synced || number.isBlank() || !seen.add(id)) continue
                    val photo = c.getString(4)?.let { uri ->
                        runCatching { resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
                    }
                    out += Contact(c.getString(1).orEmpty(), number, photo, c.getInt(3) == 1)
                }
            }
        }
    }.onFailure { log += "Contactos Android: ${it.javaClass.simpleName}" }
    return Loaded(out.sortedBy { it.name.lowercase() }, log.joinToString("\n"))
}

/**
 * Llama al número con el manos libres de la pantalla del carro; si la pantalla no lo tiene o no acepta, con el
 * marcador de Android (directo con permiso) y, si tampoco hay, abre el Teléfono de fábrica.
 */
private fun call(context: Context, number: String) {
    CarPhonebook.dial(context, number) { ok ->
        if (ok) return@dial
        val uri = "tel:${Uri.encode(number)}".toUri()
        val direct = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        runCatching {
            context.startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { openDialer(context) }
    }
}

/** Favoritos de la agenda: fotos redondas con su nombre; un toque llama. */
@Composable
fun FavoritesTile(onOpen: () -> Unit = {}) {
    val c = LocalColors.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasContacts(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasContacts(context)
    }
    val loaded by produceState<Loaded?>(null, granted, CarPhonebook.version) {
        value = if (granted) withContext(Dispatchers.IO) { loadContacts(context) } else Loaded(emptyList(), "")
    }
    // Sin agenda guardada, o de hace más de medio día: se baja del celular.
    LaunchedEffect(Unit) { if (CarPhonebook.cached(context).isEmpty() || CarPhonebook.age(context) > 12 * 3600_000L) CarPhonebook.sync(context) }
    Card(Modifier.fillMaxSize().clickable(onClick = onOpen)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.tile_favorites), Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.starFilled, c.accent, 26.dp)
            }
            val chosen = remember(FavContacts.version) { FavContacts.get(context) }
            val list = loaded?.favorites(chosen)
            when {
                !granted -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        stringResource(R.string.favorites_allow),
                        { launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) },
                        primary = true,
                    )
                }
                list.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (list != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val has = loaded?.all?.isNotEmpty() == true
                        Label(stringResource(if (has) R.string.favorites_pick else R.string.favorites_empty), Typography.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 4)
                        // Sin contactos: qué encontró cada fuente, para saber dónde guarda la agenda el celular.
                        (if (CarPhonebook.syncing) CarPhonebook.status else loaded?.diag)?.takeIf { it.isNotBlank() && !has }?.let { Label(it, Typography.small, c.textMuted, 6, Modifier.padding(top = 6.dp)) }
                    }
                }
                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp)) {
                    val rows = ((minOf(list.size, MAX_FAVORITES) + 1) / 2).coerceAtLeast(1)
                    // El círculo cabe en su celda: lo limita el ancho de media tarjeta y el alto de la fila (con su nombre).
                    val avatar = minOf(maxWidth / 2 - 16.dp, maxHeight / rows - 40.dp).coerceIn(44.dp, 112.dp)
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                        for (row in list.chunked(2)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                for (f in row) FavoriteCell(f, avatar)
                                if (row.size == 1) Box(Modifier.size(avatar))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteCell(f: Contact, avatar: androidx.compose.ui.unit.Dp) {
    val c = LocalColors.current
    val context = LocalContext.current
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { call(context, f.number) }.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(avatar).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFFCF86), Color(0xFFC9822A)))),
            contentAlignment = Alignment.Center,
        ) {
            if (f.photo != null) Image(f.photo, f.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Label(f.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", Typography.title.copy(fontSize = (avatar.value * 0.42f).sp, fontWeight = FontWeight.Bold), c.onPrimary)
        }
        Label(f.name.substringBefore(' '), Typography.subtitle, maxLines = 1, modifier = Modifier.padding(top = 6.dp).widthIn(max = avatar + 24.dp))
    }
}

/**
 * Todos los contactos del celular conectado, en grande: un toque llama. Si no sale ninguno, se ve qué
 * encontró cada fuente (la base de la pantalla y los contactos de Android) para saber qué pasa.
 */
@Composable
fun ContactsScreen(close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val loaded by produceState<Loaded?>(null, CarPhonebook.version) { value = withContext(Dispatchers.IO) { loadContacts(context) } }
    PanelScaffold(
        stringResource(R.string.tile_favorites),
        close,
        actions = { Button(stringResource(if (CarPhonebook.syncing) R.string.contacts_syncing else R.string.contacts_sync), { CarPhonebook.sync(context) }) },
    ) {
        if (CarPhonebook.syncing) Label(CarPhonebook.status, Typography.body, c.accent, 2)
        val l = loaded
        when {
            l == null -> Label(stringResource(R.string.places_loading), Typography.body, c.textMuted)
            l.all.isEmpty() -> Column {
                Label(stringResource(R.string.favorites_empty), Typography.subtitle, c.textMuted, 4)
                Label(l.diag, Typography.body, c.textMuted, 12, Modifier.padding(top = 12.dp))
            }
            else -> {
                val chosen = remember(FavContacts.version) { FavContacts.get(context) }
                // Los favoritos primero; sin repetir el mismo número (la pantalla repite a veces el contacto).
                val rows = remember(l, chosen) {
                    l.all.distinctBy { digits(it.name) + "|" + digits(it.number) }
                        .sortedWith(compareByDescending<Contact> { digits(it.number) in chosen || it.starred }.thenBy { it.name.lowercase() })
                }
                var query by remember { mutableStateOf("") }
                TextInput(query, { query = it }, stringResource(R.string.contacts_search), Modifier.fillMaxWidth(), icon = true)
                // Busca por nombre (sin tildes) o por número.
                val shown = remember(rows, query) {
                    val q = plain(query.trim())
                    val qd = digits(query)
                    if (q.isEmpty()) rows else rows.filter { plain(it.name).contains(q) || (qd.isNotEmpty() && digits(it.number).contains(qd)) }
                }
                Label(stringResource(R.string.contacts_hint), Typography.small, c.textMuted, 2, Modifier.padding(top = 6.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
                    items(shown.size) { i ->
                        val f = shown[i]
                        val fav = f.starred || digits(f.number) in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable { call(context, f.number) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Label(f.name, Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
                            Label(f.number, Typography.body, c.textMuted, 1)
                            IconButton(if (fav) Icons.starFilled else Icons.star, { FavContacts.toggle(context, f.number) }, size = 64.dp, color = if (fav) c.accent else c.textMuted)
                        }
                    }
                }
            }
        }
    }
}
