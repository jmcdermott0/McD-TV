package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Holiday mode on this TV, and its Holiday Movies shelf ([Holiday.ID]). The Home row and Browse > Holiday Movies
 * both call [shelf], so they show the same titles in the same order (Home shows the first 20).
 * Built like True Crime (TrueCrimeHub.kt), with the History hub's helpers and caches:
 *  - Candidates from TMDB discover: movies with TMDB's Christmas, Santa Claus or Thanksgiving keywords,
 *    US-rated G or PG only (the same filter as the Kids profile's rows), most voted first.
 *  - The hand-placed family classics (Holiday.MARQUEE), looked up on TMDB by name and kept on the TV.
 *  - Each title's plot keywords (HistoryHub's shared cache), then Holiday.shelf picks and orders.
 * Catalog metadata only: every profile sees the same shelf, Kids included. Only "Not for me" and Settings >
 * Taste "Topics to skip" apply. Titles are listed whether or not a source has them yet, like every shelf.
 */
object HolidayHub {
    private const val SHELF_TTL_MS = 15 * 60_000L
    private const val MAX = 60

    private const val STORED_KEY = "holiday_shelf"
    private const val STORED_TTL_MS = 24 * 3_600_000L
    private const val STORED_VERSION = 1 // bump when Holiday's rules change, so an old shelf is rebuilt
    private const val RETRY_MS = 3_000L

    /** Automated QA only ("--ei qa_holiday_day 20261201"): the date Holiday mode uses instead of today. 0 = today. */
    @Volatile var qaDay = 0

    fun today(): Int = qaDay.takeIf { it > 0 } ?: DailyPicks.dayOf(System.currentTimeMillis())

    /** Holiday mode is on: the switch in Settings is on and today is in the season (Nov 1 to Jan 6). */
    fun active(): Boolean = Holiday.active(Prefs.holidayMode, today())

    /** Home and Browse show the shelf to every profile while Holiday mode is on. */
    fun showFor(): Boolean = active()

    private fun keyOf(t: Title) = "${t.type}:${t.id}"

    /** Up to Thanksgiving the shelf leads with Thanksgiving; after it, with Christmas. One cached shelf per order. */
    private fun orderOf(day: Int) = if (day <= Holiday.THANKSGIVING) "thanksgiving" else "christmas"

    private class Cached(val titles: List<Title>, val at: Long)
    private val shelves = HashMap<String, Cached>()
    private val buildLock = Mutex()

    // ---------------------------------------------------------------- marquee titles kept on the TV

    @Volatile private var marqueeMem: List<Title>? = null
    private val marqueeLock = Mutex()

    /** The marquee titles found on TMDB (exact name, within a year). Kept 30 days; a miss is retried after 7. */
    private suspend fun marqueeTitles(): List<Title> = marqueeLock.withLock {
        marqueeMem ?: marqueeLoad().also { if (it.isNotEmpty()) marqueeMem = it }
    }

    private suspend fun marqueeLoad(): List<Title> = coroutineScope {
        val now = System.currentTimeMillis()
        val cache = HistoryHub.loadMap("holiday_marquee")
        fun k(m: HolidayMarquee) = "${m.type}|${m.name}|${m.year}"
        val got = Holiday.MARQUEE.map { m ->
            async {
                val c = cache.optJSONObject(k(m))
                val age = now - (c?.optLong("at") ?: 0L)
                val fresh = c != null && age < (if (c.has("t")) 30L else 7L) * 86_400_000L
                if (fresh) return@async Triple(m, c?.optJSONObject("t")?.let { with(HistoryHub) { it.toTitle() } }, false)
                val r = runCatching {
                    HistoryHub.net.withPermit {
                        listOf(m.year, m.year - 1, m.year + 1).firstNotNullOfOrNull { y -> Tmdb.findTitle(m.type, m.name, y, exact = true) }
                    }
                }
                if (r.isFailure) Triple(m, c?.optJSONObject("t")?.let { with(HistoryHub) { it.toTitle() } }, false) else Triple(m, r.getOrNull(), true)
            }
        }.awaitAll()
        if (got.any { it.third }) synchronized(this@HolidayHub) {
            val fresh = HistoryHub.loadMap("holiday_marquee")
            got.filter { it.third }.forEach { (m, t) ->
                fresh.put(k(m), JSONObject().put("at", now).apply { if (t != null) put("t", with(HistoryHub) { t.toJson() }) })
            }
            Prefs.putJson("holiday_marquee", fresh.toString())
        }
        got.mapNotNull { it.second }
    }

