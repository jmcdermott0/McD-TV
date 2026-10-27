package com.mcd.tv.data

import com.mcd.tv.data.sync.ProfileSync
import org.json.JSONArray
import org.json.JSONObject

/** What the player needs to record history and resume. */
data class PlayMeta(
    val type: String,
    val tmdbId: Int,
    val name: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int = 0,
    val episode: Int = 0,
) {
    /** One history entry per movie, or per show (latest episode wins). */
    val historyKey: String get() = "$type:$tmdbId"
    val label: String get() = if (type == "tv" && season > 0) "$name  S${season}E$episode" else name
}

data class HistoryEntry(val meta: PlayMeta, val positionMs: Long, val durationMs: Long, val updatedAt: Long) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = progress > 0.92f
}

/**
 * Favorites, Watchlist, watch history (Continue Watching) and the Background Noise show list.
 * Stored on the device as JSON, separately for each profile (see Prefs.profileKey).
 *
 * Most functions take an optional [profile] id ("p1", "p2", "p3"). null (the default) means the active
 * profile. The web app passes its own profile, so it reads and writes that profile's lists without
 * changing which profile the TV is using.
 *
 * Every change is also reported to [ProfileSync] (inside [lock]), which shares it with the other TVs of the
 * house. ProfileSync writes changes from other TVs back into these blobs while holding [lock] too.
 */
object Library {
    /** Guards read-modify-write of the stored lists (TV UI, player and relay run on different threads). */
    private val lock = Any()

    /** For ProfileSync: runs [block] under the same lock as every Library write. */
    internal fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /** The profile a call with [profile] reads and writes: [profile] when it is a known id, else the active one. */
    private fun resolve(profile: String?): String = if (profile != null && profile in Prefs.PROFILE_IDS) profile else Prefs.activeProfile

    /** The storage key for [key] in [profile], or in the active profile when [profile] is null or unknown. */
    private fun keyFor(key: String, profile: String?): String =
        if (profile != null && profile in Prefs.PROFILE_IDS) "$key@$profile" else Prefs.profileKey(key)

    /** [p] if it is a known profile id, else null (the active profile). */
    fun validProfile(p: String?): String? = p?.takeIf { it in Prefs.PROFILE_IDS }

    private fun Title.toJson() = JSONObject()
        .put("id", id).put("type", type).put("name", name).put("overview", overview)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("rating", rating).put("year", year)

    private fun JSONObject.toTitle() = Title(
        optInt("id"), optString("type"), optString("name"), optString("overview"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null },
        optDouble("rating", 0.0), optString("year"),
    )

    private fun loadTitles(key: String, profile: String? = null): List<Title> = runCatching {
        val a = JSONArray(Prefs.json(keyFor(key, profile)).ifBlank { "[]" })
        (0 until a.length()).map { a.getJSONObject(it).toTitle() }
    }.getOrDefault(emptyList())

    private fun saveTitles(key: String, list: List<Title>, profile: String? = null) =
        Prefs.putJson(keyFor(key, profile), JSONArray().apply { list.forEach { put(it.toJson()) } }.toString())

    private fun toggle(key: String, t: Title, profile: String? = null): Boolean = synchronized(lock) {
        val pid = resolve(profile)
        val list = loadTitles(key, pid)
        val has = list.any { it.id == t.id && it.type == t.type }
        saveTitles(key, if (has) list.filterNot { it.id == t.id && it.type == t.type } else listOf(t) + list, pid)
        ProfileSync.onTitleList(pid, key, "${t.type}:${t.id}", if (has) null else t.toJson().toString())
        LibraryEvents.changed()
        !has
    }

    /** Adds or removes [t] so that its membership equals [on] (no-op when it already does). */
    private fun setIn(key: String, t: Title, on: Boolean, profile: String?) {
        synchronized(lock) {
            if (contains(key, t, profile) != on) toggle(key, t, profile)
        }
    }

    private fun contains(key: String, t: Title, profile: String? = null) = loadTitles(key, profile).any { it.id == t.id && it.type == t.type }

    fun favorites(profile: String? = null) = loadTitles("lib_favorites", profile)
    fun isFavorite(t: Title) = contains("lib_favorites", t)
    fun toggleFavorite(t: Title) = toggle("lib_favorites", t)

