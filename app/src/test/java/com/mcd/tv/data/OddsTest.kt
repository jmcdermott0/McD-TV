package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Odds.kt on real ESPN answers read on Nov 16, 2026 (links and logos removed, values unchanged):
 *  - odds-scoreboard-nfl.json: Monday Night Football LAC at BAL before kick-off (DraftKings), plus two finals.
 *  - odds-core-nfl-pre.json: the core odds endpoint for the same game (no live provider before the game).
 *  - odds-scoreboard-soccer-live.json / odds-core-live-a.json / -b.json: Nations League games in play (18' and 22'),
 *    where the core endpoint lists "DraftKings - Live Odds" (provider 200) and its lines moved between the reads.
 */
class OddsTest {
    private fun res(name: String): String =
        listOf(File("src/test/resources/$name"), File("app/src/test/resources/$name")).first { it.exists() }.readText()

    @Test
    fun scoreboardPreGameLinesAreReadPerSide() {
        val (games, _) = Games.parse(res("odds-scoreboard-nfl.json"), League.NFL)
        val g = games.first { it.id == "401873065" }
        assertEquals("BAL", g.home.abbr)
        val o = assertNotNullOdds(g.odds)
        assertEquals("DraftKings", o.provider)
        assertFalse(o.live)
        assertEquals("-4.5", o.homeSpread)
        assertEquals("-112", o.homeSpreadPrice)
        assertEquals("+4.5", o.awaySpread)
        assertEquals("-108", o.awaySpreadPrice)
        assertEquals("39.5", o.total)
        assertEquals("-105", o.overPrice)
        assertEquals("-115", o.underPrice)
        assertEquals("-225", o.homeMl)
        assertEquals("+185", o.awayMl)
        assertEquals("", o.drawMl)
        assertEquals("-2.5", o.openHomeSpread)
        assertEquals("48.5", o.openTotal)
        assertEquals("-142", o.openHomeMl)
        assertEquals("+120", o.openAwayMl)
        assertEquals(-2.0, o.homeSpreadMove!!, 1e-9)
        assertEquals(-9.0, o.totalMove!!, 1e-9)
    }

    @Test
    fun finishedGamesWithoutLinesHaveNoOdds() {
        val (games, _) = Games.parse(res("odds-scoreboard-nfl.json"), League.NFL)
        games.filter { it.final }.forEach { assertNull(it.id, it.odds) }
    }

    @Test
    fun winChanceTakesOutTheBookMargin() {
        val w = Odds.winChance(awayMl = "+185", homeMl = "-225")!!
        assertEquals(34, w.away)
        assertEquals(66, w.home)
        assertEquals(0, w.draw)
        val s = Odds.winChance(awayMl = "+500", homeMl = "-150", drawMl = "+235")!!
        assertEquals(100, s.away + s.home + s.draw)
        assertTrue(s.home > s.draw && s.draw > s.away)
        assertNull(Odds.winChance("", "-110"))
    }

    @Test
    fun coreBeforeKickoffHasNoLiveLines() {
        assertNull(Odds.liveFromCore(res("odds-core-nfl-pre.json")))
    }

    @Test
    fun coreLiveLinesComeFromTheLiveProviderAndMove() {
        val a = Odds.liveFromCore(res("odds-core-live-a.json"))!!
        val b = Odds.liveFromCore(res("odds-core-live-b.json"))!!
        assertTrue(a.live)
        assertEquals("DraftKings", a.provider)
        // Faroe Islands (home) vs Moldova, 18 minutes in, 0-0.
        assertEquals("-150", a.homeMl)
        assertEquals("+500", a.awayMl)
        assertEquals("+235", a.drawMl)
        assertEquals("-0.5", a.homeSpread)
        assertEquals("-155", a.homeSpreadPrice)
        assertEquals("+0.5", a.awaySpread)
        assertEquals("+110", a.awaySpreadPrice)
        assertEquals("1.5", a.total)
        assertEquals("-176", a.overPrice)
        assertEquals("+140", a.underPrice)
        assertEquals("2.5", a.openTotal)
        // Four minutes later the home price drifted out.
        assertEquals("-140", b.homeMl)
        assertEquals(1, Odds.priceTick(a.homeMl, b.homeMl))
        assertEquals(0, Odds.priceTick(a.awayMl, b.awayMl))
        val t = OddsTicks.between(a, b)
        assertEquals(1, t.homeMl)
        assertEquals(0, t.awayMl)
    }

    @Test
    fun liveScoreboardStillCarriesThePreGameLine() {
        val (games, _) = Games.parse(res("odds-scoreboard-soccer-live.json"), League.EPL)
        val g = games.first { it.id == "401861180" }
        assertTrue(g.live)
        val o = g.odds!!
        assertFalse(o.live)
        assertEquals("-125", o.homeMl)
        assertEquals("+330", o.awayMl)
        assertEquals("+235", o.drawMl)
    }

    @Test
    fun formatting() {
        assertEquals("+235", Odds.american(235.0))
        assertEquals("-150", Odds.american("-150"))
        assertEquals("EVEN", Odds.american("even"))
        assertEquals("", Odds.american(null))
        assertEquals("", Odds.american(JSONObject.NULL))
        assertEquals("39.5", Odds.line("o39.5", signed = false))
        assertEquals("39.5", Odds.line("u39.5", signed = false))
        assertEquals("+3", Odds.line(3.0, signed = true))
        assertEquals("PK", Odds.line("0", signed = true))
        assertEquals("+4.5", Odds.flip("-4.5"))
        assertEquals("-2", Odds.moveText(-2.0))
        assertEquals("", Odds.moveText(0.0))
        assertEquals(-1, Odds.priceTick("+500", "+450"))
    }

    @Test
    fun coreUrlUsesTheLeaguePath() {
        val g = Games.parse(res("odds-scoreboard-nfl.json"), League.NFL).first.first { it.id == "401873065" }
        assertEquals(
            "https://sports.core.api.espn.com/v2/sports/football/leagues/nfl/events/401873065/competitions/401873065/odds",
            Odds.coreUrl(g),
        )
    }

    private fun assertNotNullOdds(o: GameOdds?): GameOdds {
        assertNotNull(o)
        return o!!
    }
}
