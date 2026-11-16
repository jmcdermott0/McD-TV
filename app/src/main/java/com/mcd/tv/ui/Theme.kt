package com.mcd.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import com.mcd.tv.R
import kotlin.math.cos
import kotlin.math.sin

/**
 * Jarvis palette, "Night Ops": near-black ground, hard chamfered edges, one cold arc-cyan accent for focus and
 * progress, red only for live. Property names are kept from the first theme so every screen picks up the look:
 * Red / RedBright / RedDark map to the cyan accents, Navy / NavyLight to the dark ground.
 */
object McdColors {
    val Navy = Color(0xFF050607)       // page background (darkest)
    val NavyLight = Color(0xFF12151A)  // raised surfaces, focused rows
    val Card = Color(0xF00B0D10)       // panels and cards at rest (#0B0D10 at 94%)
    val Line = Color(0xFF232932)       // hairlines and outlines
    val Red = Color(0xFF2EE6FF)        // primary accent: arc cyan (name kept for compatibility)
    val RedBright = Color(0xFF8FF3FF)  // bright accent: focus brackets, glows
    val RedDark = Color(0xFF0E4A55)    // deep accent
    val Cyan = Color(0xFF2EE6FF)
    val White = Color(0xFFEEF1F5)      // primary text
    val Muted = Color(0xFF8B95A5)      // labels and secondary text

    /** Accent aliases for new code. */
    val Accent = Red
    val AccentBright = RedBright
    val Raised = NavyLight
    val Dim = Line

    /** Text on cyan and red fills. */
    val Ink = Color(0xFF050607)

    /** Favorites, warnings and the provider stream. */
    val Amber = Color(0xFFFFB020)
    val Coral = Color(0xFFFF5468)

    /** Live badges and live clocks: the only red in the app. */
    val LiveRed = Color(0xFFFF3045)
}

/** Base gradient for every page: a near-black ground, a touch lighter at the top. */
val ScreenBackground = Brush.verticalGradient(
    0f to Color(0xFF0A0C10),
    0.5f to Color(0xFF06080A),
    1f to McdColors.Navy,
)

/**
 * The full page background: [ScreenBackground], a faint diagonal hatch (every 12dp at 2.5%) and a vignette toward
 * the edges. Use instead of `.background(ScreenBackground)`.
 */
fun Modifier.hudBackground(): Modifier = this
    .background(ScreenBackground)
    .drawBehind {
        val step = 12.dp.toPx()
        val line = Color.White.copy(alpha = 0.025f)
        var x = -size.height
        while (x < size.width) {
            drawLine(line, Offset(x, size.height), Offset(x + size.height, 0f), 1f)
            x += step
        }
        val radius = maxOf(size.width, size.height) * 0.75f
        if (radius > 0f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.55f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.65f),
                    center = Offset(size.width / 2f, size.height * 0.38f),
                    radius = radius,
                ),
            )
        }
    }

/** Saira Condensed: tall, narrow display face for headings, the wordmark, tab labels and big numbers (SIL OFL). */
val HudDisplay = FontFamily(
    Font(R.font.hud_display_bold, FontWeight.Bold),
    Font(R.font.hud_display_medium, FontWeight.Medium),
)

/** Barlow Semi Condensed: clean, sharp body face for text, cards and buttons (SIL OFL). */
val HudText = FontFamily(
    Font(R.font.hud_text_medium, FontWeight.Medium),
    Font(R.font.hud_text_semibold, FontWeight.SemiBold),
    Font(R.font.hud_text_bold, FontWeight.Bold),
)

/**
 * Headlines, section titles, card titles: Saira Condensed Bold, about the old height (fixed-height rows keep fitting)
 * but far narrower, so long titles fit without cutting. Big titles top out at 34sp.
 */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White): TextStyle {
    val v = if (size.isSp) size.value else 16f
    val px = if (v >= 20f) minOf(v * 0.98f, 34f) else v
    return TextStyle(
        fontFamily = HudDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = px.sp,
        color = color,
        letterSpacing = if (px < 20f) 0.6.sp else 0.3.sp,
    )
}

/** Saira Condensed at the exact size given (no cap): big numbers such as codes, scores and odds. */
fun hudDisplayStyle(size: TextUnit, color: Color = McdColors.White, spacing: TextUnit = 1.5.sp) = TextStyle(
    fontFamily = HudDisplay,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    color = color,
    letterSpacing = spacing,
)

