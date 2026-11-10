package com.mcd.tv.data

import java.util.Locale

/**
 * Settings > Playback > Quality (pref `quality_mode`, this TV only, never synced).
 * "auto": step down from 4K to 1080p when playback struggles. "4k": never step down. "1080": always cap at 1080p.
 */
object QualityMode {
    const val AUTO = "auto"
    const val PREFER_4K = "4k"
    const val MAX_1080 = "1080"
    val ALL = listOf(AUTO, PREFER_4K, MAX_1080)

    fun clean(v: String?): String = if (v in ALL) v!! else AUTO

    fun label(v: String): String = when (clean(v)) {
        PREFER_4K -> "Prefer 4K"
        MAX_1080 -> "Max 1080p"
        else -> "Auto"
    }
}

/**
 * Notices when playback struggles, so a 4K stream can step down to 1080p. Pure logic: the player feeds it
 * events with a monotonic clock (ms). Same rules as the web app; both pass tools/qf-vectors.json.
 *  - Stalls: 3 stalls start within 60 s, or 12 s stalled within the last 60 s, or one stall lasts 8 s.
 *    The first 10 s after a (re)start are ignored for stalls. A seek cancels the open stall.
 *  - Throughput: one sample per finished segment, at least 3 s apart, counted from the start.
 *    ratio = throughput / stream bitrate. Under 0.7: severe and low run. 0.7 to 1.0: low run, severe resets.
 *    1.1 and up: both reset. 1.0 to 1.1 is a dead band (severe resets, low stays), so a stream that wobbles
 *    around its bitrate fires at most once instead of flapping.
 *  - check() gives a reason code once, then "" until restart().
 */
class StallWatch(startMs: Long) {
    companion object {
        const val WINDOW_MS = 60_000L
        const val MAX_STALLS = 3
        const val MAX_STALLED_MS = 12_000L
        const val LONG_STALL_MS = 8_000L
        const val STARTUP_MS = 10_000L
        /** Buffering this soon after a seek belongs to the seek. */
        const val SEEK_GRACE_MS = 2_000L
        const val SAMPLE_GAP_MS = 3_000L
        const val LOW_SAMPLES = 4
        const val SEVERE_SAMPLES = 2
        const val SEVERE_RATIO = 0.7
        const val RESET_RATIO = 1.1
    }

    private var startAt = startMs
    /** Stalls as [start, end]; end = -1 while it lasts (only the last one can be open). */
    private val stalls = ArrayList<LongArray>()
    private var low = 0
    private var severe = 0
    private var lastSampleAt = Long.MIN_VALUE
    // Player glue: buffering before the first picture or right after a seek is not a stall.
    private var started = false
    private var seekPending = false
    /** When the last seek was (onSeek(now)); MIN_VALUE when not known. */
    private var seekAt = Long.MIN_VALUE

    var fired = false
        private set

    /** The last reason code check() returned (see [QualityLog.words]). */
    var reason = ""
        private set

    private fun openStall(): LongArray? = stalls.lastOrNull()?.takeIf { it[1] < 0 }

    /** A new stream or a reconnect: forget everything and ignore stalls for the next [STARTUP_MS]. */
    fun restart(now: Long) {
        startAt = now
        stalls.clear()
        low = 0
        severe = 0
        lastSampleAt = Long.MIN_VALUE
        fired = false
        started = false
        seekPending = false
        seekAt = Long.MIN_VALUE
    }

    /** The picture froze to buffer. */
    fun stall(now: Long) {
        if (openStall() == null && now - startAt >= STARTUP_MS) stalls.add(longArrayOf(now, -1L))
    }

    /** Playing again. */
    fun resume(now: Long) {
        openStall()?.let { it[1] = now }
    }

    /** The buffering came from a seek: drop the open stall. */
    fun cancel() {
        if (openStall() != null) stalls.removeAt(stalls.size - 1)
    }

    /** One finished segment: throughput and stream bitrate in bits per second (<= 0 = unknown). */
    fun sample(now: Long, bps: Double, bitrate: Double) {
        if (lastSampleAt != Long.MIN_VALUE && now - lastSampleAt < SAMPLE_GAP_MS) return
        lastSampleAt = now
        if (bps <= 0 || bitrate <= 0) {
            low = 0
            severe = 0
            return
        }
        val ratio = bps / bitrate
        when {
            ratio < SEVERE_RATIO -> { severe++; low++ }
            ratio < 1.0 -> { low++; severe = 0 }
            ratio >= RESET_RATIO -> { low = 0; severe = 0 }
            else -> severe = 0 // dead band: the hysteresis
        }
    }

