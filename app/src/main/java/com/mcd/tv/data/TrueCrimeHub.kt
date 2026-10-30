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
 * The True Crime category: one shelf with one identity ([TrueCrime.ID]). The Home row and Browse > True Crime
 * both call [shelf], so they always show the same titles in the same order (Home shows the first 20).
 * Built like the History hub (HistoryHub.kt), with its helpers and caches:
 *  - Candidates from TMDB discover (vote count order): Documentary + Crime movies and series, titles with
 *    TMDB's "true crime" keyword, and documentaries with crime keywords (serial killer, cold case...).
 *  - The hand-placed marquee titles (TrueCrime.MARQUEE), looked up once and kept on the TV.
 *  - Each title's plot keywords (HistoryHub's shared cache), then TrueCrime.shelf picks and orders.
 * Catalog metadata only: nothing reads what a profile watched, so every profile sees the same shelf. Only
 * "Not for me" and Settings > Taste "Topics to skip" apply. The Kids profile does not get the category.
 * Like every shelf in Jarvis, titles are listed whether or not a source has them yet.
 */
object TrueCrimeHub {
    private const val SHELF_TTL_MS = 15 * 60_000L
    private const val MAX = 60

    /** The built shelf kept on the TV ("truecrime_shelf"), so Home has the row right after the app restarts. */
    private const val STORED_KEY = "truecrime_shelf"
    private const val STORED_TTL_MS = 24 * 3_600_000L
    private const val STORED_VERSION = 1 // bump when TrueCrime's rules change, so an old shelf is rebuilt
    private const val RETRY_MS = 3_000L

    private fun keyOf(t: Title) = "${t.type}:${t.id}"

    /** Home and Browse show the category to every profile except Kids. */
    fun showFor(profile: String = Prefs.activeProfile): Boolean = profile != Prefs.KIDS_PROFILE

    // ---------------------------------------------------------------- shelf cache (memory)

    private class Cached(val titles: List<Title>, val at: Long)
    private val shelves = HashMap<String, Cached>()
    private val buildLock = Mutex() // Home and Browse may ask at once: build once

    // ---------------------------------------------------------------- marquee titles kept on the TV

    @Volatile private var marqueeMem: List<Title>? = null
    private val marqueeLock = Mutex()

    /** The marquee titles found on TMDB (exact name, within a year). Kept 30 days; a miss is retried after 7. */
    private suspend fun marqueeTitles(): List<Title> = marqueeLock.withLock {
        marqueeMem ?: marqueeLoad().also { if (it.isNotEmpty()) marqueeMem = it }
    }

