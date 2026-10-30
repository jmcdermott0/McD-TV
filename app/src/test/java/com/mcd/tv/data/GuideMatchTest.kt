package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Which guide id each channel reads ([GuideMatch]). The cases are real channel names from J's playlist
 * (tools/epg/vectors-input.json); tools/epg/guidematch-expected.json holds the answers, and the web app's guideMatch
 * must give the same ones (tools/epg/guidematch-test.mjs).
 */
class GuideMatchTest {

    private fun find(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above " + System.getProperty("user.dir"))
    }

    private val expected: JSONObject by lazy { JSONObject(find("tools/epg/guidematch-expected.json").readText()) }

    private class Run(val chs: List<Channel>, val plan: GuideMatch.Plan) {
        fun id(i: Int) = plan.idOf["ch$i"] ?: ""
        fun how(i: Int) = plan.how["ch$i"] ?: ""
        fun shift(i: Int) = if ("ch$i" in plan.shifted) GuideMatch.WEST_SHIFT_MS else 0L
    }

    /** Channels keyed "ch0", "ch1", ... so copies that share a link still count apart. */
    private fun run(list: List<Triple<String, String, String>>): Run {
        val chs = list.mapIndexed { i, (name, group, id) -> Channel(name, null, group, "ch$i", id.ifBlank { null }, null) }
        return Run(chs, GuideMatch.plan(chs) { it.url })
    }

    private val vectors: Run by lazy {
        val a = JSONObject(find("tools/epg/vectors-input.json").readText()).getJSONArray("channels")
        run(List(a.length()) { a.getJSONArray(it).let { r -> Triple(r.getString(0), r.getString(1), r.getString(2)) } })
    }

    private fun index(name: String): Int = vectors.chs.indexOfFirst { it.name == name }.also { assertTrue("no vector $name", it >= 0) }

    @Test
    fun sameAnswersAsTheWebApp() {
        val exp: JSONArray = expected.getJSONArray("vectors")
        assertEquals(exp.length(), vectors.chs.size)
        for (i in 0 until exp.length()) {
            val e = exp.getJSONObject(i)
            val name = vectors.chs[i].name
            assertEquals(name, e.getString("name"))
            assertEquals("id of $name", e.getString("id"), vectors.id(i))
            assertEquals("rule of $name", e.getString("how"), vectors.how(i))
            assertEquals("shift of $name", e.getLong("shift"), vectors.shift(i))
        }
    }

