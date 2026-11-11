package com.mcd.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One playable option from an addon, shown in the source picker. */
data class StreamSource(
    val addon: String,
    val name: String,
    val title: String,
    val url: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val quality: String,
    val sizeText: String,
    val sizeGb: Double,
    val seeders: Int?,
    val cached: Boolean,
    /** HTTP headers the addon says the stream needs (behaviorHints.proxyHeaders.request). */
    val headers: Map<String, String> = emptyMap(),
    /** Season and episode this source was requested for (TV only), so Real-Debrid can pick the right file in a pack. */
    val season: Int? = null,
    val episode: Int? = null,
    /** Subtitle files the addon sends with this stream (Stremio `subtitles`). The player adds them to the video. */
    val subtitles: List<ExternalSub> = emptyList(),
)

/** A browsable addon catalog of live channels or events (Stremio types tv, channel, events). */
data class LiveCatalog(val addonUrl: String, val addonName: String, val type: String, val id: String, val name: String) {
    val label get() = if (name.isBlank()) addonName else "$name"
}

/** One channel or event in a live catalog. */
data class LiveItem(val addonUrl: String, val type: String, val id: String, val name: String, val poster: String?, val info: String)

data class AddonInfo(val manifestUrl: String, val name: String, val description: String, val streams: Boolean)

/**
 * Client for the open Stremio addon protocol (manifest.json + /stream/{type}/{id}.json).
 * Jarvis ships with no addons: you add manifest URLs yourself in Settings.
 */
