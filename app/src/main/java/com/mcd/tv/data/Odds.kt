package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

// Game lines for the Games board, like a sportsbook shows them: spread, total and moneyline for each side.
//
// Two sources, both ESPN's public APIs (no key), both read on Nov 16, 2026 (fixtures in app/src/test/resources):
//  - Pre-game: the scoreboard Games.kt already loads. Each event's competitions[0].odds[0] is the top book
//    (DraftKings): pointSpread / total / moneyline, each with "open" and "close" (close = the current line).
//    No extra request.
//  - Live: during a game the scoreboard keeps the pre-game line. The core odds endpoint for the event lists a
//    second DraftKings entry, provider id 200 "DraftKings - Live Odds", whose "current" values move with the
//    game (two reads 4 minutes apart moved FRO -150 to -140). [LiveOdds] fetches it only for live games on
//    screen, at most once per 30 s per game.
//
// Odds are information only. Jarvis takes no bets and shows no betting links. The Kids profile never sees odds.

/** One game's lines. Strings are as a book prints them: "-4.5", "+185", "39.5". Empty = the book has none. */
data class GameOdds(
    val provider: String,
    /** Live (in-game) lines rather than the pre-game line. */
    val live: Boolean,
    val awaySpread: String,
    val awaySpreadPrice: String,
    val homeSpread: String,
    val homeSpreadPrice: String,
    val total: String,
    val overPrice: String,
    val underPrice: String,
    val awayMl: String,
    val homeMl: String,
    /** Soccer three-way moneyline. */
    val drawMl: String = "",
    val openHomeSpread: String = "",
    val openTotal: String = "",
    val openAwayMl: String = "",
    val openHomeMl: String = "",
) {
    val hasSpread: Boolean get() = homeSpread.isNotEmpty() || awaySpread.isNotEmpty()
    val hasTotal: Boolean get() = total.isNotEmpty()
    val hasMoneyline: Boolean get() = homeMl.isNotEmpty() && awayMl.isNotEmpty()
    val isEmpty: Boolean get() = !hasSpread && !hasTotal && !hasMoneyline

    /** How far the home spread moved since it opened (now minus open): -2.0 when -2.5 became -4.5. Null = unknown. */
    val homeSpreadMove: Double? get() = Odds.diff(homeSpread, openHomeSpread)

    /** How far the total moved since it opened: -9.0 when 48.5 became 39.5. */
    val totalMove: Double? get() = Odds.diff(total, openTotal)

    /**
     * Each side's win chance in whole percent from the moneylines, with the book's margin taken out
     * (the prices are scaled so the chances add to 100). Draw is 0 unless the game has a three-way line.
     */
    val winChance: WinChance? get() = Odds.winChance(awayMl, homeMl, drawMl)
}

data class WinChance(val away: Int, val home: Int, val draw: Int)

/**
 * Which way each price moved between two reads of live lines (Odds.priceTick): +1 longer, -1 shorter, 0 same.
 * The Games board tints a price green or red until the next read, like a sportsbook's flash.
 */
data class OddsTicks(
    val awaySpread: Int = 0,
    val homeSpread: Int = 0,
    val over: Int = 0,
    val under: Int = 0,
    val awayMl: Int = 0,
    val homeMl: Int = 0,
    val drawMl: Int = 0,
) {
    companion object {
        fun between(a: GameOdds, b: GameOdds) = OddsTicks(
            awaySpread = if (a.awaySpread == b.awaySpread) Odds.priceTick(a.awaySpreadPrice, b.awaySpreadPrice) else 0,
            homeSpread = if (a.homeSpread == b.homeSpread) Odds.priceTick(a.homeSpreadPrice, b.homeSpreadPrice) else 0,
            over = if (a.total == b.total) Odds.priceTick(a.overPrice, b.overPrice) else 0,
            under = if (a.total == b.total) Odds.priceTick(a.underPrice, b.underPrice) else 0,
            awayMl = Odds.priceTick(a.awayMl, b.awayMl),
            homeMl = Odds.priceTick(a.homeMl, b.homeMl),
            drawMl = Odds.priceTick(a.drawMl, b.drawMl),
        )
    }
}

