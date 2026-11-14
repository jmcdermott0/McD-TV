package com.mcd.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.mcd.tv.data.Look
import kotlin.math.cos
import kotlin.math.sin

/**
 * One look's colors. [JarvisPalette] is the cyan HUD (default); [CalmPalette] is the soft "dusk lake" look,
 * with the same values as the web app's Calm theme.
 */
@Immutable
data class McdPalette(
    val navy: Color,
    val navyLight: Color,
    val card: Color,
    val line: Color,
    val accent: Color,
    val accentBright: Color,
    val accentDeep: Color,
    val white: Color,
    val muted: Color,
    val ink: Color,
    val amber: Color,
    val coral: Color,
    val bgTop: Color,
    val bgMid: Color,
)

val JarvisPalette = McdPalette(
    navy = Color(0xFF02060A),
    navyLight = Color(0xFF0A2230),
    card = Color(0xD9061722),
    line = Color(0xFF0E6A7A),
    accent = Color(0xFF00E5FF),
    accentBright = Color(0xFF7DF9FF),
    accentDeep = Color(0xFF0B5F6E),
    white = Color(0xFFEAFBFF),
    muted = Color(0xFF8FB8C4),
    ink = Color(0xFF02060A),
    amber = Color(0xFFFFB300),
    coral = Color(0xFFFF4D6D),
    bgTop = Color(0xFF031018),
    bgMid = Color(0xFF02080D),
)

val CalmPalette = McdPalette(
    navy = Color(0xFF0E1620),
    navyLight = Color(0xFF243445),
    card = Color(0xD11C2836),
    line = Color(0xFF3A4D5E),
    accent = Color(0xFF9CC5B8),
    accentBright = Color(0xFFC9E4DA),
    accentDeep = Color(0xFF5E8479),
    white = Color(0xFFE8EEF2),
    muted = Color(0xFF9AABB8),
    ink = Color(0xFF0E1620),
    amber = Color(0xFFE6C79C),
    coral = Color(0xFFE39A9A),
    bgTop = Color(0xFF1A2838),
    bgMid = Color(0xFF131E2A),
)

/**
 * The active look (Settings > This TV > Look). MainActivity sets it from Prefs before the first frame.
 * It is Compose state, so every screen that reads McdColors, the shapes or the text styles redraws on a switch.
 */
object McdLook {
    private var state by mutableStateOf(Look.DEFAULT)

    var look: Look
        get() = state
        set(v) { state = v }

    val calm: Boolean get() = state == Look.CALM

    val palette: McdPalette get() = if (state == Look.CALM) CalmPalette else JarvisPalette
}

/**
 * Jarvis palette: an original holographic heads-up display. Near-black glass, cyan light, amber alerts.
 * Property names are kept from the first theme so every screen picks up the new look:
 * Red / RedBright / RedDark map to the accents, Navy / NavyLight to the dark glass.
 * Each one reads the active look ([McdLook]), so the Calm look needs no screen changes.
 */
object McdColors {
    val Navy: Color get() = McdLook.palette.navy           // page background (darkest)
    val NavyLight: Color get() = McdLook.palette.navyLight // raised surfaces, focused rows
    val Card: Color get() = McdLook.palette.card           // panels and cards at rest
    val Line: Color get() = McdLook.palette.line           // hairlines and outlines
    val Red: Color get() = McdLook.palette.accent          // primary accent (name kept for compatibility)
    val RedBright: Color get() = McdLook.palette.accentBright // bright accent: focus rings
    val RedDark: Color get() = McdLook.palette.accentDeep  // deep accent
    val Cyan: Color get() = McdLook.palette.accent
    val White: Color get() = McdLook.palette.white         // main text
    val Muted: Color get() = McdLook.palette.muted         // secondary text

    /** Accent aliases for new code. */
    val Accent: Color get() = Red
    val AccentBright: Color get() = RedBright
    val Raised: Color get() = NavyLight
    val Dim: Color get() = Line

    /** Text on accent fills. */
    val Ink: Color get() = McdLook.palette.ink

    /** Alerts and live badges. */
    val Amber: Color get() = McdLook.palette.amber
    val Coral: Color get() = McdLook.palette.coral

    /** Live badges: warning amber (the name is kept from the old red badge). */
    val LiveRed: Color get() = Amber
}

private val JarvisBackground = Brush.verticalGradient(
    0f to JarvisPalette.bgTop,
    0.55f to JarvisPalette.bgMid,
    1f to JarvisPalette.navy,
)

private val CalmBackground = Brush.verticalGradient(
    0f to CalmPalette.bgTop,
    0.55f to CalmPalette.bgMid,
    1f to CalmPalette.navy,
)

/** Base gradient for every page in the active look. */
val ScreenBackground: Brush get() = if (McdLook.calm) CalmBackground else JarvisBackground

/**
 * The full page background: [ScreenBackground] plus a soft vignette toward the edges. Jarvis adds a very
 * faint cyan grid (a line every 40dp at about 4%); Calm adds a faint moonlight glow near the top and no grid.
 * Use instead of `.background(ScreenBackground)`.
 */