    fun watchlist(profile: String? = null) = loadTitles("lib_watchlist", profile)
    fun inWatchlist(t: Title, profile: String? = null) = contains("lib_watchlist", t, profile)
    fun toggleWatchlist(t: Title, profile: String? = null) = toggle("lib_watchlist", t, profile)
    /** Puts [t] in (or takes it out of) [profile]'s watchlist, atomically. */
    fun setWatchlist(t: Title, on: Boolean, profile: String? = null) = setIn("lib_watchlist", t, on, profile)

    fun noiseShows() = loadTitles("lib_noise")
    fun inNoise(t: Title) = contains("lib_noise", t)
    fun toggleNoise(t: Title) = toggle("lib_noise", t)

    /** "Not for me": hidden from home rows and suggestions, and counted as a dislike for taste. */
    fun hidden(profile: String? = null) = loadTitles("lib_hidden", profile)
    fun hide(t: Title) { if (!contains("lib_hidden", t)) toggle("lib_hidden", t) }

    /** "I like this": a strong like for taste (Picked for You, More Like This, Live TV For You). */
    fun likes(profile: String? = null) = loadTitles("lib_likes", profile)
    fun isLiked(t: Title) = contains("lib_likes", t)
    fun toggleLike(t: Title) = toggle("lib_likes", t)

    // ---- History ----
    private fun PlayMeta.toJson() = JSONObject().put("type", type).put("tmdbId", tmdbId).put("name", name)
        .put("poster", poster ?: "").put("backdrop", backdrop ?: "").put("season", season).put("episode", episode)

    private fun JSONObject.toMeta() = PlayMeta(
        optString("type"), optInt("tmdbId"), optString("name"),
        optString("poster").ifBlank { null }, optString("backdrop").ifBlank { null },
        optInt("season"), optInt("episode"),
    )

