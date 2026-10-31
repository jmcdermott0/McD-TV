package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

// ======================= Live TV: what a stream link really answered =======================

/**
 * When a live channel fails with "Input does not start with the #EXTM3U header", the provider answered the
 * stream link with something that is not a playlist. This finds out what: it asks for the same link with the
 * same user agent, follows redirects by hand, and keeps for each hop the HTTP code, content type, length and
 * what the first bytes look like (playlist, MPEG-TS video, HTML page, JSON, text, empty). It never keeps a
 * link, a query string, a user name, a password or any body text: only the host of each hop.
 *
 * Read on the TV in Settings > About and help (recent errors), and from a phone or browser with the relay
 * command "stream_diag".
 */
object StreamDiag {

    class Hop(val host: String, val http: Int, val type: String, val length: Long, val body: String, val error: String = "") {
        fun json(): JSONObject = JSONObject().put("host", host).put("http", http).put("type", type).put("length", length)
            .put("body", body).put("error", error)

        fun words(): String = when {
            error.isNotEmpty() -> "no answer ($error)"
            http in 300..399 -> "HTTP $http redirect"
            else -> "HTTP $http, ${type.ifBlank { "no type" }}, $body"
        }
    }

    class Probe(val at: Long, val channel: String, val ua: String, val format: String, val hops: List<Hop>) {
        fun json(): JSONObject = JSONObject().put("at", at).put("channel", channel).put("ua", ua).put("format", format)
            .put("hops", JSONArray().apply { hops.forEach { put(it.json()) } })

        /** One line, like "HTTP 302 redirect, then HTTP 200, text/html, HTML page". */
        fun words(): String = hops.joinToString(", then ") { it.words() }.ifBlank { "no answer" }
    }

    /** This run only: a live channel failed as a playlist and played as MPEG-TS, so open the rest as MPEG-TS. */
    @Volatile var preferTs = false

    @Volatile var last: List<Probe> = emptyList()
        private set

    fun add(p: Probe) {
        last = (listOf(p) + last).take(6)
    }

    fun json(): JSONArray = JSONArray().apply { last.forEach { put(it.json()) } }

    /**
     * What the first bytes of an answer look like. Pure, for tests. Never returns the bytes themselves.
     * MPEG-TS packets start with the sync byte 0x47 every 188 bytes.
     */
    fun classify(head: ByteArray, n: Int): String {
        if (n <= 0) return "empty"
        var i = 0
        if (n >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < n && (head[i] == ' '.code.toByte() || head[i] == '\n'.code.toByte() || head[i] == '\r'.code.toByte() || head[i] == '\t'.code.toByte())) i++
        if (i >= n) return "blank"
        if (head[0] == 0x47.toByte() && (n < 189 || head[188] == 0x47.toByte())) return "MPEG-TS video"
        val text = String(head, i, minOf(n - i, 64), Charsets.ISO_8859_1).uppercase()
        return when {
            text.startsWith("#EXTM3U") -> "playlist"
            text.startsWith("<!DOCTYPE") || text.startsWith("<HTML") || text.startsWith("<HEAD") || text.startsWith("<BODY") -> "HTML page"
            text.startsWith("<?XML") || text.startsWith("<") -> "XML or markup"
            text.startsWith("{") || text.startsWith("[") -> "JSON"
            head.take(minOf(n, 64)).all { it >= 9 && it < 127 } -> "plain text"
            else -> "binary data"
        }
    }

    /**
     * The provider refused a live channel because the account's connection is in use (HTTP 409 Conflict). With a
     * one-screen account this happens when another TV is playing, and for a few seconds after a channel change while
     * the provider still counts the old stream. Pure, for tests.
     */
    fun isBusy(http: Int?): Boolean = http == 409

    /** Wait before try number [attempt] (0 = first retry) after a busy answer; null = stop and say why. Pure. */
    fun busyWait(attempt: Int): Long? = BUSY_WAITS.getOrNull(attempt)

    private val BUSY_WAITS = listOf(3_000L, 8_000L, 15_000L, 20_000L)

    /** What the TV says after the last busy answer. [max]: screens the plan allows (0 = not known). Pure. */
    fun busyMessage(max: Int): String {
        val plan = when {
            max == 1 -> "Your IPTV plan allows 1 screen at a time. "
            max > 1 -> "Your IPTV plan allows $max screens at a time. "
            else -> ""
        }
        return "Your IPTV provider says your connection is in use (HTTP 409). " + plan +
            "Stop the channel on the other TV, wait a few seconds, then try again."
    }

    /** "http://host:8080/live/u/p/1.m3u8?x" -> "host:8080". Nothing else of a link is ever kept. */
    fun hostOf(url: String): String =
        runCatching { java.net.URL(url).let { if (it.port > 0) "${it.host}:${it.port}" else it.host } }.getOrDefault("")

    /** An exception as short plain words: class and message, with no link (query strings cut). */
    fun words(e: Throwable): String {
        val m = (e.message ?: "").replace(Regex("[?&][^\\s]*"), "").replace(Regex("/live/[^\\s]*"), "/live/…").take(120)
        return e.javaClass.simpleName + if (m.isNotBlank()) ": $m" else ""
    }

    /** The same channel as MPEG-TS: ".../live/u/p/123.m3u8" -> ".../live/u/p/123.ts". Null for other links. Pure. */
    fun tsUrl(url: String): String? {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        if (!path.contains("/live/") || !path.endsWith(".m3u8", ignoreCase = true)) return null
        return path.dropLast(5) + ".ts" + (if (q >= 0) url.substring(q) else "")
    }

    /**
     * Asks for [url] the way the player does ([ua]) and records each hop. Redirects are followed by hand (up to 4,
     * also between http and https). Reads at most 512 bytes of the last answer. Blocking: call off the main thread.
     */
    fun probe(url: String, ua: String, channel: String, format: String): Probe {
        val hops = ArrayList<Hop>()
        var next = url
        for (i in 0 until 5) {
            val host = hostOf(next)
            val c = runCatching { java.net.URL(next).openConnection() as java.net.HttpURLConnection }.getOrElse {
                hops += Hop(host, 0, "", -1, "", words(it)); return done(channel, ua, format, hops)
            }
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 12_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "*/*")
                val code = c.responseCode
                val type = c.contentType.orEmpty().substringBefore(';').trim()
                val len = c.contentLengthLong
                val loc = c.getHeaderField("Location")
                if (code in 300..399 && !loc.isNullOrBlank()) {
                    hops += Hop(host, code, type, len, "")
                    next = runCatching { java.net.URL(java.net.URL(next), loc).toString() }.getOrElse { return done(channel, ua, format, hops) }
                    continue
                }
                val stream = runCatching { if (code in 200..299) c.inputStream else c.errorStream }.getOrNull()
                val head = ByteArray(512)
                var n = 0
                if (stream != null) {
                    stream.use { s ->
                        while (n < head.size) {
                            val r = s.read(head, n, head.size - n)
                            if (r <= 0) break
                            n += r
                        }
                    }
                }
                hops += Hop(host, code, type, len, classify(head, n))
                return done(channel, ua, format, hops)
            } catch (e: Throwable) {
                hops += Hop(host, 0, "", -1, "", words(e))
                return done(channel, ua, format, hops)
            } finally {
                runCatching { c.disconnect() }
            }
        }
        return done(channel, ua, format, hops)
    }

    private fun done(channel: String, ua: String, format: String, hops: List<Hop>): Probe {
        val p = Probe(System.currentTimeMillis(), channel, ua, format, hops)
        add(p)
        return p
    }
}
