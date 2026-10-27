package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gathers what Taste.kt needs and returns ready-to-show titles:
 *  - More Like This (Detail): a big pool (TMDB recommendations, similar, same themes, same genres)
 *    ranked by story keywords, genres, people, era and quality, then by the profile's taste.
 *  - Picked for You (Home): recommendations from the titles the profile liked most, plus top titles
 *    in its genres, ranked by taste.
 *  - Because You Watched (Home): More Like This for the latest title the profile finished.
 *  - The profile's taste for Live TV For You.
 *
 * Signals per profile: finished titles (strong), half watched (some), abandoned early (a little against),
 * favorites, "I like this", watchlist, and "Not for me" (strongly against), plus Settings > Taste.
 */
object Recommender {
    private const val FEATURE_CACHE_MAX = 500
    private const val TASTE_TTL_MS = 15 * 60_000L
    private val net = Semaphore(6) // parallel TMDB requests

    // ---------------------------------------------------------------- features (cached on the TV)

    private val featureCache = object : LinkedHashMap<String, Features>(FEATURE_CACHE_MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Features>?) = size > FEATURE_CACHE_MAX
    }
    @Volatile private var featuresLoaded = false
    private var featuresDirty = 0

    private fun loadFeatureCache() {
        if (featuresLoaded) return
        synchronized(featureCache) {
            if (featuresLoaded) return
            runCatching {
                val a = JSONArray(Prefs.json("taste_features").ifBlank { "[]" })
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    val f = Features(
                        o.optString("k"), ints(o.optJSONArray("g")), ints(o.optJSONArray("w")), ints(o.optJSONArray("p")),
                        o.optInt("y"), o.optDouble("r", 0.0), o.optInt("v"), o.optString("l"),
                    )
                    if (f.key.isNotBlank()) featureCache[f.key] = f
                }
            }
            featuresLoaded = true
        }
    }

    private fun saveFeatureCache() = synchronized(featureCache) {
        val a = JSONArray()
        featureCache.values.forEach { f ->
            a.put(
                JSONObject().put("k", f.key).put("g", JSONArray(f.genres.toList())).put("w", JSONArray(f.keywords.toList()))
                    .put("p", JSONArray(f.people.toList())).put("y", f.year).put("r", f.rating).put("v", f.votes).put("l", f.language),
            )
        }
        Prefs.putJson("taste_features", a.toString())
    }

    private fun ints(a: JSONArray?): Set<Int> = a?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.toSet() } ?: emptySet()

    /** Genres, keywords and people of one title (TMDB, cached). Null when TMDB can't be reached. */
    suspend fun features(type: String, id: Int): Features? {
        loadFeatureCache()
        val key = "$type:$id"
        synchronized(featureCache) { featureCache[key] }?.let { return it }
        val f = try {
            net.withPermit { Tmdb.features(type, id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val save = synchronized(featureCache) {
            featureCache[key] = f
            featuresDirty++
            featuresDirty >= 10
        }
        if (save) { synchronized(featureCache) { featuresDirty = 0 }; saveFeatureCache() }
        return f
    }

    private suspend fun featuresAll(keys: List<Pair<String, Int>>): Map<String, Features> = coroutineScope {
        keys.distinct().map { (t, id) -> async { features(t, id) } }.awaitAll().filterNotNull().associateBy { it.key }
    }.also { if (it.isNotEmpty()) saveFeatureCache() }

    // ---------------------------------------------------------------- taste per profile

    private class CachedTaste(val taste: TasteProfile, val at: Long, val signals: List<Signal>, val starters: Set<String> = emptySet())
    private val tastes = HashMap<String, CachedTaste>()
    private val tasteLock = Mutex()

    /** Forget cached taste (after Like, Not for me, or a Taste change). */
    fun invalidate(profile: String? = null) { synchronized(tastes) { if (profile == null) tastes.clear() else tastes.remove(profile) } }

    /** The choices in Settings > Taste for [profile]. */
    fun choicesJson(profile: String): JSONObject =
        runCatching { JSONObject(Prefs.tasteJson(profile).ifBlank { DEFAULTS[profile] ?: "{}" }) }.getOrDefault(JSONObject())

    /**
     * Starting point for a profile that never opened Settings > Taste. Each profile has its own: Dad starts on war, crime,
     * comedy, history, stand-up and poker; Mom on her own list; Kids on cartoons and family films. Saving the Taste screen replaces it.
     */
    private val DEFAULTS = mapOf(
        "p1" to """{"likeGenres":[10752,80,35,36],"avoidGenres":[],"likeWords":["stand-up comedy","poker"],"avoidWords":[]}""",
        // Mom: rom-coms, fantasy, murder documentaries, reality competitions (from her own list).
        "p2" to """{"likeGenres":[10749,35,14,80,10764,99],"avoidGenres":[],"likeWords":["true crime","murder","serial killer","romantic comedy","vampire","fashion"],"avoidWords":[]}""",
        // Kids: cartoons and family films (the kids profile only ever gets family and animation anyway).
        "p3" to """{"likeGenres":[16,10751],"avoidGenres":[],"likeWords":[],"avoidWords":[]}""",
    )

    // ---------------------------------------------------------------- starter favorites

    /** A title someone named as a favorite, found on TMDB by its exact name (and first year). */
    private class Starter(val type: String, val name: String, val year: Int? = null)

    /**
     * Favorites a person listed, counted as strong likes from day one, next to what the profile watches.
     * They seed Picked for You a few at a time (a different few each day) and are never suggested back.
     */
    private val STARTERS: Map<String, List<Starter>> = mapOf(
        "p2" to listOf(
            Starter("tv", "Project Runway", 2004),
            Starter("tv", "Taskmaster", 2015),
            Starter("tv", "Survivor", 2000),
            Starter("tv", "Criminal Minds", 2005),
            Starter("movie", "Accepted", 2006),
            Starter("movie", "He's Just Not That Into You", 2009),
            Starter("movie", "The Love Hypothesis"),
            Starter("movie", "The Lord of the Rings: The Fellowship of the Ring", 2001),
            Starter("movie", "The Lord of the Rings: The Return of the King", 2003),
            Starter("movie", "The Hobbit: An Unexpected Journey", 2012),
            Starter("movie", "Harry Potter and the Philosopher's Stone", 2001),
            Starter("movie", "Harry Potter and the Deathly Hallows: Part 2", 2011),
            Starter("tv", "Merlin", 2008),
            Starter("tv", "Desperate Housewives", 2004),
            Starter("tv", "Game of Thrones", 2011),
            Starter("tv", "A Knight of the Seven Kingdoms", 2026),
            Starter("movie", "Saving Private Ryan", 1998),
            Starter("tv", "Girls", 2012),
            Starter("tv", "The Sex Lives of College Girls", 2021),
            Starter("tv", "My Mad Fat Diary", 2013),
            Starter("tv", "The Vampire Diaries", 2009),
            Starter("tv", "True Blood", 2008),
        ),
    )

    /** TMDB (type, id) of the profile's starter favorites. Found once, then cached on the TV. */
    private suspend fun starterKeys(profile: String): List<Pair<String, Int>> {
        val list = STARTERS[profile] ?: return emptyList()
        val cache = runCatching { JSONObject(Prefs.json("taste_starters").ifBlank { "{}" }) }.getOrDefault(JSONObject())
        var changed = false
        val out = ArrayList<Pair<String, Int>>()
        for (s in list) {
            val k = "${s.type}|${s.name}|${s.year ?: ""}"
            val id = if (cache.has(k)) cache.optInt(k) else {
                val res = runCatching {
                    net.withPermit { Tmdb.findTitle(s.type, s.name, s.year, exact = true) ?: s.year?.let { Tmdb.findTitle(s.type, s.name, null, exact = true) } }
                }
                if (res.isFailure) continue // offline: try again next time
                (res.getOrNull()?.id ?: 0).also { cache.put(k, it); changed = true }
            }
            if (id > 0) out.add(s.type to id)
        }
        if (changed) Prefs.putJson("taste_starters", cache.toString())
        return out
    }


    fun saveChoices(profile: String, likeGenres: Set<Int>, avoidGenres: Set<Int>, likeWords: List<String>, avoidWords: List<String>) {
        val o = JSONObject()
            .put("likeGenres", JSONArray(likeGenres.toList()))
            .put("avoidGenres", JSONArray(avoidGenres.toList()))
            .put("likeWords", JSONArray(likeWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
            .put("avoidWords", JSONArray(avoidWords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()))
        Prefs.setTasteJson(profile, o.toString())
        invalidate(profile)
    }

    private fun strs(a: JSONArray?): List<String> = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()

    /** Topic words -> TMDB keyword ids (cached for good). */
    private suspend fun keywordIds(words: List<String>): Set<Int> {
        if (words.isEmpty()) return emptySet()
        val cache = runCatching { JSONObject(Prefs.json("taste_kwids").ifBlank { "{}" }) }.getOrDefault(JSONObject())
        val out = HashSet<Int>()
        var changed = false
        for (w in words) {
            val k = w.lowercase()
            if (cache.has(k)) { cache.optInt(k).takeIf { it > 0 }?.let(out::add); continue }
            val id = runCatching { net.withPermit { Tmdb.keywordId(w) } }.getOrNull()
            if (id != null) { cache.put(k, id); out.add(id); changed = true }
        }
        if (changed) Prefs.putJson("taste_kwids", cache.toString())
        return out
    }

    private suspend fun choices(profile: String): TasteChoices {
        val o = choicesJson(profile)
        val likeWords = strs(o.optJSONArray("likeWords"))
        val avoidWords = strs(o.optJSONArray("avoidWords"))
        return TasteChoices(
            likeGenres = ints(o.optJSONArray("likeGenres")).map(Taste::canonicalGenre).toSet(),
            avoidGenres = ints(o.optJSONArray("avoidGenres")).flatMap { listOf(it, Taste.canonicalGenre(it)) }.toSet(),
            likeKeywords = keywordIds(likeWords),
            avoidKeywords = keywordIds(avoidWords),
            likeWords = likeWords,
            avoidWords = avoidWords,
        )
    }

    /** The profile's taste, learned from its signals (cached 15 minutes). */
    suspend fun taste(profile: String = Prefs.activeProfile): TasteProfile = tasteWithSignals(profile).taste

    private suspend fun tasteWithSignals(profile: String): CachedTaste = tasteLock.withLock {
        val now = System.currentTimeMillis()
        synchronized(tastes) { tastes[profile] }?.let { if (now - it.at < TASTE_TTL_MS) return@withLock it }
        // (type, id) -> (weight, when)
        val raw = LinkedHashMap<Pair<String, Int>, Pair<Double, Long>>()
        fun add(type: String, id: Int, w: Double, at: Long) {
            val k = type to id
            val prev = raw[k]
            // Strongest signal wins (a finished film that was also a favorite counts as the favorite).
            if (prev == null || kotlin.math.abs(w) > kotlin.math.abs(prev.first)) raw[k] = w to at
        }
        for (h in Library.history(profile).take(80)) {
            val m = h.meta
            val p = h.progress
            val w = when {
                h.finished -> 1.0
                p >= 0.5 -> 0.7
                m.type == "tv" && p >= 0.2 -> 0.6 // a show you're into (history keeps the latest episode)
                p >= 0.15 -> 0.35
                now - h.updatedAt > 2 * 86_400_000L -> -0.35 // started and left it
                else -> 0.0
            }
            if (w != 0.0) add(m.type, m.tmdbId, w, h.updatedAt)
        }
        Library.favorites(profile).forEach { add(it.type, it.id, 1.2, now) }
        Library.likes(profile).forEach { add(it.type, it.id, 1.5, now) }
        Library.watchlist(profile).take(30).forEach { add(it.type, it.id, 0.35, now) }
        Library.hidden(profile).take(60).forEach { add(it.type, it.id, -1.3, now) }
        // Favorites the person listed (strong likes that never fade; "Not for me" still wins).
        val starters = starterKeys(profile)
        starters.forEach { (t, id) -> add(t, id, 1.0, now) }
        // Strongest first; features for at most 60 titles.
        val top = raw.entries.sortedByDescending { kotlin.math.abs(it.value.first) }.take(60)
        val feats = featuresAll(top.map { it.key })
        val signals = top.mapNotNull { (k, v) -> feats["${k.first}:${k.second}"]?.let { Signal(it, v.first, v.second) } }
        val built = CachedTaste(Taste.build(signals, choices(profile), now), now, signals, starters.map { "${it.first}:${it.second}" }.toSet())
        synchronized(tastes) { tastes[profile] = built }
        built
    }

    // ---------------------------------------------------------------- More Like This

    private fun keyOf(t: Title) = "${t.type}:${t.id}"

    private fun excluded(profile: String): Set<String> = Library.hidden(profile).map(::keyOf).toSet()

    private fun watchedKeys(profile: String): Set<String> =
        Library.history(profile).filter { it.finished || it.progress >= 0.6 }.map { "${it.meta.type}:${it.meta.tmdbId}" }.toSet()

    private fun kidsOk(t: Title): Boolean = t.genreIds.any { it == Taste.FAMILY || it == Taste.ANIMATION || it == Taste.KIDS }

    /**
     * Titles most like [type]/[id] for the watching profile. [fallback] (TMDB's similar list from the
     * detail reply) is used when TMDB can't be reached.
     */
    suspend fun moreLikeThis(type: String, id: Int, fallback: List<Title> = emptyList(), profile: String = Prefs.activeProfile, max: Int = 20): List<Title> = try {
        moreLikeThisInner(type, id, fallback, profile, max)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val ex = excluded(profile)
        fallback.filter { keyOf(it) !in ex }
    }

    private suspend fun moreLikeThisInner(type: String, id: Int, fallback: List<Title>, profile: String, max: Int): List<Title> = coroutineScope {
        val seedJob = async { features(type, id) }
        val tasteJob = async { runCatching { taste(profile) }.getOrDefault(TasteProfile.EMPTY) }
        val recs1 = async { runCatching { net.withPermit { Tmdb.recommendations(type, id, 1) } }.getOrDefault(emptyList()) }
        val recs2 = async { runCatching { net.withPermit { Tmdb.recommendations(type, id, 2) } }.getOrDefault(emptyList()) }
        val sim = async { if (fallback.isNotEmpty()) fallback else runCatching { net.withPermit { Tmdb.similar(type, id, 1) } }.getOrDefault(emptyList()) }
        val seed = seedJob.await() ?: return@coroutineScope fallback
        // Same themes: titles sharing the seed's rarest plot keywords; same shelf: its genres, best rated.
        val themes = async {
            val kws = seed.keywords.take(6)
            if (kws.isEmpty()) emptyList() else runCatching {
                net.withPermit {
                    Tmdb.discover(type, mapOf("with_keywords" to kws.joinToString("|"), "sort_by" to "vote_count.desc", "vote_count.gte" to "150"))
                }
            }.getOrDefault(emptyList())
        }
        val shelf = async {
            val gs = seed.genres.filter { it != Taste.DRAMA }.take(2).ifEmpty { seed.genres.take(1) }
            if (gs.isEmpty()) emptyList() else runCatching {
                net.withPermit {
                    Tmdb.discover(type, mapOf("with_genres" to gs.joinToString(","), "sort_by" to "vote_average.desc", "vote_count.gte" to "1500"))
                }
            }.getOrDefault(emptyList())
        }
        val recs = recs1.await() + recs2.await()
        val pool = LinkedHashMap<String, Title>()
        val rank = HashMap<String, Int>()
        recs.forEachIndexed { i, t -> pool.putIfAbsent(keyOf(t), t); rank.putIfAbsent(keyOf(t), i) }
        (sim.await() + themes.await() + shelf.await()).forEach { pool.putIfAbsent(keyOf(it), it) }
        val kids = profile == Prefs.KIDS_PROFILE
        val ex = excluded(profile) + "$type:$id"
        val candidatesAll = pool.values.filter { keyOf(it) !in ex && (!kids || kidsOk(it)) }
        val taste = tasteJob.await()
        // First pass on what the lists give (genres, votes, year), then full features for the best 40.
        val prelim = Taste.rankSimilar(
            seed, candidatesAll.map { Taste.Candidate(it.basicFeatures(), rank[keyOf(it)] ?: -1) }, taste, ex, max = 40,
        )
        val full = featuresAll(prelim.map { k -> k.substringBefore(':') to k.substringAfter(':').toInt() })
        val cands = prelim.map { k ->
            val t = pool.getValue(k)
            Taste.Candidate(full[k] ?: t.basicFeatures(), rank[k] ?: -1)
        }
        val ranked = Taste.rankSimilar(seed, cands, taste, ex, watched = watchedKeys(profile), max = max)
        ranked.mapNotNull { pool[it] }.ifEmpty { fallback.filter { keyOf(it) !in ex } }
    }

    // ---------------------------------------------------------------- Picked for You

    /**
     * Home's "Picked for <name>": a new row each day. A wide pool from the profile's strongest likes and liked genres is
     * ranked by taste, then [DailyPicks] draws the day's row from it (seeded by date and profile, yesterday's row left out).
     * The row is kept for the day (pref `picks_daily@<profile>`, this TV only), so a relaunch shows the same titles.
     * Titles on My List, watched, or marked "Not for me" since then drop out when the row is read.
     */
    suspend fun forYou(profile: String = Prefs.activeProfile, max: Int = 24): List<Title> = try {
        val today = qaDay.takeIf { it > 0 } ?: DailyPicks.dayOf(System.currentTimeMillis())
        val cached = readPicks(profile)
        if (cached != null && cached.day == today && cached.titles.isNotEmpty()) {
            val ex = forYouExcluded(profile)
            cached.titles.filter { keyOf(it) !in ex }.also { qaLog(today, profile, "kept", it) }
        } else {
            val ranked = forYouInner(profile, max, today)
            val ex = forYouExcluded(profile)
            val byKey = ranked.associateBy(::keyOf)
            // Yesterday's row (or the last one this TV showed) stays out today when the pool allows.
            val shown = cached?.titles?.map(::keyOf)?.toSet() ?: emptySet()
            val picks = DailyPicks.choose(ranked.map(::keyOf), today, profile, ex, shown, max).mapNotNull { byKey[it] }
            if (picks.isNotEmpty()) savePicks(profile, today, picks)
            picks.also { qaLog(today, profile, "new, pool ${ranked.size}, left out ${shown.size}", it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /** Automated QA only ("--ei qa_day 20261028"): the day Picked for <name> uses instead of today. 0 = today. */
    @Volatile var qaDay = 0

    /** QA runs compare these lines between two mocked days (logcat tag DailyPicks). */
    private fun qaLog(day: Int, profile: String, how: String, picks: List<Title>) {
        if (!Prefs.qaMode) return
        android.util.Log.i("DailyPicks", "day=$day profile=$profile $how keys=${picks.joinToString(",") { keyOf(it) }}")
    }

    /** Never picked: "Not for me", already on My List, or watched (15% or more). */
    private fun forYouExcluded(profile: String): Set<String> =
        excluded(profile) + Library.watchlist(profile).map(::keyOf) +
            Library.history(profile).filter { it.progress >= 0.15 }.map { "${it.meta.type}:${it.meta.tmdbId}" }

    private class DayPicks(val day: Int, val titles: List<Title>)

    private fun readPicks(profile: String): DayPicks? {
        val raw = Prefs.json("picks_daily@$profile")
        if (raw.isBlank()) return null
        return runCatching { parsePicks(JSONObject(raw)) }.getOrNull()
    }

    private fun parsePicks(o: JSONObject): DayPicks {
        val a = o.optJSONArray("titles") ?: JSONArray()
        val titles = (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            Title(
                t.optInt("id"), t.optString("type"), t.optString("name"), t.optString("overview"),
                t.optString("poster").ifBlank { null }, t.optString("backdrop").ifBlank { null },
                t.optDouble("rating", 0.0), t.optString("year"),
            ).takeIf { it.id > 0 && it.type.isNotBlank() }
        }
        return DayPicks(o.optInt("day"), titles)
    }

    private fun savePicks(profile: String, day: Int, titles: List<Title>) {
        val a = JSONArray()
        titles.forEach { t ->
            a.put(
                JSONObject().put("id", t.id).put("type", t.type).put("name", t.name).put("overview", t.overview.take(400))
                    .put("poster", t.poster ?: "").put("backdrop", t.backdrop ?: "").put("rating", t.rating).put("year", t.year),
            )
        }
        Prefs.putJson("picks_daily@$profile", JSONObject().put("day", day).put("titles", a).toString())
    }

    /** The ranked pool (best first, up to [max] x 4) the day's row is drawn from. */
    private suspend fun forYouInner(profile: String, max: Int, today: Int): List<Title> = coroutineScope {
        val ct = tasteWithSignals(profile)
        val taste = ct.taste
        val now = System.currentTimeMillis()
        val kids = profile == Prefs.KIDS_PROFILE
        val poolSize = max * 4
        // Seeds: 4 of the 10 strongest recent likes from watching, the rest from the starter favorites.
        // A different handful each day (drawn by date and profile), so the pool moves too.
        val liked = ct.signals.filter { it.weight > 0.5 }.sortedByDescending { it.weight * Taste.decay(it.at, now) }
        val ownTop = liked.filter { it.f.key !in ct.starters }.take(10).map { it.f.key }
        val own = DailyPicks.choose(ownTop, today, "$profile:seeds", emptySet(), emptySet(), 4)
        val fromStarters = DailyPicks.choose(
            liked.filter { it.f.key in ct.starters }.map { it.f.key }, today, "$profile:starters", emptySet(), emptySet(), 6 - own.size,
        )
        val seeds = (own + fromStarters)
            .map { it.substringBefore(':') to it.substringAfter(':').toInt() }
        // Genres to explore: every chosen like (Dad: war, crime, comedy and history), then the top learned ones.
        val learned = taste.genres.entries.filter { it.value > 0.15 }.sortedByDescending { it.value }.map { it.key }
        val genres = (taste.choices.likeGenres.toList() + learned).distinct().filter { it !in taste.choices.avoidGenres }
            .take(taste.choices.likeGenres.size.coerceIn(3, 5))
        val fromSeeds = seeds.map { (t, id) -> async { runCatching { net.withPermit { Tmdb.recommendations(t, id, 1) } }.getOrDefault(emptyList()) } }
        val fromGenres = genres.flatMap { g ->
            listOf("movie", "tv").map { type ->
                async {
                    val gid = if (type == "tv") tvGenre(g) else g
                    if (gid == null) emptyList() else runCatching {
                        net.withPermit {
                            Tmdb.discover(type, mapOf("with_genres" to gid.toString(), "sort_by" to "vote_average.desc", "vote_count.gte" to (if (type == "movie") "1500" else "400")))
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
        // Brand-new profile with no choices: start from what's popular and well made.
        val fallback: kotlinx.coroutines.Deferred<List<Title>>? =
            if (seeds.isEmpty() && genres.isEmpty()) async { runCatching { Tmdb.trending("all") }.getOrDefault(emptyList<Title>()) } else null
        val pool = LinkedHashMap<String, Title>()
        val cands = ArrayList<Taste.Candidate>()
        (fromSeeds.awaitAll().flatten() + fromGenres.awaitAll().flatten() + (fallback?.await() ?: emptyList())).forEach { t ->
            if (kids && !kidsOk(t)) return@forEach
            pool.putIfAbsent(keyOf(t), t)
            cands.add(Taste.Candidate(t.basicFeatures()))
        }
        val ex = forYouExcluded(profile) + ct.starters + seeds.map { "${it.first}:${it.second}" }
        // Full features (avoided topics, liked people) for the best of a first pass.
        val prelim = Taste.rankForYou(cands, taste, ex, poolSize)
        val full = featuresAll(prelim.map { k -> k.substringBefore(':') to k.substringAfter(':').toInt() })
        val counts = cands.groupingBy { it.f.key }.eachCount()
        val second = prelim.flatMap { k ->
            val f = full[k] ?: pool.getValue(k).basicFeatures()
            List(counts[k] ?: 1) { Taste.Candidate(f) } // keep "suggested by several seeds"
        }
        Taste.rankForYou(second, taste, ex, poolSize).mapNotNull { pool[it] }
    }

    /** A movie genre's TV equivalent (TV uses combined genres), or null when TV has none. */
    private fun tvGenre(movieGenre: Int): Int? = when (movieGenre) {
        Taste.ACTION, 12 -> Taste.ACTION_ADVENTURE
        Taste.WAR, Taste.HISTORY -> Taste.WAR_POLITICS
        878, 14 -> 10765
        Taste.ROMANCE, Taste.HORROR, Taste.THRILLER, 10402, 10770 -> null
        else -> movieGenre
    }

    // ---------------------------------------------------------------- Because You Watched

    /** The latest title the profile finished (last 60 days) and what's most like it. */
    suspend fun becauseYouWatched(profile: String = Prefs.activeProfile): Pair<String, List<Title>>? {
        val now = System.currentTimeMillis()
        val last = Library.history(profile)
            .filter { (it.finished || it.progress >= 0.7) && now - it.updatedAt < 60 * 86_400_000L }
            .maxByOrNull { it.updatedAt } ?: return null
        val list = moreLikeThis(last.meta.type, last.meta.tmdbId, emptyList(), profile, 20)
        return if (list.size >= 4) last.meta.name to list else null
    }
}
