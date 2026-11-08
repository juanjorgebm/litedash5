package com.irvingguerra.litedash.feature.player

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import java.io.File
import com.irvingguerra.litedash.feature.music.Drive
import com.irvingguerra.litedash.feature.music.Drives
import androidx.core.content.ContextCompat

/** Una canción de la memoria (dos con los mismos datos son la misma: tocar USB otra vez no la reinicia). */
data class Track(
    override val uri: Uri,
    override val title: String,
    val artist: String,
    val album: String,
    /** Carpeta donde está, relativa a la memoria ("Rock/Metallica"). */
    val folder: String,
    val durationMs: Long,
    /** Nombre del archivo, para ordenar como en la carpeta. */
    val file: String,
) : Playable {
    override val subtitle get() = artist
    /** Por carpeta y archivo, no por id: MediaStore da ids nuevos cada vez que se reconecta la memoria. */
    override val key get() = "usb:$folder/$file"
}

/**
 * Las memorias conectadas y sus canciones: primero por MediaStore (Android ya las tiene
 * indexadas); si no trae nada, recorriendo las carpetas de la memoria (varias pantallas montan
 * el USB donde el índice de Android no lo revisa y su propio reproductor lee los archivos).
 */
object Library {
    /** El permiso para leer la música de la memoria, según la versión de Android. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** El permiso del aviso del reproductor, que solo existe desde Android 13. */
    val noticePermission: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    // Lo último que se leyó con música y de qué memorias (y montajes) salió: abrir la biblioteca
    // o tocar USB otra vez no relee la memoria. Solo se guarda si hubo canciones.
    @Volatile
    private var cache: Pair<String, Pair<Drive?, List<Track>>>? = null

    /**
     * Qué tocar: la primera memoria con música (hay pantallas con dos USB y la "primera" puede
     * venir vacía); si ninguna, la carpeta que eligió el usuario; si nada, la primera memoria
     * (vacía) o null. Lee el disco (salvo que ya lo haya leído con las mismas memorias):
     * llamarla fuera del hilo principal.
     */
    fun find(context: Context): Pair<Drive?, List<Track>> {
        val drives = Drives.list(context)
        val folder = folderDrive(context)
        val key = (drives.map { it.id } + listOfNotNull(folder?.id) + Drives.revision.toString()).joinToString("|")
        cache?.takeIf { it.first == key }?.let { return it.second }
        val found = (drives + listOfNotNull(folder)).firstNotNullOfOrNull { d -> tracks(context, d).takeIf { it.isNotEmpty() }?.let { d to it } }
        if (found != null) cache = key to found
        return found ?: (drives.firstOrNull() to emptyList())
    }

    /** La carpeta elegida como "memoria" (si sigue habiendo permiso para leerla). */
    private fun folderDrive(context: Context): Drive? {
        val uri = PlayerStore(context).musicFolder?.toUri() ?: return null
        if (context.contentResolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return null
        val name = DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':').substringAfterLast('/').ifBlank { "USB" }
        return Drive(name = name, volume = null, path = null, id = FOLDER + uri)
    }

    /** El nombre de la carpeta elegida (para Ajustes); null = ninguna, se busca sola en las memorias. */
    fun folderName(context: Context): String? = folderDrive(context)?.name

