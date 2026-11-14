package com.mcd.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Shapes ----------------

private val JarvisShape: Shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
private val JarvisShapeSmall: Shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
private val JarvisShapeTiny: Shape = CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp)
private val CalmShape: Shape = RoundedCornerShape(12.dp)
private val CalmShapeSmall: Shape = RoundedCornerShape(10.dp)
private val CalmShapeTiny: Shape = RoundedCornerShape(8.dp)
private val CalmPill: Shape = RoundedCornerShape(percent = 50)

/** Card / panel shape. Jarvis: top-left and bottom-right corners cut. Calm: 12dp rounded corners. */
val HudShape: Shape get() = if (McdLook.calm) CalmShape else JarvisShape

/** Smaller shape for rows, chips and small panels. Calm: 10dp rounded corners. */
val HudShapeSmall: Shape get() = if (McdLook.calm) CalmShapeSmall else JarvisShapeSmall

/** Tiny shape for little badges and guide cells. Calm: 8dp rounded (a full pill on small badges). */
val HudShapeTiny: Shape get() = if (McdLook.calm) CalmShapeTiny else JarvisShapeTiny

/** Buttons: the small chamfer in Jarvis, a fully round pill in Calm. */
val HudButtonShape: Shape get() = if (McdLook.calm) CalmPill else JarvisShapeSmall

/** Partly desaturates backdrops so the cyan tint on top reads as a duotone. */
val HudDuotone: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.35f) })

// ---------------- Focus decoration ----------------

/**
 * Four small L-shaped corner brackets, drawn over the content. A negative [inset] draws them outside
 * the bounds (keep it before any clip in the chain). Calm draws a soft rounded sage ring there instead.
 */
fun Modifier.hudBrackets(
    visible: Boolean,
    color: Color = McdColors.AccentBright,
    inset: Dp = 5.dp,
    arm: Dp = 12.dp,
    stroke: Dp = 2.dp,
): Modifier = if (!visible) this else if (McdLook.calm) calmRing(McdColors.Accent.copy(alpha = 0.85f), inset, stroke) else drawWithContent {
    drawContent()
    val i = inset.toPx()
    val a = arm.toPx()
    val s = stroke.toPx()
    val l = i
    val t = i
    val r = size.width - i
    val b = size.height - i
    drawLine(color, Offset(l, t), Offset(l + a, t), s, StrokeCap.Square)
    drawLine(color, Offset(l, t), Offset(l, t + a), s, StrokeCap.Square)
    drawLine(color, Offset(r, t), Offset(r - a, t), s, StrokeCap.Square)
    drawLine(color, Offset(r, t), Offset(r, t + a), s, StrokeCap.Square)
    drawLine(color, Offset(l, b), Offset(l + a, b), s, StrokeCap.Square)
    drawLine(color, Offset(l, b), Offset(l, b - a), s, StrokeCap.Square)
    drawLine(color, Offset(r, b), Offset(r - a, b), s, StrokeCap.Square)
    drawLine(color, Offset(r, b), Offset(r, b - a), s, StrokeCap.Square)
}

/**
 * Calm focus mark: one soft rounded ring at [inset] (negative = outside the bounds), drawn over the content.
 * The corner radius follows the 12dp card corners. No glow.
 */
private fun Modifier.calmRing(color: Color, inset: Dp, stroke: Dp): Modifier = drawWithContent {
    drawContent()
    val i = inset.toPx()
    val s = stroke.toPx().coerceAtLeast(1.5f)
    val w = size.width - 2 * i
    val h = size.height - 2 * i
    if (w <= 0f || h <= 0f) return@drawWithContent
    val r = (12.dp.toPx() - i).coerceIn(4.dp.toPx(), minOf(w, h) / 2f)
    drawRoundRect(color, topLeft = Offset(i, i), size = Size(w, h), cornerRadius = CornerRadius(r, r), style = Stroke(s))
}

/**
 * Soft outer glow: a few growing copies of [shape] at low alpha, drawn behind the element.
 * Works on every Android version (no blur effects needed). Keep it before any clip in the chain.
 * Calm draws no glow: one soft sage ring just outside [shape] instead.
 */
fun Modifier.hudGlow(visible: Boolean, shape: Shape = HudShape, color: Color = McdColors.Accent, layers: Int = 4): Modifier =
    if (!visible) this else if (McdLook.calm) drawBehind {
        val g = 3.dp.toPx()
        val outline = shape.createOutline(Size(size.width + 2 * g, size.height + 2 * g), layoutDirection, this)
        translate(-g, -g) { drawOutline(outline, color.copy(alpha = 0.7f), style = Stroke(2.dp.toPx())) }
    } else drawBehind {
        val step = 2.5.dp.toPx()
        for (k in layers downTo 1) {
            val g = k * step
            val outline = shape.createOutline(Size(size.width + 2 * g, size.height + 2 * g), layoutDirection, this)
            translate(-g, -g) { drawOutline(outline, color.copy(alpha = 0.08f)) }
        }
    }

/** Horizontal scanlines over the content (every [spacing], very faint). None in Calm. */
fun Modifier.hudScanlines(spacing: Dp = 3.dp, color: Color = McdColors.AccentBright.copy(alpha = 0.04f)): Modifier =
    if (McdLook.calm) this else drawWithContent {
    drawContent()
    val step = spacing.toPx().coerceAtLeast(2f)
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += step
    }
}

// ---------------- Cards and panels ----------------

