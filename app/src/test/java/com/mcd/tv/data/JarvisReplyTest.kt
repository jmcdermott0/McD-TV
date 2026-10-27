package com.mcd.tv.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ask Jarvis must never dead-end on a reply it cannot read. Bug of 2026-10-27: "Daniel day Lewis" on the TV's
 * Search tab showed "Jarvis could not read that answer" and only a Try again button.
 */
class JarvisReplyTest {

    /** A Messages API body whose single text block is [text]. */
    private fun api(text: String, stop: String = "end_turn"): String = JSONObject()
        .put("type", "message")
        .put("stop_reason", stop)
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .toString()

    private val good = """{"guesses":[{"title":"There Will Be Blood","year":2007,"type":"movie","confidence":90,"why":"His Oscar turn, sir."},""" +
        """{"title":"Lincoln","year":2012,"type":"movie","confidence":85,"why":"The president himself."}],"clarify":"A man of few roles, sir."}"""

    // ---------------- content() ----------------

    @Test fun contentReadsTextBlocks() {
        val r = JarvisReplyParser.content(api("hello"))
        assertEquals("hello", r.text)
        assertEquals("", r.problem)
    }

    @Test fun contentFlagsEmptyAndBrokenBodies() {
        assertTrue(JarvisReplyParser.content("").problem.isNotBlank())
        assertTrue(JarvisReplyParser.content(null).problem.isNotBlank())
        assertTrue(JarvisReplyParser.content("<html>gateway error</html>").problem.isNotBlank())
        assertTrue(JarvisReplyParser.content("""{"content":[]}""").problem.isNotBlank())
        assertTrue(JarvisReplyParser.content("""{"type":"message"}""").problem.isNotBlank())
    }

    @Test fun contentSkipsNonTextBlocks() {
        val body = JSONObject().put("stop_reason", "end_turn").put(
            "content",
            JSONArray().put(JSONObject().put("type", "thinking").put("thinking", "hmm"))
                .put(JSONObject().put("type", "text").put("text", "{\"guesses\":[]}")),
        ).toString()
        assertEquals("{\"guesses\":[]}", JarvisReplyParser.content(body).text)
    }

    // ---------------- parse() ----------------

    @Test fun strictJson() {
        val r = JarvisReplyParser.parse(good)
        assertEquals(listOf("There Will Be Blood", "Lincoln"), r.guesses.map { it.title })
        assertEquals(2007, r.guesses[0].year)
        assertEquals("movie", r.guesses[0].type)
        assertEquals(90, r.guesses[0].confidence)
        assertEquals("A man of few roles, sir.", r.clarify)
        assertEquals("", r.problem)
    }

    @Test fun emptyAnswer() {
        val r = JarvisReplyParser.parse("")
        assertFalse(r.usable)
        assertTrue(r.problem.isNotBlank())
        assertFalse(JarvisReplyParser.parse(null).usable)
    }

    @Test fun proseAroundJsonAndCodeFences() {
        val r = JarvisReplyParser.parse("Certainly, sir. Here you are:\n```json\n$good\n```\nDo enjoy.")
        assertEquals(2, r.guesses.size)
        assertEquals("Lincoln", r.guesses[1].title)
    }

    @Test fun truncatedJsonKeepsCompleteGuesses() {
        val cut = good.substring(0, good.indexOf("\"why\":\"The president"))
        val r = JarvisReplyParser.parse(cut, "max_tokens")
        assertEquals(listOf("There Will Be Blood", "Lincoln"), r.guesses.map { it.title })
        assertEquals(2012, r.guesses[1].year)
        assertEquals("His Oscar turn, sir.", r.guesses[0].why)
    }

    @Test fun unescapedQuotesInsideWhy() {
        val bad = """{"guesses":[{"title":"There Will Be Blood","year":2007,"type":"movie","confidence":88,"why":"The "milkshake" film, sir."},""" +
            """{"title":"Gangs of New York","year":2002,"type":"movie","confidence":70,"why":"Bill the Butcher."}],"clarify":"Quite."}"""
        val r = JarvisReplyParser.parse(bad)
        assertEquals(listOf("There Will Be Blood", "Gangs of New York"), r.guesses.map { it.title })
        assertEquals("The \"milkshake\" film, sir.", r.guesses[0].why)
        assertEquals(70, r.guesses[1].confidence)
        assertEquals("Quite.", r.clarify)
    }

    @Test fun personIntentWithoutGuesses() {
        val r = JarvisReplyParser.parse("""{"person":"Daniel Day-Lewis","guesses":[],"clarify":"Ah, the great method actor, sir."}""")
        assertTrue(r.usable)
        assertEquals("Daniel Day-Lewis", r.person)
        assertTrue(r.guesses.isEmpty())
    }

