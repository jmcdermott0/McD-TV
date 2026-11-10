package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Step down from 4K: struggle detection (same thresholds as the web app), sibling channel and 1080p source picks. */
class QualityFallbackTest {

    // ---------------------------------------------------------------- StallWatch

    // Most StallWatch rules are in tools/qf-vectors.json (QualityVectorsTest). These cover the player glue.

    /** A watch that started at 0 and has been playing since 1 s. */
    private fun playing(): StallWatch = StallWatch(0).also { it.onReady(1_000) }

    private fun stall(w: StallWatch, at: Long, lengthMs: Long) {
        w.onBuffering(at)
        w.onReady(at + lengthMs)
    }

    @Test
    fun threeShortStallsInAMinuteFire() {
        val w = playing()
        stall(w, 20_000, 1_000)
        assertEquals("", w.check(21_000))
        stall(w, 40_000, 1_000)
        assertEquals("", w.check(41_000))
        w.onBuffering(60_000)
        assertEquals("stalls", w.check(60_000))
        assertEquals("stalls", w.reason)
    }

    @Test
    fun aLongStallWinsOverTheCount() {
        val w = playing()
        stall(w, 20_000, 1_000)
        stall(w, 30_000, 1_000)
        w.onBuffering(40_000)
        assertEquals("long_stall", w.check(48_000)) // 3 stalls too, but the long one is named first
    }

    @Test
    fun bufferingBeforePlaybackStartedIsNotAStall() {
        val w = StallWatch(0)
        w.onBuffering(12_000)
        assertEquals("", w.check(30_000))
    }

    @Test
    fun bufferingAfterASeekIsNotAStall() {
        val w = playing()
        w.onSeek()
        w.onBuffering(20_000)
        assertEquals("", w.check(30_000))
        w.onReady(30_000)
        w.onBuffering(40_000) // a real stall again
        assertEquals("long_stall", w.check(48_000))
    }

    @Test
    fun aSeekThatNeverBufferedDoesNotHideTheNextStall() {
        val w = playing()
        w.onSeek(20_000) // a seek inside the buffer: no buffering, no READY after it
        w.onBuffering(20_500) // the seek's own buffering, if any, is still ignored
        assertEquals("", w.check(29_000))
        w.onBuffering(40_000) // a real stall, long after the seek
        assertEquals("long_stall", w.check(48_000))
    }

    @Test
    fun aSeekDuringAStallCancelsIt() {
        val w = playing()
        w.onBuffering(20_000)
        w.onSeek()
        assertEquals("", w.check(30_000))
    }

    @Test
    fun restartIgnoresTheNextTenSecondsAndRearms() {
        val w = playing()
        w.onBuffering(20_000)
        assertEquals("long_stall", w.check(28_000))
        assertEquals("", w.check(29_000))
        w.restart(30_000)
        w.onReady(31_000)
        w.onBuffering(35_000)
        assertEquals("", w.check(44_000)) // a startup stall
        w.onReady(44_000)
        w.onBuffering(50_000)
        assertEquals("long_stall", w.check(58_000))
        assertTrue(w.fired)
    }

    @Test
    fun samplesCountFromTheStart() {
        val w = StallWatch(0)
        w.sample(0, 5e6, 10e6)
        w.sample(3_000, 5e6, 10e6)
        assertEquals("very_slow", w.check(3_000))
    }

    // ---------------------------------------------------------------- estimator, bitrate, up-front memory, log

    @Test
    fun estimatorIsZeroUntilASample() {
        val e = ThroughputEstimator()
        assertEquals(0.0, e.estimate(), 0.0)
        e.sample(2_000, 2_500_000)
        assertEquals(10_000_000.0, e.estimate(), 1.0)
    }

    @Test
    fun bitrateMeterIsBytesOverMediaTimeAndRestartsOnANewHeight() {
        val m = BitrateMeter()
        assertEquals(0.0, m.bps(), 0.0)
        m.add(2160, 12_000_000, 6_000) // 16 Mbps
        m.add(2160, 15_000_000, 6_000) // 20 Mbps
        assertEquals(18_000_000.0, m.bps(), 1.0)
        m.add(1080, 3_000_000, 4_000)
        assertEquals(6_000_000.0, m.bps(), 1.0)
        m.add(1080, 1_000, 0) // no media time: ignored
        assertEquals(6_000_000.0, m.bps(), 1.0)
    }

