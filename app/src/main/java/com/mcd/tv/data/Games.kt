package com.mcd.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// ======================= Games: this week's schedule + where to watch =======================
//
// Schedules come from the public ESPN scoreboard feed (scores and start times only, no video).
// GameMatcher then finds the channels in your own playlist that carry each game: event channels named
// after the game, channels whose program guide lists it, and the broadcast networks (CBS, FOX, ESPN...).

/** Sport family, used to tell an NFL "Giants" channel from an MLB "Giants" one. */
enum class Sport { FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, SOCCER }

enum class League(
    val label: String,
    /** Short name for headers ("NFL", "CFB"). */
    val short: String,
    val emoji: String,
    val path: String,
    val sport: Sport,
    /** Football: ESPN lists games by week; everything else by day. */
    val weekly: Boolean,
    /** Extra scoreboard query (college: all FBS games instead of the featured few). */
    val extraQuery: String,
    /** Months (1-12) the league normally plays; used to show the league before its schedule loads. */
    val months: Set<Int>,
    /** Words and phrases (lowercase) that name the league in channel names and the guide. */
    val keywords: List<String>,
    /** Phrases naming whole-league channels (RedZone, NFL Network...) for the "League channels" row. */
    val leagueChannels: List<String>,
) {
    NFL(
        "NFL", "NFL", "🏈", "football/nfl", Sport.FOOTBALL, true, "",
        setOf(9, 10, 11, 12, 1, 2),
        listOf("nfl", "redzone", "red zone", "sunday ticket", "nfl network", "game pass"),
        listOf("nfl redzone", "redzone", "red zone", "nfl network", "nfl net", "nfl channel", "sunday ticket", "nfl game pass", "nfl plus", "sky sports nfl"),
    ),
    NCAAF(
        "College Football", "COLLEGE FOOTBALL", "🏈", "football/college-football", Sport.FOOTBALL, true, "groups=80",
        setOf(8, 9, 10, 11, 12, 1),
        listOf("ncaaf", "college football", "cfb", "ncaa football"),
        listOf("sec network", "acc network", "big ten network", "btn", "espnu", "cbs sports network", "cbssn", "longhorn network", "college football", "ncaaf"),
    ),
    NBA(
        "NBA", "NBA", "🏀", "basketball/nba", Sport.BASKETBALL, false, "",
        setOf(10, 11, 12, 1, 2, 3, 4, 5, 6),
        listOf("nba", "nba tv", "league pass"),
        listOf("nba tv", "nba league pass", "league pass"),
    ),
    MLB(
        "MLB", "MLB", "⚾", "baseball/mlb", Sport.BASEBALL, false, "",
        setOf(3, 4, 5, 6, 7, 8, 9, 10),
        listOf("mlb", "mlb network", "mlb tv", "extra innings"),
        listOf("mlb network", "mlb tv", "mlb big inning", "big inning", "extra innings"),
    ),
    NHL(
        "NHL", "NHL", "🏒", "hockey/nhl", Sport.HOCKEY, false, "",
        setOf(10, 11, 12, 1, 2, 3, 4, 5, 6),
        listOf("nhl", "nhl network", "center ice"),
        listOf("nhl network", "nhl center ice", "center ice"),
    ),
    WNBA(
        "WNBA", "WNBA", "🏀", "basketball/wnba", Sport.BASKETBALL, false, "",
        setOf(5, 6, 7, 8, 9, 10),
        listOf("wnba"),
        listOf("wnba"),
    ),
    MLS(
        "MLS", "MLS", "⚽", "soccer/usa.1", Sport.SOCCER, false, "",
        setOf(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
        listOf("mls", "mls season pass"),
        listOf("mls season pass", "mls"),
    ),
    EPL(
        "Premier League", "PREMIER LEAGUE", "⚽", "soccer/eng.1", Sport.SOCCER, false, "",
        setOf(8, 9, 10, 11, 12, 1, 2, 3, 4, 5),
        listOf("premier league", "epl"),
        listOf("premier league", "epl", "sky sports premier league"),
    ),
    ;

    /** Rail label: "🏈 NFL Games". */
    val railLabel: String get() = if (this == NFL) "$emoji NFL Games" else "$emoji $label"

    fun inSeason(month: Int): Boolean = month in months

    companion object {
        /** Hidden from Live Games until a profile changes it (Settings > Addons and Live TV > Live Games). */
        val DEFAULT_HIDDEN: Set<League> = setOf(WNBA, MLS, EPL)

        /**
         * Hidden leagues from their stored names ([stored] null = never set: [DEFAULT_HIDDEN]). Unknown names are
         * skipped, so a league removed later never breaks the setting.
         */
        fun hiddenFrom(stored: List<String>?): Set<League> =
            stored?.mapNotNull { n -> entries.firstOrNull { it.name == n } }?.toSet() ?: DEFAULT_HIDDEN
    }
}

/** One side of a game. [nickname] is "Packers", [location] "Green Bay", [short] "Packers" (college: "Alabama"). */
data class TeamLine(
    val id: String,
    val name: String,
    val short: String,
    val abbr: String,
    val nickname: String,
    val location: String,
    val logo: String?,
    val score: String,
    val record: String,
    /** College poll rank (1-25), 0 when unranked. */
    val rank: Int = 0,
)

data class Game(
    val id: String,
    val league: League,
    /** "pre", "in" or "post". */
    val state: String,
    /** ESPN's short status: "Q3 5:12", "Final", "Halftime". */
    val detail: String,
    /** Epoch milliseconds (UTC). */
    val startMs: Long,
    /** Start time not set yet ("TBD"). */
    val timeTbd: Boolean,
    val home: TeamLine,
    val away: TeamLine,
    /** Networks: "CBS", "FOX", "ESPN", "Prime Video"... */
    val broadcasts: List<String>,
    /** The top book's pre-game lines from the scoreboard (Odds.kt). Null when the book has none. */
    val odds: GameOdds? = null,
) {
    val live: Boolean get() = state == "in"
    val final: Boolean get() = state == "post"

    /** Unique across leagues. */
    val key: String get() = "${league.name}:$id"
}

/**
 * A profile's favorite team in one league. [name] is the team as ESPN lists it ("Eagles", "76ers"); it matches a
 * game's team by nickname, short name, full name or abbreviation, so "Philadelphia Eagles" and "PHI" work too.
 * Stored as "NFL:Eagles".
 */
data class FavoriteTeam(val league: League, val name: String) {
    val stored: String get() = "${league.name}:$name"

    fun matches(t: TeamLine): Boolean {
        val n = GameMatcher.norm(name)
        if (n.isEmpty()) return false
        return listOf(t.nickname, t.short, t.name, t.abbr).any { it.isNotEmpty() && GameMatcher.norm(it) == n }
    }

    companion object {
        /** Favorites a profile starts with until it changes them: Dad (p1) follows Philadelphia. */
        val DEFAULTS: Map<String, List<FavoriteTeam>> = mapOf(
            "p1" to listOf(
                FavoriteTeam(League.NFL, "Eagles"),
                FavoriteTeam(League.MLB, "Phillies"),
                FavoriteTeam(League.NHL, "Flyers"),
                FavoriteTeam(League.NBA, "76ers"),
            ),
        )

        /** "NFL:Eagles" -> the team; null for an unknown league or an empty name. */
        fun parse(s: String): FavoriteTeam? {
            val i = s.indexOf(':')
            if (i <= 0) return null
            val league = League.entries.firstOrNull { it.name == s.substring(0, i) } ?: return null
            val name = s.substring(i + 1).trim()
            return if (name.isEmpty()) null else FavoriteTeam(league, name)
        }

        /** A profile's favorites from storage ([stored] null = never set: that profile's [DEFAULTS]). */
        fun listFrom(stored: List<String>?, profile: String): List<FavoriteTeam> =
            stored?.mapNotNull(::parse)?.distinct() ?: DEFAULTS[profile].orEmpty()
    }
}

/** Favorite-team games: the star on a game card, its place at the top of the league and the My Teams chip. */
object MyTeams {
    fun isMine(g: Game, teams: List<FavoriteTeam>): Boolean =
        teams.any { it.league == g.league && (it.matches(g.home) || it.matches(g.away)) }

    /** [games] with favorite-team games first; both parts keep their order. */
    fun pinned(games: List<Game>, teams: List<FavoriteTeam>): List<Game> {
        if (teams.isEmpty()) return games
        val (mine, rest) = games.partition { isMine(it, teams) }
        return mine + rest
    }

    /** Favorite-team games across leagues that are live or still to come: live first, then by start time. */
    fun collect(schedules: Collection<Schedule>, teams: List<FavoriteTeam>): List<Game> {
        if (teams.isEmpty()) return emptyList()
        return schedules.flatMap { it.games }
            .filter { !it.final && isMine(it, teams) }
            .sortedWith(compareBy<Game> { if (it.live) 0 else 1 }.thenBy { it.startMs })
    }
}

/** A league's games for this week. [weekLabel] is "WEEK 6" for football when ESPN says, else null. */
class Schedule(val league: League, val games: List<Game>, val weekLabel: String?, val fetchedAt: Long) {
    val liveCount: Int get() = games.count { it.live }
}

object Games {
    private const val BASE = "https://site.api.espn.com/apis/site/v2/sports/"
    private const val MAX_EVENTS = 150

    private val cache = HashMap<League, Schedule>()

    /** One download per league at a time (the rail, the pane and search can all ask at once). */
    private val locks: Map<League, Mutex> = League.entries.associateWith { Mutex() }

    /** The last loaded schedule for [league] (no network), or null. */
    fun cached(league: League): Schedule? = synchronized(cache) { cache[league] }

    /** Cache life: 60 s while a game is live or about to start, otherwise 10 minutes. */
    private fun ttl(s: Schedule, now: Long): Long {
        val busy = s.games.any { it.live || (it.state == "pre" && it.startMs - now in -3_600_000L..600_000L) }
        return if (busy) 60_000L else 600_000L
    }

    /**
     * This week's games for [league], live first is up to the caller (the list is in start order).
     * A failed refresh falls back to the last good schedule; with none it throws.
     */
    suspend fun schedule(league: League, force: Boolean = false): Schedule = locks.getValue(league).withLock {
        val now = System.currentTimeMillis()
        val old = cached(league)
        // force (Retry) still reuses a schedule fetched in the last few seconds by someone else.
        if (old != null && now - old.fetchedAt < (if (force) 5_000L else ttl(old, now))) return@withLock old
        val fresh = try {
            fetch(league)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (old != null) return@withLock old
            throw e
        }
        synchronized(cache) { cache[league] = fresh }
        fresh
    }

    private fun day(cal: Calendar): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(cal.time)

    private suspend fun fetch(league: League): Schedule {
        val extra = if (league.extraQuery.isNotEmpty()) "&" + league.extraQuery else ""
        val byId = LinkedHashMap<String, Game>()
        var week: String? = null
        var firstError: Exception? = null
        if (league.weekly) {
            // Without dates ESPN answers with the current week (Thursday to Monday for the NFL).
            try {
                val body = Http.get("$BASE${league.path}/scoreboard?limit=300$extra", timeoutMs = 20_000)
                val (games, w) = withContext(Dispatchers.Default) { parse(body, league) }
                week = w
                games.forEach { byId[it.id] = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                firstError = e
            }
        }
        // Yesterday (late games still running after midnight) through six days ahead, one request per day.
        // ESPN answers 400 to a date range ("dates=A-B") since October 2026; single days still work.
        val days = (-1..6).map { offset -> day(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }) }
        val results = coroutineScope {
            days.map { d ->
                async {
                    try {
                        val body = Http.get("$BASE${league.path}/scoreboard?limit=300&dates=$d$extra", timeoutMs = 20_000)
                        Result.success(withContext(Dispatchers.Default) { parse(body, league) })
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        var dayError: Exception? = null
        for (r in results) {
            r.onSuccess { (games, w) ->
                if (week == null && league.weekly) week = w
                games.forEach { byId[it.id] = it }
            }.onFailure { if (dayError == null) dayError = it as? Exception ?: Exception(it) }
        }
        // Only fail when nothing loaded at all: one bad day still shows the rest of the week.
        if (byId.isEmpty() && results.none { it.isSuccess }) throw (firstError ?: dayError ?: IllegalStateException("No schedule"))
        // Drop finals from before today (yesterday's range only matters for games still running).
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        var list = byId.values.filter { g ->
            !(g.final && g.startMs < startOfToday && !(league.weekly && g.startMs > startOfToday - 4 * 86_400_000L))
        }
        if (list.size > MAX_EVENTS) {
            // Keep live games and the soonest ones.
            list = list.sortedWith(compareBy<Game> { if (it.live) 0 else if (it.final) 2 else 1 }.thenBy { it.startMs }).take(MAX_EVENTS)
        }
        return Schedule(league, list.sortedBy { it.startMs }, week, System.currentTimeMillis())
    }

    /** "2026-10-11T17:00Z" or "2026-10-11T17:00:00Z" -> epoch ms (0 when unreadable). */
    internal fun parseDate(s: String): Long {
        if (s.isBlank()) return 0L
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val d = runCatching { f.parse(s) }.getOrNull()
            if (d != null) return d.time
        }
        return 0L
    }

    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key).trim()

    /** Parses a scoreboard reply into games plus the week label ("WEEK 6", "PLAYOFFS"). */
    internal fun parse(body: String, league: League): Pair<List<Game>, String?> {
        val root = JSONObject(body)
        val week = root.optJSONObject("week")?.optInt("number", 0) ?: 0
        val seasonType = root.optJSONObject("season")?.optInt("type", 0) ?: 0
        val weekLabel = when {
            !league.weekly -> null
            seasonType == 3 -> if (league == League.NCAAF) "BOWLS" else "PLAYOFFS"
            seasonType == 1 && week > 0 -> "PRESEASON WEEK $week"
            week > 0 -> "WEEK $week"
            else -> null
        }
        val events = root.optJSONArray("events") ?: return emptyList<Game>() to weekLabel
        val out = ArrayList<Game>(events.length())
        for (i in 0 until events.length()) {
            val e = events.optJSONObject(i) ?: continue
            runCatching { parseEvent(e, league) }.getOrNull()?.let { out.add(it) }
        }
        return out to weekLabel
    }

    private fun parseEvent(e: JSONObject, league: League): Game? {
        val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val teams = comp.optJSONArray("competitors") ?: return null
        var home: TeamLine? = null
        var away: TeamLine? = null
        for (k in 0 until teams.length()) {
            val c = teams.optJSONObject(k) ?: continue
            val line = team(c) ?: continue
            if (c.str("homeAway") == "home") home = line else away = line
        }
        if (home == null || away == null) return null
        val status = (e.optJSONObject("status") ?: comp.optJSONObject("status"))?.optJSONObject("type")
        val state = status?.str("state")?.ifEmpty { null } ?: "pre"
        val detail = status?.str("shortDetail") ?: ""
        val casts = LinkedHashSet<String>()
        comp.optJSONArray("broadcasts")?.let { b ->
            for (k in 0 until b.length()) {
                val names = b.optJSONObject(k)?.optJSONArray("names") ?: continue
                for (n in 0 until names.length()) names.optString(n).trim().takeIf { it.isNotEmpty() && it != "null" }?.let { casts.add(it) }
            }
        }
        comp.optJSONArray("geoBroadcasts")?.let { b ->
            for (k in 0 until b.length()) {
                val name = b.optJSONObject(k)?.optJSONObject("media")?.str("shortName") ?: continue
                if (name.isNotEmpty() && casts.none { it.equals(name, ignoreCase = true) }) casts.add(name)
            }
        }
        val date = e.str("date").ifEmpty { comp.str("date") }
        val timeValid = if (comp.has("timeValid")) comp.optBoolean("timeValid", true) else e.optBoolean("timeValid", true)
        return Game(
            id = e.str("id").ifEmpty { comp.str("id") },
            league = league,
            state = state,
            detail = detail,
            startMs = parseDate(date),
            timeTbd = !timeValid,
            home = home,
            away = away,
            broadcasts = casts.toList(),
            odds = runCatching { Odds.fromScoreboard(comp) }.getOrNull(),
        )
    }

    private fun team(c: JSONObject): TeamLine? {
        val t = c.optJSONObject("team") ?: return null
        val logo = t.str("logo").ifEmpty {
            t.optJSONArray("logos")?.optJSONObject(0)?.str("href") ?: ""
        }.ifEmpty { null }
        val scoreAny = c.opt("score")
        val score = when (scoreAny) {
            is JSONObject -> scoreAny.str("displayValue")
            null -> ""
            else -> scoreAny.toString().takeIf { it != "null" } ?: ""
        }
        val records = c.optJSONArray("records")
        var record = ""
        if (records != null) {
            for (k in 0 until records.length()) {
                val r = records.optJSONObject(k) ?: continue
                val summary = r.str("summary")
                if (summary.isEmpty()) continue
                val overall = r.str("type") == "total" || r.str("name") == "overall"
                if (record.isEmpty() || overall) record = summary
                if (overall) break
            }
        }
        val rank = c.optJSONObject("curatedRank")?.optInt("current", 99) ?: 99
        val display = t.str("displayName")
        return TeamLine(
            id = t.str("id"),
            name = display.ifEmpty { t.str("shortDisplayName") },
            short = t.str("shortDisplayName").ifEmpty { display },
            abbr = t.str("abbreviation"),
            nickname = t.str("name"),
            location = t.str("location"),
            logo = logo,
            score = score,
            record = record,
            rank = if (rank in 1..25) rank else 0,
        )
    }

    private val teamCache = HashMap<League, List<TeamLine>>()

    /** Every team in [league] (Settings > My teams), A to Z by short name. One request per league, kept until the app closes. */
    suspend fun teams(league: League): List<TeamLine> {
        synchronized(teamCache) { teamCache[league] }?.let { return it }
        val extra = if (league.extraQuery.isNotEmpty()) "&" + league.extraQuery else ""
        val body = Http.get("$BASE${league.path}/teams?limit=500$extra", timeoutMs = 20_000)
        val list = withContext(Dispatchers.Default) { parseTeams(body) }
        if (list.isNotEmpty()) synchronized(teamCache) { teamCache[league] = list }
        return list
    }

    /** Parses ESPN's teams reply (sports[0].leagues[0].teams[].team). */
    internal fun parseTeams(body: String): List<TeamLine> {
        val teams = JSONObject(body).optJSONArray("sports")?.optJSONObject(0)
            ?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams") ?: return emptyList()
        val out = ArrayList<TeamLine>(teams.length())
        for (i in 0 until teams.length()) teams.optJSONObject(i)?.let { c -> runCatching { team(c) }.getOrNull()?.let { out.add(it) } }
        return out.distinctBy { it.id }.sortedBy { it.short.lowercase(Locale.ROOT) }
    }

    /**
     * Games this week (from schedules already loaded) for a team search: "packers", "green bay", "chiefs",
     * "kansas city", "alabama", or an abbreviation like "dal". Live first, then by start time.
     */
    fun forTeamQuery(query: String, max: Int = 8): List<Game> {
        val q = GameMatcher.norm(query)
        if (q.length < 3) return emptyList()
        val all = synchronized(cache) { cache.values.toList() }
        val out = ArrayList<Game>()
        for (s in all) for (g in s.games) {
            if (teamMatches(g.home, q) || teamMatches(g.away, q)) out.add(g)
        }
        return out.sortedWith(compareBy<Game> { if (it.live) 0 else if (it.final) 2 else 1 }.thenBy { it.startMs }).take(max)
    }

    private fun teamMatches(t: TeamLine, q: String): Boolean {
        if (q.length <= 4 && t.abbr.equals(q, ignoreCase = true)) return true
        if (q.length < 4 && !q.contains(' ')) return false
        for (field in listOf(t.name, t.short, t.nickname, t.location)) {
            if (field.isEmpty()) continue
            val f = " " + GameMatcher.norm(field) + " "
            if (f.contains(" $q ") || (q.length >= 4 && f.contains(" $q"))) return true
        }
        return false
    }
}

// ======================= Matching games to playlist channels =======================

/** A channel picked for a game: [idx] is a position in [LiveIndex.channels]; [why] says how it matched. */
class ChannelHit(val idx: Int, val score: Int, val why: String)

/** Channels for one game, best first. */
class GameChannels(val hits: List<ChannelHit>) {
    val items: IntArray = IntArray(hits.size) { hits[it].idx }
    val size: Int get() = hits.size
}

object GameMatcher {
    // Scores: event channel naming both teams > guide listing > network.
    private const val S_EVENT_BOTH = 100
    private const val S_EVENT_ABBR = 90
    private const val S_GUIDE_BOTH = 85
    private const val S_GUIDE_ONE = 60
    private const val S_EVENT_ONE = 55
    private const val S_NETWORK = 30
    private const val S_HOME = 15
    private const val MAX_PER_GAME = 40
    private const val MAX_PER_NETWORK = 10

    /** Lowercase words only: "+" -> " plus ", "@" -> " at ", "&" and punctuation -> spaces. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var space = true
        fun sp() { if (!space) { sb.append(' '); space = true } }
        for (ch in s) {
            when {
                ch == '+' -> { sp(); sb.append("plus"); space = false; sp() }
                ch == '@' -> { sp(); sb.append("at"); space = false; sp() }
                ch.isLetterOrDigit() -> { sb.append(ch.lowercaseChar()); space = false }
                else -> sp()
            }
        }
        return sb.toString().trim()
    }

    fun words(s: String): Array<String> {
        val n = norm(s)
        return if (n.isEmpty()) emptyArray() else n.split(' ').toTypedArray()
    }

    // ---------------------------------------------------------------- text indexes

    private class IntList {
        var a = IntArray(4)
        var n = 0
        fun add(v: Int) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
        fun last(): Int = if (n == 0) -1 else a[n - 1]
    }

    /** word -> documents containing it. */
    private class WordIndex {
        private val ids = HashMap<String, Int>()
        private val lists = ArrayList<IntList>()
        private val canon = ArrayList<String>()

        /** Adds a document's words and returns them interned (shared strings keep memory down). */
        fun add(doc: Int, words: Array<String>): Array<String> {
            for (k in words.indices) {
                val w = words[k]
                var id = ids[w]
                if (id == null) {
                    id = lists.size
                    ids[w] = id
                    lists.add(IntList())
                    canon.add(w)
                }
                words[k] = canon[id]
                val l = lists[id]
                if (l.last() != doc) l.add(doc)
            }
            return words
        }

        fun count(w: String): Int = ids[w]?.let { lists[it].n } ?: 0

        fun forEachDoc(w: String, f: (Int) -> Unit) {
            val id = ids[w] ?: return
            val l = lists[id]
            for (i in 0 until l.n) f(l.a[i])
        }
    }

    /** Sport bits for a word list (league names and sport words). */
    private fun sportBits(w: Array<String>): Int {
        var bits = 0
        for (i in w.indices) {
            val t = w[i]
            val next = if (i + 1 < w.size) w[i + 1] else ""
            when (t) {
                "nfl", "ncaaf", "cfb", "redzone", "xfl", "ufl", "cfl" -> bits = bits or bit(Sport.FOOTBALL)
                "nba", "wnba", "ncaab", "ncaam", "basketball", "hoops" -> bits = bits or bit(Sport.BASKETBALL)
                "mlb", "baseball", "milb" -> bits = bits or bit(Sport.BASEBALL)
                "nhl", "hockey", "ahl" -> bits = bits or bit(Sport.HOCKEY)
                "mls", "epl", "soccer", "futbol", "laliga", "uefa", "bundesliga", "fifa" -> bits = bits or bit(Sport.SOCCER)
                "college" -> if (next == "football") bits = bits or bit(Sport.FOOTBALL) else if (next == "basketball") bits = bits or bit(Sport.BASKETBALL)
                "premier" -> if (next == "league") bits = bits or bit(Sport.SOCCER)
                "champions" -> if (next == "league") bits = bits or bit(Sport.SOCCER)
                "red" -> if (next == "zone") bits = bits or bit(Sport.FOOTBALL)
            }
        }
        return bits
    }

    private fun bit(s: Sport): Int = 1 shl s.ordinal

    /** Everything about the playlist that matching needs, built once per playlist. */
    private class ChannelText(index: LiveIndex) {
        val n = index.size
        val toks = arrayOfNulls<Array<String>>(n)
        val cleanWords = IntArray(n)
        val dedupe = arrayOfNulls<String>(n)
        val sportsCtx = BooleanArray(n)
        val sport = IntArray(n)
        /** 0 = US, 1 = unknown country, 2 = elsewhere. */
        val region = ByteArray(n)
        val quality = IntArray(n)
        /** Huntsville station rank ([LiveFill.homeRank]), -1 for other channels. */
        val home = IntArray(n)
        val wi = WordIndex()

        init {
            for (s in index.sections) {
                if (s.name == LiveOrganizer.SPORTS || s.name == LiveOrganizer.EVENTS) for (i in s.items) sportsCtx[i] = true
            }
            val groupWords = HashMap<String, Array<String>>()
            val groupCountry = HashMap<String, String>()
            for (i in 0 until n) {
                val ch = index.channels[i]
                val w = wi.add(i, words(ch.name))
                toks[i] = w
                val gw = groupWords.getOrPut(ch.group) { words(ch.group) }
                val clean = index.names[i]
                cleanWords[i] = words(clean).size
                dedupe[i] = norm(clean)
                val bits = sportBits(w) or sportBits(gw)
                sport[i] = bits
                if (bits != 0) sportsCtx[i] = true
                val c = groupCountry.getOrPut(ch.group + "\u0000" + ch.name.take(8)) { LiveOrganizer.country(ch.group, ch.name) }
                region[i] = when (c) {
                    "US" -> 0
                    LiveOrganizer.UNKNOWN_COUNTRY -> 1
                    else -> 2
                }
                var q = 1
                for (t in w) {
                    when (t) {
                        "fhd", "uhd", "4k", "1080p", "1080", "2160p" -> q = maxOf(q, 3)
                        "hd", "720p", "720" -> q = maxOf(q, 2)
                        "sd", "480p" -> q = minOf(q, 0)
                    }
                }
                if (w.contains("backup")) q -= 1
                quality[i] = q
                home[i] = LiveFill.homeRank(ch.name)
            }
        }
    }

    /** The program guide as word-indexed programmes (only channels in the playlist). */
    private class GuideText(index: LiveIndex, guide: Map<String, List<Programme>>) {
        val chan: IntArray
        val start: LongArray
        val end: LongArray
        val toks: Array<Array<String>>
        val wi = WordIndex()

        init {
            val ch = ArrayList<Int>()
            val st = ArrayList<Long>()
            val en = ArrayList<Long>()
            val ws = ArrayList<Array<String>>()
            for ((url, list) in guide) {
                val i = index.indexOf(url)
                if (i < 0) continue
                for (p in list) {
                    val doc = ws.size
                    var w = words(p.title + " " + p.desc)
                    if (w.size > 48) w = w.copyOf(48).requireNoNulls()
                    ws.add(wi.add(doc, w))
                    ch.add(i)
                    st.add(p.start)
                    en.add(p.end)
                }
            }
            chan = ch.toIntArray()
            start = st.toLongArray()
            end = en.toLongArray()
            toks = ws.toTypedArray()
        }
    }

    @Volatile private var chanCache: Pair<LiveIndex, ChannelText>? = null
    @Volatile private var guideCache: Triple<LiveIndex, Map<String, List<Programme>>, GuideText>? = null

    private fun channelText(index: LiveIndex): ChannelText = synchronized(this) {
        chanCache?.let { if (it.first === index) return it.second }
        val t = ChannelText(index)
        chanCache = index to t
        t
    }

    private fun guideText(index: LiveIndex, guide: Map<String, List<Programme>>): GuideText? {
        if (guide.isEmpty()) return null
        return synchronized(this) {
            guideCache?.let { if (it.first === index && it.second === guide) return it.third }
            val t = GuideText(index, guide)
            guideCache = Triple(index, guide, t)
            t
        }
    }

    // ---------------------------------------------------------------- teams

    /** How a team can be named: phrases (word arrays); [generic] = a common word that needs sports context. */
    private class TeamKey(val phrases: List<Array<String>>, val abbr: String, val generic: Boolean)

    /** Nicknames that are everyday words, shared by two leagues, or titles of shows and movies. */
    private val GENERIC = setOf(
        "kings", "heat", "jets", "giants", "rangers", "wild", "magic", "thunder", "sun", "sky", "fire", "lightning",
        "stars", "jazz", "nets", "rockets", "warriors", "blues", "devils", "sharks", "ducks", "penguins", "kraken",
        "flames", "senators", "islanders", "hurricanes", "avalanche", "predators", "panthers", "cardinals", "lions",
        "bears", "eagles", "rams", "saints", "titans", "chiefs", "raiders", "vikings", "patriots", "cowboys", "bills",
        "dolphins", "texans", "jaguars", "royals", "pirates", "twins", "rays", "reds", "angels", "athletics", "nationals",
        "giants", "astros", "spurs", "suns", "storm", "fever", "dream", "liberty", "mercury", "wings", "aces", "sparks",
        "lynx", "mystics", "valkyries", "united", "city", "fc", "real", "inter", "sporting", "galaxy", "union", "crew",
        "fire", "dynamo", "revolution", "wolves", "blazers", "caps", "pens", "knights", "jays", "packers", "steelers",
        "browns", "colts", "chargers", "broncos", "seahawks", "bengals", "ravens", "commanders", "falcons", "saints",
        "buccaneers", "clippers", "lakers", "celtics", "bulls", "pistons", "pacers", "hawks", "hornets", "knicks",
        "orioles", "tigers", "brewers", "cubs", "mets", "padres", "marlins", "rockies", "guardians", "mariners",
        "canucks", "oilers", "jets", "bruins", "capitals", "canadiens", "sabres", "flyers",
    )

    /** Aliases broadcasters and IPTV event names use. */
    private val ALIASES = mapOf(
        "49ers" to listOf("niners"), "buccaneers" to listOf("bucs"), "76ers" to listOf("sixers"),
        "cavaliers" to listOf("cavs"), "mavericks" to listOf("mavs"), "timberwolves" to listOf("wolves"),
        "trail blazers" to listOf("blazers"), "diamondbacks" to listOf("dbacks", "d backs"), "maple leafs" to listOf("leafs"),
        "canadiens" to listOf("habs"), "golden knights" to listOf("vegas golden knights"),
    )

    private fun teamKey(t: TeamLine, league: League): TeamKey {
        val phrases = ArrayList<Array<String>>()
        fun add(s: String) {
            val w = words(s)
            if (w.isNotEmpty() && phrases.none { it.contentEquals(w) }) phrases.add(w)
        }
        var generic: Boolean
        when {
            league == League.NCAAF -> {
                // College: the school is the name ("Alabama", "Ohio State"); nicknames repeat everywhere.
                add(t.location.ifEmpty { t.short })
                add(t.short)
                generic = phrases.all { it.size == 1 }
            }
            league.sport == Sport.SOCCER -> {
                add(t.name)
                add(t.short)
                if (t.nickname.isNotEmpty() && !t.nickname.equals(t.name, ignoreCase = true)) add(t.nickname)
                generic = phrases.all { it.size == 1 && it[0] in GENERIC }
            }
            else -> {
                val nick = t.nickname.ifEmpty { t.short }
                add(nick)
                val n = norm(nick)
                ALIASES[n]?.forEach { add(it) }
                generic = n in GENERIC || n.isEmpty()
            }
        }
        return TeamKey(phrases, norm(t.abbr), generic)
    }

    /** Where [p] starts in [w] (whole words, in order), or -1. College guard: "ohio" does not match "ohio state". */
    private fun find(w: Array<String>, p: Array<String>, college: Boolean): Int {
        if (p.isEmpty() || p.size > w.size) return -1
        var i = 0
        while (i + p.size <= w.size) {
            var ok = true
            for (k in p.indices) if (w[i + k] != p[k]) { ok = false; break }
            if (ok) {
                val after = if (i + p.size < w.size) w[i + p.size] else ""
                val blocked = college && (after == "state" || after == "st" || after == "tech") && p.none { it == after }
                if (!blocked) return i
            }
            i++
        }
        return -1
    }

    private fun mentions(w: Array<String>, key: TeamKey, college: Boolean): Boolean = key.phrases.any { find(w, it, college) >= 0 }

    private val SEPARATORS = setOf("vs", "v", "at", "versus")

    /** "GB vs DAL", "DAL @ GB": both abbreviations as whole words around a separator. */
    private fun abbrPair(w: Array<String>, a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return false
        for (i in 0 until w.size - 2) {
            if (w[i + 1] !in SEPARATORS) continue
            if ((w[i] == a && w[i + 2] == b) || (w[i] == b && w[i + 2] == a)) return true
        }
        return false
    }

    private fun hasSeparator(w: Array<String>): Boolean = w.any { it in SEPARATORS }

    /** The rarest word of a phrase: the cheapest way into the index. */
    private fun keyWord(wi: WordIndex, p: Array<String>): String = p.minByOrNull { wi.count(it) } ?: p[0]

    // ---------------------------------------------------------------- networks

    /** A broadcast network: channel name phrases, words that rule a channel out, and whether sports context is needed. */
    private class Network(val label: String, val phrases: List<Array<String>>, val exclude: Set<String>, val needCtx: Boolean, val domesticOnly: Boolean)

    private fun net(label: String, phrases: List<String>, exclude: Set<String> = emptySet(), needCtx: Boolean = false, domestic: Boolean = true) =
        Network(label, phrases.map { words(it) }, exclude, needCtx, domestic)

    private val LOCAL_EXCLUDE = setOf("sports", "news", "business", "deportes", "soul", "weather", "nation", "life", "kids", "family", "movies", "plus", "sn", "universo", "golf", "classic", "now", "live", "reality", "drama", "mystery", "comedy", "soccer")

    /** Normalized broadcast name -> network. */
    private val NETWORKS: Map<String, Network> = buildMap {
        fun reg(n: Network, vararg names: String) = names.forEach { put(norm(it), n) }
        // Huntsville call signs (WHNT, WAAY, WAFF, WZDX, WHDF) find their station even when the name has no network.
        reg(net("CBS", listOf("cbs", "wcbs", "kcbs", "whnt"), LOCAL_EXCLUDE), "CBS")
        reg(net("FOX", listOf("fox", "wnyw", "kttv", "wzdx"), LOCAL_EXCLUDE), "FOX")
        reg(net("NBC", listOf("nbc", "wnbc", "knbc", "waff"), LOCAL_EXCLUDE), "NBC")
        reg(net("ABC", listOf("abc", "wabc", "kabc", "waay"), LOCAL_EXCLUDE + setOf("australia", "au")), "ABC")
        reg(net("CW", listOf("cw", "the cw", "whdf"), LOCAL_EXCLUDE), "CW", "The CW")
        reg(net("ESPN", listOf("espn"), setOf("2", "u", "news", "deportes", "classic", "plus", "8", "ocho", "college", "extra", "brasil", "brazil", "mexico", "argentina", "colombia", "chile", "caribbean", "africa", "nl", "1", "3", "4", "5", "6", "7")), "ESPN")
        reg(net("ESPN2", listOf("espn2", "espn 2"), setOf("deportes")), "ESPN2")
        reg(net("ESPNU", listOf("espnu", "espn u")), "ESPNU")
        reg(net("ESPNEWS", listOf("espnews", "espn news")), "ESPNEWS")
        reg(net("ESPN Deportes", listOf("espn deportes")), "ESPN Deportes", "ESPN Desportes")
        reg(net("ESPN+", listOf("espn plus"), needCtx = false), "ESPN+", "ESPN Plus")
        reg(net("SEC Network", listOf("sec network", "secn", "sec")), "SECN", "SEC Network", "SEC")
        reg(net("SEC Network+", listOf("sec network plus", "secn plus")), "SECN+", "SEC Network+")
        reg(net("ACC Network", listOf("acc network", "accn", "acc")), "ACCN", "ACC Network", "ACC")
        reg(net("ACC Network Extra", listOf("acc network extra", "accnx")), "ACCNX", "ACC Network Extra")
        reg(net("Big Ten Network", listOf("big ten network", "btn", "big ten", "big 10")), "BTN", "Big Ten Network", "Big Ten Net")
        reg(net("CBS Sports Network", listOf("cbs sports network", "cbssn", "cbs sports net", "cbs sports")), "CBSSN", "CBS Sports Network", "CBS Sports Net")
        reg(net("FS1", listOf("fs1", "fox sports 1")), "FS1", "Fox Sports 1")
        reg(net("FS2", listOf("fs2", "fox sports 2")), "FS2")
        reg(net("Fox Deportes", listOf("fox deportes")), "FOX Deportes")
        reg(net("NFL Network", listOf("nfl network", "nfl net", "nfln")), "NFL Net", "NFL Network", "NFLN")
        reg(net("Prime Video", listOf("prime video", "amazon prime", "thursday night football", "tnf"), needCtx = true, domestic = false), "Prime Video", "Amazon Prime Video", "Prime", "Amazon")
        reg(net("Peacock", listOf("peacock"), setOf("tv"), needCtx = true, domestic = false), "Peacock")
        reg(net("Netflix", listOf("netflix"), needCtx = true, domestic = false), "Netflix")
        reg(net("Paramount+", listOf("paramount plus"), needCtx = true, domestic = false), "Paramount+", "Paramount Plus")
        reg(net("Apple TV", listOf("apple tv", "mls season pass"), needCtx = true, domestic = false), "Apple TV", "Apple TV+", "MLS Season Pass")
        reg(net("YouTube", listOf("youtube"), needCtx = true, domestic = false), "YouTube")
        reg(net("TNT", listOf("tnt"), setOf("drama", "uk", "comedy", "series")), "TNT")
        reg(net("TBS", listOf("tbs")), "TBS")
        reg(net("truTV", listOf("trutv", "tru tv")), "truTV", "TRUTV")
        reg(net("Max", listOf("max sports", "hbo max"), needCtx = true), "Max", "HBO Max")
        reg(net("NBA TV", listOf("nba tv", "nbatv")), "NBA TV", "NBATV")
        reg(net("MLB Network", listOf("mlb network", "mlbn", "mlb net")), "MLB Network", "MLBN", "MLB Net")
        reg(net("NHL Network", listOf("nhl network", "nhln", "nhl net")), "NHL Network", "NHLN", "NHL Net")
        reg(net("USA Network", listOf("usa network", "usa net"), setOf("news")), "USA Net", "USA Network", "USA")
        reg(net("Golf Channel", listOf("golf channel")), "Golf", "Golf Channel")
        reg(net("Telemundo", listOf("telemundo"), setOf("internacional", "novelas")), "Telemundo")
        reg(net("Universo", listOf("universo")), "Universo")
        reg(net("Univision", listOf("univision"), setOf("novelas", "tlnovelas")), "Univision")
        reg(net("UniMás", listOf("unimas")), "UniMás", "UniMas")
        reg(net("TUDN", listOf("tudn")), "TUDN")
        reg(net("ION", listOf("ion"), setOf("plus", "mystery")), "ION")
        reg(net("Longhorn Network", listOf("longhorn network", "lhn")), "LHN", "Longhorn Network")
        reg(net("Pac-12 Network", listOf("pac 12")), "Pac-12 Network", "P12N")
        reg(net("MSG", listOf("msg")), "MSG")
        reg(net("NESN", listOf("nesn")), "NESN")
        reg(net("YES", listOf("yes network", "yes")), "YES", "YES Network")
        reg(net("Marquee", listOf("marquee")), "Marquee", "Marquee Sports Network", "Marquee Sports Net")
        reg(net("SportsNet LA", listOf("sportsnet la", "spectrum sportsnet la")), "SportsNet LA", "SNLA", "Spectrum SportsNet LA")
        reg(net("Monumental", listOf("monumental")), "Monumental", "MNMT", "Monumental Sports Network")
        reg(net("Altitude", listOf("altitude")), "Altitude", "ALT", "Altitude Sports")
        reg(net("Root Sports", listOf("root sports")), "ROOT SPORTS", "Root Sports")
        reg(net("Sky Sports", listOf("sky sports"), needCtx = false, domestic = false), "Sky Sports")
        reg(net("TNT Sports", listOf("tnt sports"), domestic = false), "TNT Sports")
    }

    /** Network for a broadcast name; unknown ones (regional networks) match by their own words with sports context. */
    private fun networkFor(name: String): Network? {
        val n = norm(name)
        if (n.isEmpty()) return null
        NETWORKS[n]?.let { return it }
        // Regional sports networks: "FDSN Southeast", "Bally Sports Detroit", "NBC Sports Chicago".
        for (prefix in listOf("fdsn ", "fanduel sports network ", "fanduel sports ", "bally sports ")) {
            if (n.startsWith(prefix)) {
                val region = n.removePrefix(prefix)
                return net(name, listOf("fanduel sports $region", "bally sports $region", "fdsn $region", "fanduel $region"), needCtx = false)
            }
        }
        if (n.startsWith("nbc sports ")) return net(name, listOf(n), needCtx = false)
        if (n.startsWith("sportsnet") || n.startsWith("tsn")) return net(name, listOf(n), domestic = false)
        return Network(name, listOf(words(n)), emptySet(), needCtx = true, domesticOnly = false)
    }

    // ---------------------------------------------------------------- matching

    /**
     * Ranked channels for each game (keyed by [Game.key]). Heavy work: call on Dispatchers.Default.
     * [guide] is the XMLTV guide keyed by stream URL (empty when not loaded).
     */
    fun match(games: List<Game>, index: LiveIndex, guide: Map<String, List<Programme>>): Map<String, GameChannels> {
        if (games.isEmpty() || index.size == 0) return emptyMap()
        val ct = channelText(index)
        val gt = guideText(index, guide)
        val out = HashMap<String, GameChannels>(games.size * 2)
        for (g in games) out[g.key] = matchOne(g, index, ct, gt)
        return out
    }

    private fun matchOne(g: Game, index: LiveIndex, ct: ChannelText, gt: GuideText?): GameChannels {
        val best = HashMap<Int, ChannelHit>()
        fun offer(i: Int, score: Int, why: String) {
            val bonus = ct.quality[i] + when (ct.region[i].toInt()) { 0 -> 2; 1 -> 1; else -> 0 }
            val s = score + bonus
            val old = best[i]
            if (old == null || old.score < s) best[i] = ChannelHit(i, s, why)
        }
        val league = g.league
        val own = bit(league.sport)
        val college = league == League.NCAAF
        val a = teamKey(g.away, league)
        val h = teamKey(g.home, league)
        val versus = "${g.away.short} at ${g.home.short}"

        // (a) Event channels: the channel name names the game.
        val candidates = HashSet<Int>()
        for (k in listOf(a, h)) {
            for (p in k.phrases) ct.wi.forEachDoc(keyWord(ct.wi, p)) { candidates.add(it) }
        }
        if (a.abbr.isNotEmpty() && h.abbr.isNotEmpty()) {
            val (rare, other) = if (ct.wi.count(a.abbr) <= ct.wi.count(h.abbr)) a.abbr to h.abbr else h.abbr to a.abbr
            ct.wi.forEachDoc(rare) { i -> if (ct.toks[i]?.contains(other) == true) candidates.add(i) }
        }
        for (i in candidates) {
            val w = ct.toks[i] ?: continue
            val bits = ct.sport[i]
            if (bits != 0 && bits and own == 0) continue // another sport's channel
            val ctx = ct.sportsCtx[i]
            val leagueWord = bits and own != 0
            val ma = mentions(w, a, college)
            val mh = mentions(w, h, college)
            when {
                ma && mh -> if (ctx || !(a.generic && h.generic)) offer(i, S_EVENT_BOTH, "Event: $versus")
                abbrPair(w, a.abbr, h.abbr) && (ctx || leagueWord) -> offer(i, S_EVENT_ABBR, "Event: $versus")
                (ma || mh) && ctx && hasSeparator(w) -> {
                    val generic = if (ma) a.generic else h.generic
                    if (!generic || leagueWord) offer(i, S_EVENT_ONE, "Event: ${if (ma) g.away.short else g.home.short}")
                }
            }
        }

        // (b) Program guide: what's on around kick-off mentions the teams.
        if (gt != null && g.startMs > 0) {
            val from = g.startMs - 3 * 3_600_000L
            val to = g.startMs + 30 * 60_000L
            val minEnd = g.startMs + 15 * 60_000L
            val progs = HashSet<Int>()
            for (k in listOf(a, h)) for (p in k.phrases) gt.wi.forEachDoc(keyWord(gt.wi, p)) { progs.add(it) }
            for (p in progs) {
                if (gt.start[p] !in from..to || gt.end[p] < minEnd) continue
                val i = gt.chan[p]
                val w = gt.toks[p]
                val bits = sportBits(w) or ct.sport[i]
                if (bits != 0 && bits and own == 0) continue
                val ma = mentions(w, a, college)
                val mh = mentions(w, h, college)
                val sporty = bits and own != 0 || w.any { it == sportWord(league.sport) }
                when {
                    ma && mh -> if (ct.sportsCtx[i] || sporty || !(a.generic && h.generic)) offer(i, S_GUIDE_BOTH, "Guide: $versus")
                    ma || mh -> {
                        val generic = if (ma) a.generic else h.generic
                        if (sporty && (!generic || ct.sportsCtx[i])) offer(i, S_GUIDE_ONE, "Guide: ${if (ma) g.away.short else g.home.short}")
                    }
                }
            }
        }

        // (c) Networks carrying the game.
        for ((order, b) in g.broadcasts.withIndex()) {
            val nw = networkFor(b) ?: continue
            val primary = if (order == 0) 3 else 0
            val found = HashMap<String, Int>() // dedupe key -> channel
            for (p in nw.phrases) {
                if (p.isEmpty()) continue
                ct.wi.forEachDoc(keyWord(ct.wi, p)) { i ->
                    val w = ct.toks[i] ?: return@forEachDoc
                    if (find(w, p, false) < 0) return@forEachDoc
                    // A Huntsville station of this network counts even as "WHNT News 19".
                    val home = ct.home[i] >= 0 && LiveFill.HOME_LOCALS[ct.home[i]].second == nw.label
                    if (!home && nw.exclude.isNotEmpty() && w.any { it in nw.exclude && it !in p }) return@forEachDoc
                    if (nw.domesticOnly && ct.region[i].toInt() == 2) return@forEachDoc
                    if (!home && nw.needCtx && !ct.sportsCtx[i]) return@forEachDoc
                    val bits = ct.sport[i]
                    if (bits != 0 && bits and own == 0) return@forEachDoc
                    val key = ct.dedupe[i] ?: return@forEachDoc
                    val prev = found[key]
                    if (prev == null || rank(ct, i) > rank(ct, prev)) found[key] = i
                }
            }
            found.values.sortedWith(compareByDescending<Int> { if (ct.home[it] >= 0) 1 else 0 }.thenByDescending { rank(ct, it) }).take(MAX_PER_NETWORK).forEach { i ->
                // The plain network ("CBS", "CBS HD") before local affiliates ("CBS 5 KPHO").
                val exact = if (ct.cleanWords[i] <= nw.phrases.minOf { it.size.coerceAtLeast(1) }) 4 else 0
                // Huntsville stations first among a network's channels.
                val home = if (ct.home[i] >= 0 && LiveFill.HOME_LOCALS[ct.home[i]].second == nw.label) S_HOME else 0
                offer(i, S_NETWORK + exact + primary + home, nw.label)
            }
        }

        val hits = best.values.sortedWith(compareByDescending<ChannelHit> { it.score }.thenBy { it.idx })
        // Near-identical names (same channel in HD and SD): keep the best one.
        val seen = HashSet<String>()
        val out = ArrayList<ChannelHit>()
        for (hit in hits) {
            val key = ct.dedupe[hit.idx] ?: continue
            if (!seen.add(key)) continue
            out.add(hit)
            if (out.size >= MAX_PER_GAME) break
        }
        return GameChannels(out)
    }

    private fun sportWord(s: Sport): String = when (s) {
        Sport.FOOTBALL -> "football"
        Sport.BASKETBALL -> "basketball"
        Sport.BASEBALL -> "baseball"
        Sport.HOCKEY -> "hockey"
        Sport.SOCCER -> "soccer"
    }

    /** Preference among copies of one channel: US groups, then quality. */
    private fun rank(ct: ChannelText, i: Int): Int = (2 - ct.region[i]) * 10 + ct.quality[i]

    /** Whole-league channels (RedZone, NFL Network, numbered NFL event channels...), best first. */
    fun leagueChannels(league: League, index: LiveIndex, max: Int = 30): IntArray {
        if (index.size == 0) return IntArray(0)
        val ct = channelText(index)
        val own = bit(league.sport)
        val score = HashMap<Int, Int>()
        val strong = league.leagueChannels.map { words(it) }
        for ((order, p) in strong.withIndex()) {
            if (p.isEmpty()) continue
            ct.wi.forEachDoc(keyWord(ct.wi, p)) { i ->
                val w = ct.toks[i] ?: return@forEachDoc
                if (find(w, p, false) < 0) return@forEachDoc
                val bits = ct.sport[i]
                if (bits != 0 && bits and own == 0) return@forEachDoc
                if (p.size == 1 && !ct.sportsCtx[i]) return@forEachDoc
                score[i] = maxOf(score[i] ?: 0, 100 - order) // listed order: RedZone before NFL Network...
            }
        }
        // Channels named for the league in sports groups ("NFL 01", "USA NFL Game 3").
        val primary = words(league.keywords.first())
        if (primary.size == 1) {
            ct.wi.forEachDoc(primary[0]) { i ->
                if (ct.sportsCtx[i] && i !in score) score[i] = 10
            }
        }
        val seen = HashSet<String>()
        return score.entries
            .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenByDescending { rank(ct, it.key) }.thenBy { it.key })
            .map { it.key }
            .filter { i -> ct.dedupe[i]?.let { seen.add(it) } ?: false }
            .take(max)
            .toIntArray()
    }
}
