package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

// ======================= Live TV: extra guide for free streaming channels =======================

/**
 * A second guide, for the free streaming channels the IPTV provider's guide does not carry
 * ("RK: Law & Crime", "PRIME: Universal Crime", "AT&T: 50 Cent Action"...).
 *
 * Source: i.mjh.nz, the public XMLTV guides of the Roku Channel and Plex free channels, rebuilt about every
 * hour and read here from the maintainer's own GitHub repo (it allows browsers, so the web app reads the
 * same files). Measured on J's playlist (Oct 29, 2026): 1,551 of the 6,528 guide-able channels without a
 * provider guide get one (Roku 1,216, Plex 1,021).
 *
 * It loads on its own, after the provider's guide (Live TV, the player and Games merge it into
 * LiveSession.guide when it arrives), so the provider's guide never waits for it.
 * It only adds: a channel that has any programme from the provider's guide keeps it ([merge]). Channels are
 * matched by name: the lineup tag ("RK:", "PRIME:", "AT&T:") and quality marks are dropped ([playlistKeys])
 * and compared with the guide's display names ([sourceKeys]). The parser is plain Kotlin, so tests run on
 * the JVM. The web app does the same (docs/app/index.html, GX in the LV module).
 */
object GuideExtra {
    private const val BASE = "https://raw.githubusercontent.com/matthuisman/i.mjh.nz/master/"

    /** In order: a channel found in the first file is not looked up in the next. */
    val SOURCES = listOf(BASE + "Roku/all.xml.gz", BASE + "Plex/us.xml.gz")

    private const val WINDOW_MS = 8 * 3600_000L
    private const val CACHE_MS = 6 * 3600_000L
    private const val RETRY_MS = 10 * 60_000L
    private const val MAX_KEPT = 40_000
    private const val MAX_DESC = 160

    // ---------------------------------------------------------------- name keys (same rules as the web GX)

    private val RAISED = Regex("[\u02B0-\u02FF\u1D00-\u1DBF\u2070-\u209F]")
    private val LINEUP = Regex("^\\s*[A-Za-z0-9&+.' ]{1,12}[|:]\\s*")
    private val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")
    private val NOISE = Regex("\\b(?:hd|fhd|uhd|sd|4k|raw|east|eastern|backup|hevc|1080p|720p|60fps|us|usa|feed|stream)\\b")
    private val NON = Regex("[^a-z0-9+]+")
    private val THE = Regex("^the")
    private val TAIL = Regex("(?:channel|network|tv)$")

    private fun core(s: String): List<String> {
        var t = BRACKETS.replace(s, " ").lowercase(Locale.ROOT).replace("&", " and ")
        t = NON.replace(NOISE.replace(t, " "), "")
        if (t.isEmpty()) return emptyList()
        val a = THE.replace(t, "")
        return listOf(t, a, TAIL.replace(a, "")).distinct().filter { it.length >= 3 }
    }

    /** Keys of a playlist name: "PRIME: CHAOS ON CAM BY A&E ᴿᴬᵂ" -> [chaosoncambyaande]. */
    fun playlistKeys(name: String): List<String> {
        var s = RAISED.replace(name, "")
        repeat(2) { s = LINEUP.replace(s, "") }
        return core(s)
    }

    /** Keys of a guide display name ("Law & Crime" -> [lawandcrime]). No lineup tag is dropped here. */
    fun sourceKeys(name: String): List<String> = core(RAISED.replace(name, ""))

    // ---------------------------------------------------------------- pure steps