    @Test fun personIntentWithGuesses() {
        val r = JarvisReplyParser.parse("""{"person":"Daniel Day-Lewis","guesses":[{"title":"Phantom Thread","year":2017,"type":"movie","confidence":80,"why":"His last role."}],"clarify":"x"}""")
        assertEquals("Daniel Day-Lewis", r.person)
        assertEquals("Phantom Thread", r.guesses.single().title)
    }

    @Test fun topLevelArrayAndOtherKeys() {
        val a = JarvisReplyParser.parse("""[{"title":"Lincoln","year":2012}]""")
        assertEquals("Lincoln", a.guesses.single().title)
        val b = JarvisReplyParser.parse("""{"titles":[{"name":"My Left Foot","year":"1989","type":"film","confidence":0.7}]}""")
        val g = b.guesses.single()
        assertEquals("My Left Foot", g.title)
        assertEquals(1989, g.year)
        assertEquals("movie", g.type)
        assertEquals(70, g.confidence)
    }

    @Test fun proseList() {
        val text = "Daniel Day-Lewis is best known for:\n1. There Will Be Blood (2007)\n2. **Lincoln** (2012)\n- The Last of the Mohicans (1992)\n" +
            "He also starred in *Phantom Thread* (2017)."
        val r = JarvisReplyParser.parse(text)
        assertEquals(listOf("There Will Be Blood", "Lincoln", "The Last of the Mohicans", "Phantom Thread"), r.guesses.map { it.title })
        assertEquals(1992, r.guesses[2].year)
    }

    @Test fun plainProseWithNothingUsable() {
        val r = JarvisReplyParser.parse("I'm terribly sorry, sir, I cannot help with that.")
        assertFalse(r.usable)
        assertEquals("answer had no JSON", r.problem)
    }

    @Test fun brokenJsonWithNoTitles() {
        val r = JarvisReplyParser.parse("""{"guesses":[{"tit""", "max_tokens")
        assertFalse(r.usable)
        assertTrue(r.problem, r.problem.contains("cut off"))
    }

    @Test fun neverThrowsOnJunk() {
        listOf("{", "}", "[", "{{{{", "\"", "{\"guesses\":", "null", "[1,2,", "{\"guesses\":\"x\"}", "\u0000\u0001", "(2007)")
            .forEach { JarvisReplyParser.parse(it) }
    }

    @Test fun capsAtSixGuesses() {
        val arr = (1..10).joinToString(",") { """{"title":"T$it","year":2000}""" }
        assertEquals(6, JarvisReplyParser.parse("""{"guesses":[$arr]}""").guesses.size)
    }

    // ---------------- name helpers ----------------

    @Test fun nameVariantsHyphenateTheSurname() {
        assertEquals(listOf("Daniel day Lewis", "Daniel day-Lewis"), JarvisReplyParser.nameVariants("  Daniel  day Lewis "))
        assertEquals(listOf("Tom Hanks"), JarvisReplyParser.nameVariants("Tom Hanks"))
        assertEquals(JarvisReplyParser.nameKey("Daniel Day-Lewis"), JarvisReplyParser.nameKey("Daniel day Lewis"))
    }

    @Test fun pickPerson() {
        val names = listOf("Daniel Day Kim", "Daniel Day-Lewis", "Lewis Daniel")
        assertEquals(1, JarvisReplyParser.pickPerson("Daniel day Lewis", names))
        assertEquals(1, JarvisReplyParser.pickPerson("day lewis", names))
        assertEquals(0, JarvisReplyParser.pickPerson("Tom Hanks", listOf("Tom Hanks", "Colin Hanks")))
        assertEquals(-1, JarvisReplyParser.pickPerson("Tom Hanks", listOf("Tom Holland")))
        assertEquals(-1, JarvisReplyParser.pickPerson("Interstellar", listOf("Matthew McConaughey")))
        assertEquals(-1, JarvisReplyParser.pickPerson("", names))
    }

    @Test fun looksLikeName() {
        assertTrue(JarvisReplyParser.looksLikeName("Daniel day Lewis"))
        assertTrue(JarvisReplyParser.looksLikeName("Robert Downey Jr."))
        assertTrue(JarvisReplyParser.looksLikeName("Lupita Nyong'o"))
        assertFalse(JarvisReplyParser.looksLikeName("Interstellar"))
        assertFalse(JarvisReplyParser.looksLikeName("movie where they travel through a wormhole"))
        assertFalse(JarvisReplyParser.looksLikeName("Ocean's 11"))
    }

    // ---------------- the whole flow: an actor's name always gets their films ----------------

    private val filmography = listOf("There Will Be Blood", "Lincoln", "Gangs of New York", "Phantom Thread", "The Last of the Mohicans")

