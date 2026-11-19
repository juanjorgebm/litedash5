package com.irvingguerra.litedash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
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

/** Un contacto favorito: nombre, número y foto (si tiene). */
private class Favorite(val name: String, val number: String, val photo: ImageBitmap?)

/** Cuántos favoritos caben en la pieza (dos columnas por dos filas). */
private const val MAX_FAVORITES = 6

private fun hasContacts(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

/** Los contactos con estrella (y teléfono), con su foto reducida. Solo en IO. */
private fun loadFavorites(context: Context): List<Favorite> = runCatching {
    val out = ArrayList<Favorite>()
    val resolver = context.contentResolver
    resolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_THUMBNAIL_URI),
        "${ContactsContract.Contacts.STARRED} = 1 AND ${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
        null,
        "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE LOCALIZED ASC",
    )?.use { c ->
        while (c.moveToNext() && out.size < MAX_FAVORITES) {
            val id = c.getLong(0)
            val name = c.getString(1).orEmpty()
            val number = resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(id.toString()),
                "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC",
            )?.use { n -> if (n.moveToFirst()) n.getString(0) else null } ?: continue
            val photo = c.getString(2)?.let { uri ->
                runCatching { resolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
            }
            out += Favorite(name, number, photo)
        }
    }
    out
}.getOrDefault(emptyList())

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
fun FavoritesTile() {
    val c = LocalColors.current
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasContacts(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasContacts(context)
    }
    val favorites by produceState<List<Favorite>?>(null, granted) {
        value = if (granted) withContext(Dispatchers.IO) { loadFavorites(context) } else emptyList()
    }
    Card(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.tile_favorites), Typography.title, maxLines = 1, modifier = Modifier.weight(1f))
                Icon(Icons.starFilled, c.accent, 26.dp)
            }
            val list = favorites
            when {
                !granted -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Button(
                        stringResource(R.string.favorites_allow),
                        { launcher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)) },
                        primary = true,
                    )
                }
                list.isNullOrEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (list != null) Label(stringResource(R.string.favorites_empty), Typography.body, c.textMuted)
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
private fun FavoriteCell(f: Favorite, avatar: androidx.compose.ui.unit.Dp) {
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
