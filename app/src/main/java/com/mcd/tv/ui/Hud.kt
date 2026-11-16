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
import androidx.compose.ui.text.font.FontWeight
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

/** Chamfered card / panel shape: top-left and bottom-right corners cut. No round corners anywhere. */
val HudShape: Shape = CutCornerShape(topStart = 7.dp, bottomEnd = 7.dp)

/** Smaller chamfer for buttons, badges and chips. */
val HudShapeSmall: Shape = CutCornerShape(topStart = 5.dp, bottomEnd = 5.dp)

/** Tiny chamfer for little badges. */
val HudShapeTiny: Shape = CutCornerShape(topStart = 3.dp, bottomEnd = 3.dp)

/** Partly desaturates backdrops so the cyan tint on top reads as a duotone. */
val HudDuotone: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.35f) })

// ---------------- Focus decoration ----------------

/**
 * Focus reticle: two heavy L-shaped brackets on the top-right and bottom-left corners (the chamfered ones are
 * the other two), drawn over the content. A negative [inset] draws them outside the bounds (keep it before any
 * clip in the chain).
 */
fun Modifier.hudBrackets(
    visible: Boolean,
    color: Color = McdColors.AccentBright,
    inset: Dp = 4.dp,
    arm: Dp = 14.dp,
    stroke: Dp = 2.5.dp,
): Modifier = if (!visible) this else drawWithContent {
    drawContent()
    val i = inset.toPx()
    val a = arm.toPx()
    val s = stroke.toPx()
    val l = i
    val t = i
    val r = size.width - i
    val b = size.height - i
    drawLine(color, Offset(r, t), Offset(r - a, t), s, StrokeCap.Square)
    drawLine(color, Offset(r, t), Offset(r, t + a), s, StrokeCap.Square)
    drawLine(color, Offset(l, b), Offset(l + a, b), s, StrokeCap.Square)
    drawLine(color, Offset(l, b), Offset(l, b - a), s, StrokeCap.Square)
}

/**
 * Soft outer glow: a few growing copies of [shape] at low alpha, drawn behind the element.
 * Works on every Android version (no blur effects needed). Keep it before any clip in the chain.
 */
fun Modifier.hudGlow(visible: Boolean, shape: Shape = HudShape, color: Color = McdColors.Accent, layers: Int = 4): Modifier =
    if (!visible) this else drawBehind {
        val step = 2.5.dp.toPx()
        for (k in layers downTo 1) {
            val g = k * step
            val outline = shape.createOutline(Size(size.width + 2 * g, size.height + 2 * g), layoutDirection, this)
            translate(-g, -g) { drawOutline(outline, color.copy(alpha = 0.08f)) }
        }
    }

/** Horizontal scanlines over the content (every [spacing], very faint). */
fun Modifier.hudScanlines(spacing: Dp = 3.dp, color: Color = McdColors.AccentBright.copy(alpha = 0.04f)): Modifier = drawWithContent {
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
    focusedBorder = Border(border = BorderStroke(2.dp, McdColors.Accent), shape = HudShape),
)

@Composable
fun hudCardGlow() = CardDefaults.glow(
    focusedGlow = Glow(elevationColor = McdColors.Accent.copy(alpha = 0.45f), elevation = 14.dp),
)

/** Border for clickable tv-material Surfaces (rows, list items). */
@Composable
fun hudSurfaceBorder() = ClickableSurfaceDefaults.border(
    border = Border(border = BorderStroke(1.dp, McdColors.Line.copy(alpha = 0.8f)), shape = HudShape),
    focusedBorder = Border(border = BorderStroke(2.dp, McdColors.Accent), shape = HudShape),
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
        Box(Modifier.fillMaxSize().hudBrackets(focused)) {
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
            .hudBrackets(true, McdColors.Accent.copy(alpha = 0.35f), inset = 4.dp, arm = 10.dp, stroke = 1.5.dp)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Red "LIVE" badge with dark lettering (the only red in the app). */
@Composable
fun LiveBadge(modifier: Modifier = Modifier, text: String = "LIVE") {
    Text(
        text,
        style = hudLabelStyle(8.sp, McdColors.Ink).copy(fontWeight = FontWeight.Bold),
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
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 2.5.dp.toPx()
                val d = size.minDimension
                val tl = Offset(sw / 2f, sw / 2f)
                val sz = Size(d - sw, d - sw)
                drawArc(McdColors.Line, startAngle = 135f, sweepAngle = 270f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                if (f > 0f) {
                    drawArc(color.copy(alpha = 0.25f), startAngle = 135f, sweepAngle = 270f * f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw * 2.6f, cap = StrokeCap.Round))
                    drawArc(color, startAngle = 135f, sweepAngle = 270f * f, useCenter = false, topLeft = tl, size = sz, style = Stroke(sw, cap = StrokeCap.Round))
                }
            }
            Text(valueText, style = hudDisplayStyle(10.sp, McdColors.White, 0.sp), maxLines = 1, textAlign = TextAlign.Center)
        }
        Text(label, style = hudLabelStyle(8.sp, color), maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Clock: "7:42 PM" and "THU 08 OCT", updated on each minute. */
@Composable
fun HudClock(modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(60_000L - t % 60_000L + 50L)
        }
    }
    val time = remember(now / 60_000L) { SimpleDateFormat("h:mm a", Locale.US).format(Date(now)) }
    val date = remember(now / 60_000L) { SimpleDateFormat("EEE dd MMM", Locale.US).format(Date(now)).uppercase(Locale.US) }
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(time, style = hudDisplayStyle(15.sp, McdColors.AccentBright, 1.sp), maxLines = 1)
        Text(date, style = hudLabelStyle(8.sp, McdColors.Muted), maxLines = 1)
    }
}
