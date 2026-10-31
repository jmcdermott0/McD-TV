package com.mcd.tv.data

import java.util.Locale

// ======================= Live TV: the curated My Guide lineup =======================
//
// J, Oct 31, 2026: "The guide kinda sucks. It's a ton of Reelz channels. I want the guide to consist of local
// Huntsville channels, sports, some crime, some movies like HBO channels, and some random."
//
// My Guide used to rank up to 60 channels by taste. Big playlists carry the same network many times under
// lineup tags ("AT&T:", "PRIME:", "RK:", "TV:", "US:"), and the taste ranking saw each tag as a new network, so a
// crime-leaning profile got six copies of Reelz. Now My Guide is this fixed lineup: one copy per network, in five
// groups. Every other channel stays in Live TV's sections, All channels and Search; it only leaves My Guide.
//
// The networks below were picked from J's real playlist on Oct 31, 2026 (16,620 entries), keeping only networks
// the guide can fill: the provider's own guide, the Roku and Plex guide (GuideExtra), or game listings
// (LiveFill: ESPN2, ESPNU and the Huntsville stations during their games). Pure Kotlin, tested on the JVM
// (LiveLineupTest). The kids profile keeps its own taste-picked guide (LiveForYou).

object LiveLineup {
    const val LOCALS = "Huntsville Locals"
    const val SPORTS = "Sports"
    const val CRIME = "Crime"
    const val MOVIES = "Movies"
    const val MORE = "More to Watch"

    /** Group order on screen. */
    val GROUPS = listOf(LOCALS, SPORTS, CRIME, MOVIES, MORE)

    /** One network of the lineup: [pattern] must match the whole [norm] name of a copy. */
    class Net(val group: String, val label: String, pattern: String) {
        val re = Regex(pattern)
    }

    val NETWORKS: List<Net> = listOf(
        // Huntsville, AL stations (call sign in the name). WHIQ (PBS) and WHDF (CW) are not in the playlist.
        Net(LOCALS, "WAFF 48 NBC", ".*\\(waff\\).*"),
        Net(LOCALS, "WHNT 19 CBS", ".*\\(whnt\\).*"),
        Net(LOCALS, "WAAY 31 ABC", ".*\\(waay\\).*"),
        Net(LOCALS, "WZDX 54 FOX", ".*\\(wzdx\\).*"),
        // NFL, college football, MLB, NHL, NBA, and the Philadelphia teams.
        Net(SPORTS, "ESPN", "espn(?: usa| east)?"),
        Net(SPORTS, "ESPN2", "espn ?2(?: east)?"),
        Net(SPORTS, "ESPNU", "espn ?u"),
        Net(SPORTS, "ESPNews", "espn ?news"),
        Net(SPORTS, "SEC Network", "sec network"),
        Net(SPORTS, "ACC Network", "acc network"),
        Net(SPORTS, "Big Ten Network", "big ten network|btn"),
        Net(SPORTS, "FS1", "fox sports 1|fs1"),
        Net(SPORTS, "FS2", "fox sports 2|fs2"),
        Net(SPORTS, "NFL Network", "nfl network"),
        Net(SPORTS, "NFL RedZone", "nfl red ?zone"),
        Net(SPORTS, "MLB Network", "mlb network"),
        Net(SPORTS, "NHL Network", "nhl network"),
        Net(SPORTS, "NBA TV", "nba tv"),
        Net(SPORTS, "TNT", "tnt(?: east)?"),
        Net(SPORTS, "CBS Sports Network", "cbs sports network"),
        Net(SPORTS, "NBC Sports Philadelphia", "nbc sports philadel(?:ph|f)ia"),
        // Some crime: one Reelz (the provider files it as "REELZ FAMUS"), not its free-channel spin-offs.
        Net(CRIME, "Reelz", "reelz(?: ?channel| famus)?"),
        Net(CRIME, "Law & Crime", "law ?(?:&|and) ?crime(?: network)?"),
        Net(CRIME, "Oxygen True Crime", "oxygen(?: true crime)?"),
        Net(CRIME, "Court TV", "court ?tv"),
        // Movies.
        Net(MOVIES, "HBO", "hbo(?: east)?"),
        Net(MOVIES, "HBO Signature", "hbo signature(?: east)?"),
        Net(MOVIES, "Cinemax", "cinemax(?: east)?"),
        Net(MOVIES, "Showtime", "showtime(?: east)?"),
        Net(MOVIES, "Starz", "starz(?: east)?"),
        Net(MOVIES, "AMC", "amc(?: east)?"),
        // Some random.
        Net(MORE, "Discovery", "discovery(?: channel)?"),
        Net(MORE, "History", "history(?: channel)?"),
        Net(MORE, "A&E", "a ?& ?e"),
        Net(MORE, "FX", "fx"),
        Net(MORE, "USA Network", "usa(?: network)?"),
        Net(MORE, "Comedy Central", "comedy central"),
        Net(MORE, "Food Network", "food network"),
        Net(MORE, "HGTV", "hgtv"),
        Net(MORE, "Smithsonian Channel", "smithsonian(?: channel)?"),
    )

