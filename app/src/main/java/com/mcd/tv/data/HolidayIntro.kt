package com.mcd.tv.data

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

// Holiday mode's intro (issue #47): what it sounds like and how its snow falls. Pure Kotlin, unit tested in
// HolidayIntroTest. The screen is ui/IntroScreen.kt; the track is res/raw/holiday_intro.ogg, built by
// tools/holiday/make_holiday_intro.py.

/** The one sound the intro plays. Never both. */
enum class IntroSound {
    /** The intro video's own sound (Jarvis sting and voiceover). Holiday mode off, or the holiday track failed. */
    ORIGINAL,

    /** The holiday track only. The video plays muted. */
    HOLIDAY_TRACK,
}

object HolidayIntro {
    /** The intro video's beats (measured from its frames, 30 fps). The track and the snow follow them. */
    const val HIT1_MS = 1_100L
    const val HIT2_MS = 4_300L
    const val FADE_FROM_MS = 4_900L
    const val FADE_TO_MS = 5_500L
    const val SNOW_FADE_IN_MS = 500L

    /** The intro's greeting, at the second flash. John's rule: every holiday greeting reads "Merry Christmas" (HolidayIntroTest checks the app). */
    const val GREETING = "MERRY CHRISTMAS"

    /** Where the intro starts: Holiday mode off is always the original. On, the holiday track if it was built. */
    fun start(holiday: Boolean, trackBuilt: Boolean): IntroSound =
        if (holiday && trackBuilt) IntroSound.HOLIDAY_TRACK else IntroSound.ORIGINAL

    /** The holiday track failed to play: fall back to the video's own sound, so the intro is never silent. */
    fun afterTrackError(): IntroSound = IntroSound.ORIGINAL

    /** The intro video's volume for a sound: muted while the holiday track plays, untouched otherwise. */
    fun videoVolume(sound: IntroSound): Float = if (sound == IntroSound.HOLIDAY_TRACK) 0f else 1f

    /** The snow's overall opacity at a point of the video: fades in at the start and out with the picture. */
    fun snowAlpha(videoMs: Long): Float = when {
        videoMs <= 0L -> 0f
        videoMs < SNOW_FADE_IN_MS -> videoMs.toFloat() / SNOW_FADE_IN_MS
        videoMs <= FADE_FROM_MS -> 1f
        videoMs >= FADE_TO_MS -> 0f
        else -> (FADE_TO_MS - videoMs).toFloat() / (FADE_TO_MS - FADE_FROM_MS)
    }
}

/**
 * One depth layer of snow. Sizes in dp (radius), speeds in screen heights a second, sway in screen widths.
 * [soft]: drawn with the wide, blurred sprite (far flakes); otherwise with the crisper one.
 */
data class SnowLayer(
    val count: Int,
    val minRadius: Float,
    val maxRadius: Float,
    val minSpeed: Float,
    val maxSpeed: Float,
    val minAlpha: Float,
    val maxAlpha: Float,
    val minSway: Float,
    val maxSway: Float,
    val soft: Boolean,
)

/**
 * The snowfall: a fixed, capped set of flakes in flat arrays (no objects per frame), on two depth layers.
 * Far flakes are small, dim, slow and soft; near flakes are large, bright and faster, so the snow has depth.
 * Each flake sways side to side on its own sine wave while it falls, and wraps from the bottom to the top
 * off screen, so none pop in or out. Positions are 0..1 of the screen; the screen scales them.
 */
class SnowField(seed: Long = 1225L, val layers: List<SnowLayer> = DEFAULT_LAYERS) {
    val size: Int = layers.sumOf { it.count }.coerceAtMost(MAX_FLAKES)
    val layer = IntArray(size)
    val radius = FloatArray(size)
    val alpha = FloatArray(size)
    private val x0 = FloatArray(size)
    private val y0 = FloatArray(size)
    private val speed = FloatArray(size)
    private val sway = FloatArray(size)
    private val swayHz = FloatArray(size)
    private val phase = FloatArray(size)

    init {
        val rnd = java.util.Random(seed)
        fun between(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        var i = 0
        layers.forEachIndexed { li, l ->
            repeat(l.count) {
                if (i >= size) return@repeat
                // One draw per flake ties size, speed and brightness together inside a layer: bigger is nearer.
                val depth = rnd.nextFloat()
                layer[i] = li
                radius[i] = l.minRadius + depth * (l.maxRadius - l.minRadius)
                speed[i] = l.minSpeed + (0.25f * rnd.nextFloat() + 0.75f * depth) * (l.maxSpeed - l.minSpeed)
                alpha[i] = l.minAlpha + (0.3f * rnd.nextFloat() + 0.7f * depth) * (l.maxAlpha - l.minAlpha)
                sway[i] = between(l.minSway, l.maxSway)
                swayHz[i] = between(0.18f, 0.42f)
                phase[i] = between(0f, (2 * PI).toFloat())
                x0[i] = rnd.nextFloat()
                y0[i] = rnd.nextFloat()
                i++
            }
        }
    }

    /** Flake [i]'s horizontal position at [t] seconds, 0..1 (wrapped). */
    fun x(i: Int, t: Float): Float {
        val drift = WIND * speed[i] * t
        return frac(x0[i] + drift + sway[i] * sin(phase[i] + (2 * PI).toFloat() * swayHz[i] * t))
    }

    /** Flake [i]'s vertical position at [t] seconds: from -MARGIN (just above) to 1 + MARGIN (just below). */
    fun y(i: Int, t: Float): Float = frac(y0[i] + speed[i] * t) * (1f + 2 * MARGIN) - MARGIN

    companion object {
        /** The cap, whatever the layers ask for: keeps the draw cheap on a Fire TV Stick. */
        const val MAX_FLAKES = 120

        /** How far above and below the screen a flake wraps, so it never pops in or out. */
        const val MARGIN = 0.03f

        /** A light breeze: flakes drift a little to the right as they fall (nearer ones more). */
        const val WIND = 0.12f

        val FAR = SnowLayer(
            count = 70, minRadius = 1.4f, maxRadius = 2.6f, minSpeed = 0.045f, maxSpeed = 0.075f,
            minAlpha = 0.28f, maxAlpha = 0.50f, minSway = 0.004f, maxSway = 0.010f, soft = true,
        )
        val NEAR = SnowLayer(
            count = 34, minRadius = 3.2f, maxRadius = 6.0f, minSpeed = 0.11f, maxSpeed = 0.19f,
            minAlpha = 0.70f, maxAlpha = 0.95f, minSway = 0.010f, maxSway = 0.022f, soft = false,
        )
        val DEFAULT_LAYERS = listOf(FAR, NEAR)

        private fun frac(v: Float): Float = v - floor(v)
    }
}
