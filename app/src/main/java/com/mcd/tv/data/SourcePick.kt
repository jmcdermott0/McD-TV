package com.mcd.tv.data

/**
 * The Sources screen's pick phase (STATE-MACHINES.md, "Sources"): one value in place of a `busy` flag and a
 * free `status` string that had to agree. Pure, tests in SourcePickTest.
 *
 *   Idle(note) --begin--> Working(note) --progress--> Working(note')
 *   Working --fail(msg)--> Idle(msg)        (the message stays until the next action)
 *   Working --cancelled--> Idle("")         (Retry, or the list reloaded under an auto-play)
 *   any --retry--> Idle("")                 (Retry clears an old message: the list is asked for again)
 *   Working --opened--> Opened              (the player took over; nothing else may start)
 */
sealed interface SourcePick {
    /** The line under the title ("" = none). */
    val note: String

    data class Idle(override val note: String = "") : SourcePick
    data class Working(override val note: String) : SourcePick
    data object Opened : SourcePick { override val note: String get() = "" }

    val busy: Boolean get() = this !is Idle

    companion object {
        /** Starts a pick, or null when one is already running (a second OK press does nothing). */
        fun begin(p: SourcePick, note: String): SourcePick? = if (p is Idle) Working(note) else null
        fun progress(p: SourcePick, note: String): SourcePick = if (p is Working) Working(note) else p
        fun fail(p: SourcePick, message: String): SourcePick = if (p is Working) Idle(message) else p
        fun cancelled(p: SourcePick): SourcePick = if (p is Working) Idle() else p
        fun opened(p: SourcePick): SourcePick = if (p is Working) Opened else p
        fun retry(p: SourcePick): SourcePick = if (p is Opened) p else Idle()
    }
}
