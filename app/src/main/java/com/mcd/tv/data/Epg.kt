package com.mcd.tv.data

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

// ======================= Live TV: XMLTV program guide =======================

/**
 * One programme. [start] / [end] are epoch milliseconds. [untimed]: an event channel that names its game but no
 * start time (shown as "Live event"; [start] is the top of the hour). From the XMLTV guide also: [sub] (sub-title),
 * [categories], [live] (<live/>) and [rerun] (<previously-shown/>), which Live TV's rows read (see LiveHome).
 */
data class Programme(
    val title: String,
    val start: Long,
    val end: Long,
    val desc: String,
    val untimed: Boolean = false,
    val sub: String = "",
    val categories: List<String> = emptyList(),
    val live: Boolean = false,
    val rerun: Boolean = false,
)

/**
 * Reads the playlist's XMLTV guide (plain or .gz), keeping only what Live TV shows:
 * programmes for channels in the playlist that are on now or start in the next 12 hours.
 * Guide map keys are [keyOf] (the channel's stream URL). Any failure gives an empty guide.
 * Which guide id each channel reads is decided by [GuideMatch].
 */
object Epg {
    /**
     * Kept ahead of now. Longer than [CACHE_MS] plus My Guide's three hours, so a guide that is almost due for a
     * reload still fills the whole grid (with 8 h, the grid's right edge read "No guide info" for the last hour).
     */
    private const val WINDOW_MS = 12 * 3600_000L
    private const val CACHE_MS = 6 * 3600_000L
    private const val MAX_KEPT = 60_000
    private const val MAX_DESC = 160

    /** A failed or empty download is tried again after this, instead of being cached for [CACHE_MS]. */
    private const val RETRY_MS = 90_000L

    private val lock = Mutex()
    private var cacheKey = ""
    private var cacheAt = 0L
    private var failKey = ""
    private var failAt = 0L

    /** The most recently loaded guide (empty until a load finishes). */
    @Volatile var current: Map<String, List<Programme>> = emptyMap()
        private set

    /** Coverage of the last guide read (Settings > Live TV health shows it), or null before the first one. */
    class Stats(val channels: Int, val withId: Int, val listed: Int, val byHow: Map<String, Int>)

    @Volatile var lastStats: Stats? = null
        private set

    /**
     * What the last guide load did, for the house "tv_guide" report (phones read it; no link, no login):
     * when it ran, how long it took, how many programmes were kept, and why it failed ("" when it worked).
     */
    @Volatile var lastAt = 0L
        private set
    @Volatile var lastMs = 0L
        private set
    @Volatile var lastKept = 0
        private set
    @Volatile var lastError = ""
        private set
    /** <channel> and <programme> elements in the last guide read (before matching). */
    @Volatile var lastXmlChannels = 0
        private set
    @Volatile var lastXmlProgrammes = 0
        private set