/** Small uppercase label: Saira Condensed SemiBold, wide tracking. */
fun hudLabelStyle(size: TextUnit = 10.sp, color: Color = McdColors.Accent) = TextStyle(
    fontFamily = HudDisplay,
    fontWeight = FontWeight.Medium,
    fontSize = (if (size.isSp) size.value * 1.1f else 11f).sp,
    color = color,
    letterSpacing = 1.6.sp,
)

/** Every Material text style in Barlow Semi Condensed, so plain Text() calls pick up the body face. */
private fun hudTypography(): Typography {
    val b = Typography()
    fun TextStyle.hud() = copy(fontFamily = HudText)
    return Typography(
        displayLarge = b.displayLarge.hud(),
        displayMedium = b.displayMedium.hud(),
        displaySmall = b.displaySmall.hud(),
        headlineLarge = b.headlineLarge.hud(),
        headlineMedium = b.headlineMedium.hud(),
        headlineSmall = b.headlineSmall.hud(),
        titleLarge = b.titleLarge.hud(),
        titleMedium = b.titleMedium.hud(),
        titleSmall = b.titleSmall.hud(),
        bodyLarge = b.bodyLarge.hud(),
        bodyMedium = b.bodyMedium.hud(),
        bodySmall = b.bodySmall.hud(),
        labelLarge = b.labelLarge.hud(),
        labelMedium = b.labelMedium.hud(),
        labelSmall = b.labelSmall.hud(),
    )
}

@Composable
fun McdTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McdColors.Accent,
            onPrimary = McdColors.Ink,
            background = McdColors.Navy,
            onBackground = McdColors.White,
            surface = McdColors.Card,
            onSurface = McdColors.White,
        ),
        typography = hudTypography(),
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = HudText))) {
            content()
        }
    }
}

/**
 * HUD ring glyph: a thin ring with 12 short ticks and a small inner dot (original mark, used beside
 * the wordmark and in the launcher art).
 */
@Composable
fun HudRing(diameter: Dp, modifier: Modifier = Modifier, color: Color = McdColors.Accent) {
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val sw = (r * 0.09f).coerceAtLeast(1f)
        // Soft glow behind the ring.
        drawCircle(color.copy(alpha = 0.18f), radius = r * 0.72f, center = c, style = Stroke(sw * 3.5f))
        drawCircle(color, radius = r * 0.72f, center = c, style = Stroke(sw))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val inner = r * (if (i % 3 == 0) 0.80f else 0.84f)
            val outer = r * 0.98f
            val dx = cos(a).toFloat()
            val dy = sin(a).toFloat()
            drawLine(color, Offset(c.x + dx * inner, c.y + dy * inner), Offset(c.x + dx * outer, c.y + dy * outer), sw)
        }
        drawCircle(color.copy(alpha = 0.25f), radius = r * 0.26f, center = c)
        drawCircle(McdColors.AccentBright, radius = r * 0.13f, center = c)
    }
}

/**
 * The Jarvis mark: a diamond outline with a solid diamond inside (original mark, also the intro's emblem).
 */
@Composable
fun JarvisMark(size: Dp, modifier: Modifier = Modifier, color: Color = McdColors.Accent) {
    Canvas(modifier.size(size)) {
        val w = this.size.minDimension
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = w / 2f * 0.92f
        val sw = (w * 0.085f).coerceAtLeast(1.5f)
        val outer = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x, c.y - r); lineTo(c.x + r, c.y); lineTo(c.x, c.y + r); lineTo(c.x - r, c.y); close()
        }
        drawPath(outer, color.copy(alpha = 0.22f), style = Stroke(sw * 3f))
        drawPath(outer, color, style = Stroke(sw))
        val ir = r * 0.42f
        val inner = androidx.compose.ui.graphics.Path().apply {
            moveTo(c.x, c.y - ir); lineTo(c.x + ir, c.y); lineTo(c.x, c.y + ir); lineTo(c.x - ir, c.y); close()
        }
        drawPath(inner, color)
    }
}

/** The "JARVIS" wordmark for headers: the diamond mark plus wide-tracked Saira Condensed lettering. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val style = TextStyle(
        fontFamily = HudDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = (30 * scale).sp,
        letterSpacing = (6 * scale).sp,
        color = McdColors.White,
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * scale).dp)) {
        JarvisMark((26 * scale).dp)
        Text("JARVIS", style = style, maxLines = 1)
    }
}
