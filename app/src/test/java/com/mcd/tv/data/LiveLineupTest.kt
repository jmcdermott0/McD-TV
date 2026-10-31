package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * My Guide's curated lineup (LiveLineup) on real channel names from J's playlist (Oct 31, 2026):
 * test resources live-lineup-fixture.txt. Listings ("P", "X") are stood in by one future programme each.
 */
class LiveLineupTest {
    private val now = 1_793_500_000_000L

    private class Fixture(val channels: List<Channel>, val guide: Map<String, List<Programme>>)

    private fun fixture(): Fixture {
        val f = listOf(File("src/test/resources/live-lineup-fixture.txt"), File("app/src/test/resources/live-lineup-fixture.txt")).first { it.exists() }
        val groups = HashMap<String, String>()
        val channels = ArrayList<Channel>()
        val guide = HashMap<String, List<Programme>>()
        f.readLines().forEach { line ->
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val p = line.split('|')
            if (p[0] == "GROUP") { groups[p[1]] = p.drop(2).joinToString("|"); return@forEach }
            val url = "http://example.invalid/live/${channels.size}.ts"
            val ch = Channel(p[2], null, groups.getValue(p[0]), url, tvgId = p[1].ifBlank { null })
            channels += ch
            if (p[3] == "P" || p[3] == "X") guide[url] = listOf(Programme("Show", now - 600_000, now + 1_800_000, ""))
        }
        return Fixture(channels, guide)
    }

    private fun picks(): Pair<Fixture, List<LiveLineup.Pick>> = fixture().let { it to LiveLineup.picks(it.channels, it.guide, now) }

    @Test fun normDropsLineupFeedAndQualityTags() {
        assertEquals("reelz famus", LiveLineup.norm("US: REELZ FAMUS HD"))
        assertEquals("nbc 48 (waff) huntsville", LiveLineup.norm("US: NBC 48 (WAFF) HUNTSVILLE (D) ᴿᴬᵂ"))
        assertEquals("fox sports 1", LiveLineup.norm("US: FOX SPORTS 1 4K"))
        assertEquals("a&e", LiveLineup.norm("AT&T: A&E ᴿᴬᵂ"))
        assertEquals("reelzchannel", LiveLineup.norm("TV: REELZCHANNEL ᴿᴬᵂ"))
    }

    @Test fun oneReelzOnlyAndItHasTheGuide() {
        val (fx, p) = picks()
        val reelz = p.filter { it.net.label == "Reelz" }
        assertEquals(1, reelz.size)
        assertEquals("US: REELZ FAMUS HD", fx.channels[reelz[0].index].name)
        // The playlist's 7 Reelz-named copies: 4 are Reelz itself, 3 are the free "Famous and Infamous" spin-off.
        val named = fx.channels.filter { it.name.contains("REELZ", ignoreCase = true) }
        assertEquals(7, named.size)
        assertEquals(4, named.count { LiveLineup.netOf(it)?.label == "Reelz" })
        assertNull(LiveLineup.netOf(fx.channels.first { it.name.startsWith("RK: REELZ FAMOUS") }))
    }

    @Test fun oneCopyPerNetworkInGroupOrder() {
        val (_, p) = picks()
        assertEquals(p.size, p.map { it.net }.toSet().size)
        assertEquals(40, p.size)
        val sizes = p.groupBy { it.net.group }.mapValues { it.value.size }
        assertEquals(mapOf(LiveLineup.LOCALS to 4, LiveLineup.SPORTS to 17, LiveLineup.CRIME to 4, LiveLineup.MOVIES to 6, LiveLineup.MORE to 9), sizes)
        // Group order holds: no pick of an earlier group after a later one.
        val order = p.map { LiveLineup.GROUPS.indexOf(it.net.group) }
        assertEquals(order.sorted(), order)
    }

    @Test fun usFeedsOnlyAndNoEvents() {
        val (fx, p) = picks()
        for (x in p) assertTrue(fx.channels[x.index].group.startsWith("US|"))
        assertNull(LiveLineup.netOf(Channel("CA: NBA TV", null, "CA| CANADA ᴴᴰ/ᴿᴬᵂ ⁶⁰ᶠᵖˢ", "u1", "NBATV.ca")))
        assertNull(LiveLineup.netOf(Channel("US: ESPN HD", null, "US| PPV EVENT ⁽ᴮᴷ⁾", "u2")))
    }

    @Test fun picksTheCopyWithListingsThenItsOwnIdThenTheBestFeed() {
        val (fx, p) = picks()
        val by = p.associate { it.net.label to fx.channels[it.index] }
        assertEquals("US: FOX SPORTS 1 4K", by.getValue("FS1").name)
        assertEquals("US: ESPN USA HD", by.getValue("ESPN").name)
        assertEquals("US: TNT HD", by.getValue("TNT").name)
        assertEquals("US: HBO HD", by.getValue("HBO").name)
        assertEquals("US: FOX 54 (WZDX) HUNTSVILLE HD", by.getValue("WZDX 54 FOX").name)
        assertEquals("US: NBC SPORTS PHILADELFIA", by.getValue("NBC Sports Philadelphia").name)
        assertEquals("US: NBC 48 (WAFF) HUNTSVILLE (D) ᴿᴬᵂ", by.getValue("WAFF 48 NBC").name)
        // A copy with listings beats a better-looking copy without.
        val a = Channel("US: TNT 4K", null, "US| ENTERTAINMENT", "a")
        val b = Channel("AT&T: TNT ᴿᴬᵂ", null, "US| AT&T", "b")
        val g = mapOf("b" to listOf(Programme("NBA", now, now + 3_600_000, "")))
        assertEquals("b", LiveLineup.picks(listOf(a, b), g, now).single().let { listOf(a, b)[it.index].url })
    }

    @Test fun everyPickHasListingsExceptTheKnownGaps() {
        val (fx, p) = picks()
        val blank = p.filter { fx.guide[fx.channels[it.index].url].isNullOrEmpty() }.map { it.net.label }.toSet()
        // No guide source carries these: the three Huntsville stations (game listings only), ESPN2 and ESPNU
        // (ESPN game listings only), and NFL RedZone.
        assertEquals(setOf("WAFF 48 NBC", "WHNT 19 CBS", "WAAY 31 ABC", "ESPN2", "ESPNU", "NFL RedZone"), blank)
    }

    @Test fun leftOutNetworksNeverJoin() {
        val (fx, _) = picks()
        for (n in listOf("AT&T: TBS ᴿᴬᵂ", "US: TCM HD", "US: FXM HD", "AT&T: MGM+ ᴿᴬᵂ", "US: HBO 2 HD", "AT&T: INVESTIGATION DISCOVERY ᴿᴬᵂ",
            "AT&T: NATIONAL GEOGRAPHIC ᴿᴬᵂ", "AT&T: PARAMOUNT NETWORK ᴿᴬᵂ")) {
            assertNull(n, LiveLineup.netOf(fx.channels.first { it.name == n }))
        }
    }

    @Test fun sectionHasOneSubgroupPerGroup() {
        val fx = fixture()
        val s = LiveLineup.section(fx.channels, fx.guide, now)
        assertEquals(LiveLineup.GROUPS, s.subgroups.map { it.label })
        assertEquals(40, s.count)
        assertEquals(s.items.toList(), s.subgroups.flatMap { it.items.toList() })
    }
}
