package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/** A movie or TV show as shown on a card. type is "movie" or "tv". */
data class Title(
    val id: Int,
    val type: String,
    val name: String,
    val overview: String,
    val poster: String?,
    val backdrop: String?,
    val rating: Double,
    val year: String,
    val originCountries: List<String> = emptyList(),
    val language: String = "",
    /** "yyyy-MM-dd" (movies: release date, shows: first air date), or "" when unknown. */
    val releaseDate: String = "",
    /** TMDB genre ids (from lists' genre_ids or details' genres). Not saved with Library lists. */
    val genreIds: List<Int> = emptyList(),
    /** Number of TMDB votes behind [rating]. */
    val votes: Int = 0,
)

/** The parts of a title a list gives us, for a first pass of ranking (no keywords or people yet). */
fun Title.basicFeatures(): Features =
    Features("$type:$id", genreIds.toSet(), emptySet(), emptySet(), year.toIntOrNull() ?: 0, rating, votes, language)

/** Where titles may come from. Set in Settings or the web page. */
enum class OriginFilter(val key: String, val label: String) {
    ALL("all", "All countries"),
    US("us", "Made in the US only"),
    NO_ASIA("no_asia", "Hide Asian-made titles"),
}

/** East, South, Southeast and Central Asia (country codes and original languages). */
private val ASIAN_COUNTRIES = setOf(
    "JP", "KR", "KP", "CN", "TW", "HK", "MO", "MN", "IN", "PK", "BD", "LK", "NP", "BT", "MV", "AF",
    "TH", "VN", "LA", "KH", "MM", "MY", "SG", "ID", "PH", "BN", "TL", "KZ", "UZ", "TM", "KG", "TJ",
)
private val ASIAN_LANGUAGES = setOf(
    "ja", "ko", "zh", "cn", "hi", "ta", "te", "ml", "kn", "mr", "bn", "pa", "gu", "ur", "or", "as",
    "th", "vi", "id", "ms", "tl", "km", "lo", "my", "mn", "ne", "si", "dz", "kk", "uz",
)

/** True if the title passes the user's origin setting. */
fun Title.allowedByOrigin(f: OriginFilter): Boolean = when (f) {
    OriginFilter.ALL -> true
    OriginFilter.US -> if (originCountries.isNotEmpty()) "US" in originCountries else language == "en"
    OriginFilter.NO_ASIA -> originCountries.none { it in ASIAN_COUNTRIES } && language !in ASIAN_LANGUAGES
}

data class Genre(val id: Int, val name: String)

val MOVIE_GENRES = listOf(
    Genre(28, "Action"), Genre(12, "Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
    Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(14, "Fantasy"), Genre(36, "History"),
    Genre(27, "Horror"), Genre(10402, "Music"), Genre(9648, "Mystery"), Genre(10749, "Romance"),
    Genre(878, "Sci-Fi"), Genre(53, "Thriller"), Genre(10752, "War"), Genre(37, "Western"),
)
val TV_GENRES = listOf(
    Genre(10759, "Action & Adventure"), Genre(16, "Animation"), Genre(35, "Comedy"), Genre(80, "Crime"),
    Genre(99, "Documentary"), Genre(18, "Drama"), Genre(10751, "Family"), Genre(10762, "Kids"), Genre(9648, "Mystery"),
    Genre(10764, "Reality"), Genre(10765, "Sci-Fi & Fantasy"), Genre(10768, "War & Politics"), Genre(37, "Western"),
)

data class CastMember(val name: String, val character: String, val photo: String?, val id: Int = 0)

/** A person's page: bio plus every movie and show they acted in or worked on. */
data class Person(
    val id: Int,
    val name: String,
    val photo: String?,
    val department: String,
    val birthday: String,
    val placeOfBirth: String,
    val bio: String,
    val acting: List<Title>,
    val crew: List<Pair<Title, String>>, // title + job (Director, Writer, ...)
)
/** A person in search results. [department] is TMDB's "known for" ("Acting", "Directing"). */
data class PersonHit(val id: Int, val name: String, val department: String, val photo: String?)

/** One search: matching titles (origin setting applied) and matching people. */
data class SearchHits(val titles: List<Title>, val people: List<PersonHit>)

data class SeasonInfo(val number: Int, val name: String, val episodeCount: Int)
data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val still: String?,
    val airDate: String,
)

