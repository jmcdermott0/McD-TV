package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Play Next at credits. Container files are real ffmpeg and mkvmerge output (app/src/test/resources/playnext,
 * see its README). Everything after the parse is the same pure code the TV runs.
 */
class PlayNextTest {
    private fun file(name: String): ByteArray =
        listOf(File("src/test/resources/playnext/$name"), File("app/src/test/resources/playnext/$name"))
            .first { it.exists() }.readBytes()

    /** The player's header tap keeps at most this much; tests read through the same cut. */
    private fun header(name: String, cap: Int = 512 * 1024): ByteArray = file(name).let { it.copyOf(minOf(it.size, cap)) }

    private val dur = 150_000L

    // ------------------------------------------------------------ chapters

    @Test fun ffmpegMkvChapters() {
        val s = Chapters.scan(header("qa-credits.mkv"))
        assertEquals("mkv", s.container)
        assertEquals(listOf(0L to "Intro", 8_000L to "Episode", 100_000L to "End Credits"), s.chapters.map { it.startMs to it.title })
        assertEquals(100_000L, CreditsChapter.start(s.chapters, dur))
    }

    @Test fun ffmpegMp4Chpl() {
        val s = Chapters.scan(header("qa-credits.mp4"))
        assertEquals("mp4", s.container)
        assertEquals(listOf("Intro", "Episode", "End Credits"), s.chapters.map { it.title })
        assertEquals(100_000L, CreditsChapter.start(s.chapters, dur))
    }

    @Test fun mkvmergeEndingThenPreview() {
        val s = Chapters.scan(header("mkvmerge-ending.mkv"))
        assertEquals(5, s.chapters.size)
        assertEquals(140_000L, s.chapters.first { it.title == "Ending" }.endMs)
        // Opening Credits at 0:08 never counts; Ending at 1:40 does, and a Preview after it is fine.
        assertEquals(100_000L, CreditsChapter.start(s.chapters, dur))
    }

    @Test fun genericChapterNamesGiveNothing() {
        val s = Chapters.scan(header("mkvmerge-generic.mkv"))
        assertEquals(listOf("Chapter 01", "Chapter 02", "Chapter 03", "Chapter 04", "Chapter 05"), s.chapters.map { it.title })
        assertNull(CreditsChapter.start(s.chapters, dur))
    }

    @Test fun noChapters() {
        val s = Chapters.scan(header("mkvmerge-none.mkv"))
        assertEquals("mkv", s.container)
        assertTrue(s.chapters.isEmpty())
        assertNull(CreditsChapter.start(s.chapters, dur))
    }

    @Test fun postCreditsSceneMeansNoEarlyCard() {
        val s = Chapters.scan(header("mkvmerge-postcredits.mkv"))
        assertEquals(listOf("Episode", "End Credits", "Post-Credits Scene"), s.chapters.map { it.title })
        assertNull(CreditsChapter.start(s.chapters, dur))
    }

    @Test fun headerCutBeforeChaptersSaysSo() {
        val s = Chapters.scan(header("mkvmerge-ending.mkv", cap = 100))
        assertTrue(s.chapters.isEmpty())
        assertTrue(s.laterAt > 0 || s.note.isNotBlank())
    }

    @Test fun otherBytesAreNotAContainer() {
        assertEquals("other", Chapters.scan("#EXTM3U\n".toByteArray()).container)
        assertEquals("other", Chapters.scan(ByteArray(0)).container)
    }

    // ------------------------------------------------------------ names and bounds

    @Test fun creditsNames() {
        listOf("End Credits", "Credits", "Ending", "Closing Credits", "Outro", "End Titles", "ED", "ED2", "Ending Theme").forEach {
            assertTrue(it, CreditsChapter.isCredits(it))
        }
        listOf("Opening Credits", "Intro", "Chapter 12", "Post-Credits Scene", "Mid-credits scene", "Ed's Story", "Episode", "Pre-Credits").forEach {
            assertFalse(it, CreditsChapter.isCredits(it))
        }
    }

    private fun ch(vararg p: Pair<Long, String>) = p.map { Chapters.Chapter(it.first, -1L, it.second) }