fun Modifier.hudBackground(): Modifier = this
    .background(ScreenBackground)
    .drawBehind {
        val calm = McdLook.calm
        if (calm) {
            val glow = size.width * 0.6f
            if (glow > 0f) {
                drawRect(
                    Brush.radialGradient(
                        0f to CalmPalette.accentBright.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                        center = Offset(size.width / 2f, size.height * 0.05f),
                        radius = glow,
                    ),
                )
            }
        } else {
            val step = 40.dp.toPx()
            val line = McdColors.Accent.copy(alpha = 0.04f)
            val stroke = 1f
            var x = step
            while (x < size.width) {
                drawLine(line, Offset(x, 0f), Offset(x, size.height), stroke)
                x += step
            }
            var y = step
            while (y < size.height) {
                drawLine(line, Offset(0f, y), Offset(size.width, y), stroke)
                y += step
            }
        }
        val radius = maxOf(size.width, size.height) * 0.75f
        if (radius > 0f) {
            drawRect(
                Brush.radialGradient(
                    0f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (calm) 0.35f else 0.55f),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = radius,
                ),
            )
        }
    }

/** Orbitron: wide techno display face for headings, the wordmark, tab labels and big numbers (SIL OFL). */
private val Orbitron = FontFamily(
    Font(R.font.hud_display_bold, FontWeight.Bold),
    Font(R.font.hud_display_medium, FontWeight.Medium),
)

/** Rajdhani: condensed tech sans for body text, cards and buttons (SIL OFL). */
private val Rajdhani = FontFamily(
    Font(R.font.hud_text_medium, FontWeight.Medium),
    Font(R.font.hud_text_semibold, FontWeight.SemiBold),
    Font(R.font.hud_text_bold, FontWeight.Bold),
)

/** Display face: Orbitron in Jarvis, the platform sans in Calm. */
val HudDisplay: FontFamily get() = if (McdLook.calm) FontFamily.SansSerif else Orbitron

/** Body face: Rajdhani in Jarvis, the platform sans in Calm. */
val HudText: FontFamily get() = if (McdLook.calm) FontFamily.SansSerif else Rajdhani

/**
 * Headlines, section titles, card titles: Orbitron Bold. Orbitron is wide, so sizes are scaled down
 * (big titles top out at 30sp) and small sizes get extra letter spacing.
 */
fun broadcastStyle(size: TextUnit, color: Color = McdColors.White): TextStyle {
    val v = if (size.isSp) size.value else 16f
    val px = if (v >= 20f) minOf(v * 0.85f, 30f) else v * 0.92f
    if (McdLook.calm) {
        // Calm: the platform sans, lighter on big headings, no wide tracking.
        return TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = if (px >= 20f) FontWeight.Normal else FontWeight.Medium,
            fontSize = px.sp,
            color = color,
            letterSpacing = if (px < 20f) 0.3.sp else 0.sp,
        )
    }
    return TextStyle(
        fontFamily = Orbitron,
        fontWeight = FontWeight.Bold,
        fontSize = px.sp,
        color = color,
        letterSpacing = if (px < 20f) 1.2.sp else 0.5.sp,
    )
}

/** Orbitron at the exact size given (no cap): big numbers such as codes and scores. Calm: the sans, half the tracking. */
fun hudDisplayStyle(size: TextUnit, color: Color = McdColors.White, spacing: TextUnit = 1.5.sp): TextStyle =
    if (McdLook.calm) {
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = size, color = color, letterSpacing = spacing * 0.5f)
    } else {
        TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = size, color = color, letterSpacing = spacing)
    }

/** Small uppercase HUD label: Orbitron Medium, wide tracking. Calm: the sans with light tracking. */
fun hudLabelStyle(size: TextUnit = 10.sp, color: Color = McdColors.Accent): TextStyle =
    if (McdLook.calm) {
        TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = size, color = color, letterSpacing = 0.6.sp)
    } else {
        TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Medium, fontSize = size, color = color, letterSpacing = 1.5.sp)
    }

/** Every Material text style in the body face (Rajdhani, or the sans in Calm), so plain Text() calls pick up the HUD body face. */
private fun hudTypography(face: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.hud() = copy(fontFamily = face)
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
    // Reading the look here makes the whole theme follow a switch in Settings.
    val face = HudText
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = McdColors.Accent,
            onPrimary = McdColors.Ink,
            background = McdColors.Navy,
            onBackground = McdColors.White,
            surface = McdColors.Card,
            onSurface = McdColors.White,
        ),
        typography = remember(face) { hudTypography(face) },
    ) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = face))) {
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
    val calm = McdLook.calm
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val sw = (r * 0.09f).coerceAtLeast(1f)
        if (calm) {
            // Calm: a quiet moon. A thin ring and a soft filled center, no ticks and no glow.
            drawCircle(color.copy(alpha = 0.8f), radius = r * 0.78f, center = c, style = Stroke(sw * 0.8f))
            drawCircle(McdColors.AccentBright.copy(alpha = 0.85f), radius = r * 0.42f, center = c)
            return@Canvas
        }
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

/** The "JARVIS" wordmark for headers: ring glyph plus Orbitron lettering with a cyan glow. */
@Composable
fun McdLogo(modifier: Modifier = Modifier, scale: Float = 1f) {
    val calm = McdLook.calm
    val style = if (calm) {
        TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = (26 * scale).sp,
            letterSpacing = (1 * scale).sp,
            color = McdColors.White,
        )
    } else TextStyle(
        fontFamily = HudDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = (26 * scale).sp,
        letterSpacing = (3 * scale).sp,
        color = McdColors.White,
        shadow = Shadow(color = McdColors.Accent.copy(alpha = 0.85f), offset = Offset.Zero, blurRadius = 18f * scale),
    )
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((8 * scale).dp)) {
        HudRing((30 * scale).dp)
        Text(if (calm) "Jarvis" else "JARVIS", style = style, maxLines = 1)
    }
}
