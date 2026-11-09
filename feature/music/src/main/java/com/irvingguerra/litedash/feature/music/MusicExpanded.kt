package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography

/**
 * La música en grande, sin salir de LiteDash: fuentes como en un estéreo (USB, tu app de música,
 * Bluetooth), carátula, barra para adelantar, y los botones propios de la app (en Spotify: me
 * gusta, aleatorio, repetir). Ancha pone la carátula a un lado; angosta, arriba. [browser] es la
 * biblioteca USB del reproductor propio: se abre con el botón de lista o al tocar USB sin nada
 * que sonar; recibe qué hacer cuando ya se eligió algo.
 */
@Composable
fun MusicExpanded(
    repo: MusicRepository,
    /** Null = el widget ya es así de grande en el inicio: sin botón de cerrar. */
    close: (() -> Unit)?,
    modifier: Modifier = Modifier,
    browser: @Composable (onPicked: () -> Unit) -> Unit,
) {
    val song = repo.song
    // La biblioteca USB abierta; si no, lo que suena.
    var browsing by remember { mutableStateOf(false) }
    val own = song != null && repo.isOwn(song.pkg)
    ExpandedCard(repo, modifier) { wide, artworkMax ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Las fuentes van junto a los botones (en su propia fila dejaban arriba una franja vacía).
            SourceRow(repo, song, browsing, onSelect = { browsing = false }, onBrowse = { browsing = true }, Modifier.weight(1f))
            if (own || browsing) HeaderButton(if (browsing) Icons.music else Icons.list) { browsing = !browsing }
            val app = song?.pkg ?: repo.active ?: repo.preferred
            if (app != null && !repo.isOwn(app)) HeaderButton(Icons.openExternal) { repo.openApp() }
            if (close != null) HeaderButton(Icons.close, close)
        }
        Spacer(Modifier.height(16.dp))
        if (browsing) {
            browser { browsing = false }
            return@ExpandedCard
        }
        // Sin nada sonando se ve igual, con los controles: play reanuda lo último.
        NowPlaying(song, Icons.music, wide, artworkMax) { Details(repo, song, it) }
    }
}

@Composable
private fun Details(repo: MusicRepository, song: Song?, modifier: Modifier) {
    val opening = repo.starting
    Column(modifier) {
        val title = when {
            song != null -> song.title
            opening != null -> stringResource(R.string.opening_app, opening)
            repo.carMode -> stringResource(R.string.car_player)
            else -> stringResource(R.string.nothing_playing)
        }
        val error = song?.error
        val subtitle = when {
            error != null -> error
            song?.buffering == true -> stringResource(R.string.player_connecting)
            song != null -> subtitleFor(repo, song)
            opening != null -> stringResource(R.string.opening_wait)
            repo.carMode -> stringResource(R.string.car_player_help)
            repo.usbDrive != null -> stringResource(R.string.usb_drive_in, repo.usbDrive.orEmpty())
            else -> stringResource(R.string.resume_explain)
        }
        BigTitle(title, subtitle, error != null)
        if (song != null && song.durationMs > 0) {
            Spacer(Modifier.height(20.dp))
            TimeBar(repo, song)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Controls(repo, song?.playing == true, loading = song?.buffering == true, size = if (repo.carMode) 96.dp else 64.dp, playSize = if (repo.carMode) 120.dp else 76.dp, gap = if (repo.carMode) 24.dp else 12.dp)
        }
        if (song != null && song.actions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                for (a in song.actions.take(5)) ActionButton(a) { repo.action(a) }
            }
        }
    }
}

/** Botón propio de la app con su ícono (se pinta del color del texto: suelen ser monocromos). */
@Composable
private fun ActionButton(a: MediaAction, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(Modifier.size(56.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (a.icon != null) {
            Image(a.icon.asImageBitmap(), a.name, Modifier.size(26.dp), colorFilter = ColorFilter.tint(c.textMuted))
        } else {
            Label(a.name.take(2), Typography.small, c.textMuted, 1)
        }
    }
}

/** Barra de avance que se toca o arrastra para adelantar (si la app lo permite). */
@Composable
private fun TimeBar(repo: MusicRepository, song: Song) {
    val c = LocalColors.current
    val pos by rememberPosition(song)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val duration = song.durationMs
    val fraction = dragFraction ?: (pos.toFloat() / duration).coerceIn(0f, 1f)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .then(
                if (song.seekable) Modifier
                    .pointerInput(song) {
                        detectTapGestures { o -> repo.seekTo((o.x / size.width * duration).toLong()) }
                    }
                    .pointerInput(song) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                            onHorizontalDrag = { change, d ->
                                change.consume()
                                dragFraction = ((dragFraction ?: 0f) + d / size.width).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                dragFraction?.let { repo.seekTo((it * duration).toLong()) }
                                dragFraction = null
                            },
                            onDragCancel = { dragFraction = null },
                        )
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        ProgressTrack(fraction, 6.dp)
        if (song.seekable) {
            Box(Modifier.offset(maxWidth * fraction - 8.dp).size(16.dp).clip(CircleShape).background(c.primary))
        }
    }
    Row(Modifier.fillMaxWidth()) {
        Label(formatTime(if (dragFraction != null) (fraction * duration).toLong() else pos), Typography.small, c.textMuted, 1)
        Spacer(Modifier.weight(1f))
        Label(formatTime(duration), Typography.small, c.textMuted, 1)
    }
}

private fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
