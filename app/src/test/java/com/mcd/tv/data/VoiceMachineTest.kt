package com.mcd.tv.data

import com.mcd.tv.data.VoiceMachine.Action
import com.mcd.tv.data.VoiceMachine.Event
import com.mcd.tv.data.VoiceMachine.Failure
import com.mcd.tv.data.VoiceMachine.Line
import com.mcd.tv.data.VoiceMachine.Phase
import com.mcd.tv.data.VoiceMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Jarvis voice: the speaker's state machine (STATE-MACHINES.md, "Jarvis voice"). */
class VoiceMachineTest {
    private val t0 = 1_795_000_000_000L
    private fun line(text: String, kind: VoiceKind = VoiceKind.ASK, cached: Boolean = false, deadline: Long = t0 + 120_000) =
        Line(kind, text, deadline, cached)

    private fun run(s: State, vararg events: Event): Pair<State, List<Action>> {
        var st = s
        val acts = mutableListOf<Action>()
        events.forEach { e -> val (n, a) = VoiceMachine.reduce(st, e); st = n; acts += a }
        return st to acts
    }

    @Test
    fun aLineFetchesPlaysAndEnds() {
        val (s1, a1) = run(State(), Event.Add(line("Hello"), t0))
        val f = a1.single() as Action.Fetch
        assertEquals(Phase.Fetching(f.gen, line("Hello")), s1.phase)
        val (s2, a2) = run(s1, Event.Ready(f.gen, t0 + 500))
        assertEquals(Action.Play(f.gen, line("Hello")), a2.single())
        assertTrue(s2.phase is Phase.Playing)
        val (s3, a3) = run(s2, Event.Done(f.gen, t0 + 3_000))
        assertEquals(Phase.Idle, s3.phase)
        assertTrue(a3.isEmpty())
        assertEquals("Working.", VoiceMachine.status(s3, keySet = true, now = t0 + 3_000))
    }

    @Test
    fun queuedLinesFollowInOrderAndSameKindReplaces() {
        val (s1, _) = run(State(), Event.Add(line("one"), t0))
        val (s2, _) = run(s1, Event.Add(line("brief", VoiceKind.BRIEFING), t0), Event.Add(line("two"), t0))
        assertEquals(listOf("brief", "two"), s2.queue.map { it.text })
        val gen = (s2.phase as Phase.Fetching).gen
        val (s3, a3) = run(s2, Event.Ready(gen, t0), Event.Done(gen, t0 + 1))
        assertEquals("brief", (a3.last() as Action.Fetch).line.text)
        assertEquals(listOf("two"), s3.queue.map { it.text })
    }

    @Test
    fun staleAnswersAreIgnored() {
        val (s1, a1) = run(State(), Event.Add(line("Hello"), t0))
        val gen = (a1.single() as Action.Fetch).gen
        val (s2, a2) = run(s1, Event.Stop)
        assertEquals(listOf(Action.StopAudio), a2)
        val (s3, a3) = run(s2, Event.Ready(gen, t0 + 1), Event.Failed(gen, Failure.KEY, t0 + 1), Event.Done(gen, t0 + 1))
        assertEquals(Phase.Idle, s3.phase)
        assertTrue(a3.isEmpty())
        assertNull(s3.blocked)
    }

    @Test
    fun networkFailureRestsAndCachedLinesStillPlay() {
        val (s1, a1) = run(State(), Event.Add(line("Hello"), t0))
        val gen = (a1.single() as Action.Fetch).gen
        val (s2, _) = run(s1, Event.Failed(gen, Failure.OFFLINE, t0))
        assertEquals(t0 + 60_000, s2.restUntil)
        // While resting: a new line that needs the network is dropped, a cached one plays.
        val (s3, a3) = run(s2, Event.Add(line("Fresh"), t0 + 1_000))
        assertTrue(a3.isEmpty())
        assertEquals(Phase.Idle, s3.phase)
        val (s4, a4) = run(s3, Event.Add(line("Cached", VoiceKind.STILL_WATCHING, cached = true), t0 + 1_000))
        assertTrue(a4.single() is Action.Fetch)
        assertTrue(s4.phase is Phase.Fetching)
        assertTrue(VoiceMachine.status(s4, true, t0 + 1_000).startsWith("Offline"))
    }