    /** Playing (STATE_READY). */
    fun onReady(now: Long) {
        started = true
        seekPending = false
        resume(now)
    }

    /** Buffering (STATE_BUFFERING) while the player wants to play. */
    fun onBuffering(now: Long) {
        // A seek that never left READY (a seek inside the buffer) must not hide a real stall later on:
        // the seek's own buffering starts at once, so one more than SEEK_GRACE_MS after it is a stall.
        if (seekPending && seekAt != Long.MIN_VALUE && now - seekAt > SEEK_GRACE_MS) seekPending = false
        if (started && !seekPending) stall(now)
    }

    /** The viewer seeked: the buffering that follows is not a stall. */
    fun onSeek() {
        seekPending = true
        seekAt = Long.MIN_VALUE
        cancel()
    }

    /** The viewer seeked at [now]: the buffering that follows at once is not a stall. */
    fun onSeek(now: Long) {
        onSeek()
        seekAt = now
    }

    /** Stopped, ended or failed: a stall in progress ends here. */
    fun onStop(now: Long) = resume(now)

    /**
     * "" while fine, else why it struggles: "long_stall", "stalls", "stall_time", "very_slow", "slow".
     * Fires once until [restart]. Call after events and about once a second while buffering.
     */
    fun check(now: Long): String {
        if (fired) return ""
        val from = now - WINDOW_MS
        stalls.removeAll { it[1] in 0..from }
        val cur = openStall()
        var stalledMs = 0L
        for (s in stalls) stalledMs += ((if (s[1] < 0) now else s[1]) - maxOf(s[0], from)).coerceAtLeast(0L)
        val why = when {
            cur != null && now - cur[0] >= LONG_STALL_MS -> "long_stall"
            stalls.count { it[0] > from } >= MAX_STALLS -> "stalls"
            stalledMs >= MAX_STALLED_MS -> "stall_time"
            severe >= SEVERE_SAMPLES -> "very_slow"
            low >= LOW_SAMPLES -> "slow"
            else -> ""
        }
        if (why.isNotEmpty()) {
            fired = true
            reason = why
        }
        return why
    }
}

/**
 * Throughput estimate, the same as hls.js EwmaBandWidthEstimator: two exponentially weighted averages
 * (half-lives 3 s and 9 s) weighted by load time; the estimate is the lower one. Feed it finished segments.
 */
class ThroughputEstimator {
    private class Ewma(halfLifeS: Double) {
        val alpha = Math.exp(Math.log(0.5) / halfLifeS)
        var est = 0.0
        var total = 0.0
        fun sample(weight: Double, value: Double) {
            val adj = Math.pow(alpha, weight)
            est = value * (1 - adj) + adj * est
            total += weight
        }
        fun estimate(): Double {
            val zero = 1 - Math.pow(alpha, total)
            return if (zero > 0) est / zero else est
        }
    }

    private val fast = Ewma(3.0)
    private val slow = Ewma(9.0)
    var samples = 0
        private set

    /** One load: [bytes] in [durationMs] (floored to 50 ms). */
    fun sample(durationMs: Long, bytes: Long) {
        val weight = maxOf(durationMs, 50L) / 1000.0
        val value = 8.0 * bytes / weight
        fast.sample(weight, value)
        slow.sample(weight, value)
        samples++
    }

    /** Bits per second, 0 before the first sample. */
    fun estimate(): Double = if (samples == 0) 0.0 else minOf(fast.estimate(), slow.estimate())
}

/** The stream's real bitrate from its segments: bytes over media time, for one rendition (height) at a time. */
class BitrateMeter {
    var height = -1
        private set
    private var bytes = 0L
    private var mediaMs = 0L

    /** A segment of [segBytes] covering [segMediaMs] of media at [h] lines. A new height starts over. */
    fun add(h: Int, segBytes: Long, segMediaMs: Long) {
        if (segBytes <= 0 || segMediaMs <= 0) return
        if (h != height) {
            height = h
            bytes = 0
            mediaMs = 0
        }
        bytes += segBytes
        mediaMs += segMediaMs
    }

    /** Bits per second, 0 when unknown. */
    fun bps(): Double = if (mediaMs > 0) bytes * 8000.0 / mediaMs else 0.0
}