object Odds {
    /** Core odds provider id of DraftKings' in-game lines. */
    const val LIVE_PROVIDER_ID = "200"

    // ---------------------------------------------------------------- formatting

    /** American odds in book style: 235.0 -> "+235", "-150" -> "-150", 100 -> "+100", "EVEN" -> "EVEN". */
    fun american(v: Any?): String {
        val s = when (v) {
            null, JSONObject.NULL -> return ""
            is Number -> {
                val d = v.toDouble()
                if (d.isNaN() || d == 0.0) return ""
                val n = d.roundToInt()
                return if (n > 0) "+$n" else "$n"
            }
            else -> v.toString().trim()
        }
        if (s.isEmpty() || s == "null") return ""
        if (s.equals("even", ignoreCase = true) || s.equals("ev", ignoreCase = true)) return "EVEN"
        val d = s.toDoubleOrNull() ?: return ""
        if (d == 0.0) return ""
        val n = d.roundToInt()
        return if (n > 0) "+$n" else "$n"
    }

    /** A spread line in book style: "-4.5", "+4.5", "0" -> "PK". Totals strip "o"/"u": "o39.5" -> "39.5". */
    fun line(v: Any?, signed: Boolean): String {
        val raw = when (v) {
            null, JSONObject.NULL -> return ""
            is Number -> v.toString()
            else -> v.toString().trim()
        }
        val s = raw.trimStart('o', 'u', 'O', 'U').trim()
        if (s.isEmpty() || s == "null") return ""
        if (s.equals("pk", ignoreCase = true) || s.equals("pick", ignoreCase = true)) return "PK"
        val d = s.toDoubleOrNull() ?: return ""
        val body = fmtNum(abs(d))
        if (!signed) return fmtNum(d)
        return when {
            d == 0.0 -> "PK"
            d > 0 -> "+$body"
            else -> "-$body"
        }
    }

    private fun fmtNum(d: Double): String =
        if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()

    /** The other side of a spread: "-4.5" -> "+4.5", "PK" -> "PK". */
    fun flip(spread: String): String = when {
        spread.isEmpty() -> ""
        spread == "PK" -> "PK"
        spread.startsWith("-") -> "+" + spread.substring(1)
        spread.startsWith("+") -> "-" + spread.substring(1)
        else -> "-$spread"
    }

    private fun num(s: String): Double? = when {
        s.isEmpty() -> null
        s == "PK" -> 0.0
        s == "EVEN" -> 100.0
        else -> s.toDoubleOrNull()
    }

    fun diff(now: String, open: String): Double? {
        val a = num(now) ?: return null
        val b = num(open) ?: return null
        return a - b
    }

    /** Implied chance (0..1) of American odds, margin included: -225 -> 0.692, +185 -> 0.351. */
    fun implied(american: String): Double? {
        val a = num(american) ?: return null
        return when {
            a >= 100 -> 100.0 / (a + 100.0)
            a <= -100 -> -a / (-a + 100.0)
            else -> null
        }
    }

    fun winChance(awayMl: String, homeMl: String, drawMl: String = ""): WinChance? {
        val a = implied(awayMl) ?: return null
        val h = implied(homeMl) ?: return null
        val d = if (drawMl.isEmpty()) 0.0 else implied(drawMl) ?: 0.0
        val sum = a + h + d
        if (sum <= 0.0) return null
        val away = (a / sum * 100).roundToInt()
        val draw = (d / sum * 100).roundToInt()
        return WinChance(away = away, home = 100 - away - draw, draw = draw)
    }

    /** "+2" / "-9" / "+0.5" for a line move; empty when it did not move. */
    fun moveText(move: Double?): String {
        if (move == null || abs(move) < 0.01) return ""
        val body = fmtNum(abs(move))
        return if (move > 0) "+$body" else "-$body"
    }

