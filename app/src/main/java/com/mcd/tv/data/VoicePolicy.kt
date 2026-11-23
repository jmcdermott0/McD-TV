package com.mcd.tv.data

/**
 * Jarvis voice: what may speak and when. Pure, clock passed in; tests in VoicePolicyTest.
 *
 * Every spoken line also shows on screen, so a line that is skipped loses nothing. The policy only decides
 * whether Jarvis speaks a line it would show anyway.
 */
enum class VoiceKind(
    /** Stored name in the pref `voice_uses_off` (never rename). */
    val key: String,
    /** The switch's label in TV Settings > This TV > Jarvis voice. */
    val label: String,
    /** May speak while a video is open (near a pause point). */
    val player: Boolean = false,
    /** A Jarvis Wall line: at most one every [VoicePolicy.WALL_SPACING_MS]. */
    val wall: Boolean = false,
    /** May speak on the Kids profile. */
    val kidsOk: Boolean = false,
    /** Has its own switch in Settings. */
    val switchable: Boolean = true,
) {
    ASK("ask", "Ask Jarvis answers"),
    BRIEFING("briefing", "Morning briefing on the Wall", wall = true, kidsOk = true),
    REMINDER("reminders", "Calendar reminders on the Wall", wall = true, kidsOk = true),
    STILL_WATCHING("still", "Still watching?", player = true, kidsOk = true),
    SLEEP("sleep", "Sleep timer warning", player = true, kidsOk = true),
    TEST("test", "Test", player = true, kidsOk = true, switchable = false),
}

object VoicePolicy {
    /** Wall lines are at least this far apart. */
    const val WALL_SPACING_MS = 2 * 60_000L

    /** A line that has waited longer than this is dropped rather than spoken late. */
    const val LATE_MS = 2 * 60_000L

    /** The same Ask Jarvis line is not spoken twice within this time (Back to a screen shows the answer again). */
    const val REPEAT_MS = 10 * 60_000L

    /** Quiet hours, local time: no speech from 11 PM to 6 AM (the Wall dims then too). */
    fun quiet(hourOfDay: Int): Boolean = hourOfDay >= 23 || hourOfDay < 6

    /** What the policy needs to know about this TV right now. */
    data class Ctx(
        val now: Long,
        val hourOfDay: Int,
        val keySet: Boolean,
        val voiceOn: Boolean,
        val usesOff: Set<String>,
        val videoOpen: Boolean,
        val kids: Boolean,
        val lastWallAt: Long,
    )

    enum class Skip { NO_KEY, OFF, USE_OFF, QUIET, VIDEO, KIDS, SPACING, LATE }

    /** Null: speak. Otherwise the reason the line stays text only. */
    fun skip(kind: VoiceKind, deadline: Long, c: Ctx): Skip? = when {
        !c.keySet -> Skip.NO_KEY
        kind == VoiceKind.TEST -> null
        !c.voiceOn -> Skip.OFF
        kind.key in c.usesOff -> Skip.USE_OFF
        quiet(c.hourOfDay) -> Skip.QUIET
        c.videoOpen && !kind.player -> Skip.VIDEO
        c.kids && !kind.kidsOk -> Skip.KIDS
        kind.wall && c.lastWallAt > 0 && c.now - c.lastWallAt in 0 until WALL_SPACING_MS -> Skip.SPACING
        c.now > deadline -> Skip.LATE
        else -> null
    }

    /** Parses the stored list of switched-off uses ("ask,still"). */
    fun parseOff(s: String): Set<String> = s.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun encodeOff(off: Set<String>): String = off.sorted().joinToString(",")
}
