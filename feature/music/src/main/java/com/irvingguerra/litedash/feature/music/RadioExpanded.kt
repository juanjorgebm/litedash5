package com.irvingguerra.litedash.feature.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Segmented
import com.irvingguerra.litedash.core.ui.Typography

/**
 * La radio en grande: la FM de la pantalla o la radio por internet de LiteDash (si hay
 * las dos, se escoge arriba). Carátula, estación y controles, sin barra de avance (la
 * radio no tiene). [browser] es la lista de estaciones por internet (null = solo FM): se
 * abre con el botón de lista, o sola si la pieza está en internet y no suena nada.
 * [onSettings] abre los ajustes para enlazar la radio de la pantalla.
 */
@Composable
fun RadioExpanded(
    repo: MusicRepository,
    /** Null = el widget ya es así de grande en el inicio: sin botón de cerrar. */
    close: (() -> Unit)?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    browser: (@Composable (onPicked: () -> Unit) -> Unit)? = null,
    /** Abre la radio por internet a pantalla completa (null = sin botón). */
    onMaximize: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    val song = repo.radio.song
    val target = repo.appFor(SourceKind.RADIO)
    // Con qué suena la pieza: lo que ya suena manda; si no, lo que está escogido.
    val internet = when {
        repo.radioHasBoth -> repo.radioInternet
        song != null -> repo.isOwn(song.pkg)
        else -> target != null && repo.isOwn(target)
    }
    val canBrowse = browser != null && internet
    // La lista de estaciones; al entrar (o al cambiar a internet) sin nada sonando, se abre sola.
    var browsing by remember(canBrowse) { mutableStateOf(canBrowse && song == null && repo.radio.starting == null) }
    ExpandedCard(repo, modifier) { wide, artworkMax ->
        // Una sola fila arriba: el selector FM / internet (o el título si solo hay una) y los botones; deja más alto para los controles.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (repo.radioHasBoth) {
                Segmented(
                    listOf(false to stringResource(R.string.radio_fm), true to stringResource(R.string.radio_internet)),
                    repo.radioInternet,
                    { repo.radioInternet = it },
                )
                Spacer(Modifier.weight(1f))
            } else {
                Label(stringResource(R.string.radio).uppercase(), Typography.label, c.textMuted, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            if (canBrowse) HeaderButton(if (browsing) Icons.radio else Icons.list) { browsing = !browsing }
            if (canBrowse && onMaximize != null) HeaderButton(Icons.expand, onMaximize)
            if (!internet && repo.sourceApps[SourceKind.RADIO] != null) HeaderButton(Icons.openExternal) { repo.openRadioApp() }
            if (close != null) HeaderButton(Icons.close, close)
        }
        Spacer(Modifier.height(8.dp))
        if (browser != null && canBrowse && browsing) {
            browser { browsing = false }
            return@ExpandedCard
        }
        if (song == null && target == null) {
            NoRadio(onSettings, Modifier.weight(1f).fillMaxWidth())
            return@ExpandedCard
        }
        val onIdle: () -> Unit = { if (canBrowse) browsing = true }
        NowPlaying(song, Icons.radio, wide, artworkMax) { Details(repo, song, onIdle, it) }
    }
}

/** Sin FM enlazada ni radio por internet: qué hacer y el botón a Ajustes. */
@Composable
private fun NoRadio(onSettings: () -> Unit, modifier: Modifier) {
    val c = LocalColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.radio, c.textMuted, 56.dp)
        Spacer(Modifier.height(16.dp))
        Label(stringResource(R.string.radio_unlinked), Typography.subtitle.copy(textAlign = TextAlign.Center), c.textMuted, maxLines = 3)
        Spacer(Modifier.height(20.dp))
        Button(stringResource(R.string.radio_settings), onSettings, primary = true)
    }
}

@Composable
private fun Details(repo: MusicRepository, song: Song?, onIdle: () -> Unit, modifier: Modifier) {
    val opening = repo.radio.starting
    Column(modifier) {
        val title = when {
            song != null -> song.title
            opening != null -> stringResource(R.string.opening_app, opening)
            else -> stringResource(R.string.radio)
        }
        val error = song?.error
        val subtitle = when {
            error != null -> error
            song?.buffering == true -> stringResource(R.string.player_connecting)
            song != null -> subtitleFor(repo, song)
            opening != null -> stringResource(R.string.opening_wait)
            else -> radioHint(repo)
        }
        BigTitle(title, subtitle, error != null)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RadioControls(repo, song, onIdle, size = 64.dp, playSize = 80.dp, gap = 16.dp)
        }
    }
}
