package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Jarvis voice: the policy, the words, the cache and the service request. Pure parts only. */
class VoiceRulesTest {
    private val now = WallVoice.at(20261124, 14 * 60) // Tuesday Nov 24 2026, 2 PM local

    private fun ctx(
        hour: Int = 14, key: Boolean = true, on: Boolean = true, off: Set<String> = emptySet(),
        video: Boolean = false, kids: Boolean = false, lastWall: Long = 0L,
    ) = VoicePolicy.Ctx(now, hour, key, on, off, video, kids, lastWall)

    // ---------------- policy ----------------

    @Test
    fun noKeyMeansSilentForEveryKind() {
        VoiceKind.entries.forEach { k -> assertEquals(VoicePolicy.Skip.NO_KEY, VoicePolicy.skip(k, now + 60_000, ctx(key = false))) }
    }

    @Test
    fun speaksWhenEverythingAllows() {
        assertNull(VoicePolicy.skip(VoiceKind.ASK, now + 60_000, ctx()))
        assertNull(VoicePolicy.skip(VoiceKind.BRIEFING, now + 60_000, ctx()))
    }

    @Test
    fun switchesQuietHoursVideoAndKids() {
        assertEquals(VoicePolicy.Skip.OFF, VoicePolicy.skip(VoiceKind.ASK, now + 1, ctx(on = false)))
        assertEquals(VoicePolicy.Skip.USE_OFF, VoicePolicy.skip(VoiceKind.ASK, now + 1, ctx(off = setOf("ask"))))
        assertEquals(VoicePolicy.Skip.QUIET, VoicePolicy.skip(VoiceKind.BRIEFING, now + 1, ctx(hour = 23)))
        assertEquals(VoicePolicy.Skip.QUIET, VoicePolicy.skip(VoiceKind.SLEEP, now + 1, ctx(hour = 5)))
        assertNull(VoicePolicy.skip(VoiceKind.SLEEP, now + 1, ctx(hour = 6)))
        assertEquals(VoicePolicy.Skip.VIDEO, VoicePolicy.skip(VoiceKind.ASK, now + 1, ctx(video = true)))
        assertNull(VoicePolicy.skip(VoiceKind.STILL_WATCHING, now + 1, ctx(video = true)))
        assertNull(VoicePolicy.skip(VoiceKind.SLEEP, now + 1, ctx(video = true)))
        assertEquals(VoicePolicy.Skip.KIDS, VoicePolicy.skip(VoiceKind.ASK, now + 1, ctx(kids = true)))
        assertNull(VoicePolicy.skip(VoiceKind.STILL_WATCHING, now + 1, ctx(kids = true)))
    }

    @Test
    fun wallSpacingAndLateLines() {
        assertEquals(VoicePolicy.Skip.SPACING, VoicePolicy.skip(VoiceKind.REMINDER, now + 1, ctx(lastWall = now - 60_000)))
        assertNull(VoicePolicy.skip(VoiceKind.REMINDER, now + 1, ctx(lastWall = now - 3 * 60_000)))
        assertNull(VoicePolicy.skip(VoiceKind.ASK, now + 1, ctx(lastWall = now - 60_000))) // only Wall lines space out
        assertEquals(VoicePolicy.Skip.LATE, VoicePolicy.skip(VoiceKind.ASK, now - 1, ctx()))
    }

    @Test
    fun testLineIgnoresSwitchesButNeedsAKey() {
        assertNull(VoicePolicy.skip(VoiceKind.TEST, now + 1, ctx(on = false, hour = 2, video = true)))
        assertEquals(VoicePolicy.Skip.NO_KEY, VoicePolicy.skip(VoiceKind.TEST, now + 1, ctx(key = false)))
    }

    @Test
    fun usesOffRoundTripAndKeysNeverChange() {
        assertEquals(setOf("ask", "still"), VoicePolicy.parseOff(VoicePolicy.encodeOff(setOf("still", "ask"))))
        assertEquals(emptySet<String>(), VoicePolicy.parseOff(""))
        // Stored names: renaming one would turn a switched-off use back on.
        assertEquals(listOf("ask", "briefing", "reminders", "still", "sleep", "test"), VoiceKind.entries.map { it.key })
    }