/** Pure picks for the step down from 4K: is it 4K, can the player cap itself, which channel or source to use. */
object QualityFallback {
    const val NOTICE = "Switched to 1080p for smoother playback"
    const val UHD_MIN_HEIGHT = 1600

    // Up-front pick (tools/qf-vectors.json "startTier").
    const val DEFAULT_BITRATE = 16_000_000.0
    const val FRESH_MS = 30 * 60_000L
    const val MEMORY_MS = 7 * 24 * 3_600_000L
    const val UP_RATIO = 1.2
    const val CLEAR_RATIO = 1.5
    const val NOTICE_FROZE = "Playing 1080p. This channel froze in 4K before."
    const val NOTICE_SLOW = "Playing 1080p. Your connection is slower than this 4K stream."

    /** tier "4k" or "1080"; why "", "slow_network", "froze_before" or "fast_again"; clear = forget the step-down. */
    data class StartPick(val tier: String, val why: String, val clear: Boolean)

    /**
     * Which tier to open a 4K channel at, before it plays. [est] = last measured throughput (bps) at [estAt],
     * [br] = the channel's measured bitrate (0 = use 16 Mbps), [down] = when it last stepped down here (0 = never).
     */
    fun startTier(now: Long, est: Double, estAt: Long, br: Double, down: Long): StartPick {
        val rate = if (br > 0) br else DEFAULT_BITRATE
        val fresh = est > 0 && now - estAt <= FRESH_MS
        return if (down > 0 && now - down <= MEMORY_MS) {
            if (fresh && estAt > down && est >= CLEAR_RATIO * rate) StartPick("4k", "fast_again", true) else StartPick("1080", "froze_before", false)
        } else if (fresh && est < UP_RATIO * rate) {
            StartPick("1080", "slow_network", false)
        } else {
            StartPick("4k", "", false)
        }
    }

    private val SIZE = Regex("(\\d{3,5})x(\\d{3,5})")

    /** Picture height from a Live TV health line ("3840x2160 60 fps hevc"), 0 when missing. */
    fun heightOf(video: String): Int = SIZE.find(video)?.groupValues?.get(2)?.toIntOrNull() ?: 0

    /** A Live TV health session that counts as a 4K step-down: 4K picture and 3 freezes or 12 s frozen, within 7 days. */
    fun frozeIn4k(now: Long, at: Long, video: String, stalls: Int, stallMs: Long): Boolean =
        now - at in 0..MEMORY_MS && heightOf(video) >= UHD_MIN_HEIGHT && (stalls >= StallWatch.MAX_STALLS || stallMs >= StallWatch.MAX_STALLED_MS)

