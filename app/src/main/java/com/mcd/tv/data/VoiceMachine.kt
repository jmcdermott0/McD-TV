package com.mcd.tv.data

/**
 * Jarvis voice: the speaker's one state value and every transition (STATE-MACHINES.md, "Jarvis voice").
 * Pure, clock passed in events; tests in VoiceMachineTest. `Voice` carries out the actions.
 *
 * Phases: Idle, Fetching (audio from the disk cache or the voice service), Playing. Lines wait in a short queue.
 * After a network failure the speaker rests (1, then 5, then 15 minutes): cached lines still play, others are
 * dropped (their text is on screen anyway). A rejected key, an empty credit or a missing voice blocks new audio
 * until the key or voice changes. Every fetch and play carries a generation number; an answer for an older
 * generation is ignored.
 */
object VoiceMachine {
    const val MAX_QUEUE = 3
    val BACKOFF_MS = longArrayOf(60_000L, 5 * 60_000L, 15 * 60_000L)

    data class Line(val kind: VoiceKind, val text: String, val deadline: Long, val cached: Boolean)

    enum class Failure { KEY, CREDIT, VOICE, BUSY, OFFLINE, OTHER }

    sealed interface Phase {
        data object Idle : Phase
        data class Fetching(val gen: Int, val line: Line) : Phase
        data class Playing(val gen: Int, val line: Line) : Phase
    }

    data class State(
        val phase: Phase = Phase.Idle,
        val queue: List<Line> = emptyList(),
        val gen: Int = 0,
        val restUntil: Long = 0L,
        val failures: Int = 0,
        val blocked: Failure? = null,
        val lastOkAt: Long = 0L,
    )

    sealed interface Event {
        data class Add(val line: Line, val now: Long) : Event
        data class Ready(val gen: Int, val now: Long) : Event
        data class Failed(val gen: Int, val failure: Failure, val now: Long) : Event
        data class Done(val gen: Int, val now: Long) : Event
        /** A fetch ahead of time (Wall) failed: same rest or block as a failed line, nothing else changes. */
        data class PrefetchFailed(val failure: Failure, val now: Long) : Event
        /** Stop speaking and forget the queue (e.g. the screen that asked went away). */
        data object Stop : Event
        /** The key or the voice changed: clear the block and the rest. */
        data object KeyChanged : Event
    }

    sealed interface Action {
        data class Fetch(val gen: Int, val line: Line) : Action
        data class Play(val gen: Int, val line: Line) : Action
        data object StopAudio : Action
        /** A new block (key rejected, credit empty, voice missing): note it once in Recent errors. */
        data class Blocked(val failure: Failure) : Action
    }

    /** True when audio not in the cache may be fetched now. */
    fun canFetch(s: State, now: Long): Boolean = s.blocked == null && now >= s.restUntil

    fun reduce(s: State, e: Event): Pair<State, List<Action>> = when (e) {
        is Event.Add -> {
            if (!e.line.cached && !canFetch(s, e.now)) s to emptyList()
            else {
                // A newer line of the same kind replaces a queued one; the queue keeps the newest few.
                val q = (s.queue.filterNot { it.kind == e.line.kind } + e.line).takeLast(MAX_QUEUE)
                next(s.copy(queue = q), e.now)
            }
        }
        is Event.Ready -> {
            val p = s.phase
            if (p is Phase.Fetching && p.gen == e.gen) {
                s.copy(phase = Phase.Playing(p.gen, p.line), failures = 0, lastOkAt = e.now) to listOf(Action.Play(p.gen, p.line))
            } else s to emptyList()
        }
        is Event.Failed -> {
            val p = s.phase
            if (p !is Phase.Fetching || p.gen != e.gen) s to emptyList()
            else {
                val (after, blockedNow) = failed(s.copy(phase = Phase.Idle), e.failure, e.now)
                val (n, acts) = next(after, e.now)
                n to blockedNow + acts
            }
        }
        is Event.PrefetchFailed -> failed(s, e.failure, e.now)
        is Event.Done -> {
            val p = s.phase
            if (p is Phase.Playing && p.gen == e.gen) next(s.copy(phase = Phase.Idle), e.now) else s to emptyList()
        }
        Event.Stop -> {
            val busy = s.phase != Phase.Idle
            s.copy(phase = Phase.Idle, queue = emptyList(), gen = if (busy) s.gen + 1 else s.gen) to
                (if (busy) listOf(Action.StopAudio) else emptyList())
        }
        Event.KeyChanged -> s.copy(blocked = null, restUntil = 0L, failures = 0) to emptyList()
    }

    /** Counts a failure: a block (key, credit, voice) or a rest (1, 5, 15 min). A new block is noted once. */
    private fun failed(s: State, f: Failure, now: Long): Pair<State, List<Action>> {
        val failures = s.failures + 1
        val blocking = f == Failure.KEY || f == Failure.CREDIT || f == Failure.VOICE
        val rested = if (blocking) s.restUntil else now + BACKOFF_MS[(failures - 1).coerceAtMost(BACKOFF_MS.size - 1)]
        val after = s.copy(failures = failures, restUntil = rested, blocked = if (blocking) f else s.blocked)
        return after to (if (blocking && s.blocked != f) listOf(Action.Blocked(f)) else emptyList())
    }

    /** From Idle: start the next line still worth saying. Late lines and lines that can no longer be fetched drop. */
    private fun next(s: State, now: Long): Pair<State, List<Action>> {
        if (s.phase != Phase.Idle) return s to emptyList()
        val q = s.queue.filter { now <= it.deadline && (it.cached || canFetch(s, now)) }
        val line = q.firstOrNull() ?: return s.copy(queue = emptyList()) to emptyList()
        val gen = s.gen + 1
        return s.copy(phase = Phase.Fetching(gen, line), queue = q.drop(1), gen = gen) to listOf(Action.Fetch(gen, line))
    }

    /** Plain words for TV Settings. */
    fun status(s: State, keySet: Boolean, now: Long): String = when {
        !keySet -> "No key yet. Add the Jarvis voice key on the Control page."
        s.blocked == Failure.KEY -> "Key rejected. Replace the Jarvis voice key on the Control page."
        s.blocked == Failure.CREDIT -> "Jarvis voice credit is empty."
        s.blocked == Failure.VOICE -> "The voice was not found. Check the voice ID on the Control page."
        now < s.restUntil -> "Offline. Jarvis voice tries again in ${((s.restUntil - now + 59_999) / 60_000)} min."
        s.lastOkAt > 0 -> "Working."
        else -> "Ready."
    }
}