object Addons {
    /** Accepts stremio:// links and bare addon URLs. Returns the manifest.json URL. */
    fun normalize(input: String): String {
        var u = input.trim()
        if (u.startsWith("stremio://")) u = "https://" + u.removePrefix("stremio://")
        if (!u.startsWith("http")) u = "https://$u"
        if (!u.endsWith("manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    private fun base(manifestUrl: String) = manifestUrl.removeSuffix("/manifest.json")

    /** Per-request timeout for addon calls, so one slow addon cannot hold up the list. */
    private const val ADDON_TIMEOUT_MS = 10_000

    /** Addon display names by manifest URL, filled on install and on every manifest fetch. */
    private val names = ConcurrentHashMap<String, String>()

    /** The addon's name, from cache when we have it; fetches the manifest once otherwise. */
    suspend fun nameOf(manifestUrl: String, fallback: String = "Addon"): String =
        names[manifestUrl] ?: runCatching { manifest(manifestUrl).name }.getOrDefault(fallback)

    suspend fun manifest(url: String): AddonInfo {
        val o = JSONObject(Http.get(url, timeoutMs = ADDON_TIMEOUT_MS))
        val res = o.optJSONArray("resources")
        var streams = false
        if (res != null) for (i in 0 until res.length()) {
            val r = res.get(i)
            val name = if (r is JSONObject) r.optString("name") else r.toString()
            if (name == "stream") streams = true
        }
        val name = o.s("name") ?: "Addon"
        names[url] = name
        return AddonInfo(url, name, o.s("description") ?: "", streams)
    }

    private val liveTypes = setOf("tv", "channel", "events")

    /** Live channel and event catalogs from every installed addon (catalogs that need a search term are skipped). */
    suspend fun liveCatalogs(): List<LiveCatalog> = coroutineScope {
        Prefs.addonUrls.map { m ->
            async {
                withTimeoutOrNull(15_000) {
                    runCatching {
                        val o = JSONObject(Http.get(m, timeoutMs = ADDON_TIMEOUT_MS))
                        val addonName = o.s("name") ?: "Addon"
                        names[m] = addonName
                        val cats = o.optJSONArray("catalogs") ?: return@runCatching emptyList<LiveCatalog>()
                        (0 until cats.length()).mapNotNull { i ->
                            val c = cats.getJSONObject(i)
                            val type = c.optString("type")
                            if (type !in liveTypes) return@mapNotNull null
                            val extras = c.optJSONArray("extra")
                            val needsInput = extras != null && (0 until extras.length()).any { j ->
                                val e = extras.optJSONObject(j)
                                e != null && e.optBoolean("isRequired") && e.optString("name") != "skip"
                            } || (c.optJSONArray("extraRequired")?.length() ?: 0) > 0
                            if (needsInput) null else LiveCatalog(m, addonName, type, c.optString("id"), c.optString("name"))
                        }
                    }.getOrDefault(emptyList())
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }

    private fun enc(id: String) = java.net.URLEncoder.encode(id, "UTF-8").replace("+", "%20")

    /** One page of a live catalog. */
    suspend fun catalog(c: LiveCatalog, skip: Int = 0): List<LiveItem> {
        val path = if (skip > 0) "${c.id}/skip=$skip" else c.id
        val o = JSONObject(Http.get("${base(c.addonUrl)}/catalog/${c.type}/$path.json", timeoutMs = ADDON_TIMEOUT_MS))
        val metas = o.optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).map { i ->
            val m = metas.getJSONObject(i)
            val genres = m.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(" / ") { g.optString(it) } } ?: ""
            LiveItem(c.addonUrl, m.s("type") ?: c.type, m.optString("id"), m.s("name") ?: "Channel",
                m.s("logo") ?: m.s("poster"), genres.ifBlank { m.s("description")?.take(80) ?: "" })
        }
    }

    /** Streams for one live channel or event, from the addon that listed it. Torrent-only results are dropped. */
    suspend fun liveStreams(item: LiveItem): List<StreamSource> {
        val addonName = nameOf(item.addonUrl)
        val o = JSONObject(Http.get("${base(item.addonUrl)}/stream/${item.type}/${enc(item.id)}.json", timeoutMs = ADDON_TIMEOUT_MS))
        val arr = o.optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).map { parse(addonName, arr.getJSONObject(it)) }.filter { it.url != null }
    }

    /** Adds an addon after checking its manifest loads. */
    suspend fun install(input: String): AddonInfo {
        val url = normalize(input)
        val info = manifest(url)
        Prefs.updateAddonUrls { it + url }
        return info
    }

    fun remove(url: String) {
        Prefs.updateAddonUrls { it - url }
        names.remove(url)
    }

    private val qualityRx = Regex("(2160p|4k|1080p|720p|480p)", RegexOption.IGNORE_CASE)
    private val sizeRx = Regex("([0-9]+(?:\\.[0-9]+)?)\\s?(GB|MB)", RegexOption.IGNORE_CASE)
    private val seedRx = Regex("👤\\s?([0-9]+)")

    /**
     * Asks every installed addon for streams. id is an IMDb id for movies,
     * or "tt123:season:episode" for TV. Slow addons are skipped after 20 s.
     */
    /** Names of the addons that did not answer (error or timeout) on the last [streams] call. */
    @Volatile var lastFailed: List<String> = emptyList()
        private set

    /**
     * [tmdbId] (optional) also asks your IPTV provider's on-demand library (IptvVod) for the same title.
     * Those sources are direct links that need no Real-Debrid; they sort first.
     */
    suspend fun streams(type: String, id: String, tmdbId: Int? = null): List<StreamSource> = coroutineScope {
        val stremioType = if (type == "tv") "series" else "movie"
        // TV ids look like "tt123:1:5" (season 1, episode 5).
        val parts = id.split(":")
        val season = if (type == "tv") parts.getOrNull(1)?.toIntOrNull() else null
        val episode = if (type == "tv") parts.getOrNull(2)?.toIntOrNull() else null
        val iptv = async { IptvVod.sources(type, tmdbId, season, episode) }
        // null: the addon did not answer (blocked, down or too slow). An empty list: it answered with nothing.
        val replies = Prefs.addonUrls.map { m ->
            async {
                m to withTimeoutOrNull(20_000) {
                    runCatching {
                        val addonName = nameOf(m)
                        val o = JSONObject(Http.get("${base(m)}/stream/$stremioType/$id.json", timeoutMs = ADDON_TIMEOUT_MS))
                        val arr = o.optJSONArray("streams")
                        if (arr == null) emptyList()
                        else (0 until arr.length()).map { parse(addonName, arr.getJSONObject(it)).copy(season = season, episode = episode) }
                    }.getOrNull()
                }
            }
        }.awaitAll()
        lastFailed = replies.filter { it.second == null }.map { nameOf(it.first, fallback = "An addon") }
        (iptv.await() + replies.flatMap { it.second.orEmpty() }).let { sort(it) }
    }

    private fun parse(addon: String, s: JSONObject): StreamSource {
        val name = s.s("name") ?: addon
        val title = s.s("title") ?: s.s("description") ?: ""
        val text = "$name $title"
        val q = qualityRx.find(text)?.value?.lowercase()?.let { if (it == "2160p" || it == "4k") "4K" else it } ?: "Unknown"
        val sizeMatch = sizeRx.find(title)
        val sizeGb = sizeMatch?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: 0.0
            if (it.groupValues[2].equals("MB", true)) n / 1024 else n
        } ?: 0.0
        val url = s.s("url")
        // Addons mark debrid-cached results with "+" or a lightning bolt in the name.
        val cached = name.contains("+]") || name.contains("RD+") || name.contains("⚡") ||
            (url != null && name.contains("RD", ignoreCase = false) && !name.contains("download", true))
        return StreamSource(
            addon = addon,
            name = name.replace("\n", " "),
            title = title,
            url = url,
            infoHash = s.s("infoHash"),
            fileIdx = if (s.has("fileIdx") && !s.isNull("fileIdx")) s.optInt("fileIdx") else null,
            quality = q,
            sizeText = sizeMatch?.value ?: "",
            sizeGb = sizeGb,
            seeders = seedRx.find(title)?.groupValues?.get(1)?.toIntOrNull(),
            cached = cached,
            headers = headersOf(s),
            subtitles = Captions.parseAddonSubs(s.optJSONArray("subtitles")),
        )
    }

    private fun headersOf(s: JSONObject): Map<String, String> {
        val h = s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request") ?: return emptyMap()
        return h.keys().asSequence().associateWith { h.optString(it) }
    }

    /**
     * Cached first, then quality tier (stars), resolution and size; duplicates removed (see StreamInfo).
     * Slow connection: 720p/1080p and smaller files first. Nothing is hidden here: the source list
     * and Resolver.best apply the CAM / size-limit filter (StreamInfo.arrange).
     */
    fun sort(list: List<StreamSource>): List<StreamSource> = StreamInfo.ranked(list).map { it.source }
}
