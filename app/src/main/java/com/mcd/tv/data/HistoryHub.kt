package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * The History hub (Browse > History, and the History row on Home). Gathers candidates from TMDB; the rules
 * that pick and order each section live in HistoryEras.kt.
 *  - Documentaries: Documentary-genre titles about history, and every Ken Burns film.
 *  - History: history-genre programming (History-genre movies, history series).
 *  - History Movies: fiction films set in a historical period; the hand-placed marquee dramas lead.
 *  - Ken Burns: his real crew credits (TMDB person lookup), every documentary he made.
 *  - By Era: titles whose own plot keywords place them in the era, in story order.
 * Every section comes from catalog metadata only (genres, plot keywords, credits). Nothing here reads what
 * a profile watched, liked or favorited, so a new profile sees the same sections as everyone else.
 * Crime and plain biopics are left out (HistoryEras.exclusion). The two profile settings still apply:
 * "Not for me" titles and Settings > Taste "Topics to skip" (Ken Burns keeps his whole filmography apart
 * from "Not for me").
 * Every shelf lists titles whether or not a source has them yet, like the rest of Jarvis: Detail and
 * Sources show what can play.
 *
 * Lookups that never change (keyword ids, marquee title ids, Ken Burns' id, each title's keywords) are kept
 * on the TV, so only the first visit makes many small requests.
 */
object HistoryHub {
    internal val net = Semaphore(6) // parallel TMDB requests
    private const val SHELF_TTL_MS = 15 * 60_000L
    private const val CLASS_MAX = 1500
    /** Bump when the saved keyword format changes. 4: every title's keywords (the exclusions need them). */
    private const val CLASS_VERSION = 4

    private fun keyOf(t: Title) = "${t.type}:${t.id}"

    /** The History row on Home: every profile except Kids whose Settings > Taste likes History or War (Dad by default). */
    fun homeRowFor(profile: String = Prefs.activeProfile): Boolean {
        if (profile == Prefs.KIDS_PROFILE) return false
        val likes = Recommender.choicesJson(profile).optJSONArray("likeGenres") ?: return false
        return (0 until likes.length()).any { likes.optInt(it) == HistoryEras.HISTORY || likes.optInt(it) == HistoryEras.WAR }
    }

    // ---------------------------------------------------------------- shelf cache (memory)

    private class Cached(val titles: List<Title>, val at: Long)
    private val shelves = HashMap<String, Cached>()

    /** One shelf, reused for 15 minutes (the hub and its See all grids share it). */
    private suspend fun cached(profile: String, id: String, build: suspend () -> List<Title>): List<Title> {
        val k = "$profile|$id"
        val now = System.currentTimeMillis()
        synchronized(shelves) { shelves[k] }?.let { if (now - it.at < SHELF_TTL_MS) return it.titles }
        val list = build()
        if (list.isNotEmpty()) synchronized(shelves) { shelves[k] = Cached(list, now) }
        return list
    }

    // ---------------------------------------------------------------- lookups kept on the TV