    // ---------------- words ----------------

    @Test
    fun cleanRemovesTagsAndCapsLength() {
        assertEquals("Hello there, sir.", VoiceLines.clean("  Hello [laughs]   there, sir. ".replace("[laughs]", "")))
        assertEquals("a b c", VoiceLines.clean("a [b] c"))
        val long = VoiceLines.clean("word ".repeat(200))
        assertTrue(long.length <= VoiceLines.MAX_CHARS)
        assertTrue(long.endsWith("."))
    }

    @Test
    fun spokenTimes() {
        assertEquals("3 PM", VoiceLines.spokenTime(WallVoice.at(20261124, 15 * 60)))
        assertEquals("3:30 PM", VoiceLines.spokenTime(WallVoice.at(20261124, 15 * 60 + 30)))
        assertEquals("9:05 AM", VoiceLines.spokenTime(WallVoice.at(20261124, 9 * 60 + 5)))
        assertEquals("noon", VoiceLines.spokenTime(WallVoice.at(20261124, 12 * 60)))
        assertEquals("12 AM", VoiceLines.spokenTime(WallVoice.at(20261124, 0)))
    }

    @Test
    fun fixedAndReminderLines() {
        assertEquals("Still watching, sir?", VoiceLines.STILL_WATCHING)
        assertEquals("Pardon me. Soccer practice starts in 15 minutes.", VoiceLines.headsUp("  Soccer   practice "))
        assertEquals("A reminder: Essay 3 is due tomorrow.", VoiceLines.dueSoon(listOf("Essay 3")))
        assertEquals("A reminder: due tomorrow are Essay 3, Quiz 2 and Lab.", VoiceLines.dueSoon(listOf("Essay 3", "Quiz 2", "Lab", "Lab")))
        assertEquals("", VoiceLines.dueSoon(emptyList()))
        assertEquals("Good morning, sir. Jarvis voice is working.", VoiceLines.test(8))
        assertFalse(listOf(VoiceLines.STILL_WATCHING, VoiceLines.SLEEP_WARNING, VoiceLines.test(20)).any { '\u2014' in it })
    }

    private val today = 20261124
    private val sources = listOf(
        Wall.Source("Family", "F", 0, "family", null),
        Wall.Source("School", "S", 0, "school", null),
    )

    private fun feed(events: List<Wall.Event>, rain: Int? = 10) = Wall.Feed(
        generated = now, sources = sources, events = events,
        weather = Wall.Weather(41, 2, listOf(Wall.WeatherDay(today, 63, 38, 2, rain))),
    )

    @Test
    fun briefingSaysWeatherAndTodayInOrder() {
        val f = feed(listOf(
            Wall.Event(0, "Dentist", start = WallVoice.at(today, 15 * 60 + 30), end = WallVoice.at(today, 16 * 60)),
            Wall.Event(0, "Trash day", day = today),
            Wall.Event(1, "Essay 3", day = today), // school: never in the briefing
            Wall.Event(0, "Tomorrow thing", day = Day.plus(today, 1)),
        ))
        assertEquals(
            "Good morning, sir. It is 41 degrees and partly cloudy, with a high of 63. Today: Trash day and Dentist at 3:30 PM.",
            VoiceLines.briefing(f, today, 7, null, ""),
        )
        assertTrue(VoiceLines.briefing(feed(emptyList(), rain = 70), today, 7, null, "").contains("Nothing is on the calendar today. ").not())
        assertTrue(VoiceLines.briefing(feed(emptyList(), rain = 70), today, 7, null, "").contains("Rain is likely today."))
        assertEquals("Good morning, sir.", VoiceLines.briefing(null, today, 7, null, ""))
    }

    @Test
    fun briefingMentionsTheBabyOnlyOnMilestones() {
        val f = feed(emptyList())
        assertTrue(VoiceLines.briefing(f, today, 7, Day.plus(today, 30), "Sam").endsWith("30 days until Sam."))
        assertTrue(VoiceLines.briefing(f, today, 7, Day.plus(today, 3), "").endsWith("3 days to go."))
        assertFalse(VoiceLines.briefing(f, today, 7, Day.plus(today, 31), "Sam").contains("Sam"))
    }