    @Test
    fun liveTvHealthSessionsSeedAStepDown() {
        val now = 10L * 86_400_000L
        assertEquals(2160, QualityFallback.heightOf("3840x2160 60 fps hevc"))
        assertEquals(0, QualityFallback.heightOf(""))
        assertTrue(QualityFallback.frozeIn4k(now, now - 3_600_000, "3840x2160 60 fps hevc", 3, 2_000))
        assertTrue(QualityFallback.frozeIn4k(now, now - 3_600_000, "3840x2160 hevc", 1, 12_000))
        assertFalse(QualityFallback.frozeIn4k(now, now - 3_600_000, "3840x2160 hevc", 2, 11_000))
        assertFalse(QualityFallback.frozeIn4k(now, now - 3_600_000, "1920x1080 60 fps avc", 5, 30_000))
        assertFalse(QualityFallback.frozeIn4k(now, now - 8L * 86_400_000L, "3840x2160 hevc", 5, 30_000))
    }

    @Test
    fun qualityMemoryRoundTrips() {
        val st = QualityMemory.State(18_400_000.4, 1_000L)
        st.ch["espn"] = QualityMemory.Channel(20_000_000.0, 5_000L, 0L)
        st.ch["fox"] = QualityMemory.Channel(0.0, 0L, 7_000L)
        val back = QualityMemory.decode(QualityMemory.encode(st))
        assertEquals(18_400_000.0, back.est, 0.0)
        assertEquals(1_000L, back.estAt)
        assertEquals(20_000_000.0, back.ch["espn"]!!.br, 0.0)
        assertEquals(5_000L, back.ch["espn"]!!.down)
        assertEquals(7_000L, back.ch["fox"]!!.clr)
        assertEquals(0.0, QualityMemory.decode("not json").est, 0.0)
    }

    @Test
    fun qualityLogKeepsTheNewest40() {
        var list = emptyList<QualityLog.Entry>()
        for (i in 1..45) list = QualityLog.push(list, QualityLog.Entry(i.toLong(), "ESPN", "a", "b", "slow", 1e6))
        assertEquals(40, list.size)
        assertEquals(45L, list.first().at)
        val back = QualityLog.decode(QualityLog.encode(list))
        assertEquals(list, back)
        assertEquals("3840x2160 18.4 Mbps", QualityLog.rendition(3840, 2160, 18_400_000.0))
        assertEquals("1920x1080", QualityLog.rendition(1920, 1080, 0.0))
        assertTrue(QualityLog.words("very_slow").isNotEmpty())
    }

    // ---------------------------------------------------------------- 4K and the track cap

    @Test
    fun fourKByHeightOrWholeWordName() {
        assertTrue(QualityFallback.is4k(2160, "ESPN"))
        assertTrue(QualityFallback.is4k(1600, ""))
        assertFalse(QualityFallback.is4k(1080, "ESPN FHD"))
        assertTrue(QualityFallback.is4k(0, "US| ESPN 4K"))
        assertTrue(QualityFallback.is4k(0, "Sky Sports UHD"))
        assertTrue(QualityFallback.is4k(0, "NBC (2160p)"))
        assertTrue(QualityFallback.is4k(0, "Movie.2160.WEB"))
        assertFalse(QualityFallback.is4k(0, "UHDTV Network"))
        assertFalse(QualityFallback.is4k(0, "Channel 24K"))
        assertFalse(QualityFallback.is4k(0, "Fox 21600"))
    }

    @Test
    fun capOnlyWithALowerTrack() {
        assertTrue(QualityFallback.canCapTo1080(listOf(2160, 1080, 720)))
        assertFalse(QualityFallback.canCapTo1080(listOf(2160)))
        assertFalse(QualityFallback.canCapTo1080(listOf(2160, 1440)))
        assertFalse(QualityFallback.canCapTo1080(emptyList()))
    }

    @Test
    fun qualityModeCleansUnknownValues() {
        assertEquals("auto", QualityMode.clean(null))
        assertEquals("auto", QualityMode.clean("max"))
        assertEquals("4k", QualityMode.clean("4k"))
        assertEquals("1080", QualityMode.clean("1080"))
        assertEquals("Max 1080p", QualityMode.label("1080"))
    }

