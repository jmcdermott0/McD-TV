package com.mcd.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * GuideExtra: name keys, parsing a real i.mjh.nz excerpt (the four channels from J's photo of Oct 29, 2026)
 * and the merge rule (the provider's guide always wins). Channel names are real names from J's playlist.
 */
class GuideExtraTest {
    private fun fixture(): String =
        listOf(File("src/test/resources/guide-extra-roku-fixture.xml"), File("app/src/test/resources/guide-extra-roku-fixture.xml"))
            .first { it.exists() }.readText()

    private fun ch(name: String) = Channel(name, null, "US| ROKU", "http://x/live/u/p/" + name.hashCode() + ".m3u8")

    // 2026-10-29 23:30 UTC (6:30 PM in Huntsville).
    private val now = Epg.parseTime("20261029233000 +0000")!!

    @Test
    fun keysDropLineupTagsAndMarks() {
        assertEquals(listOf("lawandcrime"), GuideExtra.playlistKeys("RK: LAW & CRIME"))
        assertEquals(listOf("lawandcrime"), GuideExtra.playlistKeys("PRIME: LAW & CRIME ᴿᴬᵂ"))
        assertTrue("lawandcrime" in GuideExtra.playlistKeys("US: LAW & CRIME NETWORK ᴿᴬᵂ"))
        assertEquals(listOf("chaosoncambyaande"), GuideExtra.playlistKeys("PRIME: CHAOS ON CAM BY A&E ᴿᴬᵂ"))
        assertEquals(listOf("reelzfamousandinfamous"), GuideExtra.playlistKeys("RK: REELZ FAMOUS AND INFAMOUS"))
        assertEquals(listOf("reelzfamousandinfamous"), GuideExtra.playlistKeys("TUBI: REELZ FAMOUS & INFAMOUS ᴿᴬᵂ"))
        assertEquals(listOf("universalcrime"), GuideExtra.playlistKeys("PRIME: UNIVERSAL CRIME ᴿᴬᵂ"))
        assertEquals(listOf("lawandcrime"), GuideExtra.sourceKeys("Law & Crime"))
        // Guide names keep their own colon: "MGM Presents: Horror" is not just "horror".
        assertEquals(listOf("mgmpresentshorror"), GuideExtra.sourceKeys("MGM Presents: Horror"))
        assertTrue(GuideExtra.playlistKeys("US: TV").isEmpty())
    }

    @Test
    fun photographedChannelsGetRealTitles() = runBlocking {
        val photo = listOf(
            ch("RK: LAW & CRIME"), ch("RK: REELZ FAMOUS AND INFAMOUS"), ch("PRIME: CHAOS ON CAM BY A&E ᴿᴬᵂ"), ch("RK: UNIVERSAL CRIME"),
        )
        val other = ch("US: NBC 13 BIRMINGHAM AL (WVTM) HD")
        val want = GuideExtra.wanted(photo + other)
        val g = GuideExtra.parse(StringReader(fixture()), want, now)
        fun nowOn(c: Channel) = Epg.nowNext(Epg.keyOf(c), g, now).first?.title
        assertEquals("Police Pursuits by Law&Crime", nowOn(photo[0]))
        assertEquals("Sherri Papini's Abduction: REELZ Investigates", nowOn(photo[1]))
        assertEquals("Parking Wars", nowOn(photo[2]))
        assertEquals("Columbo", nowOn(photo[3]))
        assertEquals("Cops Reloaded", Epg.nowNext(Epg.keyOf(photo[0]), g, now).second?.title)
        assertNull(g[Epg.keyOf(other)])
        // Window: on now or starting in the next 8 hours, sorted.
        for (list in g.values) {
            assertTrue(list.zipWithNext().all { (a, b) -> a.start < b.start })
            assertTrue(list.all { it.end > now && it.start < now + 8 * 3600_000L })
        }
        // Filled channels leave the wanted lists, so the next file does not fill them again.
        assertTrue(want.values.none { Epg.keyOf(photo[0]) in it })
        assertTrue(want.values.any { Epg.keyOf(other) in it })
    }

    @Test
    fun secondFileDoesNotRefill() = runBlocking {
        val c = ch("RK: LAW & CRIME")
        val want = GuideExtra.wanted(listOf(c))
        val first = GuideExtra.parse(StringReader(fixture()), want, now)
        val second = GuideExtra.parse(StringReader(fixture()), want, now)
        assertFalse(first.isEmpty())
        assertTrue(second.isEmpty())
    }

    @Test
    fun providerGuideAlwaysWins() {
        val p = Programme("WVTM 13 News at 6", now - 60_000, now + 1_800_000, "")
        val x = Programme("Other show", now - 60_000, now + 1_800_000, "")
        val primary = mapOf("nbc" to listOf(p), "empty" to emptyList())
        val extra = mapOf("nbc" to listOf(x), "empty" to listOf(x), "law" to listOf(x))
        val m = GuideExtra.merge(primary, extra)
        assertEquals(listOf(p), m["nbc"])
        assertEquals(listOf(x), m["empty"])
        assertEquals(listOf(x), m["law"])
        assertTrue(GuideExtra.merge(primary, emptyMap()) === primary)
    }

    @Test
    fun entitiesAndChunkBoundaries() = runBlocking {
        assertEquals("A&E's \"Best\" <1>", GuideExtra.unescape("A&amp;E&#39;s &quot;Best&quot; &lt;1&gt;"))
        // A big padding block pushes elements across the scanner's 64 KB reads.
        val pad = "<!-- " + "x".repeat(200_000) + " -->\n"
        val xml = fixture().replace("<tv generator-info-name", pad + "<tv generator-info-name")
        val c = ch("RK: UNIVERSAL CRIME")
        val g = GuideExtra.parse(StringReader(xml), GuideExtra.wanted(listOf(c)), now)
        assertEquals("Columbo", Epg.nowNext(Epg.keyOf(c), g, now).first?.title)
    }
}
