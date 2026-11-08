package com.irvingguerra.litedash.core.ui

import android.annotation.SuppressLint
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Colores de la marca (los mismos de brand/logo).
object Brand {
    val LightWhite = Color(0xFFF4F6F8)
    val Amber = Color(0xFFFFB547)
}

/** Diseño Okavango: fondo grafito y tarjetas de gris pizarra a negro carbón, con la luz de arriba a la izquierda. */
val Slate = Color(0xFF343B45)
val Coal = Color(0xFF171B21)

/** Colores que se repiten entre los temas: tinta (fondo oscuro), tinta y papel del claro, y su ámbar. */
private val Ink = Color(0xFF0C0E11)
private val LightInk = Color(0xFF16181C)
private val LightPaper = Color(0xFFE9ECF0)
private val LightAmber = Color(0xFFE59500)

@Immutable
data class Colors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val line: Color,
    val text: Color,
    val textMuted: Color,
    /** El color de realce: interruptores, íconos y lo que se llena. Se puede elegir ([withAccent]). */
    val accent: Color,
    val danger: Color,
    /** Advertencia (Salud, Llantas, códigos pendientes): siempre ámbar, aunque se elija otro color. */
    val warning: Color,
    /** Relleno de lo principal (botón principal, play, barra de avance, chip elegido) y el texto encima. */
    val primary: Color,
    val onPrimary: Color,
)

/**
 * Los colores con el realce que eligió el usuario; null = el natural (ámbar, y lo principal en
 * blanco o negro). Con un color, lo principal también lo toma; nunca las tarjetas ni el fondo.
 * Un color que no se vería se ajusta: en oscuro, uno casi negro se aclara; en claro, uno claro se
 * oscurece. Lo de encima ([Colors.onPrimary]) es tinta o blanco, el que más contraste.
 */
fun Colors.withAccent(chosen: Color?): Colors {
    if (chosen == null) return this
    var color: Color = chosen
    if (dark) while (color.luminance() < 0.12f) color = lerp(color, Color.White, 0.1f)
    else while (color.luminance() > 0.35f) color = lerp(color, Color.Black, 0.08f)
    // En oscuro, uno casi blanco tampoco: el interruptor prendido llevaría bolita blanca sobre blanco.
    if (dark) while (color.luminance() > 0.8f) color = lerp(color, Color.Black, 0.08f)
    return copy(accent = color, primary = color, onPrimary = if (contrast(color, Ink) >= contrast(color, Color.White)) Ink else Color.White)
}

/** El contraste entre dos colores (1–21), como lo mide WCAG. */
private fun contrast(a: Color, b: Color): Float {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05f) / (lo + 0.05f)
}

// Estilo limpio (Irving, 2026-11-01: "más estilo Apple o DuduAuto"): tarjetas lisas sin
// borde sobre un fondo apenas distinto, texto secundario gris y el color de realce solo en lo importante.
val DarkColors = Colors(
    dark = true,
    background = Ink,
    surface = Color(0xFF232930),
    surfaceHigh = Color(0xFF2F363F),
    line = Color(0xFF3A424D),
    text = Color.White,
    textMuted = Color(0xFFA9B2BD),
    accent = Brand.Amber,
    danger = Color(0xFFFF6B5E),
    warning = Brand.Amber,
    // Los botones activos y lo principal van en ámbar con tinta encima.
    primary = Brand.Amber,
    onPrimary = Color(0xFF2B1A05),
)

val LightColors = Colors(
    dark = false,
    background = LightPaper,
    surface = Color.White,
    surfaceHigh = Color(0xFFF0F2F5),
    line = Color(0xFFE3E6EA),
    text = LightInk,
    textMuted = Color(0xFF7D8590),
    accent = LightAmber,
    danger = Color(0xFFD93A2B),
    warning = LightAmber,
    primary = LightInk,
    onPrimary = LightPaper,
)

val LocalColors = staticCompositionLocalOf { DarkColors }

/** Esquinas: moderadas (Irving pidió menos redondeado que la propuesta). */
object Radius {
    /** Tarjetas del tablero y de Ajustes. */
    val card = 28.dp
    /** Cosas dentro de una tarjeta: portadas, botones, campos, filas. */
    val inner = 16.dp
    /** Detalles chicos (barras, indicadores). */
    val small = 6.dp
}

/**
 * Sin Material: la app usa solo foundation para mantenerse ligera.
 * [scale] agranda o achica toda la interfaz (pantallas que mienten con su dpi); [accent], el color elegido.
 */