    // ---------------------------------------------------------------- scoreboard (pre-game)

    /** The top book's lines from a scoreboard competition (its "odds" array). Null when the game has none. */
    fun fromScoreboard(comp: JSONObject): GameOdds? {
        val arr = comp.optJSONArray("odds") ?: return null
        val o = best(arr) ?: return null
        val provider = o.optJSONObject("provider")?.let { it.optString("displayName").ifEmpty { it.optString("name") } } ?: ""
        val ps = o.optJSONObject("pointSpread")
        val tot = o.optJSONObject("total")
        val ml = o.optJSONObject("moneyline")
        fun cur(obj: JSONObject?, side: String, key: String) = obj?.optJSONObject(side)?.optJSONObject("close")?.opt(key)
        fun open(obj: JSONObject?, side: String, key: String) = obj?.optJSONObject(side)?.optJSONObject("open")?.opt(key)

        var homeSpread = line(cur(ps, "home", "line"), signed = true)
        var awaySpread = line(cur(ps, "away", "line"), signed = true)
        // Older feeds: only "spread" (the home line) and "details".
        if (homeSpread.isEmpty() && o.has("spread") && !o.isNull("spread")) homeSpread = line(o.opt("spread"), signed = true)
        if (awaySpread.isEmpty() && homeSpread.isNotEmpty()) awaySpread = flip(homeSpread)
        if (homeSpread.isEmpty() && awaySpread.isNotEmpty()) homeSpread = flip(awaySpread)

        var total = line(cur(tot, "over", "line"), signed = false)
        if (total.isEmpty() && o.has("overUnder") && !o.isNull("overUnder")) total = line(o.opt("overUnder"), signed = false)

        var homeMl = american(cur(ml, "home", "odds"))
        var awayMl = american(cur(ml, "away", "odds"))
        if (homeMl.isEmpty()) homeMl = american(o.optJSONObject("homeTeamOdds")?.opt("moneyLine"))
        if (awayMl.isEmpty()) awayMl = american(o.optJSONObject("awayTeamOdds")?.opt("moneyLine"))
        var drawMl = american(cur(ml, "draw", "odds"))
        if (drawMl.isEmpty()) drawMl = american(o.optJSONObject("drawOdds")?.opt("moneyLine"))

        val odds = GameOdds(
            provider = provider,
            live = false,
            awaySpread = awaySpread,
            awaySpreadPrice = american(cur(ps, "away", "odds")),
            homeSpread = homeSpread,
            homeSpreadPrice = american(cur(ps, "home", "odds")),
            total = total,
            overPrice = american(cur(tot, "over", "odds")),
            underPrice = american(cur(tot, "under", "odds")),
            awayMl = awayMl,
            homeMl = homeMl,
            drawMl = drawMl,
            openHomeSpread = line(open(ps, "home", "line"), signed = true),
            openTotal = line(open(tot, "over", "line"), signed = false),
            openAwayMl = american(open(ml, "away", "odds")),
            openHomeMl = american(open(ml, "home", "odds")),
        )
        return odds.takeUnless { it.isEmpty }
    }

