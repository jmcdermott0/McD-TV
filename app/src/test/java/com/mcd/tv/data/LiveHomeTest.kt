package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Live TV's default view ([LiveHome]) against the stand-in playlist and guide snapshot (tools/livehome/fixture).
 * Every output must equal tools/livehome/expected.json, which the web app's tools/webtest/livehome-test.mjs
 * checks too: both apps build the same rows from the same playlist and guide.
 */
class LiveHomeTest {

    private fun find(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above " + System.getProperty("user.dir"))
    }

    private fun gz(rel: String): String = GZIPInputStream(find(rel).inputStream()).bufferedReader().readText()

    private val expected: JSONObject by lazy { JSONObject(find("tools/livehome/expected.json").readText()) }

    private fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }

    private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** XMLTV the way Epg.parse reads it (title, sub-title, desc, category, live, previously-shown), by guide id. */
    private fun xmltv(text: String): Map<String, List<Programme>> {
        val out = HashMap<String, ArrayList<Programme>>()
        val re = Regex("<programme\\b([^>]*)>([\\s\\S]*?)</programme>")
        fun attr(a: String, k: String) = Regex("$k=\"([^\"]*)\"").find(a)?.groupValues?.get(1) ?: ""
        fun tag(body: String, t: String) = Regex("<$t\\b[^>]*>([\\s\\S]*?)</$t>").find(body)?.groupValues?.get(1)?.let { unesc(it).trim() } ?: ""
        for (m in re.findAll(text)) {
            val a = m.groupValues[1]
            val body = m.groupValues[2]
            val cats = Regex("<category\\b[^>]*>([\\s\\S]*?)</category>").findAll(body).map { unesc(it.groupValues[1]).trim() }.toList()
            val p = Programme(
                tag(body, "title"), Epg.parseTime(attr(a, "start"))!!, Epg.parseTime(attr(a, "stop"))!!, tag(body, "desc"),
                sub = tag(body, "sub-title"), categories = cats, live = Regex("<live\\s*/?>").containsMatchIn(body),
                rerun = body.contains("<previously-shown"),
            )
            out.getOrPut(attr(a, "channel")) { ArrayList() }.add(p)
        }
        for (l in out.values) l.sortBy { it.start }
        return out
    }

    private class Fixture(val chs: List<Channel>, val idx: LiveGroups, val progs: (Int) -> List<Programme>)

    private val fixture: Fixture by lazy {
        val raw = M3u.parse(gz("tools/livehome/fixture/playlist.m3u.gz"))
        val chs = LiveOrganizer.build(raw).channels
        val guide = xmltv(gz("tools/livehome/fixture/guide.xml.gz"))
        // Epg.parse's match: GuideMatch over the whole playlist (as the TV hands it over), guide ids case-insensitive.
        val plan = GuideMatch.plan(raw)
        val lower = guide.keys.associateBy { it.lowercase() }
        val gid = chs.map { c -> plan.idOf[Epg.keyOf(c)]?.let { lower[it] } ?: "" }
        // A West feed: East listings 3 hours later; live events stay at their real time and replace what they overlap.
        fun progs(i: Int): List<Programme> {
            val l = if (gid[i].isEmpty()) emptyList() else guide[gid[i]].orEmpty()
            if (Epg.keyOf(chs[i]) !in plan.shifted) return l
            val sh = GuideMatch.WEST_SHIFT_MS
            val live = l.filter { it.live }
            return l.filter { p -> p.live || live.none { x -> p.start + sh < x.end && x.start < p.end + sh } }
                .map { p -> if (p.live) p else p.copy(start = p.start + sh, end = p.end + sh) }.sortedBy { it.start }
        }
        val country = chs.map { LiveOrganizer.country(it.group, it.name) }
        val section = chs.mapIndexed { i, c -> LiveOrganizer.section(c.group, c.name, country[i]) }
        val idx = LiveHome.groups(chs, { country[it] }, { section[it] })
        Fixture(chs, idx, { i -> progs(i) })
    }

    @Test
    fun catalogIsTheWebAppsCatalog() {
        assertEquals(strings(expected.getJSONArray("catalog")), LiveHome.CATALOG.trim().split("\n"))
    }

    @Test
    fun identifyCases() {
        val a = expected.getJSONArray("identify")
        val bad = ArrayList<String>()
        for (k in 0 until a.length()) {
            val c = a.getJSONArray(k)
            val (name, group, want) = Triple(c.getString(0), c.getString(1), c.getString(2))
            val country = LiveOrganizer.country(group, name)
            val id = LiveHome.identify(name, group, country, LiveOrganizer.section(group, name, country))
            val got = id.key + (if (id.region.isNotEmpty() && id.region != "national") " (${id.region})" else "") + (id.fold?.let { " -> ${it.key}" } ?: "")
            if (got != want) bad.add("$name @ $group: got $got, want $want")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun programmeCases() {
        val a = expected.getJSONArray("programmes")
        val bad = ArrayList<String>()
        for (k in 0 until a.length()) {
            val c = a.getJSONObject(k)
            val o = c.getJSONObject("p")
            val cats = o.optJSONArray("cats")?.let { strings(it) } ?: emptyList()
            val p = Programme(o.getString("title"), 0, 0, o.optString("desc", ""), sub = o.optString("sub", ""), categories = cats, live = o.optBoolean("live", false), rerun = o.optBoolean("rerun", false))
            val g = LiveHome.gameOf(p)
            val game = g?.let { "${it.first}: ${it.second}" + if (it.third.isNotEmpty()) " | ${it.third}" else "" }
            val want = if (c.isNull("game")) null else c.getString("game")
            if (game != want) bad.add("${p.title}: game $game, want $want")
            val t = LiveHome.topics(p)
            val topics = listOfNotNull("docs".takeIf { t.docs }, "military".takeIf { t.military }, "airforce".takeIf { t.airforce }, "tech".takeIf { t.tech }).joinToString(",")
            if (topics != c.getString("topics")) bad.add("${p.title}: topics $topics, want ${c.getString("topics")}")
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun azGroupsMatchAndHoldEveryChannel() {
        val f = fixture
        val want = expected.getJSONObject("groups")
        assertEquals(want.getInt("channels"), f.chs.size)
        val labels = f.idx.list.map { "${it.label} [${it.kind}, ${it.feeds.size}]" }
        val wantLabels = strings(want.getJSONArray("labels"))
        val diff = labels.indices.filter { it >= wantLabels.size || labels[it] != wantLabels[it] }.take(10).map { "$it: ${labels[it]} vs ${wantLabels.getOrNull(it)}" }
        assertTrue("A-Z entries differ from the web app:\n" + diff.joinToString("\n"), diff.isEmpty() && labels.size == wantLabels.size)
        // No channel unreachable: each one sits in exactly one A-Z entry.
        val count = IntArray(f.chs.size)
        for (g in f.idx.list) for (i in g.feeds) count[i]++
        assertTrue("every channel once", count.all { it == 1 })
    }

    private fun games(snap: String): Pair<Long, List<HomeGame>> {
        val o = JSONObject(find("tools/livehome/fixture/games.json").readText()).getJSONObject(snap)
        val a = o.getJSONArray("games")
        fun side(s: JSONObject) = HomeSide(s.getString("name"), s.getString("short"), s.getString("nick"))
        return o.getLong("now") to List(a.length()) { k ->
            val g = a.getJSONObject(k)
            HomeGame(g.getString("league"), g.getString("id"), g.getString("state"), g.getLong("start"), g.optString("detail", ""),
                side(g.getJSONObject("away")), side(g.getJSONObject("home")), strings(g.getJSONArray("casts")))
        }
    }

    private val dad = listOf(HomeTeam("NFL", "Eagles"), HomeTeam("MLB", "Phillies"), HomeTeam("NHL", "Flyers"), HomeTeam("NBA", "76ers"))

    private fun run(now: Long, games: List<HomeGame>, teams: List<HomeTeam>): List<HomeRow> {
        val f = fixture
        return LiveHome.home(f.idx, f.chs, f.progs, games, teams, now) { i -> LiveFill.eventProgramme(f.chs[i].name, f.chs[i].group, now) }
    }

    @Test
    fun snapshotsMatchTheWebApp() {
        val f = fixture
        val (nowA, gamesA) = games("A")
        val (nowB, gamesB) = games("B")
        val runs = mapOf(
            "A guide only" to run(nowA, emptyList(), dad),
            "A with ESPN" to run(nowA, gamesA, dad),
            "B with ESPN" to run(nowB, gamesB, dad),
            "A fresh profile (no teams)" to run(nowA, gamesA, emptyList()),
        )
        val bad = ArrayList<String>()
        val want = expected.getJSONObject("snapshots")
        for ((name, rows) in runs) {
            val w = want.getJSONArray(name)
            for (r in rows.indices) {
                val wr = w.getJSONObject(r)
                assertEquals("$name row $r", wr.getString("id"), rows[r].id)
                val got = rows[r].tiles.map { t -> listOf(t.title, t.sub, t.badge, if (t.feeds.isNotEmpty()) f.chs[t.feeds[0]].name else "", t.feeds.size.toString()).joinToString(" | ") }
                val exp = strings(wr.getJSONArray("tiles"))
                if (got != exp) bad.add("$name ${rows[r].id}:\n  got  $got\n  want $exp")
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun liveEaglesGamePinnedFromTheGuideAlone() {
        val f = fixture
        val (nowA, _) = games("A")
        val rows = run(nowA, emptyList(), dad)
        val t = rows.first { it.id == "myteams" }.tiles.first()
        assertTrue(t.live && t.mine && t.title.contains("Eagles") && t.badge.startsWith("LIVE · ★ MY TEAM"))
        assertTrue(f.chs[t.feeds[0]].name, f.chs[t.feeds[0]].name.contains("WTXF Philadelphia"))
        val leagues = rows.first { it.id == "games" }.tiles.map { it.league }.toSet()
        assertTrue(leagues.toString(), leagues.all { it in setOf("NFL", "NCAAF", "MLB", "NHL", "NBA") })
    }
}