    @Test
    fun backoffGrowsAndResetsOnSuccess() {
        var s = State()
        var at = t0
        val rests = mutableListOf<Long>()
        repeat(4) {
            // After each rest ends, the next line fetches and fails again.
            val (s1, a1) = run(s, Event.Add(line("x$it", deadline = at + 120_000), at))
            val gen = (a1.single() as Action.Fetch).gen
            s = run(s1, Event.Failed(gen, Failure.BUSY, at)).first
            rests += s.restUntil - at
            at = s.restUntil
        }
        assertEquals(listOf(60_000L, 300_000L, 900_000L, 900_000L), rests)
        val (s5, a5) = run(s, Event.Add(line("ok", deadline = at + 120_000), at))
        val gen = (a5.single() as Action.Fetch).gen
        assertEquals(0, run(s5, Event.Ready(gen, at)).first.failures)
    }

    @Test
    fun rejectedKeyBlocksUntilTheKeyChanges() {
        val (s1, a1) = run(State(), Event.Add(line("Hello"), t0))
        val gen = (a1.single() as Action.Fetch).gen
        val (s2, a2) = run(s1, Event.Failed(gen, Failure.KEY, t0))
        assertEquals(listOf(Action.Blocked(Failure.KEY)), a2)
        assertEquals(Failure.KEY, s2.blocked)
        assertTrue(VoiceMachine.status(s2, true, t0).startsWith("Key rejected"))
        assertTrue(run(s2, Event.Add(line("Again"), t0 + 10)).second.isEmpty())
        // Noted once, not on every failure.
        assertTrue(run(s2, Event.PrefetchFailed(Failure.KEY, t0 + 20)).second.isEmpty())
        val (s3, _) = run(s2, Event.KeyChanged)
        assertNull(s3.blocked)
        assertTrue(run(s3, Event.Add(line("Again"), t0 + 30)).second.single() is Action.Fetch)
    }

    @Test
    fun creditAndVoiceBlocksAndPrefetchRest() {
        val (s1, a1) = run(State(), Event.PrefetchFailed(Failure.CREDIT, t0))
        assertEquals(Failure.CREDIT, s1.blocked)
        assertEquals(listOf(Action.Blocked(Failure.CREDIT)), a1)
        val (s2, _) = run(State(), Event.PrefetchFailed(Failure.OFFLINE, t0))
        assertEquals(t0 + 60_000, s2.restUntil)
        assertEquals(Phase.Idle, s2.phase)
        val (s3, _) = run(State(), Event.PrefetchFailed(Failure.VOICE, t0))
        assertTrue(VoiceMachine.status(s3, true, t0).startsWith("The voice was not found"))
    }

    @Test
    fun lateLinesAreDroppedAndQueueIsShort() {
        val (s1, a1) = run(State(), Event.Add(line("late", deadline = t0 - 1), t0))
        assertTrue(a1.isEmpty())
        assertEquals(Phase.Idle, s1.phase)
        val busy = run(State(), Event.Add(line("first"), t0)).first
        val kinds = listOf(VoiceKind.BRIEFING, VoiceKind.REMINDER, VoiceKind.SLEEP, VoiceKind.STILL_WATCHING)
        val full = kinds.fold(busy) { st, k -> run(st, Event.Add(line(k.key, k), t0)).first }
        assertEquals(VoiceMachine.MAX_QUEUE, full.queue.size)
        assertEquals(listOf("reminders", "sleep", "still"), full.queue.map { it.text })
    }

    @Test
    fun noKeyStatus() {
        assertTrue(VoiceMachine.status(State(), keySet = false, now = t0).startsWith("No key yet"))
        assertEquals("Ready.", VoiceMachine.status(State(), keySet = true, now = t0))
    }
}