    /** Olvida la carpeta elegida (vuelve a buscar sola en las memorias) y suelta su permiso. */
    fun clearFolder(context: Context) {
        val store = PlayerStore(context)
        store.musicFolder?.toUri()?.let { uri -> runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        store.musicFolder = null
    }

    /**
     * Guarda la carpeta que eligió el usuario con el selector de Android (ACTION_OPEN_DOCUMENT_TREE):
     * se lee por el sistema de documentos, así sirve aunque Android no tenga indexada la memoria.
     */
    fun setFolder(context: Context, uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        PlayerStore(context).musicFolder = uri.toString()
    }

    /** Todas las canciones de [drive], ordenadas por carpeta y archivo (como se ven en la memoria). */
    private fun tracks(context: Context, drive: Drive): List<Track> {
        val found = if (drive.id.startsWith(FOLDER)) folderTracks(context, drive.id.removePrefix(FOLDER).toUri())
        else indexed(context, drive).ifEmpty { drive.path?.let { scan(File(it)) }.orEmpty() }
        return found.sortedWith(compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.folder }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.file })
    }

    /** Las canciones de la carpeta elegida, por el sistema de documentos. */
    private fun folderTracks(context: Context, tree: Uri): List<Track> {
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        return walk(DocumentsContract.getTreeDocumentId(tree), { id ->
            val out = ArrayList<Entry<String>>()
            context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val child = c.getString(0) ?: continue
                    val name = c.getString(1).orEmpty()
                    val mime = c.getString(2).orEmpty()
                    val dir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    out += Entry(child, name, dir, !dir && (mime.startsWith("audio/") || isAudio(name)))
                }
            }
            out
        }) { child, name, folder -> Track(DocumentsContract.buildDocumentUriUsingTree(tree, child), name.substringBeforeLast('.'), "", "", folder, 0, name) }
    }

    /** Las de MediaStore (rápido, con artista, álbum y duración). */
    private fun indexed(context: Context, drive: Drive): List<Track> {
        val uri = if (drive.volume != null) MediaStore.Audio.Media.getContentUri(drive.volume) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val root = drive.path?.trimEnd('/')
        // Sin volumen ni ruta no hay cómo separar la memoria del resto: sin esto saldría toda la música interna.
        if (drive.volume == null && root == null) return emptyList()
        // En API < 29 el volumen "external" junta todo: se filtra por la ruta de la memoria.
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (drive.volume == null && root != null) append(" AND ${MediaStore.Audio.Media.DATA} LIKE ?")
        }
        val args = if (drive.volume == null && root != null) arrayOf("$root/%") else null
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val out = ArrayList<Track>()
        runCatching {
            context.contentResolver.query(uri, projection, selection, args, null)?.use { c ->
                val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val data = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val display = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val path = c.getString(data).orEmpty()
                    val file = c.getString(display) ?: path.substringAfterLast('/')
                    val folder = path.substringBeforeLast('/', "").let { if (root != null) it.removePrefix(root) else it }.trim('/')
                    out += Track(
                        uri = ContentUris.withAppendedId(uri, c.getLong(id)),
                        title = c.getString(title)?.takeIf { it.isNotBlank() } ?: file.substringBeforeLast('.'),
                        artist = c.getString(artist)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "",
                        album = c.getString(album)?.takeIf { it.isNotBlank() && it != MediaStore.UNKNOWN_STRING } ?: "",
                        folder = folder,
                        durationMs = c.getLong(duration),
                        file = file,
                    )
                }
            }
        }
        return out
    }

    /**
     * Recorre las carpetas de la memoria buscando audio (sin índice de Android): título = nombre
     * del archivo; artista, álbum y duración no se saben sin abrir cada archivo (lento en una USB).
     */
    private fun scan(root: File): List<Track> = walk(root, { dir ->
        dir.listFiles()?.map { f -> Entry(f, f.name, f.isDirectory, !f.isDirectory && isAudio(f.name)) }
    }) { f, name, folder -> Track(Uri.fromFile(f), name.substringBeforeLast('.'), "", "", folder, 0, name) }

    /** Un archivo o carpeta dentro de [walk]: [node] es lo que sirve para abrirlo. */
    private class Entry<N>(val node: N, val name: String, val dir: Boolean, val audio: Boolean)

    /**
     * Recorre las carpetas desde [root] a lo ancho y junta las canciones ([track] con el nombre del
     * archivo y su carpeta relativa). Sin ocultas ni carpetas de sistema, y con tope de profundidad,
     * carpetas y archivos para que una memoria enorme no se quede leyendo minutos.
     */
    private fun <N> walk(root: N, children: (N) -> List<Entry<N>>?, track: (N, String, String) -> Track): List<Track> {
        val out = ArrayList<Track>()
        val pending = ArrayDeque<Triple<N, String, Int>>().apply { add(Triple(root, "", 0)) }
        var dirs = 0
        while (pending.isNotEmpty() && out.size < MAX_FILES && dirs < MAX_DIRS) {
            val (node, folder, depth) = pending.removeFirst()
            dirs++
            for (e in runCatching { children(node) }.getOrNull().orEmpty()) {
                if (e.name.startsWith(".")) continue
                if (e.dir) {
                    if (depth < MAX_DEPTH && e.name !in SKIP_DIRS) pending.add(Triple(e.node, if (folder.isEmpty()) e.name else "$folder/${e.name}", depth + 1))
                } else if (e.audio) {
                    out += track(e.node, e.name, folder)
                }
            }
        }
        return out
    }

    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO

    private val AUDIO = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr", "3gp", "mka")
    private val SKIP_DIRS = setOf("Android", "LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")
    private const val MAX_DEPTH = 8
    private const val MAX_DIRS = 2_000
    private const val MAX_FILES = 10_000
    private const val FOLDER = "folder:"
}