    // ---------------------------------------------------------------- names

    private val FANCY = Regex("[\\u0250-\\u02FF\\u1D00-\\u1DBF\\u2070-\\u209F\\u2C7C\\u2C7D]+")
    private val LINEUP = Regex("^\\s*[A-Za-z0-9&+.' ]{1,12}[|:]\\s*")
    private val FEED_TAG = Regex("\\((?:[a-z]|\\d{1,2})\\)|\\[\\s*\\]")
    private val QUALITY = Regex("(?<![a-z0-9])(?:hd|fhd|uhd|sd|4k|raw|\\d{2}\\s?fps|hevc|1080p|720p|backup)(?![a-z0-9])")
    private val SPACES = Regex("\\s+")

    /**
     * A copy's name without lineup tags, feed tags or quality marks, lower case:
     * "US: REELZ FAMUS HD" -> "reelz famus", "US: NBC 48 (WAFF) HUNTSVILLE (D) ᴿᴬᵂ" -> "nbc 48 (waff) huntsville".
     */
    fun norm(raw: String): String {
        var s = FANCY.replace(raw, " ")
        repeat(2) { s = LINEUP.replace(s, "") }
        s = s.lowercase(Locale.ROOT)
        s = FEED_TAG.replace(s, " ")
        s = QUALITY.replace(s, " ")
        return SPACES.replace(s, " ").trim()
    }

    /** Provider groups that hold events, replays and 24/7 loops, never a network's own feed. */
    private val NOT_A_FEED = Regex("(?i)PPV|REPLAY|24/7")

    /** The lineup network a copy belongs to, or null. US copies only. */
    fun netOf(ch: Channel): Net? {
        if (NOT_A_FEED.containsMatchIn(ch.group)) return null
        if (LiveOrganizer.country(ch.group, ch.name) != "US") return null
        val n = norm(ch.name)
        return NETWORKS.firstOrNull { it.re.matches(n) }
    }

    // ---------------------------------------------------------------- picking one copy

    /**
     * How good one copy is for the guide: listings now or later first (the provider's guide or the Roku and Plex
     * guide, already merged into [guide]), then a guide id of its own, then the feed itself (4K over FHD over HD,
     * East over West, no backups: [LiveOrganizer.feedScore]).
     */
    internal fun copyScore(ch: Channel, guide: Map<String, List<Programme>>, now: Long): Int {
        var s = 0
        if (guide[Epg.keyOf(ch)]?.any { it.end > now } == true) s += 1000
        if (!ch.tvgId.isNullOrBlank()) s += 100
        s += LiveOrganizer.feedScore(ch.name, ch.group, "US")
        return s
    }

    /** One picked channel: [index] into the playlist index, its [net]. */
    class Pick(val index: Int, val net: Net)

    /**
     * The lineup for [channels]: one copy per network, in [NETWORKS] order (so grouped as [GROUPS]). Networks the
     * playlist does not carry are skipped. On a tie the earlier copy wins (playlists list the main feed first).
     */
    fun picks(channels: List<Channel>, guide: Map<String, List<Programme>>, now: Long): List<Pick> {
        val best = HashMap<Net, Pair<Int, Int>>() // net -> (index, score)
        for (i in channels.indices) {
            val ch = channels[i]
            val net = netOf(ch) ?: continue
            val sc = copyScore(ch, guide, now)
            val prev = best[net]
            if (prev == null || sc > prev.second) best[net] = i to sc
        }
        return NETWORKS.mapNotNull { n -> best[n]?.let { Pick(it.first, n) } }
    }

    /** My Guide as a section: every pick in group order, with one sub-group per group that has channels. */
    fun section(channels: List<Channel>, guide: Map<String, List<Programme>>, now: Long, title: String = "My Guide"): LiveSection {
        val p = picks(channels, guide, now)
        val groups = GROUPS.mapNotNull { g ->
            val items = p.filter { it.net.group == g }.map { it.index }.toIntArray()
            if (items.isEmpty()) null else LiveSubgroup(g, items)
        }
        return LiveSection(title, p.map { it.index }.toIntArray(), groups)
    }
}