    /** Name key -> the channel keys ([Epg.keyOf]) that want it. */
    fun wanted(channels: List<Channel>): HashMap<String, MutableList<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (ch in channels) {
            val k = Epg.keyOf(ch)
            for (n in playlistKeys(ch.name)) out.getOrPut(n) { ArrayList(1) }.let { if (k !in it) it.add(k) }
        }
        return out
    }

    /** The provider's guide, plus [extra] for channels it has nothing for. The provider's rows are never changed. */
    fun merge(primary: Map<String, List<Programme>>, extra: Map<String, List<Programme>>): Map<String, List<Programme>> {
        if (extra.isEmpty()) return primary
        val out = HashMap<String, List<Programme>>(primary.size + extra.size)
        out.putAll(primary)
        for ((k, v) in extra) if (primary[k].isNullOrEmpty() && v.isNotEmpty()) out[k] = v
        return out
    }

    /**
     * Reads one XMLTV file. Channels whose display name has a key in [wanted] get their programmes that are on
     * now or start within the window. Returns channel key -> programmes, sorted. A channel key filled here is
     * also removed from [wanted]'s lists, so a later file does not fill it again.
     */
    suspend fun parse(reader: Reader, wanted: MutableMap<String, MutableList<String>>, now: Long): Map<String, List<Programme>> {
        val until = now + WINDOW_MS
        val idKeys = HashMap<String, List<String>>()
        val out = HashMap<String, ArrayList<Programme>>()
        var kept = 0
        val ctx = coroutineContext
        val scan = XmltvScanner(reader)
        var n = 0
        while (true) {
            val el = scan.next() ?: break
            if (++n % 500 == 0) ctx.ensureActive()
            if (el.channel) {
                val id = attr(el.text, "id") ?: continue
                for (dn in DISPLAY.findAll(el.text)) {
                    val keys = sourceKeys(unescape(dn.groupValues[1]))
                    val hit = keys.firstNotNullOfOrNull { k -> wanted[k]?.takeIf { it.isNotEmpty() } }
                    if (hit != null) { idKeys[id] = hit.toList(); break }
                }
            } else {
                val keys = idKeys[attr(el.text, "channel") ?: continue] ?: continue
                val start = Epg.parseTime(attr(el.text, "start")) ?: continue
                val end = Epg.parseTime(attr(el.text, "stop")) ?: continue
                if (end <= now || start >= until) continue
                val p = programme(el.text, start, end)
                for (k in keys) out.getOrPut(k) { ArrayList() }.add(p)
                if (++kept >= MAX_KEPT) break
            }
        }
        val result = HashMap<String, List<Programme>>(out.size)
        for ((k, list) in out) {
            list.sortBy { it.start }
            val clean = ArrayList<Programme>(list.size)
            var last = Long.MIN_VALUE
            for (p in list) if (p.start != last) { clean.add(p); last = p.start }
            result[k] = clean
        }
        if (result.isNotEmpty()) {
            for (list in wanted.values) list.removeAll { result.containsKey(it) }
        }
        return result
    }

    private val DISPLAY = Regex("<display-name[^>]*>([^<]*)</display-name>")
    private val TITLE = Regex("<title[^>]*>([^<]*)</title>")
    private val SUB = Regex("<sub-title[^>]*>([^<]*)</sub-title>")
    private val DESC = Regex("<desc[^>]*>([^<]*)</desc>")
    private val CATEGORY = Regex("<category[^>]*>([^<]*)</category>")
    private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|amp|lt|gt|quot|apos);")

    private fun attr(tag: String, name: String): String? {
        val i = tag.indexOf(" $name=\"").takeIf { it >= 0 } ?: return null
        val from = i + name.length + 3
        val to = tag.indexOf('"', from).takeIf { it >= 0 } ?: return null
        return unescape(tag.substring(from, to))
    }

    internal fun unescape(s: String): String = if ('&' !in s) s else ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e == "amp" -> "&"
            e == "lt" -> "<"
            e == "gt" -> ">"
            e == "quot" -> "\""
            e == "apos" -> "'"
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            else -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    private fun programme(xml: String, start: Long, end: Long): Programme {
        val title = TITLE.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val sub = SUB.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty().take(MAX_DESC)
        val d = DESC.find(xml)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val cats = CATEGORY.findAll(xml).map { unescape(it.groupValues[1]).trim() }.filter { it.isNotEmpty() }.take(6).toList()
        return Programme(
            title.ifEmpty { "Untitled" }, start, end, if (d.length > MAX_DESC) d.take(MAX_DESC - 1) + "…" else d,
            sub = sub, categories = cats, live = "<live" in xml, rerun = "<previously-shown" in xml,
        )
    }

    /** Walks <channel> and <programme> elements of an XMLTV text, one element in memory at a time. */
    private class XmltvScanner(private val r: Reader) {
        class El(val channel: Boolean, val text: String)
        private val buf = StringBuilder()
        private val chunk = CharArray(64 * 1024)
        private var eof = false
        private var pos = 0

        // Where the next "<channel" / "<programme" starts (-1: none up to [cTo] / [pTo]). Kept between calls,
        // so a tag that no longer appears (no <channel> after the channel list) is not searched for again
        // through the whole buffer for every programme.
        private var cAt = -1
        private var cTo = 0
        private var pAt = -1
        private var pTo = 0

        private fun fill(): Boolean {
            if (eof) return false
            val n = r.read(chunk)
            if (n < 0) { eof = true; return false }
            buf.append(chunk, 0, n)
            return true
        }

        private fun find(tag: String, at: Int, to: Int, set: (Int, Int) -> Unit): Int {
            if (at >= pos) return at
            val i = buf.indexOf(tag, maxOf(pos, to - tag.length))
            val to2 = if (i < 0) buf.length else i
            set(i, to2)
            return i
        }

        fun next(): El? {
            while (true) {
                if (pos > 256 * 1024) { // drop what was read; only here, so the indices below stay valid
                    buf.delete(0, pos)
                    if (cAt >= 0) cAt -= pos
                    if (pAt >= 0) pAt -= pos
                    cTo = maxOf(0, cTo - pos); pTo = maxOf(0, pTo - pos)
                    pos = 0
                }
                val c = find("<channel", cAt, cTo) { i, t -> cAt = i; cTo = t }
                val p = find("<programme", pAt, pTo) { i, t -> pAt = i; pTo = t }
                val start = when { c < 0 -> p; p < 0 -> c; else -> minOf(c, p) }
                if (start < 0) {
                    if (!fill()) return null
                    continue
                }
                val isChannel = start == c
                val close = if (isChannel) "</channel>" else "</programme>"
                var end = buf.indexOf(close, start)
                while (end < 0) {
                    val from = maxOf(start, buf.length - close.length)
                    if (!fill()) return null
                    end = buf.indexOf(close, from)
                }
                val text = buf.substring(start, end + close.length)
                pos = end + close.length
                // "<channel" also starts "<channels>"-like tags in odd files: only a real element counts.
                val after = text.getOrNull(if (isChannel) 8 else 10)
                if (after != ' ' && after != '>' && after != '\t' && after != '\n') continue
                return El(isChannel, text)
            }
        }
    }

    // ---------------------------------------------------------------- download and cache

    private val lock = Mutex()
    @Volatile private var cacheSig = ""
    @Volatile private var cacheAt = 0L
    private var failAt = 0L
    @Volatile private var cache: Map<String, List<Programme>> = emptyMap()

    private fun sigOf(channels: List<Channel>) = "${channels.size}|${channels.firstOrNull()?.url}|${channels.lastOrNull()?.url}"

    /**
     * The extra guide for [channels] (channel key -> programmes), from cache for 6 hours. Never throws; any
     * failure gives what was read so far, or nothing.
     */
    suspend fun load(channels: List<Channel>): Map<String, List<Programme>> {
        if (channels.isEmpty() || !enabled()) return emptyMap()
        val sig = sigOf(channels)
        return try {
            lock.withLock {
                val t = System.currentTimeMillis()
                if (sig == cacheSig && t - cacheAt < CACHE_MS) return@withLock cache
                if (sig == cacheSig && t - failAt < RETRY_MS) return@withLock cache
                val want = wanted(channels)
                val out = HashMap<String, List<Programme>>()
                val now = System.currentTimeMillis()
                val tries = ArrayList<GuideTry>()
                for (src in SOURCES) {
                    val t0 = System.currentTimeMillis()
                    val st = IntArray(1)
                    val bytes = LongArray(1)
                    var err = ""
                    val part = try {
                        withContext(Dispatchers.IO) { download(src, want, now, st, bytes) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        err = GuideTry.words(e)
                        emptyMap() // OutOfMemoryError included: a guide is never worth a crash
                    }
                    for ((k, v) in part) if (k !in out) out[k] = v
                    tries += GuideTry(t0, System.currentTimeMillis() - t0, GuideTry.hostOf(src) + "/" + src.substringAfterLast("master/"),
                        "", st[0], bytes[0], part.values.sumOf { it.size }, part.size, err)
                }
                GuideDiag.extra(tries)
                cacheSig = sig
                if (out.isEmpty()) { failAt = t; return@withLock cache }
                cache = out
                cacheAt = t
                out
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    private suspend fun download(
        url: String, want: MutableMap<String, MutableList<String>>, now: Long, http: IntArray, bytes: LongArray,
    ): Map<String, List<Programme>> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = true
            http[0] = c.responseCode
            if (http[0] !in 200..299) return emptyMap()
            val counted = object : java.io.FilterInputStream(c.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) bytes[0]++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) bytes[0] += it }
            }
            val raw = BufferedInputStream(counted, 64 * 1024)
            raw.mark(4)
            val b0 = raw.read()
            val b1 = raw.read()
            raw.reset()
            val input: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
            return InputStreamReader(input, Charsets.UTF_8).use { parse(it, want, now) }
        } finally {
            c.disconnect()
        }
    }

    /** Channels the extra guide has listings for right now (0 before it loads), for the house "tv_guide" report. */
    fun loadedCount(): Int = cache.size

    /** Settings > Addons and Live TV > Guide for free channels (Prefs.guideExtra, on by default). */
    fun enabled(): Boolean = runCatching { Prefs.guideExtra }.getOrDefault(true)

    /** The toggle was turned off: forget what was loaded, so nothing of it shows again until it is turned on. */
    fun clear() {
        cache = emptyMap()
        cacheSig = ""
        cacheAt = 0L
        failAt = 0L
    }

    /** [guide] without the rows this layer added: the channels the provider has nothing for go back to empty. */
    fun withoutExtra(guide: Map<String, List<Programme>>, provider: Map<String, List<Programme>>): Map<String, List<Programme>> =
        guide.filterKeys { k -> !provider[k].isNullOrEmpty() }

    /** The extra guide already loaded for [channels], or nothing (no download). */
    fun cached(channels: List<Channel>): Map<String, List<Programme>> =
        if (channels.isNotEmpty() && enabled() && sigOf(channels) == cacheSig && System.currentTimeMillis() - cacheAt < CACHE_MS) cache else emptyMap()

    /** [guide] with the extra rows already loaded for [channels]; the provider's rows win. */
    fun withCached(guide: Map<String, List<Programme>>, channels: List<Channel>): Map<String, List<Programme>> = merge(guide, cached(channels))
}
