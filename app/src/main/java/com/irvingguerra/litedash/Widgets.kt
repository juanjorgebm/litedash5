package com.irvingguerra.litedash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.TileTypes

/*
 * Widgets de otras apps de Android (Musixmatch, el clima de fábrica…): LiteDash hace de "host".
 * Cada uno es una pieza [TileTypes.WIDGET] cuyo dato es su appWidgetId.
 */

/** El anfitrión de widgets de LiteDash: uno solo, escuchando mientras haya un widget a la vista. */
private object WidgetHost {
    private const val HOST_ID = 4242
    private var host: AppWidgetHost? = null
    private var listening = 0

    fun get(context: Context): AppWidgetHost =
        host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }

    fun acquire(context: Context) {
        if (listening++ == 0) runCatching { get(context).startListening() }
    }

    fun release(context: Context) {
        if (--listening <= 0) {
            listening = 0
            runCatching { get(context).stopListening() }
        }
    }
}

/** Un widget de Android dentro de una tarjeta; sin él (se desinstaló la app o se borró) avisa. */
@Composable
fun WidgetTile(data: String) {
    val c = LocalColors.current
    val context = LocalContext.current
    val id = data.toIntOrNull()
    val info = remember(id) { id?.let { runCatching { AppWidgetManager.getInstance(context).getAppWidgetInfo(it) }.getOrNull() } }
    DisposableEffect(Unit) {
        WidgetHost.acquire(context)
        onDispose { WidgetHost.release(context) }
    }
    Card(Modifier.fillMaxSize()) {
        if (id == null || info == null) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Label(stringResource(R.string.pick_widget_empty), Typography.small, c.textMuted)
            }
            return@Card
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
            val widthDp = maxWidth.value.toInt()
            val heightDp = maxHeight.value.toInt()
            AndroidView(
                factory = { ctx ->
                    WidgetHost.get(ctx).createView(ctx, id, info).apply { setAppWidget(id, info) } as AppWidgetHostView
                },
                update = { view -> runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Elegir uno de los widgets que hay instalados y ponerlo en el inicio. */
@Composable
fun PickWidgetPanel(d: Dependencies, close: () -> Unit) {
    val c = LocalColors.current
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val providers = remember { runCatching { manager.installedProviders.orEmpty() }.getOrDefault(emptyList()) }
    val pm = context.packageManager
    // Etiqueta de cada uno (una llamada al sistema por widget: se lee una sola vez).
    val items = remember(providers) {
        providers.map { it to runCatching { it.loadLabel(pm) }.getOrDefault(it.provider.packageName) }
            .sortedBy { it.second.lowercase() }
    }
    var noRoom by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null) }

    fun finish(id: Int) {
        if (d.dashboard.addTile(TileTypes.WIDGET, id.toString())) close()
        else {
            noRoom = true
            runCatching { WidgetHost.get(context).deleteAppWidgetId(id) }
        }
    }

    // 3.º paso: si el widget trae su pantalla de ajustes, se abre antes de ponerlo.
    val configure = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (id, _) = pending ?: return@rememberLauncherForActivityResult
        if (result.resultCode == Activity.RESULT_OK) finish(id)
        else runCatching { WidgetHost.get(context).deleteAppWidgetId(id) }
        pending = null
    }
    fun configureOrFinish(id: Int, info: AppWidgetProviderInfo) {
        val component = info.configure
        if (component == null) return finish(id)
        pending = id to info
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(component)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        // Algunos widgets no dejan abrir su ajuste desde fuera: se pone sin él.
        if (runCatching { configure.launch(intent) }.isFailure) { pending = null; finish(id) }
    }

    // 2.º paso: Android pide permiso al usuario para que LiteDash ponga el widget.
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val (id, info) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.resultCode == Activity.RESULT_OK) configureOrFinish(id, info)
        else runCatching { WidgetHost.get(context).deleteAppWidgetId(id) }
    }

    PanelScaffold(stringResource(R.string.pick_widget), close) {
        if (noRoom) Label(stringResource(R.string.no_room), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        if (failed) Label(stringResource(R.string.widget_bind_failed), Typography.body, c.danger, modifier = Modifier.padding(bottom = 12.dp))
        if (items.isEmpty()) Label(stringResource(R.string.pick_widget_empty), Typography.body, c.textMuted)
        else CardGrid(items, minWidth = 220.dp, height = 110.dp, onClick = { (info, _) ->
            failed = false
            val id = runCatching { WidgetHost.get(context).allocateAppWidgetId() }.getOrNull()
            if (id == null) { failed = true; return@CardGrid }
            val allowed = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.provider) }.getOrDefault(false)
            if (allowed) configureOrFinish(id, info)
            else {
                pending = id to info
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                if (runCatching { bind.launch(intent) }.isFailure) {
                    pending = null
                    failed = true
                    runCatching { WidgetHost.get(context).deleteAppWidgetId(id) }
                }
            }
        }) { (info, label) ->
            Icon(iconOf(TileTypes.WIDGET), c.accent, 28.dp)
            Label(label, Typography.subtitle, maxLines = 1)
            Label(info.provider.packageName, Typography.small, c.textMuted, maxLines = 1)
        }
    }
}
