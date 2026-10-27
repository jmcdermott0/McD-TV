package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Picked for <name> changes once a day, the same way on every TV, and never repeats yesterday's row when it can. */
class DailyPicksTest {
    private val pool = (1..120).map { "movie:$it" }
    private val oct27 = 20261027
    private val oct28 = 20261028

    @Test fun sameDayGivesTheSameRow() {
        val a = DailyPicks.choose(pool, oct27, "p1", emptySet(), emptySet(), 24)
        val b = DailyPicks.choose(pool, oct27, "p1", emptySet(), emptySet(), 24)
        assertEquals(24, a.size)
        assertEquals(a, b)
    }

    @Test fun nextDayGivesADifferentRow() {
        val a = DailyPicks.choose(pool, oct27, "p1", emptySet(), emptySet(), 24)
        val b = DailyPicks.choose(pool, oct28, "p1", emptySet(), emptySet(), 24)
        assertNotEquals(a, b)
        assertNotEquals(a.toSet(), b.toSet())
    }

    @Test fun consecutiveDaysDoNotOverlapWhenThePoolIsBigEnough() {
        var yesterday = emptySet<String>()
        for (day in listOf(20261027, 20261028, 20261029, 20261030, 20261031, 20261101)) {
            val today = DailyPicks.choose(pool, day, "p1", emptySet(), yesterday, 24)
            assertEquals(24, today.size)
            assertTrue("day $day repeats ${today.filter { it in yesterday }}", today.none { it in yesterday })
            yesterday = today.toSet()
        }
    }

    @Test fun smallPoolFillsWithYesterdaysTitlesLast() {
        val small = (1..30).map { "movie:$it" }
        val day1 = DailyPicks.choose(small, oct27, "p1", emptySet(), emptySet(), 24)
        val day2 = DailyPicks.choose(small, oct28, "p1", emptySet(), day1.toSet(), 24)
        assertEquals(24, day2.size)
        val fresh = small.filter { it !in day1 }
        assertEquals(fresh.toSet(), day2.take(fresh.size).toSet())
    }

    @Test fun excludedTitlesNeverShow() {
        val listed = setOf("movie:1", "movie:2", "movie:3")
        val watched = (4..20).map { "movie:$it" }.toSet()
        val picks = DailyPicks.choose(pool, oct27, "p1", listed + watched, emptySet(), 24)
        assertTrue(picks.none { it in listed || it in watched })
        assertEquals(picks.size, picks.distinct().size)
    }

    @Test fun profilesGetTheirOwnRow() {
        val dad = DailyPicks.choose(pool, oct27, "p1", emptySet(), emptySet(), 24)
        val mom = DailyPicks.choose(pool, oct27, "p2", emptySet(), emptySet(), 24)
        assertNotEquals(dad, mom)
    }

    @Test fun betterRankedTitlesShowMoreOften() {
        // Taste still leads: over a month, the top quarter of the ranking shows far more than the bottom quarter.
        var top = 0
        var bottom = 0
        var yesterday = emptySet<String>()
        for (d in 1..30) {
            val picks = DailyPicks.choose(pool, 20261100 + d, "p1", emptySet(), yesterday, 24)
            top += picks.count { it.substringAfter(':').toInt() <= 30 }
            bottom += picks.count { it.substringAfter(':').toInt() > 90 }
            yesterday = picks.toSet()
        }
        assertTrue("top $top, bottom $bottom", top > bottom * 2)
    }

    @Test fun theSeedIsFixedForADayAndProfile() {
        // Every TV in the house runs the same arithmetic, so the same day and profile always give the same number.
        assertEquals(DailyPicks.seed(oct27, "p1"), DailyPicks.seed(oct27, "p1"))
        assertNotEquals(DailyPicks.seed(oct27, "p1"), DailyPicks.seed(oct28, "p1"))
        assertNotEquals(DailyPicks.seed(oct27, "p1"), DailyPicks.seed(oct27, "p2"))
    }

    @Test fun dayFollowsTheLocalCalendar() {
        val chicago = TimeZone.getTimeZone("America/Chicago")
        // 2026-10-28 04:30 UTC is still October 27 in Chicago (CDT, UTC-5).
        assertEquals(20261027, DailyPicks.dayOf(1793161800000L, chicago))
        // 2026-10-28 05:30 UTC is just after midnight there.
        assertEquals(20261028, DailyPicks.dayOf(1793165400000L, chicago))
        // Home reloads the row at the next local midnight: 04:30 UTC is 23:30 there, half an hour before it.
        assertEquals(30 * 60_000L, DailyPicks.msUntilNextDay(1793161800000L, chicago))
    }
}
