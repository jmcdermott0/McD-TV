package com.mcd.tv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Movies and episodes from your IPTV provider's on-demand library, offered on every source list next to
 * the addons. Uses the Xtream Codes app API of the playlist link saved in Settings (Prefs.m3uUrl); with no
 * such link this does nothing. Matching is by TMDB id (see XtreamVod).
 *
 * The provider's lists (tens of thousands of titles, 10+ MB each) are read as a stream into a small index,
 * saved in cacheDir and refreshed at most once a day. Every failure is silent: the source list just shows
 * the addons. Links hold the playlist's username and password: never log or show them.
 */
object IptvVod {
    private const val INDEX_MAX_AGE = 24 * 3600_000L
    private const val SERIES_MAX_AGE = 6 * 3600_000L
    /** After a failed download, wait this long before trying again. */
    private const val RETRY_AFTER = 30 * 60_000L

    private val USER_AGENTS = listOf(
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "VLC/3.0.20 LibVLC/3.0.20",
    )

    /** Downloads run here, so a source list that stops waiting (or closes) does not cancel them. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var dir: File? = null
    @Volatile private var index: XtreamVod.Index? = null
    @Volatile private var indexKey = ""
    @Volatile private var indexAt = 0L
    @Volatile private var failedAt = 0L
    @Volatile private var failedKey = ""
    private var loading: Deferred<XtreamVod.Index?>? = null
    /** The account [loading] belongs to: a caller for another account never waits on it (wrong account's links). */
    private var loadingKey = ""

    /** The user agent the provider accepted; IPTV sources play with it. */
    @Volatile private var userAgent: String? = null

    private val seriesCache = LinkedHashMap<String, Pair<Long, List<XtreamVod.Episode>>>()

    /** Called once from MainActivity: where the index file goes. */
    fun init(context: Context) {
        dir = context.applicationContext.cacheDir
    }

    /**
     * Sources for a movie ([season] null) or an episode, by TMDB id. Waits at most [waitMs] for the first
     * download of the provider's library (it keeps going in the background for next time).
     */
    suspend fun sources(type: String, tmdbId: Int?, season: Int?, episode: Int?, waitMs: Long = 25_000): List<StreamSource> {
        if (tmdbId == null || tmdbId <= 0) return emptyList()
        val acct = runCatching { XtreamVod.Account.of(Prefs.m3uUrl) }.getOrNull() ?: return emptyList()
        return try {
            withTimeoutOrNull(waitMs) {
                val idx = index(acct) ?: return@withTimeoutOrNull emptyList()
                if (type == "tv") {
                    if (season == null || episode == null) emptyList() else episodeSources(acct, idx, tmdbId, season, episode)
                } else {
                    XtreamVod.movieSources(acct, idx.movies[tmdbId].orEmpty(), userAgent)
                }
            } ?: emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun episodeSources(acct: XtreamVod.Account, idx: XtreamVod.Index, tmdbId: Int, season: Int, episode: Int): List<StreamSource> =
        coroutineScope {
            idx.series[tmdbId].orEmpty().take(4).map { show ->
                async {
                    val eps = runCatching { episodes(acct, show.id) }.getOrNull().orEmpty()
                    XtreamVod.findEpisode(eps, season, episode)?.let { XtreamVod.episodeSource(acct, it, userAgent) }
                }
            }.awaitAll().filterNotNull()
        }

    /** One series' episodes (get_series_info), kept 6 hours. */
    private suspend fun episodes(acct: XtreamVod.Account, seriesId: String): List<XtreamVod.Episode> {
        val key = "${acct.key}:$seriesId"
        synchronized(seriesCache) {
            seriesCache[key]?.let { (at, eps) -> if (System.currentTimeMillis() - at < SERIES_MAX_AGE) return eps }
        }
        val ua = userAgent ?: USER_AGENTS.first()
        val body = Http.get(acct.api("get_series_info") + "&series_id=" + Http.enc(seriesId), mapOf("User-Agent" to ua, "Accept" to "*/*"), timeoutMs = 20_000)
        val eps = XtreamVod.parseSeriesInfo(body)
        synchronized(seriesCache) {
            seriesCache[key] = System.currentTimeMillis() to eps
            while (seriesCache.size > 40) seriesCache.remove(seriesCache.keys.first())
        }
        return eps
    }

    /** The library index: from memory, else the cache file, else a download. Null when unavailable. */
    private suspend fun index(acct: XtreamVod.Account): XtreamVod.Index? {
        val key = acct.key
        val now = System.currentTimeMillis()
        index?.let { if (indexKey == key && now - indexAt < INDEX_MAX_AGE) return it }
        val job = synchronized(this) {
            loading?.takeIf { it.isActive && loadingKey == key } ?: scope.async { load(acct) }.also { loading = it; loadingKey = key }
        }
        return job.await()
    }

    private fun load(acct: XtreamVod.Account): XtreamVod.Index? {
        val key = acct.key
        val now = System.currentTimeMillis()
        index?.let { if (indexKey == key && now - indexAt < INDEX_MAX_AGE) return it }
        val file = dir?.let { File(it, "iptv_vod_index.tsv") }
        val saved = file?.takeIf { it.isFile }?.let { f -> runCatching { f.bufferedReader().useLines { XtreamVod.decode(it) } }.getOrNull() }
            ?.takeIf { it.accountKey == key }
        if (saved != null && now - saved.savedAt < INDEX_MAX_AGE) return keep(key, saved.index, saved.savedAt)
        // A download failed recently: use the old copy (or nothing) until it is time to try again.
        if (failedKey == key && now - failedAt < RETRY_AFTER) return saved?.let { keep(key, it.index, now - INDEX_MAX_AGE + RETRY_AFTER) }
        val fresh = download(acct)
        if (fresh == null) {
            failedKey = key; failedAt = now
            return saved?.let { keep(key, it.index, now - INDEX_MAX_AGE + RETRY_AFTER) }
        }
        if (file != null) runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.bufferedWriter().use { XtreamVod.encode(fresh, key, now, it) }
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        return keep(key, fresh, now)
    }

    private fun keep(key: String, idx: XtreamVod.Index, at: Long): XtreamVod.Index {
        index = idx; indexKey = key; indexAt = at
        return idx
    }

    /** Both lists, read as streams. Tries the user agents IPTV apps use, like Live TV does. */
    private fun download(acct: XtreamVod.Account): XtreamVod.Index? {
        for (ua in (listOfNotNull(userAgent, M3u.userAgent) + USER_AGENTS).distinct()) {
            val movies = readList(acct.api("get_vod_streams"), "stream_id", ua) ?: continue
            val series = readList(acct.api("get_series"), "series_id", ua) ?: HashMap()
            if (movies.isEmpty() && series.isEmpty()) continue
            userAgent = ua
            return XtreamVod.Index(movies, series)
        }
        return null
    }

    private fun readList(url: String, idKey: String, ua: String): HashMap<Int, MutableList<XtreamVod.Item>>? {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            if (c.responseCode !in 200..299) return null
            c.inputStream.reader(Charsets.UTF_8).use { XtreamVod.readList(it, idKey) }
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }
}