    internal fun loadMap(key: String): JSONObject = runCatching { JSONObject(Prefs.json(key).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    /** TMDB keyword ids for names (exact names only; 0 = TMDB has none). */
    internal suspend fun keywordIds(names: List<String>): List<Int> = coroutineScope {
        val wanted = names.distinct()
        val cache = loadMap("history_kwids")
        val found = wanted.filter { !cache.has(it) }
            .map { n -> async { n to runCatching { net.withPermit { Tmdb.keywordId(n, exact = true) } } } }.awaitAll()
            .filter { it.second.isSuccess } // offline: try again next time
        if (found.isNotEmpty()) {
            found.forEach { (n, r) -> cache.put(n, r.getOrNull() ?: 0) }
            synchronized(this@HistoryHub) {
                val fresh = loadMap("history_kwids")
                found.forEach { (n, r) -> fresh.put(n, r.getOrNull() ?: 0) }
                Prefs.putJson("history_kwids", fresh.toString())
            }
        }
        wanted.mapNotNull { n -> cache.optInt(n, 0).takeIf { it > 0 } }
    }

    internal fun Title.toJson() = JSONObject().put("id", id).put("type", type).put("name", name).put("overview", overview)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("rating", rating).put("year", year)
        .put("date", releaseDate).put("lang", language).put("votes", votes)
        .put("genres", org.json.JSONArray(genreIds)).put("oc", org.json.JSONArray(originCountries))

    internal fun JSONObject.toTitle() = Title(
        optInt("id"), optString("type"), optString("name"), optString("overview"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null }, optDouble("rating", 0.0), optString("year"),
        originCountries = optJSONArray("oc")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        language = optString("lang"), releaseDate = optString("date"), votes = optInt("votes"),
        genreIds = optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
    )

    /**
     * The marquee titles found on TMDB (exact name, within a year), with their entries. Kept on the TV for
     * 30 days; a title TMDB doesn't have yet is looked for again after a week.
     */
    private suspend fun marqueeTitles(): List<Pair<Marquee, Title>> = marqueeLock.withLock {
        marqueeMem ?: marqueeLoad().also { if (it.isNotEmpty()) marqueeMem = it }
    }
    private val marqueeLock = Mutex() // the shelves load in parallel; look the marquee titles up once
    @Volatile private var marqueeMem: List<Pair<Marquee, Title>>? = null

    private suspend fun marqueeLoad(): List<Pair<Marquee, Title>> = coroutineScope {
        val now = System.currentTimeMillis()
        val cache = loadMap("history_marquee")
        fun k(m: Marquee) = "${m.type}|${m.name}|${m.year}"
        val got = HistoryEras.MARQUEE.map { m ->
            async {
                val c = cache.optJSONObject(k(m))
                val age = now - (c?.optLong("at") ?: 0L)
                val fresh = c != null && age < (if (c.has("t")) 30L else 7L) * 86_400_000L
                if (fresh) return@async Triple(m, c?.optJSONObject("t")?.toTitle(), false)
                val r = runCatching {
                    net.withPermit {
                        listOf(m.year, m.year - 1, m.year + 1).firstNotNullOfOrNull { y -> Tmdb.findTitle(m.type, m.name, y, exact = true) }
                    }
                }
                if (r.isFailure) Triple(m, c?.optJSONObject("t")?.toTitle(), false) else Triple(m, r.getOrNull(), true)
            }
        }.awaitAll()
        if (got.any { it.third }) synchronized(this@HistoryHub) {
            val fresh = loadMap("history_marquee")
            got.filter { it.third }.forEach { (m, t) ->
                fresh.put(k(m), JSONObject().put("at", now).apply { if (t != null) put("t", t.toJson()) })
            }
            Prefs.putJson("history_marquee", fresh.toString())
        }
        got.mapNotNull { (m, t, _) -> t?.let { m to it } }
    }

    private suspend fun kenBurnsId(): Int? {
        Prefs.json("history_kenburns").toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        val id = net.withPermit { Tmdb.findPerson("Ken Burns", "Directing") } ?: return null
        Prefs.putJson("history_kenburns", id.toString())
        return id
    }

    // ---------------------------------------------------------------- keywords of each title

    private val kwCache = LinkedHashMap<String, List<String>>()
    @Volatile private var kwLoaded = false

    private fun loadKwCache() {
        if (kwLoaded) return
        synchronized(kwCache) {
            if (kwLoaded) return
            runCatching {
                val o = JSONObject(Prefs.json("history_class").ifBlank { "{}" })
                if (o.optInt("v") == CLASS_VERSION) {
                    val m = o.optJSONObject("m") ?: JSONObject()
                    m.keys().forEach { k -> kwCache[k] = m.optString(k).split(";").filter { it.isNotBlank() } }
                }
            }
            kwLoaded = true
        }
    }

    private fun saveKwCache() = synchronized(kwCache) {
        while (kwCache.size > CLASS_MAX) kwCache.remove(kwCache.keys.first())
        val m = JSONObject()
        kwCache.forEach { (k, kws) -> m.put(k, kws.joinToString(";")) }
        Prefs.putJson("history_class", JSONObject().put("v", CLASS_VERSION).put("m", m).toString())
    }

    /** Plot keywords of each title, normalized (one small request per title the TV has not seen). */
    internal suspend fun keywordsOf(titles: List<Title>): Map<String, List<String>> = coroutineScope {
        loadKwCache()
        val out = HashMap<String, List<String>>()
        val missing = ArrayList<Title>()
        synchronized(kwCache) {
            titles.distinctBy(::keyOf).forEach { t -> kwCache[keyOf(t)]?.let { out[keyOf(t)] = it } ?: missing.add(t) }
        }
        val fetched = missing.map { t -> async { t to runCatching { net.withPermit { Tmdb.keywordNames(t.type, t.id) } }.getOrNull() } }.awaitAll()
        var changed = false
        for ((t, names) in fetched) {
            val kws = names.orEmpty().map(HistoryEras::norm).filter { it.isNotEmpty() }.take(30)
            out[keyOf(t)] = kws
            if (names != null) { synchronized(kwCache) { kwCache[keyOf(t)] = kws }; changed = true } // offline: try again next time
        }
        if (changed) saveKwCache()
        out
    }

    /** Titles as the section rules see them: catalog metadata only. */
    private suspend fun catalog(titles: List<Title>): List<CatalogTitle> {
        val kws = keywordsOf(titles)
        val kb = kenBurnsKeys()
        return titles.distinctBy(::keyOf).map { t ->
            CatalogTitle(
                keyOf(t), t.name, t.year.toIntOrNull() ?: 0, t.genreIds.toSet(), kws[keyOf(t)].orEmpty(),
                t.language, t.votes, t.rating, american(t), keyOf(t) in kb,
            )
        }
    }

    // ---------------------------------------------------------------- filters

    /** "Not for me" titles, and titles whose keywords or name hit a Settings > Taste "Topics to skip" word. */
    internal fun blocked(profile: String): (Title, List<String>) -> Boolean {
        val hidden = Library.hidden(profile).map(::keyOf).toSet()
        val words = Recommender.choicesJson(profile).optJSONArray("avoidWords")
        val avoid = if (words == null) emptyList() else (0 until words.length()).map { HistoryEras.norm(words.optString(it)) }.filter { it.isNotEmpty() }
        return { t, kws ->
            keyOf(t) in hidden || avoid.any { w ->
                val name = " ${HistoryEras.norm(t.name)} "
                name.contains(" $w ") || kws.any { k -> " $k ".contains(" $w ") }
            }
        }
    }

    internal fun american(t: Title) = if (t.originCountries.isNotEmpty()) "US" in t.originCountries else t.language == "en"

    // ---------------------------------------------------------------- shelves

    internal const val NOT_KIDS_MOVIE = "16,10751,14,878,27"
    internal const val NOT_KIDS_TV = "16,10762,10765"

    /** TMDB discover pages, in parallel; a failed page is left out. (type, page, params) */
    internal suspend fun discoverAll(queries: List<Triple<String, Int, Map<String, String>>>): List<Title> = coroutineScope {
        queries.map { (type, page, p) ->
            async { runCatching { net.withPermit { Tmdb.discover(type, p + ("sort_by" to "vote_count.desc"), page) } }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    /**
     * One section: candidates from TMDB, picked and ordered by [pick] (HistoryEras), then the profile's
     * "Not for me" and "Topics to skip" taken out.
     */
    private suspend fun build(profile: String, candidates: List<Title>, pick: (List<CatalogTitle>) -> List<String>): List<Title> {
        val byKey = candidates.associateBy(::keyOf)
        val cat = catalog(candidates)
        val kws = cat.associate { it.key to it.keywords }
        val block = blocked(profile)
        return pick(cat).mapNotNull(byKey::get).filterNot { block(it, kws[keyOf(it)].orEmpty()) }.take(60)
    }

    /** Titles that depict [era]: the marquee titles first, then the best keyword matches. */
    suspend fun eraShelf(era: Era, profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "era:${era.id}") { eraShelfInner(era, profile) }.take(max)

    private suspend fun eraShelfInner(era: Era, profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().filter { era in it.first.eras }.map { it.second } }
        val ids = keywordIds(HistoryEras.DISCOVER_TERMS.getValue(era)).joinToString("|")
        val found = if (ids.isEmpty()) emptyList() else discoverAll(
            listOf("movie" to 1, "movie" to 2, "tv" to 1).map { (type, page) ->
                Triple(
                    type, page,
                    mapOf(
                        "with_keywords" to ids, "vote_count.gte" to (if (type == "movie") "40" else "10"),
                        "without_genres" to (if (type == "movie") NOT_KIDS_MOVIE else NOT_KIDS_TV),
                    ),
                )
            },
        )
        build(profile, marquee.await() + found) { HistoryEras.eraShelf(era, it) }
    }

    /** Documentaries: history documentaries (movies and series) and every Ken Burns film. */
    suspend fun documentaries(profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "docs") { documentariesInner(profile) }.take(max)

    private suspend fun documentariesInner(profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().map { it.second } }
        val kb = async { kenBurnsOrNone() }
        val ids = keywordIds(HistoryEras.HISTORY_DISCOVER_TERMS + HistoryEras.DISCOVER_TERMS.values.flatten()).joinToString("|")
        val found = discoverAll(
            listOf(
                Triple("movie", 1, mapOf("with_genres" to "99,36", "vote_count.gte" to "20")),
                Triple("movie", 2, mapOf("with_genres" to "99,36", "vote_count.gte" to "20")),
            ) + (if (ids.isEmpty()) emptyList() else listOf(
                Triple("movie", 1, mapOf("with_genres" to "99", "with_keywords" to ids, "vote_count.gte" to "20")),
                Triple("tv", 1, mapOf("with_genres" to "99", "with_keywords" to ids, "vote_count.gte" to "5")),
                Triple("tv", 2, mapOf("with_genres" to "99", "with_keywords" to ids, "vote_count.gte" to "5")),
            )),
        )
        build(profile, marquee.await() + kb.await() + found, HistoryEras::documentaries)
    }

    /** History: history-genre programming, movies and series. */
    suspend fun historyPrograms(profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "history") { historyProgramsInner(profile) }.take(max)

    private suspend fun historyProgramsInner(profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().map { it.second } }
        val ids = keywordIds(HistoryEras.HISTORY_DISCOVER_TERMS + HistoryEras.DISCOVER_TERMS.values.flatten()).joinToString("|")
        val found = discoverAll(
            listOf(
                Triple("movie", 1, mapOf("with_genres" to "36", "without_genres" to NOT_KIDS_MOVIE, "vote_count.gte" to "150")),
                Triple("movie", 2, mapOf("with_genres" to "36", "without_genres" to NOT_KIDS_MOVIE, "vote_count.gte" to "150")),
            ) + (if (ids.isEmpty()) emptyList() else listOf(
                Triple("tv", 1, mapOf("with_genres" to "99|10768|18", "with_keywords" to ids, "without_genres" to NOT_KIDS_TV, "vote_count.gte" to "10")),
                Triple("tv", 2, mapOf("with_genres" to "99|10768|18", "with_keywords" to ids, "without_genres" to NOT_KIDS_TV, "vote_count.gte" to "10")),
            )),
        )
        build(profile, marquee.await() + found, HistoryEras::historyPrograms)
    }

    /** History Movies: fiction films set in a historical period. Young Washington and its kin lead. */
    suspend fun historyMovies(profile: String = Prefs.activeProfile, max: Int = 30): List<Title> =
        cached(profile, "movies") { historyMoviesInner(profile) }.take(max)

    private suspend fun historyMoviesInner(profile: String): List<Title> = coroutineScope {
        val marquee = async { marqueeTitles().filter { it.first.type == "movie" }.map { it.second } }
        val terms = HistoryEras.DISCOVER_TERMS.filterKeys { it != Era.WEST }.values.flatten() +
            HistoryEras.PRESIDENTIAL_TERMS + HistoryEras.PERIOD_DISCOVER_TERMS
        val ids = keywordIds(terms).joinToString("|")
        val noDocs = "99,$NOT_KIDS_MOVIE"
        val found = discoverAll(
            listOf(Triple("movie", 1, mapOf("with_genres" to "36", "without_genres" to noDocs, "vote_count.gte" to "300"))) +
                (if (ids.isEmpty()) emptyList() else listOf(1, 2).map { page ->
                    Triple("movie", page, mapOf("with_genres" to "36|10752", "with_keywords" to ids, "without_genres" to noDocs, "vote_count.gte" to "150"))
                }),
        )
        build(profile, marquee.await() + found, HistoryEras::historyMovies)
    }

    /** Ken Burns' whole filmography from his TMDB credits, in shelf order (see HistoryEras.kenBurnsOrder). */
    private suspend fun kenBurnsAll(): List<Title> = kenBurnsLock.withLock { cached("", "kenburns") { kenBurnsLoad() } }
    private val kenBurnsLock = Mutex() // every shelf asks for his films at once on the first visit; look them up once

    private suspend fun kenBurnsLoad(): List<Title> {
        val id = kenBurnsId() ?: throw IllegalStateException("Couldn't find Ken Burns on TMDB.")
        val p = net.withPermit { Tmdb.person(id) }
        val byKey = p.crew.associate { keyOf(it.first) to it.first }
        val credits = p.crew.map { (t, jobs) -> Credit(keyOf(t), t.name, t.releaseDate, t.genreIds.toSet(), jobs.split(", "), t.votes) }
        return HistoryEras.kenBurnsOrder(credits, today()).mapNotNull(byKey::get)
    }

    /** Ken Burns' films, or none when TMDB can't be reached (the other shelves still load). */
    private suspend fun kenBurnsOrNone(): List<Title> = try {
        kenBurnsAll()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Keys of Ken Burns' films (they are history whatever their genres say). */
    private suspend fun kenBurnsKeys(): Set<String> = kenBurnsOrNone().map(::keyOf).toSet()

    /** Every documentary Ken Burns made, less this profile's "Not for me" titles. */
    suspend fun kenBurns(profile: String = Prefs.activeProfile): List<Title> {
        val hidden = Library.hidden(profile).map(::keyOf).toSet()
        return kenBurnsAll().filter { keyOf(it) !in hidden }
    }

    private fun today(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    /** Home's History row: Ken Burns. The same for every profile; it never depends on what was watched. */
    suspend fun homeRow(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? =
        runCatching { kenBurns(profile) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "History · Ken Burns" to it.take(20) }

    /** A shelf by id, for its See all grid. */
    suspend fun shelf(id: String, profile: String = Prefs.activeProfile): List<Title> = when {
        id == HubSection.DOCUMENTARIES.id -> documentaries(profile, 60)
        id == HubSection.HISTORY.id -> historyPrograms(profile, 60)
        id == HubSection.MOVIES.id -> historyMovies(profile, 60)
        id == HubSection.KEN_BURNS.id -> kenBurns(profile)
        id.startsWith("era:") -> Era.fromId(id.substringAfter(':'))?.let { eraShelf(it, profile, 60) } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Automated QA only: marks one title watched ("movie|The Irishman|2019"), so the emulator run can prove
     * the hub does not change with what a profile watched.
     */
    suspend fun qaWatch(spec: String) {
        val p = spec.split("|")
        if (p.size < 2) { android.util.Log.w("HistoryHub", "qa_watched: expected type|name|year, got $spec"); return }
        val t = Tmdb.findTitle(p[0], p[1], p.getOrNull(2)?.toIntOrNull(), exact = true)
        android.util.Log.i("HistoryHub", "qa_watched $spec -> ${t?.type}:${t?.id}")
        if (t != null) Library.markWatched(PlayMeta(t.type, t.id, t.name, t.poster, t.backdrop))
    }
}
