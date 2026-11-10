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
import androidx.compose.runtime.getValue
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
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
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
    /** Los marcados con estrella; si no hay marca, los primeros de la lista. */
    val favorites: List<Contact> get() = all.filter { it.starred }.ifEmpty { all }.take(MAX_FAVORITES)
}

/**
 * Los contactos del celular conectado por Bluetooth. La pantalla (chip nFore) los baja por PBAP y los guarda en su
 * propia base (`content://nfore.db.provider`, tablas PhoneBookContent y PhoneNumberDetail) o, según la
 * configuración, en los contactos de Android como cuenta. Se prueban las dos y se anota qué contestó cada una.
 * Los contactos locales sin cuenta (los de la pantalla) no se usan. Solo en IO.
 */
private fun loadContacts(context: Context): Loaded {
    val log = ArrayList<String>()
    val out = ArrayList<Contact>()
    val resolver = context.contentResolver
    // 0) El proveedor de la app Teléfono de fábrica (btphonelogic): guarda los contactos del celular conectado.
    runCatching {
        val uri = "content://com.neusoft.geely.btphonelogic.utility.ContactProvider/contact".toUri()
        val c = resolver.query(uri, null, null, null, null)
        if (c == null) log += "Teléfono de fábrica: sin respuesta"
        else c.use {
            val cols = it.columnNames.toList()
            log += "Teléfono de fábrica: ${it.count} filas (${cols.take(10).joinToString()})"
            // Las columnas no se conocen: nombre = la que diga name; número = la que diga num/phone/tel.
            val name = cols.indexOfFirst { n -> n.contains("name", true) }
            val number = cols.indexOfFirst { n -> n.contains("num", true) || n.contains("phone", true) || n.contains("tel", true) }
            val star = cols.indexOfFirst { n -> n.contains("fav", true) || n.contains("star", true) || n.contains("collect", true) }
            while (it.moveToNext() && name >= 0 && number >= 0) {
                val n = it.getString(name).orEmpty()
                val num = it.getString(number).orEmpty()
                if (num.isNotBlank()) out += Contact(n.ifBlank { num }, num, null, star >= 0 && (it.getString(star) ?: "0") !in setOf("", "0", "false"))
            }
        }
    }.onFailure { log += "Teléfono de fábrica: ${it.javaClass.simpleName} ${it.message.orEmpty().take(70)}" }
    // 1) La base de la pantalla.
    val bases = listOf("content://nfore.db.provider/PhoneBookContent", "content://nfore.db.provider/phonebookcontent", "content://nfore.db.provider/PhoneBookContent/")
    if (out.isEmpty()) for (base in bases) {
        val ok = runCatching {
            val names = LinkedHashMap<Long, String>()
            resolver.query(base.toUri(), null, null, null, null)?.use { c ->
                val id = c.getColumnIndex("_id")
                val name = c.getColumnIndex("FullName")
                log += "PhoneBookContent: ${c.count} filas (${c.columnNames.take(8).joinToString()})"
                while (c.moveToNext() && id >= 0 && name >= 0) names[c.getLong(id)] = c.getString(name).orEmpty()
            } ?: error("sin respuesta")
            val numbers = HashMap<Long, String>()
            resolver.query(base.replace("PhoneBookContent", "PhoneNumberDetail", true).toUri(), null, null, null, null)?.use { c ->
                val cid = c.getColumnIndex("Content_ID")
                val num = c.getColumnIndex("Number")
                log += "PhoneNumberDetail: ${c.count} filas"
                while (c.moveToNext() && cid >= 0 && num >= 0) { val k = c.getLong(cid); if (k !in numbers) numbers[k] = c.getString(num).orEmpty() }
            }
            for ((id, name) in names) numbers[id]?.takeIf { it.isNotBlank() }?.let { out += Contact(name, it, null, false) }
            names.isNotEmpty()
        }.onFailure { log += "nFore: ${it.javaClass.simpleName} ${it.message.orEmpty().take(70)}" }.getOrDefault(false)
        if (ok) break
    }
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

/** Llama al número (directo con permiso; si no, el marcador con el número puesto). */
private fun call(context: Context, number: String) {
    val uri = "tel:${Uri.encode(number)}".toUri()
    val direct = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
    runCatching {
        context.startActivity(Intent(if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    val loaded by produceState<Loaded?>(null, granted) {
        value = if (granted) withContext(Dispatchers.IO) { loadContacts(context) } else Loaded(emptyList(), "")
    }
    Card(Modifier.fillMaxSize().clickable(onClick = onOpen)) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.tile_favorites), Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.starFilled, c.accent, 26.dp)
            }
            val list = loaded?.favorites
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
                        Label(stringResource(R.string.favorites_empty), Typography.body.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center), c.textMuted, 4)
                        // Qué cuentas de contactos hay en la pantalla: ayuda a saber dónde guarda la agenda del celular.
                        loaded?.diag?.takeIf { it.isNotBlank() }?.let { Label(it, Typography.small, c.textMuted, 6, Modifier.padding(top = 6.dp)) }
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
    val loaded by produceState<Loaded?>(null) { value = withContext(Dispatchers.IO) { loadContacts(context) } }
    PanelScaffold(stringResource(R.string.tile_favorites), close) {
        val l = loaded
        when {
            l == null -> Label(stringResource(R.string.places_loading), Typography.body, c.textMuted)
            l.all.isEmpty() -> Column {
                Label(stringResource(R.string.favorites_empty), Typography.subtitle, c.textMuted, 4)
                Label(l.diag, Typography.body, c.textMuted, 12, Modifier.padding(top = 12.dp))
            }
            else -> androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(bottom = 16.dp)) {
                items(l.all.size) { i ->
                    val f = l.all[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { call(context, f.number) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Label(f.name, Typography.subtitle, maxLines = 1, modifier = Modifier.weight(1f))
                        Label(f.number, Typography.body, c.textMuted, 1)
                    }
                }
            }
        }
    }
}