    private fun why(e: Throwable): String = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160)

    /** The last good guide when it was for the same playlist, else nothing. */
    private fun lastGood(key: String): Map<String, List<Programme>> = if (key == cacheKey) current else emptyMap()

    /** Key a channel is filed under in the guide map. */
    fun keyOf(ch: Channel): String = ch.url

    suspend fun load(url: String, channels: List<Channel>): Map<String, List<Programme>> {
        if (url.isBlank() || channels.isEmpty()) return emptyMap()
        val key = "$url|${channels.size}"
        return try {
            lock.withLock {
                if (key == cacheKey && System.currentTimeMillis() - cacheAt < CACHE_MS) return@withLock current
                if (key == failKey && System.currentTimeMillis() - failAt < RETRY_MS) return@withLock lastGood(key)
                val t0 = System.currentTimeMillis()
                lastError = ""
                val guide = try {
                    withContext(Dispatchers.IO) { download(url, channels) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    lastError = why(e)
                    emptyMap() // OutOfMemoryError included: a guide is never worth a crash
                }
                lastAt = System.currentTimeMillis()
                lastMs = lastAt - t0
                if (guide.isEmpty() && lastError.isEmpty()) lastError = "empty guide: no programme matched a channel"
                if (guide.isEmpty()) {
                    // Failed, refused or empty: keep the last good guide and try again in [RETRY_MS].
                    failKey = key
                    failAt = System.currentTimeMillis()
                    return@withLock lastGood(key)
                }
                current = guide
                cacheKey = key
                cacheAt = System.currentTimeMillis()
                failKey = ""
                guide
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            lastError = why(e)
            emptyMap() // OutOfMemoryError included: a guide is never worth a crash
        }
    }

    /** What's on now and next for a channel key, from [guide] (defaults to the last loaded guide). */
    fun nowNext(key: String, guide: Map<String, List<Programme>> = current, now: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = guide[key] ?: return null to null
        var cur: Programme? = null
        var next: Programme? = null
        for (p in list) { // sorted by start
            if (p.end <= now) continue
            if (p.start <= now && cur == null) { cur = p; continue }
            if (p.start > now) { next = p; break }
        }
        return cur to next
    }

    private suspend fun download(url: String, channels: List<Channel>): Map<String, List<Programme>> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "VLC/3.0.20 LibVLC/3.0.20")
            val code = c.responseCode
            if (code !in 200..299) { lastError = "HTTP $code"; return emptyMap() }
            val raw = BufferedInputStream(c.inputStream, 64 * 1024)
            raw.mark(4)
            val b0 = raw.read()
            val b1 = raw.read()
            raw.reset()
            val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw
            return input.use { parse(it, channels) }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun parse(input: InputStream, channels: List<Channel>): Map<String, List<Programme>> {
        // Guide id per channel: its own tvg-id, else a copy of the same network that has one ([GuideMatch]).
        val plan = GuideMatch.plan(channels)
        val byTvgId = plan.byTvgId
        // Guide channels whose id no playlist channel names: matched by display-name (exact, then normalized).
        val byName = HashMap<String, MutableList<String>>()
        val byKey = HashMap<String, MutableList<String>>()
        for (ch in channels) {
            val k = keyOf(ch)
            ch.tvgName?.lowercase()?.let { byName.getOrPut(it) { ArrayList(1) }.add(k) }
            byName.getOrPut(ch.name.lowercase()) { ArrayList(1) }.let { if (k !in it) it.add(k) }
            val gk = LiveFill.guideKey(ch.name)
            if (gk.isNotEmpty()) byKey.getOrPut(gk) { ArrayList(1) }.add(k)
        }
        // XMLTV channel id -> channel keys (filled from <channel> elements).
        val idMap = HashMap<String, List<String>>()
        fun keysFor(xmlId: String): List<String>? = idMap[xmlId] ?: byTvgId[xmlId.lowercase()]
        fun shiftOf(k: String): Long = if (k in plan.shifted) GuideMatch.WEST_SHIFT_MS else 0L

        val now = System.currentTimeMillis()
        val until = now + WINDOW_MS
        val out = HashMap<String, ArrayList<Programme>>()
        var kept = 0
        var xmlCh = 0
        var xmlPr = 0
        val ctx = coroutineContext

        val p = Xml.newPullParser()
        try {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, null)
            var event = p.eventType
            var counter = 0
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    if (++counter % 2000 == 0) ctx.ensureActive()
                    when (p.name) {
                        "channel" -> { xmlCh++; readChannel(p, byTvgId, byName, byKey)?.let { (id, keys) -> idMap[id] = keys } }
                        "programme" -> {
                            xmlPr++
                            val keys = keysFor(p.getAttributeValue(null, "channel") ?: "")
                            val start = parseTime(p.getAttributeValue(null, "start"))
                            val end = parseTime(p.getAttributeValue(null, "stop"))
                            // A West feed reads its East twin's listings 3 hours later (live events air at the
                            // same time on both), so the window is per channel.
                            fun inWindow(sh: Long) = end!! + sh > now && start!! + sh < until
                            if (keys == null || start == null || end == null || keys.none { k -> inWindow(0L) || inWindow(shiftOf(k)) }) {
                                skip(p)
                            } else {
                                val prog = readProgramme(p, start, end)
                                for (k in keys) {
                                    val sh = if (prog.live) 0L else shiftOf(k)
                                    if (!inWindow(sh)) continue
                                    out.getOrPut(k) { ArrayList() }.add(if (sh == 0L) prog else prog.copy(start = start + sh, end = end + sh))
                                }
                                if (++kept >= MAX_KEPT) break
                            }
                        }
                    }
                }
                event = p.next()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Malformed or cut-off guide: keep what was read so far.
            lastError = "guide cut off: " + why(e)
        }
        // Sort each channel's list and drop duplicates (a channel matched by two guide ids).
        val result = HashMap<String, List<Programme>>(out.size)
        for ((k, list) in out) {
            // A West feed: a live event (kept at its real time) replaces the delayed shows it overlaps.
            if (k in plan.shifted && list.any { it.live }) {
                val live = list.filter { it.live }
                list.removeAll { p -> !p.live && live.any { l -> p.start < l.end && l.start < p.end } }
            }
            list.sortBy { it.start }
            val clean = ArrayList<Programme>(list.size)
            var lastStart = Long.MIN_VALUE
            for (prog in list) if (prog.start != lastStart) { clean.add(prog); lastStart = prog.start }
            result[k] = clean
        }
        lastKept = kept
        lastXmlChannels = xmlCh
        lastXmlProgrammes = xmlPr
        val keys = channels.mapTo(HashSet()) { keyOf(it) }
        lastStats = Stats(keys.size, plan.how.size, result.keys.count { it in keys }, plan.how.values.groupingBy { it }.eachCount())
        return result
    }

    /** Reads a <channel> element; returns its id and the playlist keys it matches, or null. */
    private fun readChannel(
        p: XmlPullParser,
        byTvgId: Map<String, List<String>>,
        byName: Map<String, List<String>>,
        byKey: Map<String, List<String>>,
    ): Pair<String, List<String>>? {
        val id = p.getAttributeValue(null, "id") ?: run { skip(p); return null }
        var keys: List<String>? = byTvgId[id.lowercase()]
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "display-name") {
                val name = readText(p).trim().lowercase()
                if (keys == null && name.isNotEmpty()) keys = byName[name] ?: byKey[LiveFill.guideKey(name)]
            }
            event = p.next()
        }
        return keys?.let { id to it }
    }

    private fun readProgramme(p: XmlPullParser, start: Long, end: Long): Programme {
        var title = ""
        var sub = ""
        var desc = ""
        var cats: ArrayList<String>? = null
        var live = false
        var rerun = false
        val depth = p.depth
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> { val t = readText(p).trim(); if (title.isEmpty()) title = t }
                    "sub-title" -> { val t = readText(p).trim(); if (sub.isEmpty()) sub = t.take(MAX_DESC) }
                    "desc" -> { val d = readText(p).trim(); if (desc.isEmpty()) desc = if (d.length > MAX_DESC) d.take(MAX_DESC - 1) + "…" else d }
                    // Live TV's rows: what kind of programme (Documentary, War, Football...), live or a repeat.
                    "category" -> { val c = readText(p).trim(); if (c.isNotEmpty() && (cats?.size ?: 0) < 6) (cats ?: ArrayList<String>(3).also { cats = it }).add(c) }
                    "live" -> live = true
                    "previously-shown" -> rerun = true
                }
            }
            event = p.next()
        }
        return Programme(title.ifEmpty { "Untitled" }, start, end, desc, sub = sub, categories = cats ?: emptyList(), live = live, rerun = rerun)
    }

    /** Text inside the current start tag; leaves the parser on its end tag (tolerates nested tags). */
    private fun readText(p: XmlPullParser): String {
        val depth = p.depth
        val sb = StringBuilder()
        var event = p.next()
        while (!(event == XmlPullParser.END_TAG && p.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) sb.append(p.text)
            event = p.next()
        }
        return sb.toString()
    }

    /** Skips the current element and everything inside it; leaves the parser on its end tag. */
    private fun skip(p: XmlPullParser) {
        if (p.eventType != XmlPullParser.START_TAG) return
        var level = 1
        while (level > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> level++
                XmlPullParser.END_TAG -> level--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** "20261008183000 +0000" (offset optional) -> epoch ms, or null. */
    internal fun parseTime(s: String?): Long? {
        if (s == null || s.length < 12) return null
        fun num(from: Int, len: Int): Int? {
            var v = 0
            for (i in from until from + len) {
                val c = s.getOrNull(i) ?: return null
                if (c !in '0'..'9') return null
                v = v * 10 + (c - '0')
            }
            return v
        }
        val y = num(0, 4) ?: return null
        val mo = num(4, 2) ?: return null
        val d = num(6, 2) ?: return null
        val h = num(8, 2) ?: return null
        val mi = num(10, 2) ?: return null
        val sec = if (s.length >= 14) num(12, 2) ?: 0 else 0
        if (mo !in 1..12 || d !in 1..31) return null
        var offsetMin = 0
        val tz = s.substring(minOf(14, s.length)).trim()
        if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
            val oh = tz.substring(1, 3).toIntOrNull()
            val om = tz.substring(3, 5).toIntOrNull()
            if (oh != null && om != null) offsetMin = (oh * 60 + om) * (if (tz[0] == '-') -1 else 1)
        }
        val days = daysFromCivil(y, mo, d)
        val utcSec = days * 86_400L + h * 3600L + mi * 60L + sec - offsetMin * 60L
        return utcSec * 1000L
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm; no java.time on API 24). */
    private fun daysFromCivil(year: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}

// ======================= Which guide id a channel reads =======================
//
// Pure (no XML, no network): unit tests in GuideMatchTest; the web app mirrors it (docs/app/index.html, LV guideMatch).
// Why it exists: on J's real playlist (2026-10-29) 15,280 of 16,620 entries carry no guide id. Most of those are free
// lineup channels the provider's guide does not list at all, but many are copies of a network or a station whose
// other copy does carry an id. Each rule below only ever borrows an id that a channel of the same name, the same
// guide-id stem or the same call sign already carries; it never invents one.

object GuideMatch {
    /** Values of [Plan.how]: why a channel reads the guide id it reads. */
    const val OWN = "own"
    const val NAME = "name"
    const val LINEUP = "lineup"
    const val STEM = "idstem"
    const val CALL = "callsign"
    const val WEST = "west"
    const val NETWORK = "network"

    /** A West feed airs its East twin's schedule this much later. */
    const val WEST_SHIFT_MS = 3 * 3600_000L

    /**
     * [byTvgId]: guide id (lower case) -> channel keys. [shifted]: channel keys that read their East twin's listings
     * 3 hours later. [how]: channel key -> rule (channels with no guide id are absent). [idOf]: channel key -> guide id.
     */
    class Plan(
        val byTvgId: Map<String, List<String>>,
        val shifted: Set<String>,
        val how: Map<String, String>,
        val idOf: Map<String, String>,
    )

    /**
     * Lineup and region tags the organizer keeps in the name ("TV: CNN", "CA FR: HISTORIA", "SS: beIN SPORTS 1")
     * but that say nothing about which network it is. Not "RK:": Roku's own channels read Roku's guide (GuideExtra).
     */
    private val LINEUP_TAG = Regex("^\\s*(?:TV|(?!RK)[A-Z]{2}(?: (?:EN|FR))?)\\s*[:|]\\s*")

    /** Free lineups: their station channels are often a news-only stream, so a call sign alone is not enough there. */
    private val FAST_TAG = Regex(
        "^\\s*(?:PRIME|AMAZON|TUBI|RK|ROKU|PLUTO(?: ?TV)?|PLEX|XUMO|SAMSUNG(?: TV PLUS)?|VIZIO|LG|DISTRO|STIRR|FREEVEE|FAST)\\s*[:|]",
        RegexOption.IGNORE_CASE,
    )
    private val CALL_PAREN = Regex("\\(([KW][A-Z]{2,3})(?:-[A-Z]{2})?\\)")
    private val CALL_LEAD = Regex("^(?:[A-Z&+]{2,6}\\s*[:|]\\s*)*([KW][A-Z]{2,3})(?:-?(?:TV|DT|CD|LD))?\\b(?=.*\\b(?:ABC|CBS|NBC|FOX|CW|PBS|MYTV|TELEMUNDO|UNIVISION|ION)(?![A-Z]))")
    private val ID_SUFFIX = Regex("^(.+)\\.([a-z]{2})$")
    private val NOT_STEM = Regex("[^a-z0-9+!]")
    private val WEST_END = Regex("^(.+) west$")
    private val NO_SHIFT = Regex("\\b(?:sports?|sportsnet|news|bally|weather|abc|cbs|nbc|fox|cw|pbs)\\b")

    private val PAY_TAG = Regex(
        "^\\s*(?:AT&T|ATT|TV|DIRECTV|DTV|SLING|SPECTRUM|FUBO(?:TV)?|PHILO|HULU|YOUTUBE TV|YTTV|DISH|XFINITY|FIOS|VERIZON)\\s*[:|]",
        RegexOption.IGNORE_CASE,
    )
    private val ALT = Regex("\\b(?:alt|alternate)\\b", RegexOption.IGNORE_CASE)
    private val WEST_WORD = Regex("\\bwest\\b")

    /** Networks whose channels are local stations: one station's guide is not another's. */
    private val BROADCAST = setOf("abc", "cbs", "nbc", "fox", "cw", "telemundo", "univision", "unimas", "mynet", "ion", "pbs")

    /**
     * The catalog network a channel is a plain copy of ("AT&T: MS NOW" and "US: MSNBC HD" are both "msnbc"), with
     * "|w" for a West feed; null for free lineups, alternate feeds, folders, other countries and station networks.
     */
    private fun networkKey(ch: Channel, country: String, key: String, lender: Boolean): String? {
        if (FAST_TAG.containsMatchIn(ch.name) || ALT.containsMatchIn(ch.name)) return null
        // Borrowing by catalog name only inside a pay-TV lineup ("AT&T: E!", "TV: MS NOW"): untagged names that the
        // catalog aliases ("NFL CHANNEL", "AXS TV NOW") are often a free channel of the same brand.
        if (!lender && (country != "US" || !PAY_TAG.containsMatchIn(ch.name))) return null
        val r = LiveHome.identify(ch.name, ch.group, country, "")
        if (r.net == null || r.fold != null || r.kind == "intl" || r.region == "market" || r.key in BROADCAST) return null
        return r.key + if (WEST_WORD.containsMatchIn(key)) "|w" else ""
    }

    /** Guide-id country suffix for a channel's country ([LiveOrganizer.country]). */
    private val SUFFIX = mapOf("US" to "us", "Canada" to "ca", "UK" to "uk")

    /**
     * "hbo west" -> "hbo": the East twin of a West feed key, or null. Regional names ("sportsnet west") and broadcast
     * networks stay: a network's West feed is a different station with its own local shows.
     */
    fun westTwin(key: String): String? {
        val m = WEST_END.matchEntire(key) ?: return null
        return if (NO_SHIFT.containsMatchIn(key)) null else m.groupValues[1]
    }

    /** Stem of a guide id with a two-letter country suffix: "AWealthofEntertainment.us" -> ("us", "awealthofentertainment"). */
    private fun stemOf(id: String): Pair<String, String>? =
        ID_SUFFIX.matchEntire(id)?.let { it.groupValues[2] to NOT_STEM.replace(it.groupValues[1], "") }

    /** A US channel never borrows another country's feed ("theweathernetwork.ca" for The Weather Channel). */
    private fun usOk(country: String, id: String): Boolean = country != "US" || stemOf(id)?.first.let { it == null || it == "us" }

    /** Call sign in the name: "ABC (WPLG) Miami", or a leading one next to a network ("US: KTRK ABC 13"). */
    fun callSign(name: String): String? {
        val u = name.uppercase(Locale.ROOT)
        return (CALL_PAREN.find(u) ?: CALL_LEAD.find(u))?.groupValues?.get(1)?.lowercase(Locale.ROOT)
    }

    fun plan(channels: List<Channel>, keyOf: (Channel) -> String = Epg::keyOf): Plan {
        val n = channels.size
        val gk = Array(n) { LiveFill.guideKey(channels[it].name) }
        val own = Array(n) { channels[it].tvgId?.trim()?.takeIf { s -> s.isNotEmpty() }?.lowercase(Locale.ROOT) }
        val ctry = arrayOfNulls<String>(n)
        fun countryOf(i: Int): String = ctry[i] ?: LiveOrganizer.country(channels[i].group, channels[i].name).also { ctry[i] = it }
        // Name with a lineup tag taken off ("TV: CNN" -> "cnn"); the plain guide key when there is no tag.
        fun lineupKey(i: Int): String {
            val name = channels[i].name
            val t = LINEUP_TAG.replace(name, "")
            return if (t != name) LiveFill.guideKey(t) else gk[i]
        }
        // The first guide id seen per normalized name (any country, and per country), and per id stem.
        val donor = HashMap<String, String>()
        val local = HashMap<String, String>()
        val stems = HashMap<String, String>()
        val nets = HashMap<String, String>()
        val idNets = HashMap<String, MutableSet<String>>()
        for (i in 0 until n) {
            val id = own[i] ?: continue
            if (gk[i].isNotEmpty()) donor.putIfAbsent(gk[i], id)
            val lk = lineupKey(i)
            if (lk.isNotEmpty()) local.putIfAbsent(countryOf(i) + "|" + lk, id)
            stemOf(id)?.let { (suf, stem) -> if (stem.isNotEmpty()) stems.putIfAbsent("$suf|$stem", id) }
            networkKey(channels[i], countryOf(i), lk, lender = true)?.let { nk ->
                nets.putIfAbsent(nk, id)
                idNets.getOrPut(id) { HashSet(2) }.add(nk.substringBefore('|'))
            }
        }
        // An id the provider files under two networks ("Hallmark Mystery" filed under hallmark.us) is not lent out.
        nets.values.removeAll { (idNets[it]?.size ?: 0) > 1 }
        val byTvgId = HashMap<String, MutableList<String>>()
        val shifted = HashSet<String>()
        val how = HashMap<String, String>()
        val idOf = HashMap<String, String>()
        for (i in 0 until n) {
            val ch = channels[i]
            val k = keyOf(ch)
            var id = own[i]
            var rule = if (id != null) OWN else ""
            if (id == null && gk[i].isNotEmpty()) donor[gk[i]]?.let { id = it; rule = NAME }
            val key = lineupKey(i)
            if (id == null && key.isNotEmpty()) {
                val c = countryOf(i)
                val v = local["$c|$key"] ?: if (key != gk[i]) donor[key] else null
                if (v != null && usOk(c, v)) { id = v; rule = LINEUP }
                val suf = SUFFIX[c]
                if (id == null && suf != null) {
                    stems["$suf|${NOT_STEM.replace(key, "")}"]?.let { id = it; rule = STEM }
                }
                if (id == null && suf == "us" && !FAST_TAG.containsMatchIn(ch.name)) {
                    callSign(ch.name)?.let { cs -> stems["us|$cs"]?.let { id = it; rule = CALL } }
                }
                if (id == null) {
                    networkKey(ch, c, key, lender = false)?.let { nk -> nets[nk]?.takeIf { usOk(c, it) }?.let { id = it; rule = NETWORK } }
                }
            }
            // West feed: the East twin's id with listings 3 hours later, also when the provider gave the West copy
            // the East id itself ("HBO West" filed under hbo.us showed East times).
            val twinId = westTwin(key)?.let { donor[it] }
            if (twinId != null && (id == null || id == twinId) && usOk(countryOf(i), twinId)) {
                if (id == null) rule = WEST
                id = twinId
                shifted.add(k)
            }
            val gid = id ?: continue
            byTvgId.getOrPut(gid) { ArrayList(1) }.let { if (k !in it) it.add(k) }
            how.putIfAbsent(k, rule)
            idOf.putIfAbsent(k, gid)
        }
        return Plan(byTvgId, shifted, how, idOf)
    }
}

// ======================= Filling the guide where the playlist has none =======================
//
// Mirrors the web app's LV helpers (docs/app/index.html: gkey, homeRank, netOf, eventProg, gameProg), so the TV
// and the web app show the same rows. Pure functions; unit tests in LiveFillTest.

object LiveFill {
    // ---------------------------------------------------------------- guide keys

    private val BACKUP_TAG = Regex("[(\\[]\\s*(?:backup|bk|alt|alternate)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val WEST_TAG = Regex("[(\\[]\\s*w\\s*[)\\]]")
    private val EAST_TAG = Regex("[(\\[]\\s*e\\s*[)\\]]")
    private val PACIFIC = Regex("\\bpacific\\b")
    private val DROP = Regex("\\b(?:hd|fhd|uhd|sd|4k|east|eastern|feed|backup|hevc|raw|1080p|720p)\\b")
    private val NON_KEY = Regex("[^a-z0-9+!]+")
    private val TRAIL_US = Regex(" (?:us|usa)$")
    private val NUM_JOIN = Regex("\\b([a-z]+) (\\d{1,2})\\b")

    /**
     * Key a channel borrows a guide by: "US| ESPN 2 FHD" and "ESPN2 (backup)" -> "espn2", "A&E" -> "a and e".
     * East and plain feeds share a key; West ("HBO West", "HBO (Pacific)", "HBO (W)") stays apart, 3 hours off.
     */
    fun guideKey(name: String): String {
        var t = LiveOrganizer.cleanName(BACKUP_TAG.replace(name, " ")).lowercase(Locale.ROOT)
        t = t.replace("&", " and ")
        t = WEST_TAG.replace(t, " west ")
        t = EAST_TAG.replace(t, " ")
        t = PACIFIC.replace(t, "west")
        t = DROP.replace(t, " ")
        t = NON_KEY.replace(t, " ").trim()
        t = TRAIL_US.replace(t, "")
        return NUM_JOIN.replace(t, "$1$2")
    }

    // ---------------------------------------------------------------- Huntsville and networks

    /** Huntsville stations, in the order Local and Games list them: call sign to network. */
    val HOME_LOCALS: List<Pair<String, String>> = listOf("whnt" to "CBS", "waay" to "ABC", "waff" to "NBC", "wzdx" to "FOX", "whdf" to "CW")
    private val HOME_RE = Regex("(?:^|[^a-z0-9])(whnt|waay|waff|wzdx|whdf)(?:-?(?:tv|dt|cd|ld)?\\d{0,2})?(?![a-z0-9])")

    /** Position of a Huntsville station in [HOME_LOCALS], or -1. */
    fun homeRank(name: String): Int {
        val m = HOME_RE.find(name.lowercase(Locale.ROOT)) ?: return -1
        val sign = m.groupValues[1]
        return HOME_LOCALS.indexOfFirst { it.first == sign }
    }

    /** Network of a Huntsville station's call sign, or null. */
    fun homeNetwork(name: String): String? = homeRank(name).let { if (it >= 0) HOME_LOCALS[it].second else null }

    private val NET_EXCL = Regex("\\b(?:news|sports?|business|deportes|weather|kids|family|life|soul|movies|classic|plus|now|live|reality|drama|mystery|comedy|soccer|nation|extra|college|latino|ocho|espanol)\\b")
    private val SECN = Regex("^(?:sec network|secn|sec)$")
    private val ACCN = Regex("^(?:acc network|accn|acc)$")
    private val BTN = Regex("^(?:big ten network|big ten|big10 network|big10|btn)$")
    private val ESPN = Regex("^(?:espn|espn2|espnu)$")
    private val BROADCAST = Regex("(?:^| )(cbs|fox|abc|nbc|cw)\\d{0,2}(?= |$)")

    /** Network of a plain network or affiliate channel ("ESPN", "FOX 54 Huntsville", "SEC Network"), or "". */
    fun networkOf(name: String): String {
        homeNetwork(name)?.let { return it }
        val k = guideKey(name)
        return when {
            SECN.matches(k) -> "SECN"
            ACCN.matches(k) -> "ACCN"
            BTN.matches(k) -> "BTN"
            ESPN.matches(k) -> k.uppercase(Locale.ROOT)
            NET_EXCL.containsMatchIn(k) || k.contains('+') -> ""
            else -> BROADCAST.find(k)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: ""
        }
    }

    /** ESPN broadcast name in the same codes ("SEC Network" -> "SECN", "The CW" -> "CW"). */
    fun castNetwork(b: String): String {
        val u = b.uppercase(Locale.ROOT).filter { it in 'A'..'Z' || it in '0'..'9' }
        return when (u) {
            "SECNETWORK" -> "SECN"
            "ACCNETWORK" -> "ACCN"
            "BIGTENNETWORK", "BIGTEN" -> "BTN"
            "THECW" -> "CW"
            else -> u
        }
    }

    // ---------------------------------------------------------------- event channels

    private val FOOTBALL = Regex("\\b(?:nfl|ncaaf|cfb|football|redzone|xfl|ufl|cfl)\\b")
    private val LONG_EVENT = Regex("\\b(?:ufc|boxing|ppv|mma|pfl|wwe|aew|bellator|golf|pga)\\b")
    private val BASEBALL = Regex("\\b(?:mlb|baseball|milb)\\b")
    private val SHORT_GAME = Regex("\\b(?:nba|wnba|ncaab|ncaam|basketball|nhl|hockey|ahl)\\b")
    private val SOCCER = Regex("\\b(?:mls|epl|soccer|premier league|la ?liga|serie a|bundesliga|uefa|champions league|futbol|liga mx)\\b")

    /** Typical length in minutes for an event named [text] (lower case). */
    internal fun eventMinutes(text: String): Int = when {
        FOOTBALL.containsMatchIn(text) -> 210
        LONG_EVENT.containsMatchIn(text) -> 240
        BASEBALL.containsMatchIn(text) -> 180
        SHORT_GAME.containsMatchIn(text) -> 150
        SOCCER.containsMatchIn(text) -> 120
        else -> 180
    }

    private val ZONES = mapOf(
        "et" to "America/New_York", "est" to "America/New_York", "edt" to "America/New_York",
        "ct" to "America/Chicago", "cst" to "America/Chicago", "cdt" to "America/Chicago",
        "mt" to "America/Denver", "mst" to "America/Denver", "mdt" to "America/Denver",
        "pt" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
        "uk" to "Europe/London", "bst" to "Europe/London", "gmt" to "UTC", "utc" to "UTC",
    )
    private const val ZONE = "(et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|uk|gmt|bst|utc)"
    private val AMPM = Regex("(?:^|[^\\d:])(\\d{1,2})(?::([0-5]\\d))?\\s*([ap])\\.?\\s*m\\b\\.?\\s*$ZONE?\\b", RegexOption.IGNORE_CASE)
    private val CLK24 = Regex("(?:^|[^\\d:])([01]?\\d|2[0-3]):([0-5]\\d)\\s*$ZONE?\\b", RegexOption.IGNORE_CASE)
    private val DATES = Regex(
        "\\b(?:mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day)?\\b\\.?,?|\\b\\d{1,2}[/.]\\d{1,2}(?:[/.]\\d{2,4})?\\b|" +
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?\\b",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_MD = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/\\d{2,4})?\\b")
    private val DATE_NAMED = Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE)
    private val EMPTY_BRACKETS = Regex("[(\\[]\\s*[)\\]]")
    private val SPACES = Regex("\\s+")
    private val JUNK = Regex("^[\\s|:;,.\\-–()\\[\\]@]+|[\\s|:;,.\\-–()\\[\\]@]+$")
    private val NO_EVENT = Regex("\\b(?:no (?:event|game|stream)s?|off ?air|offline|tba|tbd|coming soon|not started|starts soon)\\b", RegexOption.IGNORE_CASE)
    private val SEP = Regex("\\s(?:vs\\.?|v\\.?|versus|at)\\s|\\s?@\\s?", RegexOption.IGNORE_CASE)
    private val PREFIX = Regex("^([^:|]{1,30}?)\\s*(?:[:|]|\\s[-–]\\s)\\s*(?=\\S)")
    private val NUMBERED = Regex("^([A-Za-z+]+(?: [A-Za-z+]+){0,2} ?\\d{1,3})\\s+(?=\\S.*(?:\\s(?:vs\\.?|v\\.?|versus|at)\\s|@))", RegexOption.IGNORE_CASE)
    private val VERSUS = Regex("^(.+?)(?:\\s(vs\\.?|v\\.?|versus|at)\\s|\\s?(@)\\s?)(.+)$", RegexOption.IGNORE_CASE)
    private val CHANNEL_NO = Regex("^[A-Za-z+ ]+ ?\\d{1,2}$")
    private val LETTER = Regex("[A-Za-z]")
    private val TWO_LETTERS = Regex("[A-Za-z]{2}")
    private const val MONTHS = "janfebmaraprmayjunjulaugsepoctnovdec"

    /**
     * An event channel's own listing: "NCAAF 05: Alabama vs Tennessee 3:30PM ET" -> "Alabama vs Tennessee" from
     * 3:30 PM Eastern for 3.5 hours. The start is on the date in the name, else the nearest day with that time.
     * Finished events give null. A name with a game but no time gives an [Programme.untimed] row.
     * Needs a numbered or labelled prefix ("NFL 03:") or a time, so "Alien vs Predator" is not an event.
     */
    fun eventProgramme(name: String, group: String, now: Long): Programme? {
        var s = SPACES.replace(LiveOrganizer.cleanName(name), " ")
        var hh = -1
        var mm = 0
        var tz: String? = null
        var last = AMPM.findAll(s).lastOrNull()
        if (last != null) {
            hh = (last.groupValues[1].toInt() % 12) + if (last.groupValues[3].equals("p", true)) 12 else 0
            mm = last.groupValues[2].toIntOrNull() ?: 0
            tz = ZONES[last.groupValues[4].lowercase(Locale.ROOT)]
        } else {
            last = CLK24.findAll(s).lastOrNull()
            if (last != null) {
                hh = last.groupValues[1].toInt()
                mm = last.groupValues[2].toInt()
                tz = ZONES[last.groupValues[3].lowercase(Locale.ROOT)]
            }
        }
        if (last != null) {
            val lead = if (last.value.first().isDigit()) 0 else 1
            s = s.substring(0, last.range.first + lead) + " " + s.substring(last.range.last + 1)
        }
        // A date in the name ("10/11", "Oct 11") picks the day.
        var md: IntArray? = null
        val dm = DATE_MD.find(s)
        val dn = DATE_NAMED.find(s)
        if (dm != null && dm.groupValues[1].toInt() in 1..12 && dm.groupValues[2].toInt() in 1..31) {
            md = intArrayOf(dm.groupValues[1].toInt(), dm.groupValues[2].toInt())
        } else if (dn != null && dn.groupValues[2].toInt() in 1..31) {
            md = intArrayOf(MONTHS.indexOf(dn.groupValues[1].lowercase(Locale.ROOT)) / 3 + 1, dn.groupValues[2].toInt())
        }
        s = DATES.replace(s, " ")
        s = EMPTY_BRACKETS.replace(s, " ")
        s = JUNK.replace(SPACES.replace(s, " "), "")
        var prefix = ""
        val p = PREFIX.find(s)
        if (p != null && !SEP.containsMatchIn(" " + p.groupValues[1] + " ")) {
            prefix = p.groupValues[1].trim()
            s = s.substring(p.range.last + 1)
        } else {
            NUMBERED.find(s)?.let { q ->
                prefix = q.groupValues[1]
                s = s.substring(q.range.last + 1)
            }
        }
        s = JUNK.replace(s, "")
        if (s.isEmpty() || NO_EVENT.containsMatchIn(s) || (prefix.isEmpty() && last == null)) return null
        val v = VERSUS.find(s)
        val title = if (v != null) {
            val a = JUNK.replace(v.groupValues[1], "")
            val b = JUNK.replace(v.groupValues[4], "")
            if (!LETTER.containsMatchIn(a) || !LETTER.containsMatchIn(b)) return null
            val at = v.groupValues[3].isNotEmpty() || v.groupValues[2].equals("at", true)
            a + (if (at) " at " else " vs ") + b
        } else {
            if (prefix.isEmpty() || last == null || !TWO_LETTERS.containsMatchIn(s)) return null
            if (CHANNEL_NO.matches(prefix)) s else "$prefix: $s"
        }
        val dur = eventMinutes("$name $group".lowercase(Locale.ROOT)) * 60_000L
        if (hh < 0) {
            val top = now / 3_600_000L * 3_600_000L
            return Programme(title, top, top + dur, "", untimed = true)
        }
        val zone = tz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        var bestStart = 0L
        var bestGap = Long.MAX_VALUE
        for (k in -1..1) {
            val c = Calendar.getInstance(zone, Locale.US)
            c.timeInMillis = now
            if (md != null) {
                val y = c.get(Calendar.YEAR) + k
                c.clear()
                c.set(y, md[0] - 1, md[1], hh, mm, 0)
            } else {
                c.add(Calendar.DAY_OF_MONTH, k)
                c.set(Calendar.HOUR_OF_DAY, hh)
                c.set(Calendar.MINUTE, mm)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
            }
            val start = c.timeInMillis
            val end = start + dur
            val gap = if (now < start) start - now else if (now >= end) now - end + 1 else 0L
            if (gap < bestGap) { bestGap = gap; bestStart = start }
        }
        if (now >= bestStart + dur || bestStart - now > 18 * 3_600_000L) return null
        return Programme(title, bestStart, bestStart + dur, "")
    }

    // ---------------------------------------------------------------- the network's game

    /** The live or next game on [net] from [games] (schedules already loaded), as a programme. */
    fun gameProgramme(net: String, games: List<Game>, now: Long): Programme? {
        if (net.isEmpty()) return null
        var live: Game? = null
        var next: Game? = null
        for (g in games) {
            if (g.final || g.broadcasts.none { castNetwork(it) == net }) continue
            if (g.live) {
                if (live == null || g.startMs > live.startMs) live = g
            } else if (g.startMs > now - 30 * 60_000L && g.startMs - now < 12 * 3_600_000L && (next == null || g.startMs < next.startMs)) {
                next = g
            }
        }
        val g = live ?: next ?: return null
        val dur = when (g.league.sport) {
            Sport.FOOTBALL -> 210
            Sport.BASEBALL -> 180
            Sport.BASKETBALL, Sport.HOCKEY -> 150
            Sport.SOCCER -> 120
        } * 60_000L
        val sep = if (g.league.sport == Sport.SOCCER) " vs " else " at "
        val title = g.away.short.ifEmpty { g.away.name } + sep + g.home.short.ifEmpty { g.home.name }
        val end = maxOf(g.startMs + dur, if (live != null) now + 30 * 60_000L else 0L)
        return Programme(title, g.startMs, end, if (live != null && g.detail.isNotEmpty()) "Live now. ${g.detail}" else "")
    }

    /** Games in the schedules already loaded (no network). */
    fun loadedGames(): List<Game> {
        // Rebuilt only when a schedule changes: Live TV rows call this while scrolling.
        var version = 0L
        for (l in League.entries) version = version * 31 + (Games.cached(l)?.fetchedAt ?: 0L)
        synchronized(gamesLock) {
            if (version != gamesVersion || gamesList == null) {
                gamesList = League.entries.flatMap { Games.cached(it)?.games.orEmpty() }
                gamesVersion = version
            }
            return gamesList!!
        }
    }

    private val gamesLock = Any()
    private var gamesVersion = Long.MIN_VALUE
    private var gamesList: List<Game>? = null

    /**
     * The fallback listing (event title, else the network's game) for a channel with no guide, remembered per
     * channel and minute: rows scrolling into view reuse it instead of running the event regexes again.
     */
    private fun fallback(ch: Channel, now: Long, games: () -> List<Game>): Programme? {
        val list = games()
        val key = ch.url + '|' + ch.name + '|' + (now / 60_000L) + '|' + System.identityHashCode(list)
        synchronized(fbCache) { if (fbCache.containsKey(key)) return fbCache[key] }
        val fb = eventProgramme(ch.name, ch.group, now) ?: gameProgramme(networkOf(ch.name), list, now)
        synchronized(fbCache) { fbCache[key] = fb }
        return fb
    }

    private val fbCache = object : LinkedHashMap<String, Programme?>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Programme?>?): Boolean = size > 4000
    }

    /**
     * Now and next for a channel: the real guide first; with none, the event channel's own title, then the live
     * or next game on its network.
     */
    fun nowNext(ch: Channel, guide: Map<String, List<Programme>>, now: Long, games: () -> List<Game> = ::loadedGames): Pair<Programme?, Programme?> {
        val real = Epg.nowNext(Epg.keyOf(ch), guide, now)
        if (real.first != null || real.second != null) return real
        val fb = fallback(ch, now, games) ?: return real
        return if (fb.untimed || fb.start <= now) fb to null else null to fb
    }

    /**
     * A channel's listings for grids and ranking (My Guide, For You): the real guide when it has anything not yet
     * over; else the same fallback as [nowNext] (event title, then the network's game) as a one-row list.
     * No network: [games] reads schedules already loaded.
     */
    fun programmes(ch: Channel, guide: Map<String, List<Programme>>, now: Long, games: () -> List<Game> = ::loadedGames): List<Programme> {
        val real = guide[Epg.keyOf(ch)].orEmpty()
        if (real.any { it.end > now }) return real
        val fb = fallback(ch, now, games)
        return if (fb != null) listOf(fb) else real
    }
}
