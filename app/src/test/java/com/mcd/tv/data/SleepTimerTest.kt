package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** The app's sleep timer (SleepTimer): one timer across player screens. */
class SleepTimerTest {
    private var now = 1_000_000L
    private val t = SleepTimer { now }

    @Test fun menuCycles() {
        assertEquals(SleepTimer.Mode.Off, t.mode)
        assertEquals(SleepTimer.Mode.At(30, now + 30 * 60_000L), t.next())
        assertEquals(60, (t.next() as SleepTimer.Mode.At).minutes)
        assertEquals(90, (t.next() as SleepTimer.Mode.At).minutes)
        assertEquals(SleepTimer.Mode.EndOfVideo, t.next())
        assertEquals("Sleep timer: End of this video", t.label())
        assertEquals(SleepTimer.Mode.Off, t.next())
        assertEquals(-1L, t.remainingMs())
    }

    @Test fun carriesToTheNextEpisodeButNotToALaterEvening() {
        t.onPlayerStart()
        t.next() // 30 min
        now += 22 * 60_000L
        t.onPlayerLeave() // episode ends, next one starts 15 s later
        now += 15_000L
        t.onPlayerStart()
        assertEquals(8 * 60_000L - 15_000L, t.remainingMs())
        // Left for good: the next player hours later starts with the timer off.
        t.onPlayerLeave()
        now += 3 * 3600_000L
        t.onPlayerStart()
        assertEquals(SleepTimer.Mode.Off, t.mode)
    }

    @Test fun firedAndEndOfVideo() {
        t.next(); t.next(); t.next(); t.next()
        assertEquals(SleepTimer.Mode.EndOfVideo, t.mode)
        // A source switch mid-video keeps End of this video.
        t.onPlayerLeave(); now += 5_000L; t.onPlayerStart()
        assertEquals(SleepTimer.Mode.EndOfVideo, t.mode)
        t.fired()
        assertEquals(SleepTimer.Mode.Off, t.mode)
        // A deadline that passed between two players is due at once.
        t.next()
        t.onPlayerLeave(); now += 31 * 60_000L
        // over CARRY_MS: dropped, not fired late
        t.onPlayerStart()
        assertEquals(SleepTimer.Mode.Off, t.mode)
        t.next(); now += 29 * 60_000L; t.onPlayerLeave(); now += 2 * 60_000L; t.onPlayerStart()
        assertEquals(0L, t.remainingMs())
    }
}