    private val UHD_NAME = Regex("(?<![a-z0-9])(?:4k|uhd|2160p?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val FHD_NAME = Regex("(?<![a-z0-9])(?:fhd|1080[pi]?)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HD_NAME = Regex("(?<![a-z0-9])hd(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val HD720_NAME = Regex("(?<![a-z0-9])720p?(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val SD_NAME = Regex("(?<![a-z0-9])(?:sd|480p|576p|360p)(?![a-z0-9])", RegexOption.IGNORE_CASE)
    private val LETTERS = Regex("[a-z]+")

    /** "ESPN 4K", "Sky UHD", "NBC 2160p". */
    fun is4kName(name: String): Boolean = UHD_NAME.containsMatchIn(name)

    /** 4K by the picture height (video format or size, 0 = unknown) or by the channel or source name. */
    fun is4k(height: Int, name: String): Boolean = height >= UHD_MIN_HEIGHT || is4kName(name)

    /** A multi-variant stream with a track at 1080p or lower: cap the track selector instead of switching. */
    fun canCapTo1080(supportedHeights: List<Int>): Boolean =
        supportedHeights.size >= 2 && supportedHeights.any { it in 1..1080 }

    /** How good a step-down sibling is by its name: 4 = 1080p / FHD, 3 = HD, 2 = no label, 1 = 720p, -1 = never (4K, SD). */
    fun siblingTier(name: String): Int = when {
        UHD_NAME.containsMatchIn(name) -> -1
        FHD_NAME.containsMatchIn(name) -> 4
        HD720_NAME.containsMatchIn(name) -> 1
        HD_NAME.containsMatchIn(name) -> 3
        SD_NAME.containsMatchIn(name) -> -1
        else -> 2
    }

    /**
     * A cheap filter before the guide key: a letter run of [key] every sibling's lowercase name must contain.
     * Skips "and" and "west", which the guide key can add ("A&E", "(W)", "Pacific"). Null = no filter.
     */
    internal fun keyProbe(key: String): String? =
        LETTERS.findAll(key).map { it.value }.filter { it != "and" && it != "west" }.maxByOrNull { it.length }

    /**
     * The 1080p version of [current] in [channels]: same guide key (quality words removed; West stays apart
     * from East), a different stream. Prefers 1080p / FHD, then HD, then no label, then 720p. Never 4K or SD.
     * Ties keep playlist order, so a backup feed only wins when nothing else matches.
     */
    fun pickSibling(current: Channel, channels: List<Channel>): Channel? {
        val key = LiveFill.guideKey(current.name)
        if (key.isBlank()) return null
        val probe = keyProbe(key)
        var best: Channel? = null
        var bestScore = Int.MIN_VALUE
        for (ch in channels) {
            if (ch.url == current.url) continue
            if (probe != null && !ch.name.lowercase(Locale.ROOT).contains(probe)) continue
            val tier = siblingTier(ch.name)
            if (tier < 0) continue
            if (LiveFill.guideKey(ch.name) != key) continue
            val backup = ch.name.contains("backup", ignoreCase = true)
            val score = tier * 2 + if (backup) 0 else 1
            if (score > bestScore) {
                best = ch
                bestScore = score
            }
        }
        return best
    }

    /**
     * Index of a 1080p source for the same title in a ranked source list, or null. Looks after [current] first
     * (sources above it were usually tried already), then above it. Skips the playing file.
     */
    fun pick1080Source(list: List<StreamSource>, current: Int): Int? {
        val cur = list.getOrNull(current)
        fun same(s: StreamSource): Boolean = cur != null && (
            (!s.url.isNullOrBlank() && s.url == cur.url) ||
                (!s.infoHash.isNullOrBlank() && s.infoHash.equals(cur.infoHash, ignoreCase = true) && s.fileIdx == cur.fileIdx)
            )
        val order = ((current + 1) until list.size) + (0 until current.coerceIn(0, list.size))
        return order.firstOrNull { i ->
            val s = list[i]
            !same(s) && StreamInfo.parse(s).let { it.resolution == "1080p" && !it.cam }
        }
    }
}

/**
 * What this TV learned about 4K channels (pref `quality_memory`, this TV only, never synced, not per profile):
 * {"est": last throughput bps, "estAt": ms, "ch": {guideKey: {"br": measured bitrate bps, "down": last step-down ms,
 * "clr": when a step-down was forgiven, so older Live TV health sessions do not bring it back}}}.
 * Kept in memory; written when a player closes or steps down.
 */
object QualityMemory {
    private const val KEY = "quality_memory"
    private const val KEEP = 60

    class Channel(var br: Double = 0.0, var down: Long = 0L, var clr: Long = 0L)

    class State(var est: Double = 0.0, var estAt: Long = 0L, val ch: LinkedHashMap<String, Channel> = LinkedHashMap())

    private var state: State? = null
    private var dirty = false

    fun decode(json: String): State = runCatching {
        val o = org.json.JSONObject(json)
        val st = State(o.optDouble("est", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optLong("estAt"))
        val c = o.optJSONObject("ch")
        if (c != null) {
            val keys = c.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val x = c.optJSONObject(k) ?: continue
                st.ch[k] = Channel(x.optDouble("br", 0.0).takeIf { !it.isNaN() } ?: 0.0, x.optLong("down"), x.optLong("clr"))
            }
        }
        st
    }.getOrDefault(State())

    fun encode(st: State): String {
        val c = org.json.JSONObject()
        for ((k, v) in st.ch) {
            val x = org.json.JSONObject()
            if (v.br > 0) x.put("br", Math.round(v.br))
            if (v.down > 0) x.put("down", v.down)
            if (v.clr > 0) x.put("clr", v.clr)
            c.put(k, x)
        }
        return org.json.JSONObject().put("est", Math.round(st.est)).put("estAt", st.estAt).put("ch", c).toString()
    }

    @Synchronized
    private fun get(): State = state ?: decode(runCatching { Prefs.json(KEY) }.getOrDefault("").ifBlank { "{}" }).also { state = it }

    /** A copy of what is known about [key] (never null) and the last estimate. */
    @Synchronized
    fun read(key: String): Triple<Double, Long, Channel> {
        val st = get()
        val c = st.ch[key]
        return Triple(st.est, st.estAt, Channel(c?.br ?: 0.0, c?.down ?: 0L, c?.clr ?: 0L))
    }

    /** The last throughput estimate this TV measured, bps (0 = none). */
    @Synchronized
    fun estimate(): Double = get().est

    /** The newest throughput estimate (memory only until [save]). */
    @Synchronized
    fun noteEstimate(bps: Double, now: Long) {
        if (bps <= 0) return
        val st = get()
        st.est = bps
        st.estAt = now
        dirty = true
    }

    /** Updates [key]: a measured bitrate, a step-down time, or a cleared step-down (null = leave as is). */
    @Synchronized
    fun noteChannel(key: String, br: Double? = null, down: Long? = null, clearedAt: Long? = null) {
        if (key.isBlank()) return
        val st = get()
        val c = st.ch.remove(key) ?: Channel()
        if (br != null && br > 0) c.br = br
        if (down != null) c.down = down
        if (clearedAt != null) {
            c.down = 0L
            c.clr = clearedAt
        }
        st.ch[key] = c // newest last
        while (st.ch.size > KEEP) st.ch.remove(st.ch.keys.first())
        dirty = true
    }

    @Synchronized
    fun save() {
        if (!dirty) return
        dirty = false
        val st = state ?: return
        runCatching { Prefs.putJson(KEY, encode(st)) }
    }
}

/**
 * Every quality change on this TV (pref `quality_log`, the last [KEEP], this TV only, never synced): rendition
 * switches by the player, caps, up-front picks and switches to a 1080p channel or source. Shown in
 * Settings > Addons and Live TV > Quality changes.
 */
object QualityLog {
    private const val KEY = "quality_log"
    const val KEEP = 40

    /** [from] / [to] like "3840x2160 18.4 Mbps"; [code] see [words]; [est] = throughput estimate (bps, 0 = none). */
    data class Entry(val at: Long, val title: String, val from: String, val to: String, val code: String, val est: Double)

    /** Plain words for a reason code. */
    fun words(code: String): String = when (code) {
        "long_stall" -> "One freeze lasted 8 s."
        "stalls" -> "3 freezes in a minute."
        "stall_time" -> "12 s frozen in a minute."
        "very_slow" -> "Network far below the stream bitrate."
        "slow" -> "Network below the stream bitrate."
        "froze_before" -> "This channel froze in 4K before."
        "slow_network" -> "Connection slower than this 4K stream."
        "fast_again" -> "Connection fast enough for 4K again."
        "max_1080" -> "Quality is set to Max 1080p."
        "player_down" -> "The player lowered the quality."
        "player_up" -> "The player raised the quality."
        "locked" -> "The player left 4K. It stays at 1080p or lower now."
        "capped" -> "Following the 1080p cap."
        "no_lower" -> "No 1080p version found. Kept 4K."
        else -> ""
    }

    /** "18.4 Mbps", "" when unknown. */
    fun mbps(bps: Double): String = if (bps > 0) String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0) else ""

    /** "3840x2160 18.4 Mbps". */
    fun rendition(width: Int, height: Int, bps: Double): String =
        listOf(if (width > 0 && height > 0) "${width}x$height" else "", mbps(bps)).filter { it.isNotEmpty() }.joinToString(" ")

    fun encode(list: List<Entry>): String {
        val a = org.json.JSONArray()
        for (e in list) {
            a.put(
                org.json.JSONObject().put("at", e.at).put("t", e.title).put("f", e.from).put("to", e.to).put("c", e.code)
                    .put("e", Math.round(e.est)),
            )
        }
        return a.toString()
    }

    fun decode(json: String): List<Entry> = runCatching {
        val a = org.json.JSONArray(json)
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Entry(o.optLong("at"), o.optString("t"), o.optString("f"), o.optString("to"), o.optString("c"), o.optDouble("e", 0.0))
        }
    }.getOrDefault(emptyList())

    /** [list] with [e] in front, at most [KEEP]. */
    fun push(list: List<Entry>, e: Entry): List<Entry> = (listOf(e) + list).take(KEEP)

    private var cache: List<Entry>? = null

    /** Newest first. */
    @Synchronized
    fun recent(): List<Entry> = cache ?: decode(runCatching { Prefs.json(KEY) }.getOrDefault("").ifBlank { "[]" }).also { cache = it }

    @Synchronized
    fun add(e: Entry) {
        val list = push(recent(), e)
        cache = list
        runCatching { Prefs.putJson(KEY, encode(list)) }
    }

    @Synchronized
    fun clear() {
        cache = emptyList()
        runCatching { Prefs.putJson(KEY, "[]") }
    }
}