    @Test fun creditsTooEarlyOrTooLongAreIgnored() {
        val d = 42 * 60_000L
        // 41:00 of 42:00: fine.
        assertEquals(41 * 60_000L, CreditsChapter.start(ch(0L to "Episode", 41 * 60_000L to "Credits"), d))
        // In the first half: a misnamed chapter.
        assertNull(CreditsChapter.start(ch(0L to "Episode", 15 * 60_000L to "Credits"), d))
        // 14 minutes before the end (over 12 min and 35%): not credits.
        assertNull(CreditsChapter.start(ch(0L to "Episode", 28 * 60_000L to "Credits"), d))
        // 5 seconds: not worth a card.
        assertNull(CreditsChapter.start(ch(0L to "Episode", d - 5_000L to "Credits"), d))
        // A short studio logo after the credits is fine.
        assertEquals(41 * 60_000L, CreditsChapter.start(ch(0L to "Episode", 41 * 60_000L to "Credits", d - 10_000L to "Chapter 9"), d))
    }

    // ------------------------------------------------------------ learning

    @Test fun learningNeedsTwoAgreeingSkips() {
        assertNull(CreditsLearn.learned(emptyList()))
        assertNull(CreditsLearn.learned(listOf(62_000L)))
        // Two skips 8 s apart: the later point (smaller remaining) wins.
        assertEquals(54_000L, CreditsLearn.learned(listOf(62_000L, 54_000L)))
        // Two that disagree by more than 20 s: not yet.
        assertNull(CreditsLearn.learned(listOf(62_000L, 120_000L)))
        // The newest pair wins over an old one.
        assertEquals(118_000L, CreditsLearn.learned(listOf(120_000L, 118_000L, 60_000L, 61_000L)))
        assertEquals(5, (1..9).fold(emptyList<Long>()) { l, i -> CreditsLearn.add(l, i * 1000L) }.size)
    }

    @Test fun samplesStayInsideCreditsBounds() {
        val d = 22 * 60_000L
        assertTrue(CreditsLearn.sampleOk(45_000L, d))
        assertFalse(CreditsLearn.sampleOk(5_000L, d)) // the last seconds: just the end
        assertFalse(CreditsLearn.sampleOk(7 * 60_000L, d)) // a skipped act, not credits
        assertFalse(CreditsLearn.sampleOk(6 * 60_000L, d)) // over 25% of a 22 min episode
    }

    @Test fun pendingCommitsOnlyForTheNextEpisodeSoon() {
        val p = CreditsLearn.Pending("tv:1", 2, 5, 50_000L, 1_000_000L)
        assertTrue(CreditsLearn.commits(p, "tv:1", 2, 6, 1_060_000L))
        assertTrue(CreditsLearn.commits(p.copy(episode = 10), "tv:1", 3, 1, 1_060_000L))
        assertFalse(CreditsLearn.commits(p, "tv:1", 2, 7, 1_060_000L)) // skipped an episode: not a credits skip
        assertFalse(CreditsLearn.commits(p, "tv:2", 2, 6, 1_060_000L))
        assertFalse(CreditsLearn.commits(p, "tv:1", 2, 6, 1_000_000L + 4 * 60_000L))
        assertFalse(CreditsLearn.commits(null, "tv:1", 2, 6, 1_060_000L))
    }

    @Test fun seekChainKeepsTheFirstStart() {
        val c = SeekChain()
        assertEquals(1_000_000L, c.from(1_000_000L, 0L))
        assertEquals(1_000_000L, c.from(1_010_000L, 1_000L)) // fast-forward presses in a row
        assertEquals(1_000_000L, c.from(1_020_000L, 2_500L))
        assertEquals(1_200_000L, c.from(1_200_000L, 10_000L)) // a new jump later
    }

    // ------------------------------------------------------------ the decision

