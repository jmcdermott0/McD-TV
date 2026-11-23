package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import java.util.Calendar

/**
 * Jarvis voice: the words. Pure; tests in VoiceLinesTest. Short plain sentences in the Jarvis butler voice.
 * The same text shows on screen while it is spoken.
 */
object VoiceLines {
    const val STILL_WATCHING = "Still watching, sir?"
    const val SLEEP_WARNING = "The sleep timer ends in one minute."

    /** Most characters Jarvis sends for one line (a long line costs more and keeps talking over the room). */
    const val MAX_CHARS = 300

    /** Days before the due date on which the morning briefing mentions the baby. 0 to 6 count every day. */
    private val BABY_MILESTONES = setOf(100, 90, 60, 50, 40, 30, 21, 14, 7)

    /** Most events the briefing names. */
    private const val BRIEF_EVENTS = 4

    /**
     * Text ready to speak: one line, no square brackets (the voice service reads [words] as delivery tags),
     * no repeated spaces, cut at a word under [MAX_CHARS].
     */
    fun clean(text: String): String {
        val t = text.replace('[', ' ').replace(']', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.length <= MAX_CHARS) return t
        val cut = t.take(MAX_CHARS)
        val at = cut.lastIndexOf(' ').takeIf { it > MAX_CHARS / 2 } ?: cut.length
        return cut.take(at).trimEnd(',', ';', ':', ' ') + "."
    }

    fun greeting(hourOfDay: Int): String = when {
        hourOfDay < 12 -> "Good morning, sir."
        hourOfDay < 17 -> "Good afternoon, sir."
        else -> "Good evening, sir."
    }

    fun test(hourOfDay: Int): String = greeting(hourOfDay) + " Jarvis voice is working."

    /** "3 PM", "3:30 PM", "noon". */
    fun spokenTime(ms: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        if (h == 12 && m == 0) return "noon"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ampm" else "$h12:${m.toString().padStart(2, '0')} $ampm"
    }

    /** A calendar title as Jarvis says it: trimmed, one line, at most 80 characters. */
    fun title(t: String): String = t.replace(Regex("\\s+"), " ").trim().take(80).trimEnd()

    fun headsUp(title: String): String = clean("Pardon me. ${title(title)} starts in 15 minutes.")

    fun dueSoon(titles: List<String>): String {
        val names = titles.map(::title).filter { it.isNotEmpty() }.distinct()
        return when (names.size) {
            0 -> ""
            1 -> clean("A reminder: ${names[0]} is due tomorrow.")
            else -> clean("A reminder: due tomorrow are ${join(names.take(3))}.")
        }
    }

    /** True for a school item: a school calendar, or a title starting with "SNHU" (as the Wall's Due soon box). */
    fun isSchool(feed: Wall.Feed, e: Wall.Event): Boolean =
        feed.sources.getOrNull(e.source)?.kind == "school" || e.title.startsWith("SNHU", ignoreCase = true)

    /**
     * The morning briefing for [today]: greeting, weather, today's calendar, and the baby countdown on a
     * milestone day. [babyDue] is a yyyymmdd day or null; [babyName] may be blank.
     */
    fun briefing(feed: Wall.Feed?, today: Int, hourOfDay: Int, babyDue: Int?, babyName: String): String {
        val parts = mutableListOf(greeting(hourOfDay))
        feed?.weather?.let { w ->
            val day = w.days.firstOrNull { it.day == today }
            val sky = Wall.weatherLabel(w.code).lowercase()
            var s = "It is ${w.now} degrees" + (if (sky.isNotEmpty()) " and $sky" else "")
            if (day != null) s += ", with a high of ${day.hi}"
            parts += "$s."
            if ((day?.rain ?: 0) >= 50) parts += "Rain is likely today."
        }
        if (feed != null) {
            val events = feed.events
                .filter { it.isOn(today) && !isSchool(feed, it) && title(it.title).isNotEmpty() }
                .sortedWith(compareBy({ !it.allDay }, { it.start ?: 0L }))
            val named = events.take(BRIEF_EVENTS).map { e ->
                if (e.allDay || e.start == null || Day.ofMillis(e.start) != today) title(e.title)
                else "${title(e.title)} at ${spokenTime(e.start)}"
            }
            parts += if (named.isEmpty()) "Nothing is on the calendar today." else "Today: ${join(named)}."
        }
        if (babyDue != null) {
            BabyCountdown.state(today, babyDue)?.let { st ->
                if (st.daysLeft in 0..6 || st.daysLeft in BABY_MILESTONES) parts += st.headlineFor(babyName) + "."
            }
        }
        return clean(parts.joinToString(" "))
    }

    /** "a", "a and b", "a, b and c". */
    fun join(items: List<String>): String = when (items.size) {
        0 -> ""
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