@Composable
fun hudCardBorder() = CardDefaults.border(
    border = Border(border = BorderStroke(1.dp, McdColors.Line), shape = HudShape),
    focusedBorder = Border(border = BorderStroke(if (McdLook.calm) 2.dp else 1.5.dp, McdColors.Accent), shape = HudShape),
)

/** Jarvis: a cyan glow under the focused card. Calm: none (the sage border is the focus mark). */
@Composable
fun hudCardGlow() = CardDefaults.glow(
    focusedGlow = if (McdLook.calm) Glow(elevationColor = Color.Transparent, elevation = 0.dp)
    else Glow(elevationColor = McdColors.Accent.copy(alpha = 0.55f), elevation = 12.dp),
)

/** Border for clickable tv-material Surfaces (rows, list items). */
@Composable
fun hudSurfaceBorder() = ClickableSurfaceDefaults.border(
    border = Border(border = BorderStroke(1.dp, McdColors.Line.copy(alpha = 0.7f)), shape = HudShape),
    focusedBorder = Border(border = BorderStroke(1.5.dp, McdColors.Accent), shape = HudShape),
)

/**
 * The HUD card: chamfered glass panel with a dim cyan hairline. Focus = glowing cyan border, soft
 * outer glow, four corner brackets and a 1.05 scale. [content] gets whether the card has focus.
 */
@Composable
fun HudCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = 1.05f,
    containerColor: Color = McdColors.Card,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = CardDefaults.shape(shape = HudShape),
        colors = CardDefaults.colors(containerColor = containerColor, focusedContainerColor = McdColors.Raised),
        border = hudCardBorder(),
        glow = hudCardGlow(),
        scale = CardDefaults.scale(focusedScale = focusedScale),
    ) {
        // Calm: the 2dp sage border already marks focus, so no ring inside it.
        Box(Modifier.fillMaxSize().hudBrackets(focused && !McdLook.calm)) {
            content(focused)
        }
    }
}

/** Static chamfered panel (settings sections, setup pages): glass fill, hairline border, dim corner brackets. */
@Composable
fun HudPanel(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .background(McdColors.Card, HudShape)
            .border(1.dp, McdColors.Line, HudShape)
            .hudBrackets(!McdLook.calm, McdColors.Accent.copy(alpha = 0.55f), inset = 4.dp, arm = 10.dp, stroke = 1.5.dp)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Amber "LIVE" badge. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE") {
    Text(
        text,
        style = hudLabelStyle(8.sp, McdColors.Ink),
        maxLines = 1,
        modifier = modifier
            .background(McdColors.LiveRed, HudShapeTiny)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------- Instruments ----------------

/** Loading indicator: two thin arcs turning in opposite directions. */
@Composable
fun HudSpinner(modifier: Modifier = Modifier, diameter: Dp = 24.dp, color: Color = McdColors.Accent) {
    val spin = rememberInfiniteTransition(label = "hud-spinner")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "hud-spinner-angle",
    )
    Canvas(modifier.size(diameter)) {
        val sw = 2.dp.toPx()
        val d = size.minDimension
        drawArc(
            color, startAngle = angle, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(sw / 2f, sw / 2f), size = Size(d - sw, d - sw), style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawArc(
            color.copy(alpha = 0.35f), startAngle = angle + 180f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(sw / 2f, sw / 2f), size = Size(d - sw, d - sw), style = Stroke(sw, cap = StrokeCap.Round),
        )
        val inset = d * 0.24f
        drawArc(
            McdColors.AccentBright, startAngle = -angle * 1.5f, sweepAngle = 150f, useCenter = false,
            topLeft = Offset(inset, inset), size = Size(d - 2 * inset, d - 2 * inset), style = Stroke(sw * 0.8f, cap = StrokeCap.Round),
        )
    }
}

/**
 * Circular arc gauge: a dim 270 degree track with the [fraction] (0..1) lit in [color],
 * [valueText] in the middle and a small [label] underneath.
 */
@Composable
fun HudGauge(fraction: Float, valueText: String, label: String, modifier: Modifier = Modifier, diameter: Dp = 38.dp, color: Color = McdColors.Accent) {
    val f = fraction.coerceIn(0f, 1f)
    val calm = McdLook.calm // Calm: no glow band under the lit arc.
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.5.dp.toPx()
                val d = size.minDimension
                val tl = Offset(sw / 2f, sw / 2f)
                val sz = Size(d - sw, d - sw)
                drawArc(McdColors.Line, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (f > 0f) {
                    if (!calm) drawArc(color.copy(alpha = 0.25f), startAngle = 135f, sweepAngle = 270f * f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw * 2.6f, cap = StrokeCap.Round))
                    drawArc(color, startAngle = 135f, sweepAngle = 270f * f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Text(valueText, style = hudDisplayStyle(10.sp, McdColors.White, 0.sp), maxLines = 1, textAlign = TextAlign.Center)
        }
        Text(label, style = hudLabelStyle(8.sp, color), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** HUD clock: "HH:MM" and "THU 08 OCT" in Orbitron, updated on each minute. */
@Composable
fun HudClock(modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(60_000L - t % 60_000L + 50L)
        }
    }
    val time = remember(now / 60_000L) { SimpleDateFormat("HH:mm", Locale.US).format(Date(now)) }
    val date = remember(now / 60_000L) { SimpleDateFormat("EEE dd MMM", Locale.US).format(Date(now)).uppercase(Locale.US) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, style = hudDisplayStyle(15.sp, McdColors.AccentBright, 1.sp), maxLines = 1)
        Text(date, style = hudLabelStyle(8.sp, McdColors.Muted), maxLines = 1)
    }
}
