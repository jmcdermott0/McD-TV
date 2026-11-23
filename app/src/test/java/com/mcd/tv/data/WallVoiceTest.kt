package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jarvis voice on the Wall: which lines are due, when they freeze and when they speak. */
class WallVoiceTest {
    private val today = 20261124
    private fun at(min: Int, day: Int = today) = WallVoice.at(day, min)

    private val sources = listOf(Wall.Source("Family", "F", 0, "family", null), Wall.Source("School", "S", 0, "school", null))
    private val feed = Wall.Feed(
        generated = at(0), sources = sources,
        events = listOf(
            Wall.Event(0, "Soccer practice", start = at(17 * 60 + 30), end = at(19 * 60)),
            Wall.Event(0, "Trash day", day = today),
            Wall.Event(1, "Essay 3", day = Day.plus(today, 1)),
            Wall.Event(0, "Tomorrow dinner", start = at(18 * 60, Day.plus(today, 1))),
        ),
        weather = null,
    )

    @Test
    fun itemsForTheDay() {
        val items = WallVoice.items(feed, at(6 * 60), 7 * 60, null, "")
        assertEquals(listOf("b:$today", "h:${at(17 * 60 + 30)}:${"Soccer practice".hashCode()}", "d:$today"), items.map { it.key })
        val brief = items[0]
        assertEquals(VoiceKind.BRIEFING, brief.kind)
        assertEquals(at(7 * 60), brief.speakAt)
        assertEquals(at(10 * 60), brief.until)
        val heads = items[1]
        assertEquals("Pardon me. Soccer practice starts in 15 minutes.", heads.text)
        assertEquals(at(17 * 60 + 15), heads.speakAt)
        assertEquals("A reminder: Essay 3 is due tomorrow.", items[2].text)
        assertEquals(at(18 * 60), items[2].speakAt)
    }

    @Test
    fun briefingWindowStopsAtNoon() {
        val brief = WallVoice.items(null, at(6 * 60), 11 * 60, null, "").first()
        assertEquals(at(12 * 60), brief.until)
    }

    @Test
    fun freezesAheadThenSpeaksOnce() {
        val items = WallVoice.items(feed, at(6 * 60), 7 * 60, null, "")
        // 6:50: too early for anything.
        val s0 = WallVoice.step(items, at(6 * 60 + 50), emptyMap(), emptySet())
        assertTrue(s0.prefetch.isEmpty() && s0.say.isEmpty())
        // 6:55: the briefing freezes and is fetched, not spoken yet.
        val s1 = WallVoice.step(items, at(6 * 60 + 55), emptyMap(), emptySet())
        assertEquals(listOf("b:$today"), s1.prefetch.map { it.key })
        assertTrue(s1.say.isEmpty())
        // 7:00: the frozen text speaks, even if the feed changed since (no second fetch).
        val changed = WallVoice.items(feed.copy(events = emptyList()), at(7 * 60), 7 * 60, null, "")
        val s2 = WallVoice.step(changed, at(7 * 60), s1.frozen, emptySet())
        assertTrue(s2.prefetch.isEmpty())
        assertEquals(s1.prefetch.single().text, s2.say.single().text)
        // Said: never again that day.
        val s3 = WallVoice.step(items, at(7 * 60 + 1), s2.frozen, setOf("b:$today"))
        assertTrue(s3.say.isEmpty())
    }

    @Test
    fun wallOpenedLateStillBriefsThenGivesUp() {
        val items = WallVoice.items(feed, at(9 * 60), 7 * 60, null, "")
        assertEquals(listOf("b:$today"), WallVoice.step(items, at(9 * 60), emptyMap(), emptySet()).say.map { it.key })
        assertTrue(WallVoice.step(items, at(10 * 60), emptyMap(), emptySet()).say.none { it.kind == VoiceKind.BRIEFING })
    }

    @Test
    fun headsUpHasATwoMinuteWindow() {
        val items = WallVoice.items(feed, at(17 * 60), 7 * 60, null, "")
        assertEquals(1, WallVoice.step(items, at(17 * 60 + 16), emptyMap(), emptySet()).say.count { it.key.startsWith("h:") })
        assertEquals(0, WallVoice.step(items, at(17 * 60 + 17), emptyMap(), emptySet()).say.count { it.key.startsWith("h:") })
    }

    @Test
    fun saidKeysKeepTodayAndYesterday() {
        val said = mapOf("b:$today" to today, "b:old" to Day.plus(today, -1), "b:older" to Day.plus(today, -2))
        assertEquals(setOf("b:$today", "b:old"), WallVoice.pruneSaid(said, today).keys)
    }
}
