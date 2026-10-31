package com.mcd.tv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The house guide on McD-Server (tools/house-guide): its channel names must reach J's playlist copies through
 * GuideExtra's name keys, and must not touch channels the provider's guide already fills (WZDX, ESPN).
 * The programmes here are made up; the real listings are never stored in the repo.
 */
class HouseGuideTest {
    private val now = 1_793_480_000_000L

    private fun channelsFile(): File =
        listOf(File("tools/house-guide/huntsville.channels.xml"), File("../tools/house-guide/huntsville.channels.xml")).first { it.exists() }

    /** A stand-in for the grabbed file: the real channel ids and names, one made-up show each. */
    private fun standIn(): String {
        val ch = Regex("xmltv_id=\"([^\"]+)\">([^<]+)</channel>").findAll(channelsFile().readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(6, ch.size)
        val start = "20261031200000 +0000"
        val stop = "20261031230000 +0000"
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" ?><tv>" +
            ch.joinToString("") { (id, name) -> "<channel id=\"$id\"><display-name>$name</display-name></channel>" } +
            ch.joinToString("") { (id, _) -> "<programme start=\"$start\" stop=\"$stop\" channel=\"$id\"><title lang=\"en\">Show on $id</title></programme>" } +
            "</tv>"
    }

    @Test fun houseChannelsReachThePlaylistCopies() = runBlocking {
        // Real names from J's playlist (Oct 31, 2026).
        val fills = listOf(
            "US: NBC 48 (WAFF) HUNTSVILLE (D) ᴿᴬᵂ", "US: NBC 48 (WAFF) HUNTSVILLE (H) ᴿᴬᵂ", "US: CBS 19 (WHNT) HUNTSVILLE HD",
            "US: ABC 31 HUNTSVILLE AL (WAAY)", "US: ESPN 2 HD", "AT&T: ESPN2 ᴿᴬᵂ", "TV: ESPN2 ᴿᴬᵂ", "US: ESPN U HD", "AT&T: ESPNU ᴿᴬᵂ",
            "AT&T: NFL REDZONE ᴿᴬᵂ", "TV: NFL REDZONE ᴿᴬᵂ",
        )
        val untouched = listOf("US: FOX 54 (WZDX) HUNTSVILLE HD", "US: ESPN HD", "US: ESPN NEWS HD", "US: CBS 42 BIRMINGHAM AL (WIAT) HD")
        val chs = (fills + untouched).mapIndexed { i, n -> Channel(n, null, "US| X", "u$i") }
        val g = GuideExtra.parse(standIn().reader(), GuideExtra.wanted(chs), now)
        for ((i, n) in fills.withIndex()) assertTrue(n, g["u$i"].orEmpty().isNotEmpty())
        for ((k, n) in untouched.withIndex()) assertTrue(n, g["u${fills.size + k}"].isNullOrEmpty())
    }

    @Test fun houseSourceIsLastAndOnTheHomeNetwork() {
        assertEquals(GuideExtra.HOUSE, GuideExtra.SOURCES.last())
        assertTrue(GuideExtra.HOUSE.startsWith("http://192.168.1.50:8787/"))
    }
}