    @Test fun priorityChapterThenLearnedThenSetting() {
        val d = 22 * 60_000L
        assertEquals(PlayNextPoint(d - 60_000L, PlayNextSource.CHAPTER), PlayNextRules.decide(d, true, d - 60_000L, 40_000L, true, 30, false))
        assertEquals(PlayNextPoint(d - 40_000L, PlayNextSource.LEARNED), PlayNextRules.decide(d, true, null, 40_000L, true, 30, false))
        assertEquals(PlayNextPoint(d - 30_000L, PlayNextSource.SETTING), PlayNextRules.decide(d, true, null, null, true, 30, false))
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(d, true, null, null, true, 0, false))
        // "At the credits" off: only the setting.
        assertEquals(PlayNextPoint(d - 30_000L, PlayNextSource.SETTING), PlayNextRules.decide(d, true, d - 60_000L, 40_000L, false, 30, false))
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(d, true, d - 60_000L, 40_000L, false, 0, false))
        // A learned value outside the bounds is ignored.
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(d, true, null, 9 * 60_000L, true, 0, false))
        // The setting never takes more than a quarter of a short file.
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(90_000L, true, null, null, true, 45, false))
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(30_000L, true, 20_000L, null, true, 15, false))
    }

    @Test fun moviesOnlyWithTheCardOnAndAChapter() {
        val d = 120 * 60_000L
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(d, false, d - 8 * 60_000L, null, true, 30, false))
        assertEquals(PlayNextPoint(d - 8 * 60_000L, PlayNextSource.CHAPTER), PlayNextRules.decide(d, false, d - 8 * 60_000L, null, true, 30, true))
        assertEquals(PlayNextPoint.NONE, PlayNextRules.decide(d, false, null, 60_000L, true, 30, true))
    }

    // ------------------------------------------------------------ the trigger

    @Test fun cardShowsWhenPlaybackReachesTheCredits() {
        val t = PlayNextTrigger(100_000L, dur)
        var shownAt = -1L
        var pos = 0L
        while (pos <= dur) {
            if (t.tick(pos) == PlayNextTrigger.Event.SHOW && shownAt < 0) shownAt = pos
            pos += 500
        }
        assertEquals(100_000L, shownAt)
        assertTrue(dur - shownAt >= 50_000L)
    }

    @Test fun resumePastThePointDoesNotPopTheCard() {
        val t = PlayNextTrigger(100_000L, dur)
        assertNull(t.tick(120_000L))
        assertNull(t.tick(120_500L))
        assertFalse(t.showing)
    }

    @Test fun seeksShowHideAndDismiss() {
        val t = PlayNextTrigger(100_000L, dur)
        t.tick(50_000L)
        assertEquals(PlayNextTrigger.Event.SHOW, t.seek(50_000L, 110_000L))
        assertEquals(PlayNextTrigger.Event.HIDE, t.seek(110_000L, 60_000L))
        assertEquals(PlayNextTrigger.Event.SHOW, t.tick(60_000L).let { t.seek(60_000L, 105_000L) })
        t.dismiss()
        assertNull(t.tick(105_500L))
        assertNull(t.seek(105_500L, 10_000L))
        assertNull(t.seek(10_000L, 101_000L))
        // A jump to the very end is the end card's job.
        val u = PlayNextTrigger(100_000L, dur)
        u.tick(50_000L)
        assertNull(u.seek(50_000L, dur - 1_000L))
    }

    @Test fun noPointNoCard() {
        val t = PlayNextTrigger(-1L, dur)
        (0..300).forEach { assertNull(t.tick(it * 500L)) }
    }

    /** End to end on the real file: bytes, chapters, decision, playback. */
    @Test fun qaFileShowsTheCardFiftySecondsBeforeTheEnd() {
        val s = Chapters.scan(header("qa-credits.mkv"))
        val p = PlayNextRules.decide(dur, true, CreditsChapter.start(s.chapters, dur), null, true, 0, false)
        assertEquals(PlayNextSource.CHAPTER, p.source)
        val t = PlayNextTrigger(p.atMs, dur)
        val first = (0..300).map { it * 500L }.first { t.tick(it) == PlayNextTrigger.Event.SHOW }
        assertEquals(100_000L, first)
    }

    // ------------------------------------------------------------ next episode

    @Test fun nextEpisodeKnowsSeasonEndsAndFinales() {
        val seasons = listOf(1 to 10, 2 to 8)
        assertEquals(NextEpisode.Known(1, 5), NextEpisode.after(1, 4, seasons, null))
        assertEquals(NextEpisode.Known(2, 1), NextEpisode.after(1, 10, seasons, null))
        assertEquals(NextEpisode.None, NextEpisode.after(2, 8, seasons, null)) // series finale
        assertEquals(NextEpisode.None, NextEpisode.after(2, 3, seasons, 2 to 4)) // next one has not aired
        assertEquals(NextEpisode.Known(2, 4), NextEpisode.after(2, 3, seasons, 2 to 5))
        assertEquals(NextEpisode.Unknown, NextEpisode.after(1, 1, emptyList(), null))
        assertEquals(NextEpisode.Unknown, NextEpisode.after(3, 1, seasons, null))
        // A season with no episodes listed yet is skipped.
        assertEquals(NextEpisode.None, NextEpisode.after(2, 8, seasons + (3 to 0), null))
    }
}