data class Details(
    val title: Title,
    val tagline: String,
    val runtimeMin: Int,
    val genres: List<String>,
    val imdbId: String?,
    val cast: List<CastMember>,
    val similar: List<Title>,
    val seasons: List<SeasonInfo>,
    val nextEpisode: Episode?,
    /** US subscription services carrying this title (e.g. Netflix), from TMDB / JustWatch. */
    val providers: List<Service> = emptyList(),
    /** YouTube video key of the title's trailer (or teaser), if TMDB lists one. */
    val trailerKey: String? = null,
    /** Movie franchise (TMDB belongs_to_collection), if any. */
    val collectionId: Int? = null,
    val collectionName: String? = null,
)

/** A movie franchise: every film in it, oldest first. */
data class TitleCollection(val id: Int, val name: String, val parts: List<Title>, val poster: String? = null, val backdrop: String? = null)

/** Popular movie franchises for Browse > Collections (TMDB collection ids). */
val POPULAR_COLLECTIONS = listOf(
    10, // Star Wars
    1241, // Harry Potter
    119, // The Lord of the Rings
    86311, // The Avengers
    9485, // Fast & Furious
    645, // James Bond
    87359, // Mission: Impossible
    263, // The Dark Knight
    2344, // The Matrix
    328, // Jurassic Park
    1570, // Die Hard
    8091, // Alien
    528, // The Terminator
    230, // The Godfather
    2150, // Shrek
    10194, // Toy Story
    295, // Pirates of the Caribbean
    264, // Back to the Future
    84, // Indiana Jones
    404609, // John Wick
    1575, // Rocky
    31562, // The Bourne
    9888, // Home Alone
    86066, // Despicable Me
)

/** Original languages for Browse > By Language (TMDB with_original_language codes). */
val BROWSE_LANGUAGES = listOf(
    "en" to "English", "es" to "Spanish", "fr" to "French", "ko" to "Korean", "ja" to "Japanese", "hi" to "Hindi",
    "it" to "Italian", "de" to "German", "pt" to "Portuguese", "zh" to "Chinese", "sv" to "Swedish", "da" to "Danish",
    "no" to "Norwegian", "tr" to "Turkish", "th" to "Thai", "ta" to "Tamil",
)

/** Streaming services shown on the Services screen (TMDB watch-provider ids, US). */
data class Service(val id: Int, val name: String)

val SERVICES = listOf(
    Service(8, "Netflix"), Service(9, "Amazon Prime"), Service(337, "Disney+"), Service(15, "Hulu"),
    Service(350, "Apple TV+"), Service(386, "Peacock"), Service(1899, "Max"), Service(531, "Paramount+"),
    Service(283, "Crunchyroll"), Service(43, "Starz"),
)

/** JSON null-safe string (Android's optString returns the text "null" for JSON nulls). */
fun JSONObject.s(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }

/**
 * TMDB client: posters, descriptions, cast, seasons. Needs a free TMDB API key,
 * entered on the Control page (Settings > Phone & Computer Setup). Accepts a v3 key or a v4 read token.
 */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"

    fun img(path: String?, size: String = "w342"): String? = path?.let { IMG + size + it }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val key = Prefs.tmdbKey
        if (key.isBlank()) throw IllegalStateException("Add your TMDB API key: Settings > Addons and Live TV")
        val all = params.toMutableMap()
        val headers = mutableMapOf<String, String>()
        if (key.length > 40) headers["Authorization"] = "Bearer $key" else all["api_key"] = key
        all.putIfAbsent("language", "en-US")
        val url = "$BASE$path?" + Http.form(all)
        cacheGet(url)?.let { return JSONObject(it) }
        val body = Http.get(url, headers)
        val parsed = JSONObject(body) // only cache replies that parse
        cachePut(url, body)
        return parsed
    }

    // ---- Small in-memory LRU cache of GET replies (text, so callers always get a fresh JSONObject) ----
    private const val CACHE_MAX = 150
    private const val CACHE_TTL_MS = 10 * 60_000L
    internal class CacheEntry(val body: String, val at: Long)
    private val cache = object : LinkedHashMap<String, CacheEntry>(CACHE_MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > CACHE_MAX
    }

    private fun cacheGet(url: String): String? = synchronized(cache) {
        val e = cache[url] ?: return null
        if (System.currentTimeMillis() - e.at > CACHE_TTL_MS) {
            cache.remove(url)
            null
        } else e.body
    }

    private fun cachePut(url: String, body: String) = synchronized(cache) {
        cache[url] = CacheEntry(body, System.currentTimeMillis())
    }

    private fun parse(o: JSONObject, forcedType: String?): Title? {
        val type = forcedType ?: o.s("media_type") ?: return null
        if (type != "movie" && type != "tv") return null
        val date = o.s("release_date") ?: o.s("first_air_date") ?: ""
        return Title(
            id = o.optInt("id"),
            type = type,
            name = o.s("title") ?: o.s("name") ?: "Untitled",
            overview = o.s("overview") ?: "",
            poster = o.s("poster_path"),
            backdrop = o.s("backdrop_path"),
            rating = o.optDouble("vote_average", 0.0),
            year = date.take(4),
            originCountries = (o.optJSONArray("origin_country")
                ?: o.optJSONArray("production_countries"))?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    when (val v = a.get(i)) { is String -> v; is JSONObject -> v.s("iso_3166_1"); else -> null }
                }
            } ?: emptyList(),
            language = o.s("original_language") ?: "",
            releaseDate = date,
            genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } }
                ?: o.optJSONArray("genres")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("id") } }
                ?: emptyList(),
            votes = o.optInt("vote_count"),
        )
    }

    /** Every list in the app passes through here, so the origin setting applies everywhere. */
    private fun parseList(arr: JSONArray?, forcedType: String?): List<Title> {
        if (arr == null) return emptyList()
        val f = Prefs.origin
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), forcedType) }.filter { it.allowedByOrigin(f) }
    }

    /** Adds TMDB's own origin filter where it supports one (discover lists), so pages stay full. */
    private fun originParams(): Map<String, String> =
        if (Prefs.origin == OriginFilter.US) mapOf("with_origin_country" to "US") else emptyMap()

    /** Genre browsing, optionally limited to one year (null = all years). */
    suspend fun byGenre(type: String, genreId: Int, topRated: Boolean, page: Int = 1, year: Int? = null): List<Title> = discover(
        type,
        buildMap {
            put("with_genres", genreId.toString())
            if (year != null) put(if (type == "movie") "primary_release_year" else "first_air_date_year", year.toString())
            if (topRated) {
                put("sort_by", "vote_average.desc")
                // A single year has fewer votes per title, so the bar is lower.
                put("vote_count.gte", if (year != null) (if (type == "movie") "200" else "60") else (if (type == "movie") "1000" else "300"))
            }
        },
        page,
    )

    /**
     * Everything a person has done (TMDB combined credits), newest first.
     * Deliberately NOT filtered by the country setting: this is their entire catalogue.
     */
    suspend fun person(id: Int): Person {
        val o = get("/person/$id", mapOf("append_to_response" to "combined_credits"))
        val credits = o.optJSONObject("combined_credits")
        fun raw(arr: JSONArray?): List<Pair<Title, JSONObject>> =
            if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                val j = arr.getJSONObject(i)
                parse(j, null)?.let { it to j }
            }
        val acting = raw(credits?.optJSONArray("cast"))
            .filterNot { (t, j) -> t.type == "tv" && (j.optString("character").contains("Self", true) && j.optInt("episode_count") <= 1) }
            .map { it.first }
            .distinctBy { "${it.type}-${it.id}" }
            .sortedByDescending { it.year }
        val crew = raw(credits?.optJSONArray("crew"))
            .map { (t, j) -> t to (j.s("job") ?: "Crew") }
            .groupBy { "${it.first.type}-${it.first.id}" }
            .map { (_, v) -> v.first().first to v.map { it.second }.distinct().joinToString(", ") }
            .sortedByDescending { it.first.year }
        return Person(
            id = id,
            name = o.s("name") ?: "",
            photo = o.s("profile_path"),
            department = o.s("known_for_department") ?: "",
            birthday = o.s("birthday") ?: "",
            placeOfBirth = o.s("place_of_birth") ?: "",
            bio = o.s("biography") ?: "",
            acting = acting,
            crew = crew,
        )
    }

    /** "More like this" from TMDB (people who liked this also liked…). */
    suspend fun recommendations(type: String, id: Int, page: Int = 1): List<Title> =
        parseList(get("/$type/$id/recommendations", mapOf("page" to page.toString())).optJSONArray("results"), type)

    /** TMDB's "similar" list (genre and keyword overlap). */
    suspend fun similar(type: String, id: Int, page: Int = 1): List<Title> =
        parseList(get("/$type/$id/similar", mapOf("page" to page.toString())).optJSONArray("results"), type)

    /**
     * What a title is about, for taste matching: genres, plot keywords, directors / creators and the
     * top billed cast. One small request (keywords and credits only).
     */
    suspend fun features(type: String, id: Int): Features {
        val o = get("/$type/$id", mapOf("append_to_response" to "keywords,credits"))
        return featuresOf(o, type, id)
    }

    internal fun featuresOf(o: JSONObject, type: String, id: Int): Features {
        fun ids(a: JSONArray?, max: Int = Int.MAX_VALUE): List<Int> =
            a?.let { arr -> (0 until minOf(arr.length(), max)).mapNotNull { arr.optJSONObject(it)?.optInt("id")?.takeIf { v -> v > 0 } } } ?: emptyList()
        val kw = o.optJSONObject("keywords")?.let { it.optJSONArray("keywords") ?: it.optJSONArray("results") }
        val credits = o.optJSONObject("credits")
        val crew = credits?.optJSONArray("crew")
        val directors = crew?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.filter { it.optString("job") == "Director" }.map { it.optInt("id") }
        } ?: emptyList()
        val people = (ids(credits?.optJSONArray("cast"), 6) + directors + ids(o.optJSONArray("created_by"))).toSet()
        val date = o.s("release_date") ?: o.s("first_air_date") ?: ""
        return Features(
            key = "$type:$id",
            genres = ids(o.optJSONArray("genres")).toSet(),
            keywords = ids(kw, 40).toSet(),
            people = people,
            year = date.take(4).toIntOrNull() ?: 0,
            rating = o.optDouble("vote_average", 0.0),
            votes = o.optInt("vote_count"),
            language = o.s("original_language") ?: "",
        )
    }

    /**
     * One title by name (and first year), from any country. For a profile's starter favorites
     * (Settings > Taste). Prefers an exact name match (case and punctuation ignored), else TMDB's best
     * match; [exact] = exact matches only.
     */
    suspend fun findTitle(type: String, name: String, year: Int? = null, exact: Boolean = false): Title? {
        val params = buildMap {
            put("query", name)
            put("include_adult", "false")
            if (year != null) put(if (type == "movie") "year" else "first_air_date_year", year.toString())
        }
        val arr = get("/search/$type", params).optJSONArray("results") ?: return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { o -> parse(o, type) } }
        fun norm(x: String) = x.lowercase().filter { it.isLetterOrDigit() }
        val want = norm(name)
        return all.firstOrNull { norm(it.name) == want } ?: if (exact) null else all.firstOrNull()
    }

    /**
     * The TMDB id of a plot keyword ("stand-up comedy"), or null. Used for Settings > Taste topics.
     * [exact] = only a keyword with exactly this name (the History hub), else TMDB's best match.
     */
    suspend fun keywordId(name: String, exact: Boolean = false): Int? {
        val q = name.trim()
        if (q.isEmpty()) return null
        val arr = get("/search/keyword", mapOf("query" to q)).optJSONArray("results") ?: return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        val hit = all.firstOrNull { it.optString("name").equals(q, ignoreCase = true) } ?: if (exact) null else all.firstOrNull()
        return hit?.optInt("id")?.takeIf { it > 0 }
    }

    /** A title's plot keyword names ("american civil war", "1860s"), for the History hub's eras. */
    suspend fun keywordNames(type: String, id: Int): List<String> {
        val o = get("/$type/$id/keywords")
        val a = o.optJSONArray("keywords") ?: o.optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.s("name") }
    }

    /** A person's TMDB id by exact name (most popular first), or null. [department] prefers e.g. "Directing". */
    suspend fun findPerson(name: String, department: String? = null): Int? {
        val arr = get("/search/person", mapOf("query" to name, "include_adult" to "false")).optJSONArray("results") ?: return null
        val same = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("name").equals(name, ignoreCase = true) }
        val hit = same.firstOrNull { department != null && it.optString("known_for_department") == department } ?: same.firstOrNull()
        return hit?.optInt("id")?.takeIf { it > 0 }
    }

    private fun region(): Map<String, String> = if (Prefs.usOnly) mapOf("region" to "US") else emptyMap()

    private suspend fun list(path: String, type: String?, params: Map<String, String> = emptyMap()) =
        parseList(get(path, params).optJSONArray("results"), type)

    /** type: "all", "movie" or "tv". */
    suspend fun trending(type: String = "all", page: Int = 1) =
        list("/trending/$type/week", if (type == "all") null else type, mapOf("page" to page.toString()))
    suspend fun popular(type: String, page: Int = 1) = list("/$type/popular", type, region() + ("page" to page.toString()))
    /**
     * Top Rated, US edition: American titles only, ranked by vote average, with a
     * minimum vote count so a handful of votes can't top the chart. Movies are limited
     * to US-certified titles (G through R). Note: no public source splits ratings by
     * the voter's country or age, so this filters the titles, not the voters.
     */
    suspend fun topRated(type: String, page: Int = 1): List<Title> {
        val p = mutableMapOf(
            "sort_by" to "vote_average.desc",
            "vote_count.gte" to if (type == "movie") "2500" else "800",
            "with_origin_country" to "US",
        )
        if (type == "movie") {
            p["certification_country"] = "US"
            p["certification"] = "G|PG|PG-13|R"
        }
        return discover(type, p, page)
    }
    suspend fun nowPlaying(page: Int = 1) = list("/movie/now_playing", "movie", region() + ("page" to page.toString()))
    suspend fun search(q: String) = list("/search/multi", null, mapOf("query" to q, "include_adult" to "false"))

    /** TMDB TV genres for News, Reality and Talk. */
    private val GUEST_GENRES = setOf(10763, 10764, 10767)

    /** Titles and people for [q] in one request (the Search tab), so a name finds the person too. */
    suspend fun searchAll(q: String): SearchHits {
        val arr = get("/search/multi", mapOf("query" to q, "include_adult" to "false")).optJSONArray("results")
        return SearchHits(parseList(arr, null), parsePeople(arr))
    }

    /** People only, most popular first. */
    suspend fun searchPeople(q: String): List<PersonHit> =
        parsePeople(get("/search/person", mapOf("query" to q, "include_adult" to "false")).optJSONArray("results"))

    private fun parsePeople(arr: JSONArray?): List<PersonHit> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("adult", false)) return@mapNotNull null
            val media = o.s("media_type")
            if (media != null && media != "person") return@mapNotNull null
            val name = o.s("name") ?: return@mapNotNull null
            PersonHit(o.optInt("id"), name, o.s("known_for_department") ?: "", o.s("profile_path")).takeIf { it.id > 0 }
        }
    }

    /**
     * The person [name] means, or null: first among [hits] (a search already made), then a person search
     * for each spelling ("Daniel day Lewis", then "Daniel day-Lewis").
     */
    suspend fun personByName(name: String, hits: List<PersonHit> = emptyList()): PersonHit? {
        JarvisReplyParser.pickPerson(name, hits.map { it.name }).let { if (it >= 0) return hits[it] }
        if (!JarvisReplyParser.looksLikeName(name)) return null
        for (v in JarvisReplyParser.nameVariants(name)) {
            val people = searchPeople(v)
            JarvisReplyParser.pickPerson(name, people.map { it.name }).let { if (it >= 0) return people[it] }
        }
        return null
    }

    /**
     * A person's best-known titles, most voted first: directed titles for a director, roles for everyone else.
     * Talk, news and reality shows are left out (guest spots, not their work).
     */
    fun knownFor(p: Person, max: Int = 20): List<Title> {
        val directed = p.crew.filter { "Director" in it.second }.map { it.first }
        val main = if (p.department == "Directing" && directed.isNotEmpty()) directed + p.acting else p.acting + directed
        return main.filter { t -> t.genreIds.none { it in GUEST_GENRES } }
            .distinctBy { "${it.type}-${it.id}" }.sortedByDescending { it.votes }.take(max)
    }

    /**
     * Best TMDB match for one title of a known [type] ("movie"/"tv"), used by Ask Jarvis.
     * Prefers an exact [year] match, then the closest year, then TMDB's first result. The origin setting
     * is NOT applied (the user asked for this title by name); adult titles are always skipped.
     */
    suspend fun findTitle(type: String, name: String, year: Int?): Title? {
        if ((type != "movie" && type != "tv") || name.isBlank()) return null
        val arr = get("/search/$type", mapOf("query" to name, "include_adult" to "false")).optJSONArray("results") ?: return null
        val found = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("adult", false)) null else parse(o, type)
        }
        if (found.isEmpty()) return null
        if (year == null || year <= 0) return found.first()
        found.firstOrNull { it.year.toIntOrNull() == year }?.let { return it }
        // Closest year among the top few (TMDB ranks by relevance; don't reach for a far-down obscure match).
        return found.take(5).filter { it.year.toIntOrNull() != null }
            .minByOrNull { kotlin.math.abs(it.year.toInt() - year) } ?: found.first()
    }

    suspend fun discover(type: String, params: Map<String, String>, page: Int = 1): List<Title> {
        val p = params.toMutableMap()
        p["page"] = page.toString()
        p.putIfAbsent("sort_by", "popularity.desc")
        p["include_adult"] = "false" // adult titles are never shown anywhere in Jarvis
        originParams().forEach { (k, v) -> p.putIfAbsent(k, v) }
        if (Prefs.usOnly) p.putIfAbsent("watch_region", "US")
        return list("/discover/$type", type, p)
    }

    /**
     * A streaming service's current US catalog (TMDB, from JustWatch data, kept current daily).
     * newest = true: most recent releases first (only titles already out, with a few votes).
     */
    suspend fun byService(type: String, providerId: Int, page: Int = 1, newest: Boolean = false): List<Title> {
        val p = mutableMapOf("with_watch_providers" to providerId.toString(), "watch_region" to "US", "with_watch_monetization_types" to "flatrate")
        if (newest) {
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            if (type == "movie") { p["sort_by"] = "primary_release_date.desc"; p["primary_release_date.lte"] = today }
            else { p["sort_by"] = "first_air_date.desc"; p["first_air_date.lte"] = today }
            p["vote_count.gte"] = "5"
        }
        return discover(type, p, page)
    }

    suspend fun byYear(type: String, year: Int, page: Int = 1) = discover(
        type,
        if (type == "movie") mapOf("primary_release_year" to "$year") else mapOf("first_air_date_year" to "$year"),
        page,
    )

    /**
     * Titles in one original language (Browse > By Language), most popular first.
     * Deliberately NOT filtered by the country setting: picking a language is an explicit choice.
     */
    suspend fun byLanguage(type: String, lang: String, page: Int = 1): List<Title> {
        val p = mapOf(
            "with_original_language" to lang,
            "sort_by" to "popularity.desc",
            "vote_count.gte" to "20",
            "include_adult" to "false",
            "page" to page.toString(),
        )
        val arr = get("/discover/$type", p).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { parse(arr.getJSONObject(it), type) }
    }

    /**
     * Kids profile rows: US-rated G or PG movies in one genre (default 10751 = Family; 16 = Animation).
     * topRated = best rated first instead of most popular.
     */
    suspend fun kidsMovies(genreId: Int = 10751, topRated: Boolean = false, page: Int = 1): List<Title> = discover(
        "movie",
        buildMap {
            put("with_genres", genreId.toString())
            put("certification_country", "US")
            put("certification.lte", "PG")
            if (topRated) {
                put("sort_by", "vote_average.desc")
                put("vote_count.gte", "500")
            } else {
                put("vote_count.gte", "100")
            }
        },
        page,
    )

    /** Kids profile: TV shows in TMDB's Kids genre (10762). */
    suspend fun kidsShows(page: Int = 1): List<Title> =
        discover("tv", mapOf("with_genres" to "10762", "vote_count.gte" to "20"), page)

    suspend fun details(type: String, id: Int): Details {
        val o = get("/$type/$id", mapOf("append_to_response" to "credits,similar,external_ids,watch/providers,videos"))
        val t = parse(o, type) ?: throw IllegalStateException("Not found")
        val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList()
        val castArr = o.optJSONObject("credits")?.optJSONArray("cast")
        val cast = castArr?.let { a ->
            (0 until minOf(a.length(), 20)).map {
                val c = a.getJSONObject(it)
                CastMember(c.s("name") ?: "", c.s("character") ?: "", c.s("profile_path"), c.optInt("id"))
            }
        } ?: emptyList()
        val seasons = o.optJSONArray("seasons")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optInt("season_number") > 0 }
                .map { SeasonInfo(it.optInt("season_number"), it.s("name") ?: "Season", it.optInt("episode_count")) }
        } ?: emptyList()
        val runtime = if (type == "movie") o.optInt("runtime") else
            o.optJSONArray("episode_run_time")?.let { if (it.length() > 0) it.optInt(0) else 0 } ?: 0
        val imdb = o.s("imdb_id") ?: o.optJSONObject("external_ids")?.s("imdb_id")
        val next = o.optJSONObject("next_episode_to_air")?.let { parseEpisode(it) }
        return Details(
            title = t,
            tagline = o.s("tagline") ?: "",
            runtimeMin = runtime,
            genres = genres,
            imdbId = imdb,
            cast = cast,
            similar = parseList(o.optJSONObject("similar")?.optJSONArray("results"), type),
            seasons = seasons,
            nextEpisode = next,
            providers = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")
                ?.optJSONArray("flatrate")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map { Service(it.optInt("provider_id"), it.s("provider_name") ?: "") }
                } ?: emptyList(),
            trailerKey = pickTrailer(o.optJSONObject("videos")?.optJSONArray("results")),
            collectionId = o.optJSONObject("belongs_to_collection")?.optInt("id")?.takeIf { it > 0 },
            collectionName = o.optJSONObject("belongs_to_collection")?.s("name"),
        )
    }

    /** First YouTube trailer (official preferred), else the first YouTube teaser. */
    private fun pickTrailer(arr: JSONArray?): String? {
        if (arr == null) return null
        val vids = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            .filter { it.s("site").equals("YouTube", ignoreCase = true) && it.s("key") != null }
        val trailers = vids.filter { it.s("type") == "Trailer" }
        val pick = trailers.firstOrNull { it.optBoolean("official", false) }
            ?: trailers.firstOrNull()
            ?: vids.firstOrNull { it.s("type") == "Teaser" }
        return pick?.s("key")
    }

    /**
     * A movie franchise, sorted by release date (unreleased / undated last).
     * Deliberately NOT filtered by the country setting: every entry of the series shows.
     */
    suspend fun collection(id: Int): TitleCollection {
        val o = get("/collection/$id")
        val arr = o.optJSONArray("parts")
        val parts = if (arr == null) emptyList() else (0 until arr.length())
            .mapNotNull { i -> arr.optJSONObject(i)?.let { j -> parse(j, "movie")?.let { t -> t to (j.s("release_date") ?: "") } } }
            .sortedWith(compareBy<Pair<Title, String>>({ it.second.isBlank() }, { it.second }))
            .map { it.first }
        return TitleCollection(id, o.s("name") ?: "Collection", parts, o.s("poster_path"), o.s("backdrop_path"))
    }

    private fun parseEpisode(e: JSONObject) = Episode(
        season = e.optInt("season_number"),
        number = e.optInt("episode_number"),
        name = e.s("name") ?: "Episode",
        overview = e.s("overview") ?: "",
        still = e.s("still_path"),
        airDate = e.s("air_date") ?: "",
    )

    suspend fun season(tvId: Int, season: Int): List<Episode> {
        val o = get("/tv/$tvId/season/$season")
        val a = o.optJSONArray("episodes") ?: return emptyList()
        return (0 until a.length()).map { parseEpisode(a.getJSONObject(it)) }
    }
}