    // ---------------- cache ----------------

    @Test
    fun cacheKeyChangesWithVoiceModelSpeedAndText() {
        val k = VoiceCache.key("s2.1-pro", "v1", 1.1, "Hello")
        assertEquals(64, k.length)
        assertEquals(k, VoiceCache.key("s2.1-pro", "v1", 1.1, "Hello"))
        assertFalse(k == VoiceCache.key("s2.1-pro", "v2", 1.1, "Hello"))
        assertFalse(k == VoiceCache.key("drama-3", "v1", 1.1, "Hello"))
        assertFalse(k == VoiceCache.key("s2.1-pro", "v1", 1.0, "Hello"))
        assertFalse(k == VoiceCache.key("s2.1-pro", "v1", 1.1, "Hello."))
    }

    @Test
    fun evictsOldestFirstUntilUnderCap() {
        val e = listOf(VoiceCache.Entry("new", 40, 300), VoiceCache.Entry("old", 40, 100), VoiceCache.Entry("mid", 40, 200))
        assertEquals(emptyList<String>(), VoiceCache.evictPlan(e, 120))
        assertEquals(listOf("old"), VoiceCache.evictPlan(e, 100))
        assertEquals(listOf("old", "mid"), VoiceCache.evictPlan(e, 50))
    }

    @Test
    fun cacheSavesAndFindsAFile() {
        val dir: File = Files.createTempDirectory("voice").toFile()
        try {
            val key = VoiceCache.key("m", "v", 1.1, "Hi")
            assertNull(VoiceCache.get(dir, key))
            VoiceCache.put(dir, key, ByteArray(1000) { 1 })
            assertEquals(1000L, VoiceCache.get(dir, key)?.length())
            assertTrue(VoiceCache.has(dir, key))
            assertFalse(File(dir, "$key.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------------- service request ----------------

    @Test
    fun requestBodyAndHeaders() {
        val b = org.json.JSONObject(FishTts.body("Hello, sir.", "voice123"))
        assertEquals("Hello, sir.", b.getString("text"))
        assertEquals("voice123", b.getString("reference_id"))
        assertEquals("mp3", b.getString("format"))
        assertEquals(64, b.getInt("mp3_bitrate"))
        assertEquals(1.1, b.getJSONObject("prosody").get("speed").toString().toDouble(), 0.0001)
        val h = FishTts.headers("test-key", "")
        assertEquals("Bearer test-key", h["Authorization"])
        assertEquals("s2.1-pro", h["model"])
    }

    @Test
    fun errorRepliesMapToFailures() {
        assertEquals(VoiceMachine.Failure.KEY, FishTts.failure(401, ""))
        assertEquals(VoiceMachine.Failure.CREDIT, FishTts.failure(402, ""))
        assertEquals(VoiceMachine.Failure.VOICE, FishTts.failure(404, ""))
        assertEquals(VoiceMachine.Failure.VOICE, FishTts.failure(400, "{\"message\":\"reference_id not found\"}"))
        assertEquals(VoiceMachine.Failure.OTHER, FishTts.failure(400, "{\"message\":\"bad text\"}"))
        assertEquals(VoiceMachine.Failure.BUSY, FishTts.failure(429, ""))
        assertEquals(VoiceMachine.Failure.BUSY, FishTts.failure(503, ""))
    }

    @Test
    fun mp3Sniffing() {
        assertTrue(FishTts.looksLikeMp3(ByteArray(300).also { it[0] = 'I'.code.toByte(); it[1] = 'D'.code.toByte(); it[2] = '3'.code.toByte() }))
        assertTrue(FishTts.looksLikeMp3(ByteArray(300).also { it[0] = 0xFF.toByte(); it[1] = 0xFB.toByte() }))
        assertFalse(FishTts.looksLikeMp3("{\"status\":200}".padEnd(300).toByteArray()))
        assertFalse(FishTts.looksLikeMp3(ByteArray(10)))
    }
}