    @Test
    fun fixturePlaylistMatchesTheWebApp() {
        val text = GZIPInputStream(find("tools/livehome/fixture/playlist.m3u.gz").inputStream()).bufferedReader().readText()
        val raw = M3u.parse(text)
        val r = run(raw.map { Triple(it.name, it.group, it.tvgId ?: "") })
        val fx = expected.getJSONObject("fixture")
        assertEquals(fx.getInt("channels"), raw.size)
        val lines = raw.indices.joinToString("\n") { "$it\t${r.id(it)}\t${r.how(it)}\t${r.shift(it)}" }
        val sha = MessageDigest.getInstance("SHA-256").digest(lines.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(fx.getString("sha256"), sha)
    }

    @Test
    fun photographedChannelsHaveNoGuideIdToBorrow() {
        // Oct 29 photo: these showed "No guide info". No copy of them carries a guide id in the playlist, and the
        // provider's guide table is empty for each, so nothing may be borrowed (Reelz the network is not Reelz
        // Famous & Infamous, and Chaos on Cam is not A&E).
        for (n in listOf(
            "RK: LAW & CRIME ᴿᴬᵂ", "PRIME: LAW & CRIME ᴿᴬᵂ", "AT&T: LAW & CRIME ᴿᴬᵂ", "US: LAW & CRIME",
            "RK: REELZ FAMOUS AND INFAMOUS ᴿᴬᵂ", "TUBI: REELZ FAMOUS & INFAMOUS ᴿᴬᵂ",
            "PRIME: CHAOS ON CAM BY A&E ᴿᴬᵂ", "RK: UNIVERSAL CRIME ᴿᴬᵂ", "PRIME: UNIVERSAL CRIME ᴿᴬᵂ",
        )) assertEquals(n, "", vectors.id(index(n)))
    }

    @Test
    fun eachRuleOnRealNames() {
        fun check(name: String, id: String, how: String, shifted: Boolean = false) {
            val i = index(name)
            assertEquals("id of $name", id, vectors.id(i))
            assertEquals("rule of $name", how, vectors.how(i))
            assertEquals("shift of $name", shifted, vectors.shift(i) != 0L)
        }
        check("US: NBC 13 BIRMINGHAM AL (WVTM) HD", "wvtm.us", GuideMatch.OWN)
        check("AT&T: A WEALTH OF ENTERTAINMENT ᴿᴬᵂ", "awealthofentertainment.us", GuideMatch.STEM)
        check("TV: CNN ᴿᴬᵂ", "cnn.us", GuideMatch.LINEUP)
        check("TV: MS NOW ᴿᴬᵂ", "msnbc.us", GuideMatch.NETWORK)
        check("AT&T: E! ᴿᴬᵂ", "eentertainment.us", GuideMatch.NETWORK)
        check("US: CBS (WCBS)", "wcbs.us", GuideMatch.CALL)
        check("US: NBC (WVTM) BIRMINGHAM, AL (D) ᴿᴬᵂ", "wvtm.us", GuideMatch.CALL)
        // West feeds: East listings 3 hours later, also when the provider filed the West copy under the East id.
        check("US: HBO WEST HD", "hbo.us", GuideMatch.OWN, shifted = true)
        check("TV: HBO WEST ᴿᴬᵂ", "hbo.us", GuideMatch.LINEUP, shifted = true)
        check("US: STARZ WEST HD", "starz.us", GuideMatch.WEST, shifted = true)
        check("US: SHOWTIME WEST HD", "showtimewest.us", GuideMatch.OWN) // its own West guide: no shift
    }

    @Test
    fun guards() {
        fun none(name: String) = assertEquals(name, "", vectors.id(index(name)))
        none("TV: THE WEATHER CHANNEL ᴿᴬᵂ") // the only lender is filed under a Canadian id
        none("PRIME: ABC DENVER NEWS (KMGH) ᴿᴬᵂ") // free lineup station channels are news-only streams
        none("US: NFL CHANNEL ᴿᴬᵂ") // the free NFL Channel, not NFL Network
        none("AT&T: HALLMARK MYSTERY ᴿᴬᵂ") // hallmark.us is filed under two networks
        none("CA: CBS WEST") // a network's West feed is another station
        none("PRIME: AMC + ᴿᴬᵂ") // AMC+ is not AMC
        none("AT&T: NFL NETWORK ALT ᴿᴬᵂ")
        none("US: FOX SPORTS WEST") // regional network, not a West feed of anything
    }

    @Test
    fun westTwinAndCallSign() {
        assertEquals("hbo", GuideMatch.westTwin("hbo west"))
        assertEquals("starz encore", GuideMatch.westTwin("starz encore west"))
        assertNull(GuideMatch.westTwin("sportsnet west"))
        assertNull(GuideMatch.westTwin("cbs west"))
        assertNull(GuideMatch.westTwin("wflx west palm beach"))
        assertEquals("wplg", GuideMatch.callSign("US: ABC 10 (WPLG) Miami (A) HD"))
        assertEquals("wsfl", GuideMatch.callSign("US: CW 39 (WSFL-TV) Miami (A) HD"))
        assertEquals("ktrk", GuideMatch.callSign("RK: KTRK ABC13 HOUSTON"))
        assertNull(GuideMatch.callSign("US: WEST TEXAS RODEO"))
        assertNull(GuideMatch.callSign("US: KIDS CHANNEL"))
    }
}