    // ---------------------------------------------------------------- sibling channel

    private fun ch(name: String, url: String = "http://p/" + name.hashCode()) = Channel(name, null, "US", url)

    @Test
    fun siblingPrefersFhdThenHdThenNoLabel() {
        val uhd = ch("US| ESPN 4K")
        val list = listOf(uhd, ch("US| ESPN"), ch("US| ESPN HD"), ch("US| ESPN FHD"), ch("US| ESPN 2 FHD"))
        assertEquals("US| ESPN FHD", QualityFallback.pickSibling(uhd, list)?.name)
        assertEquals("US| ESPN HD", QualityFallback.pickSibling(uhd, list.filterNot { it.name.endsWith("ESPN FHD") })?.name)
        assertEquals("US| ESPN", QualityFallback.pickSibling(uhd, listOf(uhd, ch("US| ESPN"), ch("ESPN 720p")))?.name)
    }

    @Test
    fun siblingIsNeverAnother4kOrSd() {
        val uhd = ch("ESPN 4K")
        val list = listOf(uhd, ch("ESPN UHD"), ch("ESPN 2160p"), ch("ESPN SD"))
        assertNull(QualityFallback.pickSibling(uhd, list))
    }

    @Test
    fun siblingKeepsWestApartFromEast() {
        val uhd = ch("HBO 4K")
        val list = listOf(uhd, ch("HBO West FHD"), ch("HBO (W) HD"), ch("HBO East HD"))
        assertEquals("HBO East HD", QualityFallback.pickSibling(uhd, list)?.name)
        val west = ch("HBO West UHD")
        assertEquals("HBO West FHD", QualityFallback.pickSibling(west, list + west)?.name)
    }

    @Test
    fun siblingPrefersTheMainFeedOverABackup() {
        val uhd = ch("FOX 4K")
        val list = listOf(uhd, ch("FOX HD (backup)"), ch("FOX HD"))
        assertEquals("FOX HD", QualityFallback.pickSibling(uhd, list)?.name)
    }

    @Test
    fun siblingMatchesAmpersandNames() {
        val uhd = ch("A&E 4K")
        assertEquals("A & E FHD", QualityFallback.pickSibling(uhd, listOf(uhd, ch("A & E FHD"), ch("AMC FHD")))?.name)
    }

    @Test
    fun siblingIsNeverTheSameStream() {
        val uhd = ch("ESPN 4K", url = "http://p/1")
        assertNull(QualityFallback.pickSibling(uhd, listOf(uhd, ch("ESPN FHD", url = "http://p/1"))))
    }

    @Test
    fun differentChannelsAreNotSiblings() {
        val uhd = ch("ESPN 4K")
        assertNull(QualityFallback.pickSibling(uhd, listOf(uhd, ch("ESPN2 FHD"), ch("ESPNews HD"))))
    }

    // ---------------------------------------------------------------- on-demand 1080p source

    private fun src(name: String, url: String) = StreamSource("Addon", name, "Movie", url, null, null, "", "", 0.0, null, true)

    @Test
    fun picks1080SourceAfterTheCurrentOneFirst() {
        val list = listOf(
            src("Movie 1080p WEB-DL", "http://s/a"),
            src("Movie 2160p REMUX", "http://s/b"),
            src("Movie 720p", "http://s/c"),
            src("Movie 1080p BluRay", "http://s/d"),
        )
        assertEquals(3, QualityFallback.pick1080Source(list, 1))
        assertEquals(0, QualityFallback.pick1080Source(list.take(3), 1))
    }

    @Test
    fun no1080SourceGivesNull() {
        val list = listOf(src("Movie 2160p", "http://s/a"), src("Movie 720p", "http://s/b"), src("Movie 1080p", "http://s/a"))
        assertNull(QualityFallback.pick1080Source(list, 0)) // the 1080p entry is the same file
    }

    @Test
    fun skipsCam1080Sources() {
        val list = listOf(src("Movie 2160p", "http://s/a"), src("Movie 1080p HDCAM", "http://s/b"))
        assertNull(QualityFallback.pick1080Source(list, 0))
    }
}