    private suspend fun marqueeLoad(): List<Title> = coroutineScope {
        val now = System.currentTimeMillis()
        val cache = HistoryHub.loadMap("truecrime_marquee")
        fun k(m: CrimeMarquee) = "${m.type}|${m.name}|${m.year}"
        val got = TrueCrime.MARQUEE.map { m ->
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
        if (got.any { it.third }) synchronized(this@TrueCrimeHub) {
            val fresh = HistoryHub.loadMap("truecrime_marquee")
            got.filter { it.third }.forEach { (m, t) ->
                fresh.put(k(m), JSONObject().put("at", now).apply { if (t != null) put("t", with(HistoryHub) { t.toJson() }) })
            }
            Prefs.putJson("truecrime_marquee", fresh.toString())
        }
        got.mapNotNull { it.second }
    }

    // ---------------------------------------------------------------- the shelf

    /**
     * The TMDB discover queries, the same ones the web app's TC module sends (and the ones the test
     * fixture was read with): (type, page, params).
     */
    private fun queries(trueCrimeId: Int?, docIds: String): List<Triple<String, Int, Map<String, String>>> = buildList {
        for (type in listOf("tv", "movie")) for (page in 1..3) {
            add(Triple(type, page, mapOf("with_genres" to "${TrueCrime.DOCUMENTARY},${TrueCrime.CRIME}", "vote_count.gte" to "20")))
        }
        if (trueCrimeId != null) for (type in listOf("tv", "movie")) for (page in 1..2) {
            val notKids = if (type == "movie") HistoryHub.NOT_KIDS_MOVIE else HistoryHub.NOT_KIDS_TV
            add(Triple(type, page, mapOf("with_keywords" to "$trueCrimeId", "without_genres" to notKids, "vote_count.gte" to "20")))
        }
        if (docIds.isNotEmpty()) for (type in listOf("tv", "movie")) {
            add(Triple(type, 1, mapOf("with_genres" to "${TrueCrime.DOCUMENTARY}", "with_keywords" to docIds, "vote_count.gte" to "20")))
        }
    }

    private suspend fun build(): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles() }
        val trueCrimeId = HistoryHub.keywordIds(listOf(TrueCrime.TRUE_CRIME)).firstOrNull()
        val docIds = HistoryHub.keywordIds(TrueCrime.DOC_DISCOVER_TERMS).joinToString("|")
        val candidates = (marquee.await() + HistoryHub.discoverAll(queries(trueCrimeId, docIds))).distinctBy(::keyOf)
        if (candidates.isEmpty()) throw IllegalStateException("Couldn't load True Crime. Check the network and try again.")
        val kws = HistoryHub.keywordsOf(candidates)
        val catalog = candidates.map { t ->
            CatalogTitle(
                keyOf(t), t.name, t.year.toIntOrNull() ?: 0, t.genreIds.toSet(), kws[keyOf(t)].orEmpty(),
                t.language, t.votes, t.rating, HistoryHub.american(t),
            )
        }
        val byKey = candidates.associateBy(::keyOf)
        TrueCrime.shelf(catalog).mapNotNull(byKey::get)
    }

    /** The whole shelf (60 titles at most) for one profile: "Not for me" and "Topics to skip" taken out. */
    suspend fun shelf(profile: String = Prefs.activeProfile, max: Int = MAX): List<Title> {
        if (!showFor(profile)) return emptyList()
        val all = buildLock.withLock {
            val now = System.currentTimeMillis()
            synchronized(shelves) { shelves[""] }?.takeIf { now - it.at < SHELF_TTL_MS }?.titles
                ?: storedShelf(now, STORED_TTL_MS)?.also { synchronized(shelves) { shelves[""] = Cached(it, now) } }
                ?: try {
                    build().also { if (it.isNotEmpty()) { synchronized(shelves) { shelves[""] = Cached(it, now) }; storeShelf(it, now) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    storedShelf(now, Long.MAX_VALUE) ?: throw e // TMDB unreachable: an older shelf beats no row
                }
        }
        val kws = HistoryHub.keywordsOf(all) // cached on the TV by now: no requests
        val block = HistoryHub.blocked(profile)
        return all.filterNot { block(it, kws[keyOf(it)].orEmpty()) }.take(max)
    }

    /** Home's True Crime row: the first 20 titles of the same shelf Browse > True Crime shows. A failed load is tried once more. */
    suspend fun homeRow(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? {
        for (attempt in 0..1) {
            if (attempt > 0) delay(RETRY_MS)
            val row = runCatching { shelf(profile, 20) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (row != null) return TrueCrime.LABEL to row
        }
        return null
    }

    /** The shelf kept on the TV, when it is younger than [maxAge] and built by the current rules. */
    private fun storedShelf(now: Long, maxAge: Long): List<Title>? = runCatching {
        val o = HistoryHub.loadMap(STORED_KEY)
        if (o.optInt("v") != STORED_VERSION || now - o.optLong("at") >= maxAge) return null
        val a = o.optJSONArray("t") ?: return null
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> with(HistoryHub) { j.toTitle() } } }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun storeShelf(titles: List<Title>, now: Long) {
        val a = JSONArray().apply { titles.forEach { put(with(HistoryHub) { it.toJson() }) } }
        Prefs.putJson(STORED_KEY, JSONObject().put("v", STORED_VERSION).put("at", now).put("t", a).toString())
    }
}