    // ---------------------------------------------------------------- the shelf

    /** The TMDB discover queries: (type, page, params). Movies rated G or PG in the US only. */
    private fun queries(christmasIds: String, thanksgivingIds: String): List<Triple<String, Int, Map<String, String>>> = buildList {
        val family = mapOf("certification_country" to "US", "certification.lte" to "PG")
        if (christmasIds.isNotEmpty()) for (page in 1..3) {
            add(Triple("movie", page, family + mapOf("with_keywords" to christmasIds, "vote_count.gte" to "50")))
        }
        if (thanksgivingIds.isNotEmpty()) {
            add(Triple("movie", 1, family + mapOf("with_keywords" to thanksgivingIds, "vote_count.gte" to "10")))
        }
    }

    private suspend fun build(day: Int): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles() }
        val christmasIds = HistoryHub.keywordIds(listOf("christmas", "santa claus")).joinToString("|")
        val thanksgivingIds = HistoryHub.keywordIds(listOf("thanksgiving")).joinToString("|")
        val candidates = (marquee.await() + HistoryHub.discoverAll(queries(christmasIds, thanksgivingIds))).distinctBy(::keyOf)
        if (candidates.isEmpty()) throw IllegalStateException("Couldn't load Holiday Movies. Check the network and try again.")
        val kws = HistoryHub.keywordsOf(candidates)
        val catalog = candidates.map { t ->
            CatalogTitle(
                keyOf(t), t.name, t.year.toIntOrNull() ?: 0, t.genreIds.toSet(), kws[keyOf(t)].orEmpty(),
                t.language, t.votes, t.rating, HistoryHub.american(t),
            )
        }
        val byKey = candidates.associateBy(::keyOf)
        Holiday.shelf(catalog, day).mapNotNull(byKey::get)
    }

    /** The whole shelf (60 titles at most) for one profile: "Not for me" and "Topics to skip" taken out. */
    suspend fun shelf(profile: String = Prefs.activeProfile, max: Int = MAX): List<Title> {
        if (!showFor()) return emptyList()
        val day = today()
        val order = orderOf(day)
        val all = buildLock.withLock {
            val now = System.currentTimeMillis()
            synchronized(shelves) { shelves[order] }?.takeIf { now - it.at < SHELF_TTL_MS }?.titles
                ?: storedShelf(order, now, STORED_TTL_MS)?.also { synchronized(shelves) { shelves[order] = Cached(it, now) } }
                ?: try {
                    build(day).also { if (it.isNotEmpty()) { synchronized(shelves) { shelves[order] = Cached(it, now) }; storeShelf(order, it, now) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    storedShelf(order, now, Long.MAX_VALUE) ?: throw e // TMDB unreachable: an older shelf beats no row
                }
        }
        val kws = HistoryHub.keywordsOf(all) // cached on the TV by now: no requests
        val block = HistoryHub.blocked(profile)
        return all.filterNot { block(it, kws[keyOf(it)].orEmpty()) }.take(max)
    }

    /** Home's Holiday Movies row: the first 20 titles of the shelf Browse shows. A failed load is tried once more. */
    suspend fun homeRow(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? {
        if (!showFor()) return null
        for (attempt in 0..1) {
            if (attempt > 0) delay(RETRY_MS)
            val row = runCatching { shelf(profile, 20) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (row != null) return Holiday.LABEL to row
        }
        return null
    }

    private fun storedShelf(order: String, now: Long, maxAge: Long): List<Title>? = runCatching {
        val o = HistoryHub.loadMap(STORED_KEY)
        if (o.optInt("v") != STORED_VERSION || o.optString("order") != order || now - o.optLong("at") >= maxAge) return null
        val a = o.optJSONArray("t") ?: return null
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> with(HistoryHub) { j.toTitle() } } }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun storeShelf(order: String, titles: List<Title>, now: Long) {
        val a = JSONArray().apply { titles.forEach { put(with(HistoryHub) { it.toJson() }) } }
        Prefs.putJson(STORED_KEY, JSONObject().put("v", STORED_VERSION).put("order", order).put("at", now).put("t", a).toString())
    }
}
