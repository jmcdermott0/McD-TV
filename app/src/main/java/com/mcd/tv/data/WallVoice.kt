package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import java.util.Calendar

/**
 * Jarvis voice on the Wall: which lines are due and when. Pure, clock passed in; tests in WallVoiceTest.
 *
 * - Morning briefing: once a day at the briefing time (default 7:00), or at the first Wall minute after it,
 *   until three hours later or noon, whichever comes first.
 * - Heads-up: 15 minutes before a timed event today. All-day events and school items never speak.
 * - Due soon: at 6 PM, school items due tomorrow, in one line.
 *
 * A line's text is fixed ("frozen") up to [PREFETCH_MS] before it is due, so its audio can be fetched early and
 * an outage at the moment it plays changes nothing. Each line speaks once ([step] and the `said` keys).
 */
object WallVoice {
    /** Lines are frozen and fetched this long before they are due. */
    const val PREFETCH_MS = 6 * 60_000L

    /** The briefing window: three hours after the briefing time, never past noon. */
    private const val BRIEF_WINDOW_MS = 3 * 60 * 60_000L

    /** A heads-up speaks within this time of its moment, or not at all. */
    private const val HEADS_UP_WINDOW_MS = 2 * 60_000L

    data class Item(val key: String, val kind: VoiceKind, val text: String, val speakAt: Long, val until: Long)

    fun at(day: Int, minuteOfDay: Int): Long = Day.calendar(day).apply {
        set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        set(Calendar.MINUTE, minuteOfDay % 60)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Every Wall line for the day of [now]. Texts are built as of [now]; [step] freezes them before they play. */
    fun items(feed: Wall.Feed?, now: Long, briefAtMinute: Int, babyDue: Int?, babyName: String): List<Item> {
        val today = Day.ofMillis(now)
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val out = mutableListOf<Item>()

        val briefAt = at(today, briefAtMinute.coerceIn(0, 11 * 60 + 59))
        val briefUntil = minOf(briefAt + BRIEF_WINDOW_MS, at(today, 12 * 60))
        out += Item("b:$today", VoiceKind.BRIEFING, VoiceLines.briefing(feed, today, hour, babyDue, babyName), briefAt, briefUntil)

        if (feed != null) {
            feed.events.forEach { e ->
                val start = e.start ?: return@forEach
                if (e.allDay || Day.ofMillis(start) != today || VoiceLines.isSchool(feed, e)) return@forEach
                val title = VoiceLines.title(e.title)
                if (title.isEmpty()) return@forEach
                val speakAt = start - 15 * 60_000L
                out += Item("h:$start:${title.hashCode()}", VoiceKind.REMINDER, VoiceLines.headsUp(title), speakAt, speakAt + HEADS_UP_WINDOW_MS)
            }
            val tomorrow = Day.plus(today, 1)
            val due = feed.events.filter { VoiceLines.isSchool(feed, it) && it.firstDay == tomorrow }.map { it.title }
            val text = VoiceLines.dueSoon(due)
            if (text.isNotEmpty()) out += Item("d:$today", VoiceKind.REMINDER, text, at(today, 18 * 60), at(today, 19 * 60))
        }
        return out.sortedBy { it.speakAt }
    }

    /** What to do this minute. [prefetch]: new frozen texts to fetch now. [say]: lines to speak now, oldest first. */
    data class Step(val prefetch: List<Item>, val say: List<Item>, val frozen: Map<String, Item>)

    /**
     * One minute of the Wall. [frozen] holds texts fixed earlier (key to item); [said] the keys already spoken.
     * A line is frozen when it comes within [PREFETCH_MS] of its time, and spoken once inside its window.
     */
    fun step(items: List<Item>, now: Long, frozen: Map<String, Item>, said: Set<String>): Step {
        val keep = frozen.filterValues { now < it.until }.toMutableMap()
        val prefetch = mutableListOf<Item>()
        val say = mutableListOf<Item>()
        items.forEach { it0 ->
            if (it0.key in said || now >= it0.until || now < it0.speakAt - PREFETCH_MS) return@forEach
            val item = keep[it0.key] ?: it0.also { keep[it.key] = it; prefetch += it }
            if (now >= item.speakAt) say += item
        }
        return Step(prefetch, say, keep)
    }

    /** Spoken keys worth keeping: today's and yesterday's (keys end in or hold the day). */
    fun pruneSaid(said: Map<String, Int>, today: Int): Map<String, Int> =
        said.filterValues { it == today || it == Day.plus(today, -1) }
}
