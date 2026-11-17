package com.irvingguerra.litedash

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.irvingguerra.litedash.core.ui.Card
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.Radius
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.dashboard.TileTypes
import kotlinx.coroutines.delay

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

/** "1 nuevo", "3 mensajes nuevos", "2 sin leer", "5 unread"…: lo que escriben los widgets cuando hay algo pendiente. */
private val UNREAD = Regex("""(?i)(?<![\d:.,])([1-9]\d{0,3})\s+(?:\p{L}+\s+)?(?:nuev[oa]s?|sin\s+leer|no\s+le[ií]d[oa]s?|new|unread)(?!\p{L})""")

/** Cuántos pendientes anuncia el widget en sus textos (0 si ninguno). LiteDash no ve sus datos, solo lo que pinta. */
private fun unreadIn(view: View?): Int = when (view) {
    is TextView -> if (view.visibility == View.VISIBLE) UNREAD.find(view.text ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 0 else 0
    is ViewGroup -> if (view.visibility != View.VISIBLE) 0 else (0 until view.childCount).sumOf { unreadIn(view.getChildAt(it)) }
    else -> 0
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
        // El widget lo pinta la otra app: se leen sus textos cada rato y, si anuncia algo nuevo, se marca en rojo.
        var hosted by remember { mutableStateOf<AppWidgetHostView?>(null) }
        var unread by remember { mutableIntStateOf(0) }
        LaunchedEffect(hosted) {
            while (true) {
                unread = runCatching { unreadIn(hosted) }.getOrDefault(0)
                delay(2_000)
            }
        }
        val alert = Color(0xFFFF3B30)
        BoxWithConstraints(
            Modifier.fillMaxSize()
                .then(if (unread > 0) Modifier.border(3.dp, alert, RoundedCornerShape(Radius.card)) else Modifier)
                .padding(8.dp),
        ) {
            val widthDp = maxWidth.value.toInt()
            val heightDp = maxHeight.value.toInt()
            AndroidView(
                factory = { ctx ->
                    (WidgetHost.get(ctx).createView(ctx, id, info).apply { setAppWidget(id, info) } as AppWidgetHostView).also { hosted = it }
                },
                update = { view -> runCatching { view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp) } },
                modifier = Modifier.fillMaxSize(),
            )
            if (unread > 0) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).defaultMinSize(34.dp, 34.dp).background(alert, CircleShape)
                    .border(2.dp, Color.White, CircleShape).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Label(if (unread > 99) "99+" else unread.toString(), Typography.body, Color.White, 1) }
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