@Composable
fun LiteDashTheme(dark: Boolean = true, scale: Float = 1f, accent: Color? = null, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val colors = remember(dark, accent) { (if (dark) DarkColors else LightColors).withAccent(accent) }
    CompositionLocalProvider(
        LocalColors provides colors,
        LocalDensity provides Density(d.density * scale, d.fontScale),
        content = content,
    )
}

object Typography {
    // Más grandes que antes: se leen desde el asiento del conductor.
    val clock = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
    val title = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val subtitle = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 18.sp)
    val small = TextStyle(fontSize = 15.sp)
    val label = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
}

// El color va tercero a propósito: así se escribe en toda la app (Label(t, estilo, c.textMuted)).
@SuppressLint("ModifierParameter")
@Composable
fun Label(
    text: String,
    style: TextStyle,
    color: Color = LocalColors.current.text,
    maxLines: Int = Int.MAX_VALUE,
    modifier: Modifier = Modifier,
) {
    BasicText(text, modifier, style = style.copy(color = color), maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private val CardBrush = Brush.linearGradient(listOf(Slate, Color(0xFF272D36), Coal))
private val CardEdge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.05f)))

/** La pieza del tablero va sin fondo de tarjeta: [Card] queda transparente (lo de adentro se ve igual). */
val LocalPlainCard = compositionLocalOf { false }

/** Tarjeta base del tablero: superficie lisa, sin borde, con esquinas [Radius.card]. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.card)
    val plain = LocalPlainCard.current
    var m = modifier
    if (!plain && c.dark) {
        // Relieve: sombra que levanta la tarjeta, degradado pizarra → carbón y un borde fino
        // más claro arriba (la luz viene de arriba a la izquierda).
        m = m
            .shadow(8.dp, shape, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(CardBrush)
            .border(1.dp, CardEdge, shape)
    } else {
        m = m.clip(shape).background(if (plain) Color.Transparent else c.surface)
    }
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m, content = content)
}

/** Botón redondo con ícono, objetivo táctil de 56 dp como pide el plan. */
@Composable
fun IconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    background: Color = Color.Transparent,
    color: Color = LocalColors.current.text,
) {
    Box(
        modifier.size(size).clip(CircleShape).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, color, size * 0.45f) }
}

/** Botón de texto con esquinas [Radius.inner]. */
@Composable
fun Button(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val c = LocalColors.current
    val shape = RoundedCornerShape(Radius.inner)
    Box(
        modifier
            .clip(shape)
            .background(if (primary) c.primary else c.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Label(text, Typography.body.copy(fontWeight = FontWeight.SemiBold), if (primary) c.onPrimary else c.text, 1) }
}

/**
 * Opciones excluyentes (una u otra), estilo iOS: un bloque con la elegida resaltada.
 * Para encender o apagar algo se usa [SwitchRow].
 */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    Row(
        modifier.clip(RoundedCornerShape(Radius.inner)).background(c.surfaceHigh).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((value, text) in options) {
            val on = value == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(Radius.inner - 2.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(text, Typography.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), if (on) c.text else c.textMuted, 1)
            }
        }
    }
}

/** Un renglón con su interruptor: el texto (y una ayuda opcional) a la izquierda. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, help: String? = null) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.inner)).clickable { onChange(!checked) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Label(title, Typography.body)
            if (help != null) Label(help, Typography.small, c.textMuted)
        }
        Switch(checked)
    }
}

/** El interruptor en sí (el toque lo maneja su renglón). */
@Composable
fun Switch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val knob by animateDpAsState(if (checked) 22.dp else 2.dp, label = "interruptor")
    Box(
        modifier.size(width = 52.dp, height = 32.dp).clip(CircleShape).background(if (checked) c.accent else c.line),
    ) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(28.dp).clip(CircleShape).background(Color.White))
    }
}

/** Campo de texto de una línea con [hint]; [icon] pone la lupa (buscadores). */
@Composable
fun TextInput(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, icon: Boolean = false) {
    val c = LocalColors.current
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(Radius.card)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(Radius.card)).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) {
            Icon(Icons.search, c.textMuted, 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Label(hint, Typography.body, c.textMuted, maxLines = 1)
            BasicTextField(
                value,
                onChange,
                Modifier.fillMaxWidth(),
                textStyle = Typography.body.copy(color = c.text),
                singleLine = true,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            )
        }
    }
}
