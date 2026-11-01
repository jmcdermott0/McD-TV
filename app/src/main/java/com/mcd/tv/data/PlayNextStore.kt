package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Play Next settings and what this TV learned (see PlayNext.kt). This TV only, never synced, new keys only:
 * no existing setting is read, changed or cleared.
 *  - `playnext_credits` (default on): Play Next at the credits (chapters and learned offsets).
 *  - `playnext_end_secs` (default 0 = off): Play Next this many seconds before the end when nothing else says.
 *  - `playnext_movies` (default off): a credits card for movies (Done or Keep watching, no countdown).
 *  - `playnext_learned`: {"tv:<tmdb id>": [remaining ms, newest first]}, at most [MAX_SERIES] series.
 *  - `playnext_log`: the last [LOG_KEEP] episodes and what signal each file had, for Settings and the house report.
 */
object PlayNextStore {
    private const val LEARNED = "playnext_learned"
    private const val LOG = "playnext_log"
    const val MAX_SERIES = 200
    const val LOG_KEEP = 12

    var creditsOn: Boolean
        get() = Prefs.bool("playnext_credits", true)
        set(v) = Prefs.putBool("playnext_credits", v)

    var endSeconds: Int
        get() = Prefs.int("playnext_end_secs", 0).takeIf { it in PlayNextRules.END_CHOICES } ?: 0
        set(v) = Prefs.putInt("playnext_end_secs", v.takeIf { it in PlayNextRules.END_CHOICES } ?: 0)

    var movieCard: Boolean
        get() = Prefs.bool("playnext_movies", false)
        set(v) = Prefs.putBool("playnext_movies", v)

    fun seriesKey(meta: PlayMeta): String = "tv:${meta.tmdbId}"

    private fun learnedAll(): JSONObject = runCatching { JSONObject(Prefs.json(LEARNED).ifBlank { "{}" }) }.getOrDefault(JSONObject())

    fun samples(series: String): List<Long> {
        val a = learnedAll().optJSONArray(series) ?: return emptyList()
        return (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }
    }

    @Synchronized
    fun addSample(series: String, remainingMs: Long) {
        val all = learnedAll()
        val list = CreditsLearn.add(samples(series), remainingMs)
        all.put(series, JSONArray(list))
        // Keep the store small: drop the oldest-added series past MAX_SERIES (JSONObject keeps insertion order).
        while (all.length() > MAX_SERIES) all.remove(all.keys().next())
        Prefs.putJson(LEARNED, all.toString())
    }

    @Synchronized
    fun forget(series: String) {
        val all = learnedAll()
        if (all.has(series)) {
            all.remove(series)
            Prefs.putJson(LEARNED, all.toString())
        }
    }

    @Synchronized
    fun forgetAll() = Prefs.putJson(LEARNED, "{}")

    fun learnedCount(): Int = learnedAll().let { o -> o.keys().asSequence().count { CreditsLearn.learned(samples(it)) != null } }

    /** In memory only: an episode left in its credits, waiting for the next one to start. */
    @Volatile
    var pending: CreditsLearn.Pending? = null

    // ---------------------------------------------------------------- log

    data class LogEntry(
        val at: Long,
        val title: String,
        val container: String,
        val chapters: Int,
        val creditsTitle: String,
        val creditsAtMs: Long,
        val source: String,
        val atMs: Long,
        val durationMs: Long,
        val note: String,
    ) {
        /** One short line: "mkv, 12 chapters, credits 'End Credits' at 41:20, card at 41:20 (chapter)". */
        fun line(): String = buildString {
            append(container)
            append(", ")
            append(if (chapters == 1) "1 chapter" else "$chapters chapters")
            if (note.isNotBlank()) append(" ($note)")
            if (creditsAtMs >= 0) append(", credits '${creditsTitle.take(30)}' at ${clock(creditsAtMs)}")
            append(if (atMs >= 0) ", card at ${clock(atMs)} of ${clock(durationMs)} (${source})" else ", card at the end")
        }
    }

    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun log(): List<LogEntry> = runCatching {
        val a = JSONArray(Prefs.json(LOG).ifBlank { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            LogEntry(
                o.optLong("at"), o.optString("t"), o.optString("c"), o.optInt("n"), o.optString("ct"), o.optLong("cm", -1L),
                o.optString("s"), o.optLong("am", -1L), o.optLong("d"), o.optString("note"),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun note(e: LogEntry) {
        runCatching {
            val list = (listOf(e) + log()).take(LOG_KEEP)
            val a = JSONArray()
            list.forEach {
                a.put(
                    JSONObject().put("at", it.at).put("t", it.title.take(60)).put("c", it.container).put("n", it.chapters)
                        .put("ct", it.creditsTitle.take(40)).put("cm", it.creditsAtMs).put("s", it.source).put("am", it.atMs)
                        .put("d", it.durationMs).put("note", it.note),
                )
            }
            Prefs.putJson(LOG, a.toString())
        }
    }

    /** For the house "tv_guide" report: counts and the last few lines. No links, no logins. */
    fun report(): JSONObject {
        val l = log()
        return JSONObject()
            .put("on", creditsOn).put("end_secs", endSeconds).put("movies", movieCard)
            .put("files", l.size)
            .put("mkv", l.count { it.container == "mkv" }).put("mp4", l.count { it.container == "mp4" })
            .put("with_chapters", l.count { it.chapters > 0 }).put("credits_chapter", l.count { it.creditsAtMs >= 0 })
            .put("learned_series", learnedCount())
            .put("last", JSONArray(l.take(4).map { "${it.title.take(30)}: ${it.line()}" }))
    }
}

/** Automated emulator QA only ("--es screen playnext:<url>"): a test show whose next episode is known. */
object PlayNextQa {
    const val TMDB_ID = -7
    @Volatile
    var on = false

    fun next(meta: PlayMeta): NextEpisode.Result? =
        if (on && meta.tmdbId == TMDB_ID) NextEpisode.Known(meta.season, meta.episode + 1) else null
}
