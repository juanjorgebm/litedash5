package com.irvingguerra.litedash

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.irvingguerra.litedash.core.ui.Button
import com.irvingguerra.litedash.core.ui.Icon
import com.irvingguerra.litedash.core.ui.Icons
import com.irvingguerra.litedash.core.ui.Label
import com.irvingguerra.litedash.core.ui.LocalColors
import com.irvingguerra.litedash.core.ui.MorseMark
import com.irvingguerra.litedash.core.ui.TextInput
import com.irvingguerra.litedash.core.ui.Typography
import com.irvingguerra.litedash.feature.car.CarDrawing
import com.irvingguerra.litedash.feature.dashboard.DashboardState
import com.irvingguerra.litedash.feature.dashboard.Template
import com.irvingguerra.litedash.feature.dashboard.size
import com.irvingguerra.litedash.feature.dashboard.suggestedTemplate
import androidx.core.content.ContextCompat

/** ¿LiteDash ya es la pantalla de inicio? */
fun isHomeApp(context: Context): Boolean {
    // Android 10+: el rol del sistema. Sin esto, desde Android 11 las otras apps de
    // inicio no se ven y LiteDash creería que es la única.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        runCatching { return ContextCompat.getSystemService(context, RoleManager::class.java)!!.isRoleHeld(RoleManager.ROLE_HOME) }
    }
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == context.packageName
}

/**
 * Primer arranque: 1) tu auto (nombre y color), 2) cómo lo quieres (acomodo y
 * tema), 3) hacerlo tu pantalla de inicio. Todo se puede cambiar después.
 */
@Composable
fun Welcome(prefs: Preferences, dashboard: DashboardState, revision: Int, onFinish: () -> Unit) {
    val c = LocalColors.current
    var step by remember { mutableIntStateOf(prefs.welcomeStep.coerceIn(0, 2)) }
    fun goTo(n: Int) {
        step = n
        prefs.welcomeStep = n
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 32.dp, vertical = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MorseMark(Modifier.size(width = 92.dp, height = 24.dp))
            Spacer(Modifier.weight(1f))
            // Tres puntos: dónde vas.
            for (i in 0..2) {
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .size(width = if (i == step) 28.dp else 10.dp, height = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (i <= step) c.accent else c.line),
                )
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
            when (step) {
                0 -> CarStep(prefs)
                1 -> StyleStep(prefs, dashboard)
                else -> HomeStep(revision)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) Button(stringResource(R.string.back), { goTo(step - 1) })
            Spacer(Modifier.weight(1f))
            if (step < 2) {
                Button(stringResource(R.string.skip), onFinish)
                Button(stringResource(R.string.next), { goTo(step + 1) }, primary = true)
            } else {
                Button(stringResource(R.string.start), onFinish, primary = true)
            }
        }
    }
}

@Composable
private fun Titles(title: String, text: String) {
    Label(title, Typography.clock.copy(fontSize = Typography.title.fontSize * 1.6f))
    Spacer(Modifier.height(8.dp))
    Label(text, Typography.body, LocalColors.current.textMuted, modifier = Modifier.widthIn(max = 640.dp))
    Spacer(Modifier.height(28.dp))
}

@Composable
private fun ColumnScope.CarStep(prefs: Preferences) {
    val c = LocalColors.current
    Titles(stringResource(R.string.welcome_title), stringResource(R.string.welcome_car))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val horizontal = maxWidth > 700.dp
        val drawing = @Composable { m: Modifier ->
            CarDrawing(prefs.carColor, m)
        }
        val options = @Composable { m: Modifier ->
            Column(m, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(stringResource(R.string.your_name), Typography.label, c.textMuted)
                TextInput(prefs.driverName ?: "", { prefs.driverName = it.take(24).ifBlank { null } }, stringResource(R.string.your_name_hint), Modifier.fillMaxWidth())
                Label(stringResource(R.string.car_name), Typography.label, c.textMuted)
                TextInput(prefs.carName ?: "", { prefs.carName = it.take(24).ifBlank { null } }, stringResource(R.string.my_car), Modifier.fillMaxWidth())
                Label(stringResource(R.string.color), Typography.label, c.textMuted)
                ColorPicker(prefs.carColor, prefs::previewCarColor) { prefs.carColor = it }
            }
        }
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                drawing(Modifier.weight(1.2f))
                options(Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                drawing(Modifier.fillMaxWidth())
                options(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun StyleStep(prefs: Preferences, dashboard: DashboardState) {
    val c = LocalColors.current
    Titles(stringResource(R.string.welcome_style_title), stringResource(R.string.welcome_style))
    Label(stringResource(R.string.layout), Typography.label, c.textMuted)
    Spacer(Modifier.height(12.dp))
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    // Los acomodos que lucen en esta pantalla, el recomendado primero.
    val options = dashboard.grid?.let { c -> listOf(c.suggestedTemplate()) + Template.entries.filter { c.size in it.sizes } }
        ?.distinct().orEmpty()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((i, p) in options.withIndex()) {
            Button(stringResource(nameOf(p)), { selected = i; dashboard.template(p) }, primary = selected == i)
        }
    }
    Spacer(Modifier.height(28.dp))
    Label(stringResource(R.string.theme), Typography.label, c.textMuted)
    Spacer(Modifier.height(12.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((mode, text) in ThemeOptions) {
            Button(stringResource(text), { prefs.theme = mode }, primary = prefs.theme == mode)
        }
    }
    Spacer(Modifier.height(20.dp))
    Label(stringResource(R.string.welcome_change_later), Typography.small, c.textMuted)
}

/**
 * Pide ser la pantalla de inicio: el diálogo del sistema (Android 10+) y, si la
 * pantalla no lo tiene, sus ajustes de inicio o de apps predeterminadas.
 * Devuelve la acción y un contador que sube al volver del diálogo.
 */
@Composable
fun rememberRequestHome(): Pair<() -> Unit, Int> {
    val context = LocalContext.current
    var returns by remember { mutableIntStateOf(0) }
    val requestRole = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returns++ }
    val request = {
        val requested = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && runCatching {
            val roleManager = ContextCompat.getSystemService(context, RoleManager::class.java)!!
            if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) return@runCatching false
            requestRole.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            true
        }.getOrDefault(false)
        if (!requested) {
            // "Apps predeterminadas" existe desde Android 7.
            val defaults = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS else null
            listOfNotNull(Settings.ACTION_HOME_SETTINGS, defaults, Settings.ACTION_SETTINGS)
                .firstOrNull { action ->
                    runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                }
        }
        Unit
    }
    return request to returns
}

@Composable
private fun HomeStep(revision: Int) {
    val c = LocalColors.current
    val context = LocalContext.current
    val (request, returns) = rememberRequestHome()
    // Se vuelve a revisar al regresar de Ajustes (revision cambia en onResume).
    val alreadyHome = remember(revision, returns) { isHomeApp(context) }
    Titles(stringResource(R.string.welcome_home_title), stringResource(R.string.welcome_home))
    if (alreadyHome) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.done, c.onPrimary, 24.dp)
            }
            Spacer(Modifier.width(14.dp))
            Label(stringResource(R.string.welcome_already_home), Typography.subtitle)
        }
    } else {
        Button(stringResource(R.string.make_home), request, primary = true)
        Spacer(Modifier.height(16.dp))
        Label(stringResource(R.string.welcome_home_help), Typography.small, c.textMuted, modifier = Modifier.widthIn(max = 640.dp))
    }
}
