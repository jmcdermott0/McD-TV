package com.mcd.tv.data

import org.json.JSONObject

// ======================= Live TV: what happened to the last guide downloads =======================

/**
 * One guide download on this TV, as it really went: when, how long it took, which user agent, the HTTP answer,
 * bytes read, programmes kept, channels that got listings, and the error if it failed. Written by Epg (the
 * provider's XMLTV) and GuideExtra (the Roku and Plex guides), shown in Settings > Addons and Live TV > Live TV
 * health, sent to the phone on request (relay command "guide_diag"), and logged under the tag "JarvisGuide".
 * Never holds a link, user name or password: the host is the only part of a link kept.
 */
class GuideTry(
    val at: Long,
    val ms: Long,
    val host: String,
    val ua: String = "",
    val http: Int = 0,
    val bytes: Long = 0,
    val programmes: Int = 0,
    val channels: Int = 0,
    val error: String = "",
) {
    val ok: Boolean get() = error.isEmpty() && http in 200..299 && channels > 0

    fun json(): JSONObject = JSONObject().put("at", at).put("ms", ms).put("host", host).put("ua", ua).put("http", http)
        .put("bytes", bytes).put("programmes", programmes).put("channels", channels).put("error", error)

    /** One line for Settings, like "Provider guide 8:02 AM: OK, 1,712 channels, 41,230 shows, 38.4 MB in 41 s (VLC)". */
    fun line(clock: (Long) -> String): String {
        val size = if (bytes >= 1_000_000) "%.1f MB".format(bytes / 1e6) else "%,d KB".format(bytes / 1000)
        val time = if (ms >= 10_000) "${ms / 1000} s" else "%.1f s".format(ms / 1000.0)
        val head = clock(at) + ": "
        return when {
            error.isNotEmpty() -> head + "FAILED, " + error + (if (http != 0) " (HTTP $http)" else "") + ", after $time"
            http !in 200..299 -> head + "FAILED, HTTP $http, after $time"
            channels == 0 -> head + "no listings matched, $size in $time"
            else -> head + "OK, %,d channels, %,d shows, %s in %s".format(channels, programmes, size, time)
        }
    }

    companion object {
        /** "http://host:8080/xmltv.php?username=..." -> "host:8080". Nothing else of a link is ever kept. */
        fun hostOf(url: String): String = runCatching { java.net.URL(url).let { if (it.port > 0) "${it.host}:${it.port}" else it.host } }.getOrDefault("")

        /** An exception as short plain words: class and message, no link (query strings cut). */
        fun words(e: Throwable): String {
            val m = (e.message ?: "").replace(Regex("[?&][^\\s]*"), "").take(120)
            return e.javaClass.simpleName + if (m.isNotBlank()) ": $m" else ""
        }
    }
}

object GuideDiag {
    @Volatile var provider: GuideTry? = null
        private set
    @Volatile var extra: List<GuideTry> = emptyList()
        private set

    fun provider(t: GuideTry) {
        provider = t
        log("provider", t)
    }

    fun extra(list: List<GuideTry>) {
        extra = list
        list.forEach { log("extra", it) }
    }

    private fun log(kind: String, t: GuideTry) {
        runCatching { android.util.Log.i("JarvisGuide", "$kind ${t.json()}") }
    }

    /** For the relay command guide_diag: the phone can read how this TV's guide downloads went without a cable or adb. */
    fun json(): JSONObject = JSONObject()
        .put("provider", provider?.json() ?: JSONObject.NULL)
        .put("extra", org.json.JSONArray().apply { extra.forEach { put(it.json()) } })
}
