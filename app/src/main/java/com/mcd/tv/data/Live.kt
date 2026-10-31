package com.mcd.tv.data

import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

// ======================= Live TV: M3U playlists you supply =======================

/** tvgId / tvgName come from the playlist's tvg-id and tvg-name attributes and link the channel to the program guide. */
data class Channel(
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
)

object M3u {
    private val attr = Regex("([a-zA-Z-]+)=\"([^\"]*)\"")

    /**
     * The channel title of an #EXTINF line: everything after the first comma outside a quoted attribute, so
     * group-title="Sports, Live" and titles with commas ("Packers @ Bears, 1:00 PM") stay whole.
     * "" when the line has no title comma (the caller then falls back to tvg-name).
     */
    private fun titleOf(line: String): String {
        var quoted = false
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted else if (c == ',' && !quoted) return line.substring(i + 1)
        }
        // Unbalanced quotes: fall back to the last comma.
        return if (quoted) line.substringAfterLast(",", "") else ""
    }

    /** Parses a standard #EXTM3U playlist. */
    fun parse(text: String): List<Channel> = parseLines(text.lineSequence())

    /**
     * Parses line by line, so very large playlists (100k+ entries) never sit in memory as one string.
     * Movie and series entries (Xtream-style /movie/ and /series/ links) are skipped: Live TV shows channels only.
     */
    fun parseLines(lines: Sequence<String>, max: Int = 25_000, onGuideUrl: ((String) -> Unit)? = null): List<Channel> {
        val out = ArrayList<Channel>()
        var pendingName: String? = null
        var logo: String? = null
        var group = "Other"
        var tvgId: String? = null
        var tvgName: String? = null
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    // Header can name the XMLTV guide: url-tvg="a.xml.gz,b.xml" (or x-tvg-url). Use the first one.
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    val guide = (attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"] ?: "")
                        .split(",").map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    if (guide != null) onGuideUrl?.invoke(guide)
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.ifBlank { null }
                    group = attrs["group-title"]?.ifBlank { null } ?: "Other"
                    tvgId = attrs["tvg-id"]?.trim()?.ifBlank { null }
                    tvgName = attrs["tvg-name"]?.trim()?.ifBlank { null }
                    pendingName = titleOf(line).trim().ifBlank { attrs["tvg-name"] ?: "Channel" }
                }
                line.isNotEmpty() && !line.startsWith("#") && pendingName != null -> {
                    val vod = line.contains("/movie/") || line.contains("/series/")
                    if (!vod) out += Channel(pendingName!!, logo, group, line, tvgId, tvgName)
                    pendingName = null
                    if (out.size >= max) break
                }
            }
        }
        return out
    }

    /** XMLTV guide link from the last loaded playlist's #EXTM3U header ("" when it has none). */
    @Volatile var guideUrl: String = ""
        private set

    private var cacheUrl = ""
    private var cacheAt = 0L
    @Volatile private var cache: List<Channel> = emptyList()

    /** The last playlist loaded (even if older than 6 hours), or empty. Never downloads. */
    val cached: List<Channel> get() = cache

    private val lock = kotlinx.coroutines.sync.Mutex()

    /** User agents tried for playlist servers, most widely accepted first. */
    private val USER_AGENTS = listOf(
        "IPTVSmartersPro",
        "okhttp/4.12.0",
        "VLC/3.0.20 LibVLC/3.0.20",
        "Mozilla/5.0 (Linux; Android 11; AFTKA) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
    )

    /** How many screens the provider's account allows at once (Xtream "max_connections"); 0 when not known. */
    @Volatile var maxConnections: Int = 0
        private set

    /** The user agent the playlist server last accepted; Live TV streams are requested with it too. */
    @Volatile var userAgent: String = USER_AGENTS.first()
        private set

    /** Server, username and password of an Xtream Codes style link (.../get.php?username=..&password=..). */
    private class Xtream(val base: String, val user: String, val pass: String)

    private fun xtreamOf(url: String): Xtream? = runCatching {
        val u = java.net.URI(url.trim())
        val path = u.path.orEmpty()
        if (!path.endsWith("get.php")) return null
        val q = (u.rawQuery ?: return null).split("&").associate {
            it.substringBefore("=").lowercase() to java.net.URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
        }
        val user = q["username"]?.takeIf { it.isNotBlank() } ?: return null
        val pass = q["password"]?.takeIf { it.isNotBlank() } ?: return null
        val port = if (u.port > 0) ":${u.port}" else ""
        Xtream("${u.scheme}://${u.host}$port${path.removeSuffix("get.php").trimEnd('/')}", user, pass)
    }.getOrNull()

    /** GET as text with a user agent; null body when the server answers outside 2xx. */
    private fun httpText(url: String, ua: String): Pair<Int, String?> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 90_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", ua)
            c.setRequestProperty("Accept", "*/*")
            val code = c.responseCode
            if (code !in 200..299) return code to null
            return code to c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Live channels through the Xtream Codes app API (player_api.php), the way IPTV apps sign in.
     * Returns null when the server refuses every user agent (the caller then tries the M3U download).
     */
    private fun loadXtream(x: Xtream, onGuide: (String) -> Unit, onCode: (Int) -> Unit): List<Channel>? {
        val enc = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
        val api = "${x.base}/player_api.php?username=${enc(x.user)}&password=${enc(x.pass)}"
        for (ua in (listOf(userAgent) + USER_AGENTS).distinct()) {
            val (code, body) = runCatching { httpText(api, ua) }.getOrElse { 0 to null }
            if (body == null) { if (code != 0) onCode(code); continue }
            val info = runCatching { JSONObject(body).optJSONObject("user_info") }.getOrNull() ?: continue
            if (info.optInt("auth", 1) == 0) throw Exception("The provider rejected the username or password in your playlist link.")
            val status = info.optString("status")
            if (status.isNotBlank() && !status.equals("Active", ignoreCase = true)) {
                throw Exception("Your IPTV account is not active (status: $status). Check with your provider.")
            }
            maxConnections = info.optString("max_connections").trim().toIntOrNull() ?: 0
            val formats = info.optJSONArray("allowed_output_formats")
            val allowed = if (formats == null) emptyList() else List(formats.length()) { formats.optString(it) }
            val ext = if (allowed.isEmpty() || "m3u8" in allowed) "m3u8" else allowed.first()
            val cats = HashMap<String, String>()
            runCatching { org.json.JSONArray(httpText("$api&action=get_live_categories", ua).second ?: "[]") }.getOrNull()?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { cats[it.optString("category_id")] = it.optString("category_name") }
            }
            val streams = runCatching { org.json.JSONArray(httpText("$api&action=get_live_streams", ua).second ?: "[]") }.getOrNull() ?: continue
            val out = ArrayList<Channel>(streams.length())
            for (i in 0 until streams.length()) {
                val o = streams.optJSONObject(i) ?: continue
                val id = o.opt("stream_id")?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                val name = o.optString("name").trim().ifBlank { "Channel $id" }
                val logo = o.optString("stream_icon").takeIf { it.startsWith("http") }
                val group = cats[o.optString("category_id")]?.ifBlank { null } ?: "Other"
                val epg = o.optString("epg_channel_id").trim().takeIf { it.isNotBlank() && it != "null" }
                out += Channel(name, logo, group, "${x.base}/live/${x.user}/${x.pass}/$id.$ext", epg, name)
                if (out.size >= 25_000) break
            }
            if (out.isEmpty()) continue
            userAgent = ua
            onGuide("${x.base}/xmltv.php?username=${enc(x.user)}&password=${enc(x.pass)}")
            return out
        }
        return null
    }

    /** Downloads and parses off the main thread; shared by Live TV, Multiview and Games, refreshed every 6 hours. */
    suspend fun load(force: Boolean = false): List<Channel> {
        val url = Prefs.m3uUrl
        if (url.isBlank()) return emptyList()
        return lock.withLock {
            val fresh = url == cacheUrl && System.currentTimeMillis() - cacheAt < 6 * 3600_000L
            if (fresh && !force && cache.isNotEmpty()) return@withLock cache
            var guide = ""
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var lastCode = 0
                // Xtream Codes providers (links like .../get.php?username=..&password=..) often switch off the
                // M3U download but keep their app API on, which is what IPTV Smarters uses. Try the API first.
                val x = xtreamOf(url)
                if (x != null) {
                    val r = loadXtream(x, { g -> guide = g }, { code -> lastCode = code })
                    if (r != null) return@withContext r
                }
                // Plain M3U download. Some servers only answer the user agents of common IPTV players,
                // so try a few in turn and remember the one that works (the live player uses it too).
                var result: List<Channel>? = null
                for (ua in (listOf(userAgent) + USER_AGENTS).distinct()) {
                    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    try {
                        c.connectTimeout = 20_000
                        c.readTimeout = 90_000
                        c.instanceFollowRedirects = true
                        c.setRequestProperty("User-Agent", ua)
                        c.setRequestProperty("Accept", "*/*")
                        val code = c.responseCode
                        if (code !in 200..299) { lastCode = code; continue }
                        result = c.inputStream.bufferedReader().useLines { parseLines(it) { g -> guide = g } }
                        userAgent = ua
                        break
                    } finally { c.disconnect() }
                }
                result ?: throw Exception(
                    "The playlist server refused the link (code $lastCode). Check that the link is the full M3U link from your provider, " +
                        "that the account is active, and that it is not playing on another screen. Some providers also need you to ask them to allow a new device."
                )
            }
            if (list.isEmpty()) throw Exception("The playlist loaded but had no live channels in it.")
            cache = list; cacheUrl = url; cacheAt = System.currentTimeMillis(); guideUrl = guide
            list
        }
    }
}
