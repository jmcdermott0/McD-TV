package com.mcd.tv.data

/**
 * The player's sleep timer (MENU cycles it). One per app, not per player screen (STATE-MACHINES.md, "Sleep
 * timer"): before, every new player (next episode, next source, 1080p step-down) started with the timer off,
 * so a 90 minute timer set in episode 1 was silently gone in episode 2.
 *
 *   Off --MENU--> At(30) --MENU--> At(60) --MENU--> At(90) --MENU--> EndOfVideo --MENU--> Off
 *   At --deadline--> Off (pause, leave)         EndOfVideo --video ended--> Off (stop, leave)
 *   any --no player for CARRY_MS--> Off         (a new evening does not inherit last night's timer)
 *
 * Pure, clock injected; tests in SleepTimerTest.
 */
class SleepTimer(private val clock: () -> Long) {
    sealed interface Mode {
        data object Off : Mode
        data class At(val minutes: Int, val deadline: Long) : Mode
        data object EndOfVideo : Mode
    }

    var mode: Mode = Mode.Off
        private set
    private var leftAt = -1L

    /** A player screen opened. The timer carries over only from a player that closed moments ago. */
    fun onPlayerStart() {
        if (mode != Mode.Off && leftAt >= 0 && clock() - leftAt > CARRY_MS) mode = Mode.Off
        leftAt = -1L
    }

    /** A player screen closed (it may be replaced by the next episode or source in a moment). */
    fun onPlayerLeave() {
        leftAt = clock()
    }

    /** MENU: the next step. */
    fun next(): Mode {
        mode = when (val m = mode) {
            Mode.Off -> at(STEPS[0])
            is Mode.At -> STEPS.firstOrNull { it > m.minutes }?.let { at(it) } ?: Mode.EndOfVideo
            Mode.EndOfVideo -> Mode.Off
        }
        return mode
    }

    /** The timer did its job (deadline reached, or the video ended with End of this video). */
    fun fired() {
        mode = Mode.Off
    }

    /** Time left on an At timer (0 when due), or -1 when not counting down. */
    fun remainingMs(): Long = (mode as? Mode.At)?.let { (it.deadline - clock()).coerceAtLeast(0L) } ?: -1L

    fun label(): String = when (val m = mode) {
        Mode.Off -> "Sleep timer: Off"
        is Mode.At -> "Sleep timer: ${m.minutes} min"
        Mode.EndOfVideo -> "Sleep timer: End of this video"
    }

    private fun at(minutes: Int) = Mode.At(minutes, clock() + minutes * 60_000L)

    companion object {
        val STEPS = listOf(30, 60, 90)
        /** Longest gap between two players that still counts as "the same viewing" (episode roll-over, source switch). */
        const val CARRY_MS = 3 * 60_000L
    }
}