    fun history(profile: String? = null): List<HistoryEntry> = runCatching {
        val a = JSONArray(Prefs.json(keyFor("lib_history", profile)).ifBlank { "[]" })
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            HistoryEntry(o.getJSONObject("meta").toMeta(), o.optLong("pos"), o.optLong("dur"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    @Volatile private var watchedCache: Pair<String, Set<Int>>? = null

    /**
     * True if this movie was watched to the end (history progress >= 0.9). Cheap enough for every
     * poster card: the parsed set is reused until the stored history changes. Shows always return false
     * (show history only tracks the latest episode).
     */
    fun isWatched(type: String, id: Int): Boolean {
        if (type != "movie") return false
        val raw = Prefs.json(Prefs.profileKey("lib_history"))
        val cached = watchedCache
        val set = if (cached != null && cached.first == raw) cached.second else {
            history().filter { it.meta.type == "movie" && it.progress >= 0.9f }.map { it.meta.tmdbId }.toSet()
                .also { watchedCache = raw to it }
        }
        return id in set
    }

    /** In progress, plus shows whose latest episode was finished (the next one is up). See [ContinueRules]. */
    fun continueWatching(profile: String? = null): List<HistoryEntry> {
        val now = System.currentTimeMillis()
        return history(profile).filter {
            ContinueRules.keep(it.meta.type, it.meta.season, it.meta.episode, it.positionMs, it.progress, it.updatedAt, now)
        }
    }

    fun resumePosition(meta: PlayMeta): Long = resumeEntry(meta)?.positionMs ?: 0L

    /** The saved spot for this exact title or episode (from any device), unless it was finished. */
    fun resumeEntry(meta: PlayMeta): HistoryEntry? =
        history().firstOrNull { it.meta.historyKey == meta.historyKey && it.meta.season == meta.season && it.meta.episode == meta.episode }
            ?.takeIf { !it.finished }

    private fun HistoryEntry.toJson() = JSONObject().put("meta", meta.toJson()).put("pos", positionMs).put("dur", durationMs).put("at", updatedAt)

    /**
     * Saves a resume position. Also goes to the other TVs (ProfileSync) and to the cloud for phones and TVs
     * that are off (ResumeCloud): every 2 minutes while playing, at once when [urgent] (player stopped).
     */
    fun record(meta: PlayMeta, positionMs: Long, durationMs: Long, profile: String? = null, urgent: Boolean = false) {
        val (pid, entry) = synchronized(lock) {
            val pid = resolve(profile)
            if (meta.type == "tv" && meta.season > 0 && durationMs > 0) recordEpisode(meta, positionMs.toFloat() / durationMs, pid)
            val rest = history(pid).filterNot { it.meta.historyKey == meta.historyKey }
            val entry = HistoryEntry(meta, positionMs, durationMs, System.currentTimeMillis())
            val list = listOf(entry) + rest
            Prefs.putJson(
                keyFor("lib_history", pid),
                JSONArray().apply { list.take(200).forEach { put(it.toJson()) } }.toString(),
            )
            ProfileSync.onHistory(pid, meta.historyKey, entry.toJson().toString())
            pid to entry
        }
        ResumeCloud.onLocal(pid, entry, urgent)
    }

    /**
     * A newer position from another device (ResumeCloud), saved with its own time. Shared with the other
     * TVs like a local save, but not sent back to the cloud.
     */
    fun applyCloud(profile: String, meta: PlayMeta, positionMs: Long, durationMs: Long, at: Long): Unit = synchronized(lock) {
        val pid = resolve(profile)
        if (meta.type == "tv" && meta.season > 0 && durationMs > 0) recordEpisode(meta, positionMs.toFloat() / durationMs, pid)
        val rest = history(pid).filterNot { it.meta.historyKey == meta.historyKey }
        val entry = HistoryEntry(meta, positionMs, durationMs, at)
        val list = (listOf(entry) + rest).sortedByDescending { it.updatedAt }
        Prefs.putJson(
            keyFor("lib_history", pid),
            JSONArray().apply { list.take(200).forEach { put(it.toJson()) } }.toString(),
        )
        ProfileSync.onHistory(pid, meta.historyKey, entry.toJson().toString())
    }

    fun markWatched(meta: PlayMeta) = record(meta, 1, 1)

    // ---- Per-episode progress (history keeps only the latest episode per show) ----
    private const val EPISODES_KEY = "lib_episodes"
    private const val EPISODES_MAX = 3000

    private fun loadEpisodes(profile: String? = null): JSONObject =
        runCatching { JSONObject(Prefs.json(keyFor(EPISODES_KEY, profile)).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    private fun recordEpisode(meta: PlayMeta, progress: Float, profile: String? = null) {
        val o = loadEpisodes(profile)
        val k = "${meta.tmdbId}:${meta.season}:${meta.episode}"
        val value = progress.coerceIn(0f, 1f).toDouble()
        o.remove(k) // re-insert so the newest entries stay at the end
        o.put(k, value)
        if (o.length() > EPISODES_MAX) {
            val drop = mutableListOf<String>()
            val keys = o.keys()
            while (keys.hasNext() && o.length() - drop.size > EPISODES_MAX) drop.add(keys.next())
            drop.forEach { k2 -> o.remove(k2) }
        }
        Prefs.putJson(keyFor(EPISODES_KEY, profile), o.toString())
        ProfileSync.onEpisode(resolve(profile), k, value)
    }

    /** Watched fraction (0..1) of one episode, or null if it was never played. */
    fun progressFor(type: String, id: Int, season: Int, episode: Int): Float? {
        if (type != "tv") {
            return history().firstOrNull { it.meta.historyKey == "$type:$id" }?.progress
        }
        val o = loadEpisodes()
        val k = "$id:$season:$episode"
        if (o.has(k)) return o.optDouble(k, 0.0).toFloat()
        // Older installs: only the latest episode per show is in history.
        return history().firstOrNull { it.meta.historyKey == "tv:$id" && it.meta.season == season && it.meta.episode == episode }?.progress
    }

    /** Watched fraction for every played episode of one season, keyed by episode number. */
    fun seasonProgress(tvId: Int, season: Int): Map<Int, Float> {
        val out = mutableMapOf<Int, Float>()
        history().firstOrNull { it.meta.historyKey == "tv:$tvId" && it.meta.season == season }
            ?.let { out[it.meta.episode] = it.progress }
        val o = loadEpisodes()
        val prefix = "$tvId:$season:"
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith(prefix)) k.removePrefix(prefix).toIntOrNull()?.let { e -> out[e] = o.optDouble(k, 0.0).toFloat() }
        }
        return out
    }
}