    /** Runs the flow with [ai] as the AI reply; the plain search knows Daniel Day-Lewis under either spelling. */
    private fun flow(q: String, ai: suspend () -> JarvisRaw, notes: MutableList<String> = ArrayList(), hints: MutableList<String> = ArrayList()) =
        runBlocking {
            JarvisFlow.run(
                q,
                askAi = ai,
                resolve = { gs -> gs.map { it.title } },
                plain = { hint ->
                    hints += hint
                    val who = hint.ifBlank { q }
                    val known = JarvisReplyParser.nameKey("Daniel Day-Lewis")
                    if (JarvisReplyParser.nameVariants(who).any { JarvisReplyParser.nameKey(it) == known }) filmography else emptyList()
                },
                note = { notes += it },
            )
        }

    private val q = "Daniel day Lewis"

    @Test fun garbageAnswerFallsBackToFilmography() {
        val notes = ArrayList<String>()
        val out = flow(q, { JarvisReplyParser.content(api("Ah, sir, a most distinguished gentleman.")) }, notes)
        assertEquals(filmography, out.matches)
        assertTrue(out.fallback)
        assertEquals(JarvisFlow.FELL_BACK, out.clarify)
        assertEquals("answer had no JSON", out.problem)
        assertTrue(notes.single(), notes.single().contains("answer had no JSON"))
    }

    @Test fun emptyBodyFallsBackToFilmography() {
        val out = flow(q, { JarvisReplyParser.content("") })
        assertEquals(filmography, out.matches)
        assertTrue(out.fallback)
        assertEquals("empty reply body", out.problem)
    }

    @Test fun emptyTextFallsBackToFilmography() {
        assertEquals(filmography, flow(q, { JarvisReplyParser.content(api("")) }).matches)
        assertEquals(filmography, flow(q, { JarvisReplyParser.content(api("{\"guesses\":[]}")) }).matches)
    }

    @Test fun truncatedAnswerWithNoTitleFallsBack() {
        assertEquals(filmography, flow(q, { JarvisReplyParser.content(api("{\"guesses\":[{\"ti", "max_tokens")) }).matches)
    }

    @Test fun missingAnswerFallsBackAndKeepsTheError() {
        val out = flow(q, { throw JarvisException("Jarvis is busy. Try again in a moment.") })
        assertEquals(filmography, out.matches)
        assertTrue(out.clarify, out.clarify.startsWith("Jarvis is busy."))
    }

    @Test fun personIntentUsesTheNameJarvisGave() {
        val hints = ArrayList<String>()
        val out = flow(q, { JarvisReplyParser.content(api("""{"person":"Daniel Day-Lewis","guesses":[],"clarify":"The great man himself, sir."}""")) }, hints = hints)
        assertEquals(filmography, out.matches)
        assertEquals(listOf("Daniel Day-Lewis"), hints)
        assertEquals("The great man himself, sir.", out.clarify)
    }

    @Test fun goodAnswerSkipsTheFallback() {
        val hints = ArrayList<String>()
        val out = flow(q, { JarvisReplyParser.content(api(good)) }, hints = hints)
        assertEquals(listOf("There Will Be Blood", "Lincoln"), out.matches)
        assertFalse(out.fallback)
        assertTrue(hints.isEmpty())
    }

    @Test fun guessesThatMatchNothingFallBack() {
        val out = runBlocking {
            JarvisFlow.run(q, { JarvisReplyParser.content(api(good)) }, { emptyList<String>() }, { filmography })
        }
        assertEquals(filmography, out.matches)
        assertEquals("no guess matched a title", out.problem)
    }

    @Test fun followUpQuestionOnADescriptionIsKept() {
        val out = flow("film where a man builds a boat", { JarvisReplyParser.content(api("""{"guesses":[],"clarify":"Which decade, sir?"}""")) })
        assertTrue(out.matches.isEmpty())
        assertEquals("Which decade, sir?", out.clarify)
        assertFalse(out.fallback)
    }

    @Test fun nothingAnywhereIsAnEmptyAnswerNotACrash() {
        val out = flow("zzqx blorp", { JarvisReplyParser.content(api("no idea")) })
        assertTrue(out.matches.isEmpty())
        assertTrue(out.fallback)
    }

    @Test fun aiErrorAndNoPlainMatchesShowsTheError() {
        try {
            flow("zzqx blorp", { throw JarvisException("Your Jarvis key was rejected. Check it on the Control page.") })
            fail("expected the key error")
        } catch (e: JarvisException) {
            assertTrue(e.message!!.contains("rejected"))
        }
    }

    @Test fun plainSearchFailureAfterUnreadableAnswerIsAFriendlyError() {
        try {
            runBlocking { JarvisFlow.run(q, { JarvisReplyParser.content(api("junk")) }, { emptyList<String>() }, { throw IllegalStateException("Add your TMDB API key") }) }
            fail("expected an error")
        } catch (e: JarvisException) {
            assertEquals("Add your TMDB API key", e.message)
        }
    }
}