    /** Highest priority entry (DraftKings is priority 1); the first one when none says. */
    private fun best(arr: JSONArray): JSONObject? {
        var pick: JSONObject? = null
        var pickPri = Int.MIN_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = o.optJSONObject("provider")?.optInt("priority", 0) ?: 0
            if (pick == null || p > pickPri) {
                pick = o
                pickPri = p
            }
        }
        return pick
    }

    // ---------------------------------------------------------------- core odds (live)

    /** ESPN core odds URL for one event. [Game.league] path "football/nfl" -> sports/football/leagues/nfl. */
    fun coreUrl(g: Game): String {
        val parts = g.league.path.split('/')
        val sport = parts.getOrElse(0) { "" }
        val league = parts.getOrElse(1) { "" }
        return "https://sports.core.api.espn.com/v2/sports/$sport/leagues/$league/events/${g.id}/competitions/${g.id}/odds"
    }

    /** The in-game lines from a core odds answer: the "Live Odds" provider only. Null when it has none. */
    fun liveFromCore(body: String): GameOdds? {
        val items = runCatching { JSONObject(body).optJSONArray("items") }.getOrNull() ?: return null
        var item: JSONObject? = null
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val p = it.optJSONObject("provider") ?: continue
            if (p.optString("id") == LIVE_PROVIDER_ID || p.optString("name").contains("live", ignoreCase = true)) {
                item = it
                break
            }
        }
        val o = item ?: return null
        val provider = o.optJSONObject("provider")?.optString("name")?.substringBefore(" - ")?.trim().orEmpty()
        val home = o.optJSONObject("homeTeamOdds")
        val away = o.optJSONObject("awayTeamOdds")
        fun teamCur(t: JSONObject?, key: String) = t?.optJSONObject("current")?.optJSONObject(key)?.opt("american")
        fun teamOpen(t: JSONObject?, key: String) = t?.optJSONObject("open")?.optJSONObject(key)?.opt("american")
        val cur = o.optJSONObject("current")
        val op = o.optJSONObject("open")

        var homeSpread = line(teamCur(home, "pointSpread"), signed = true)
        var awaySpread = line(teamCur(away, "pointSpread"), signed = true)
        if (awaySpread.isEmpty() && homeSpread.isNotEmpty()) awaySpread = flip(homeSpread)
        if (homeSpread.isEmpty() && awaySpread.isNotEmpty()) homeSpread = flip(awaySpread)
        var total = line(cur?.optJSONObject("total")?.opt("american"), signed = false)
        if (total.isEmpty() && o.has("overUnder") && !o.isNull("overUnder")) total = line(o.opt("overUnder"), signed = false)
        var homeMl = american(teamCur(home, "moneyLine"))
        var awayMl = american(teamCur(away, "moneyLine"))
        if (homeMl.isEmpty()) homeMl = american(home?.opt("moneyLine"))
        if (awayMl.isEmpty()) awayMl = american(away?.opt("moneyLine"))
        var drawMl = american(cur?.optJSONObject("draw")?.opt("american"))
        if (drawMl.isEmpty()) drawMl = american(o.optJSONObject("drawOdds")?.opt("moneyLine"))

        val odds = GameOdds(
            provider = provider,
            live = true,
            awaySpread = awaySpread,
            awaySpreadPrice = american(teamCur(away, "spread")),
            homeSpread = homeSpread,
            homeSpreadPrice = american(teamCur(home, "spread")),
            total = total,
            overPrice = american(cur?.optJSONObject("over")?.opt("american")),
            underPrice = american(cur?.optJSONObject("under")?.opt("american")),
            awayMl = awayMl,
            homeMl = homeMl,
            drawMl = drawMl,
            openHomeSpread = line(teamOpen(home, "pointSpread"), signed = true),
            openTotal = line(op?.optJSONObject("total")?.opt("american"), signed = false),
            openAwayMl = american(teamOpen(away, "moneyLine")),
            openHomeMl = american(teamOpen(home, "moneyLine")),
        )
        return odds.takeUnless { it.isEmpty }
    }

    /**
     * Which way one price moved between two reads, for the green / red flash a book shows:
     * +1 = better for the bettor (a longer price: -150 to -140, +480 to +500), -1 = shorter, 0 = same or unknown.
     */
    fun priceTick(before: String, after: String): Int {
        val b = num(before) ?: return 0
        val a = num(after) ?: return 0
        if (a == b) return 0
        // Compare payouts per 100 staked: +500 pays 500, -140 pays 71.4.
        fun pay(x: Double) = if (x >= 100) x else if (x <= -100) 10000.0 / -x else 0.0
        val pa = pay(a)
        val pb = pay(b)
        return if (pa > pb) 1 else if (pa < pb) -1 else 0
    }
}
