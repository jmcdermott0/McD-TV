package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Sources screen's pick phase (SourcePick). */
class SourcePickTest {
    @Test fun oneAtATimeAndMessagesFollowThePhase() {
        var p: SourcePick = SourcePick.Idle()
        p = SourcePick.begin(p, "Finding the best source…")!!
        assertTrue(p.busy)
        assertNull(SourcePick.begin(p, "Getting stream…")) // a second press does nothing
        p = SourcePick.progress(p, "Trying 2 of 8…")
        assertEquals("Trying 2 of 8…", p.note)
        p = SourcePick.fail(p, "Real-Debrid refused it. Pick one below.")
        assertFalse(p.busy)
        assertEquals("Real-Debrid refused it. Pick one below.", p.note)
        // Retry clears the old message (it used to stay under the spinner).
        p = SourcePick.retry(p)
        assertEquals(SourcePick.Idle(), p)
    }

    @Test fun cancelledSearchFreesThePick() {
        var p: SourcePick = SourcePick.begin(SourcePick.Idle(), "Finding the best source…")!!
        p = SourcePick.cancelled(p)
        assertFalse(p.busy)
        assertTrue(SourcePick.begin(p, "Finding the best source…") != null)
    }

    @Test fun openedIsFinal() {
        var p: SourcePick = SourcePick.begin(SourcePick.Idle(), "Getting stream…")!!
        p = SourcePick.opened(p)
        assertTrue(p.busy)
        assertEquals(SourcePick.Opened, SourcePick.retry(p))
        assertEquals(SourcePick.Opened, SourcePick.fail(p, "late error"))
        assertEquals(SourcePick.Opened, SourcePick.progress(p, "late note"))
        // Progress or failure while idle changes nothing.
        assertEquals(SourcePick.Idle("x"), SourcePick.progress(SourcePick.Idle("x"), "y"))
    }
}
