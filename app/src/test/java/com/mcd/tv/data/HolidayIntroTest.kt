package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Holiday mode's intro ([HolidayIntro], [SnowField]): one sound only, and snow with depth and sway. */
class HolidayIntroTest {

    // ---------------------------------------------------------------- the sound

    @Test fun holidayModeOffIsTheOriginalSoundAtFullVolume() {
        assertEquals(IntroSound.ORIGINAL, HolidayIntro.start(holiday = false, trackBuilt = false))
        assertEquals(IntroSound.ORIGINAL, HolidayIntro.start(holiday = false, trackBuilt = true))
        assertEquals(1f, HolidayIntro.videoVolume(IntroSound.ORIGINAL), 0f)
    }

    @Test fun holidayModeOnPlaysOnlyTheHolidayTrack() {
        val s = HolidayIntro.start(holiday = true, trackBuilt = true)
        assertEquals(IntroSound.HOLIDAY_TRACK, s)
        assertEquals("the video's own Jarvis sound is muted, not layered", 0f, HolidayIntro.videoVolume(s), 0f)
    }

    @Test fun aMissingOrFailedTrackFallsBackToTheOriginalNeverSilence() {
        assertEquals(IntroSound.ORIGINAL, HolidayIntro.start(holiday = true, trackBuilt = false))
        assertEquals(IntroSound.ORIGINAL, HolidayIntro.afterTrackError())
        assertEquals(1f, HolidayIntro.videoVolume(HolidayIntro.afterTrackError()), 0f)
    }

    @Test fun snowFadesInAndOutWithThePicture() {
        assertEquals(0f, HolidayIntro.snowAlpha(0), 0f)
        assertEquals(0.5f, HolidayIntro.snowAlpha(HolidayIntro.SNOW_FADE_IN_MS / 2), 0.01f)
        assertEquals(1f, HolidayIntro.snowAlpha(HolidayIntro.HIT1_MS), 0f)
        assertEquals(1f, HolidayIntro.snowAlpha(HolidayIntro.HIT2_MS), 0f)
        assertEquals(0.5f, HolidayIntro.snowAlpha((HolidayIntro.FADE_FROM_MS + HolidayIntro.FADE_TO_MS) / 2), 0.01f)
        assertEquals(0f, HolidayIntro.snowAlpha(HolidayIntro.FADE_TO_MS), 0f)
        assertEquals(0f, HolidayIntro.snowAlpha(9_000), 0f)
    }

    @Test fun theTrackFileMatchesTheVideoBeats() {
        // The track's build script and the screen use the same beats: the video's flashes at 1.1 s and 4.3 s.
        val root = listOf(".", "..").map(::File).first { File(it, "tools/holiday").isDirectory }
        val script = File(root, "tools/holiday/make_holiday_intro.py").readText()
        assertTrue(script.contains("HIT1, HIT2 = 1.100, 4.300"))
        assertEquals(1_100L, HolidayIntro.HIT1_MS)
        assertEquals(4_300L, HolidayIntro.HIT2_MS)
        assertTrue("the track is the holiday_intro asset", script.contains("\"holiday_intro.ogg\""))
        assertTrue(File(root, "app/src/main/res/raw/holiday_intro.ogg").length() > 10_000)
        assertTrue("the old layered jingle is gone", !File(root, "app/src/main/res/raw/holiday_jingle.ogg").exists())
    }

    @Test fun theGreetingIsMerryChristmasNeverHappyHolidays() {
        assertEquals("MERRY CHRISTMAS", HolidayIntro.GREETING)
        val root = listOf(".", "..").map(::File).first { File(it, "tools/holiday").isDirectory }
        val main = File(root, "app/src/main")
        val intro = File(main, "java/com/mcd/tv/ui/IntroScreen.kt").readText()
        assertTrue("the intro shows the greeting", intro.contains("HolidayIntro.GREETING,"))
        val offenders = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .filter { Regex("happy\\s+holidays", RegexOption.IGNORE_CASE).containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("no \"Happy Holidays\" anywhere in the app: $offenders", offenders.isEmpty())
    }

    // ---------------------------------------------------------------- the snow

    private val snow = SnowField()
    private fun indices(layer: Int) = (0 until snow.size).filter { snow.layer[it] == layer }

    @Test fun flakeCountIsCapped() {
        assertTrue(snow.size in 60..SnowField.MAX_FLAKES)
        val greedy = SnowField(layers = listOf(SnowField.FAR.copy(count = 500), SnowField.NEAR))
        assertEquals(SnowField.MAX_FLAKES, greedy.size)
    }

    @Test fun twoLayersFarIsSmallerDimmerSlowerAndSofter() {
        val far = indices(0)
        val near = indices(1)
        assertTrue(far.isNotEmpty() && near.isNotEmpty())
        assertTrue("far flakes are smaller", far.maxOf { snow.radius[it] } < near.minOf { snow.radius[it] })
        assertTrue("far flakes are dimmer", far.maxOf { snow.alpha[it] } < near.minOf { snow.alpha[it] })
        assertTrue("far flakes are soft, near are crisp", SnowField.FAR.soft && !SnowField.NEAR.soft)
        // Slower: over 2 s a far flake falls less than any near flake.
        fun fall(i: Int): Float { val a = snow.y(i, 0f); val b = snow.y(i, 0.5f); return ((b - a) + 2f) % (1f + 2 * SnowField.MARGIN) }
        assertTrue("far flakes are slower", far.maxOf { fall(it) } < near.minOf { fall(it) })
    }

    @Test fun sizesSpeedsAndOpacityVary() {
        for (l in 0..1) {
            val ix = indices(l)
            assertTrue(ix.map { snow.radius[it] }.distinct().size > ix.size / 2)
            assertTrue(ix.map { snow.alpha[it] }.distinct().size > ix.size / 2)
        }
    }

    @Test fun flakesSwaySideToSideInsteadOfFallingInStraightLines() {
        // Remove the slow wind drift: what is left must go both ways (a wave), not one way (a rail).
        var swayed = 0
        for (i in 0 until snow.size) {
            val xs = (0..40).map { snow.x(i, it * 0.1f) }
            val steps = xs.zipWithNext { a, b -> var d = b - a; if (d > 0.5f) d -= 1f; if (d < -0.5f) d += 1f; d }
            if (steps.any { it > 0.0005f } && steps.any { it < -0.0005f }) swayed++
        }
        assertTrue("most flakes sway both ways ($swayed of ${snow.size})", swayed > snow.size * 0.7)
    }

    @Test fun positionsStayOnOrJustOffScreenAndWrap() {
        for (i in 0 until snow.size) for (k in 0..60) {
            val t = k * 0.37f
            assertTrue(snow.x(i, t) in 0f..1f)
            assertTrue(snow.y(i, t) in -SnowField.MARGIN..(1f + SnowField.MARGIN))
        }
    }

    @Test fun sameSeedSameSnow() {
        val other = SnowField()
        for (i in 0 until snow.size) {
            assertEquals(snow.x(i, 1.3f), other.x(i, 1.3f), 0f)
            assertEquals(snow.y(i, 1.3f), other.y(i, 1.3f), 0f)
        }
    }
}
