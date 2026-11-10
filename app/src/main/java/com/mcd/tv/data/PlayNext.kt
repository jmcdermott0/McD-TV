package com.mcd.tv.data

import kotlin.math.abs

/**
 * Play Next at credits: when the "Play next" card shows during an episode. Pure (no Android, no Prefs);
 * tests in PlayNextTest. The player wiring is ui/UpNext.kt; settings and learned offsets are PlayNextStore.
 *
 * Signals, in order (each checked against J's real streams on Nov 1, 2026, see HANDOFF):
 *  1. A chapter named for the credits in the file itself ([CreditsChapter]).
 *  2. Addon markers: none. Torrentio and MediaFusion streams carry name, title, infoHash, fileIdx and
 *     behaviorHints (bingeGroup, filename) only; the IPTV provider's episode info has duration and codecs only.
 *  3. A learned offset for the series ([CreditsLearn]): where J left the credits for the next episode before.
 *  4. Settings > Playback > "Play Next before the end" (off by default).
 * A wrong early card cuts real content, which is worse than a late one, so every rule is conservative.
 */
object CreditsChapter {
    /** Chapter names that start the end credits. */
    private val CREDITS = Regex("""(?i)\b(end(ing)?\s*credits?|closing\s*credits?|credits?|ending(\s*theme|\s*song)?|outro|end\s*titles?)\b""")
    /** Names that look like credits but are not the end credits, or hold content after them. */
    private val NOT_END = Regex("""(?i)\b(opening|open|intro|title\s*sequence|pre[-\s]?credits?|post[-\s]?credits?|mid[-\s]?credits?|after[-\s]?credits?|stinger|scene)\b""")
    /** Chapters that may follow the credits without holding the story: previews, logos, more credits. */
    private val TAIL_OK = Regex("""(?i)\b(preview|next\s*(time|episode|week)|trailer|logo|credits?|ending|outro|end\s*titles?)\b""")
    /** Anime: "ED", "ED1". Exact, upper case, so a chapter about someone called Ed never counts. */
    private val ANIME_ED = Regex("""^ED\d?$""")

    /** A later chapter shorter than this may follow the credits whatever its name (studio logos). */
    const val SHORT_TAIL_MS = 20_000L
    /** Shortest episode or movie that gets an early card. */
    const val MIN_DURATION_MS = 60_000L
    /** The credits never start in the first half of the file. */
    const val MIN_FRACTION = 0.5
    /** Credits shorter than this are not worth a card; longer than [MAX_REMAIN_MS] or 35% are not credits. */
    const val MIN_REMAIN_MS = 10_000L
    const val MAX_REMAIN_MS = 12 * 60_000L
    const val MAX_REMAIN_FRACTION = 0.35

    fun isCredits(title: String): Boolean {
        val t = title.trim()
        if (ANIME_ED.matches(t)) return true
        return CREDITS.containsMatchIn(t) && !NOT_END.containsMatchIn(t)
    }

    private fun tailOk(c: Chapters.Chapter, endMs: Long): Boolean =
        isCredits(c.title) || TAIL_OK.containsMatchIn(c.title) && !NOT_END.containsMatchIn(c.title) || endMs - c.startMs < SHORT_TAIL_MS

    /** Where the end credits start (ms), or null when the chapters do not say so safely. */
    fun start(chapters: List<Chapters.Chapter>, durationMs: Long): Long? {
        if (durationMs < MIN_DURATION_MS || chapters.isEmpty()) return null
        val list = chapters.filter { it.startMs in 0 until durationMs }.sortedBy { it.startMs }
        val ends = list.indices.map { i ->
            val next = list.getOrNull(i + 1)?.startMs ?: durationMs
            val e = list[i].endMs
            if (e > list[i].startMs) minOf(e, next) else next
        }
        val i = list.indices.firstOrNull { i ->
            val c = list[i]
            val remain = durationMs - c.startMs
            isCredits(c.title) && c.startMs >= durationMs * MIN_FRACTION &&
                remain >= MIN_REMAIN_MS && remain <= MAX_REMAIN_MS && remain <= durationMs * MAX_REMAIN_FRACTION
        } ?: return null
        // Anything after the credits that is not more credits, a preview or a short logo is story: no early card.
        for (j in i + 1 until list.size) if (!tailOk(list[j], ends[j])) return null
        return list[i].startMs
    }
}

/** Learned per-series credits offset: how far from the end J left the credits for the next episode. */
object CreditsLearn {
    /** A sample counts only inside these bounds: it is the credits, not a skipped act. */
    const val MIN_REMAIN_MS = 15_000L
    const val MAX_REMAIN_MS = 6 * 60_000L
    const val MAX_FRACTION = 0.25
    /** Samples within this of each other agree. */
    const val AGREE_MS = 20_000L
    /** Agreeing samples needed before the card shows by itself. */
    const val NEED = 2
    const val KEEP = 5
    /** The next episode must start this soon after leaving the last one. */
    const val PENDING_MS = 3 * 60_000L

    fun sampleOk(remainingMs: Long, durationMs: Long): Boolean =
        durationMs >= CreditsChapter.MIN_DURATION_MS && remainingMs in MIN_REMAIN_MS..MAX_REMAIN_MS && remainingMs <= durationMs * MAX_FRACTION

    /** Newest first, at most [KEEP]. */
    fun add(samples: List<Long>, remainingMs: Long): List<Long> = (listOf(remainingMs) + samples).take(KEEP)

    /**
     * The learned remaining time (ms before the end), or null. Needs [NEED] samples within [AGREE_MS] of the newest
     * sample that has company; gives the smallest of them (the latest point, so the card never comes early).
     */
    fun learned(samples: List<Long>): Long? {
        for (anchor in samples) {
            val group = samples.filter { abs(it - anchor) <= AGREE_MS }
            if (group.size >= NEED) return group.min()
        }
        return null
    }

    /** Left an episode in its credits; becomes a sample when the next episode of the same series starts soon. */
    data class Pending(val series: String, val season: Int, val episode: Int, val remainingMs: Long, val at: Long)

    fun commits(p: Pending?, series: String, season: Int, episode: Int, now: Long): Boolean {
        if (p == null || p.series != series || now - p.at > PENDING_MS || now < p.at) return false
        return (season == p.season && episode == p.episode + 1) || (season == p.season + 1 && episode == 1)
    }
}

/** Seeks that follow each other within [GAP_MS] count as one jump from where the first one started. */
class SeekChain {
    private var chainFrom = -1L
    private var lastAt = Long.MIN_VALUE

    /** The start of the jump that [from] continues. */
    fun from(from: Long, now: Long): Long {
        if (chainFrom < 0 || now - lastAt > GAP_MS) chainFrom = from
        lastAt = now
        return chainFrom
    }

    companion object { const val GAP_MS = 3_000L }
}

/** Which signal set the card's time. */
enum class PlayNextSource(val word: String) { CHAPTER("chapter"), LEARNED("learned"), SETTING("setting"), NONE("end") }

data class PlayNextPoint(val atMs: Long, val source: PlayNextSource) {
    companion object { val NONE = PlayNextPoint(-1L, PlayNextSource.NONE) }
}

object PlayNextRules {
    /** Settings > Playback > "Play Next before the end" choices, seconds (0 = off). */
    val END_CHOICES = listOf(0, 15, 30, 45, 60, 90)
    /** A fixed setting never takes more than this share of the file. */
    const val SETTING_MAX_FRACTION = 0.25

    /**
     * When the card shows. Episodes: chapter, then learned, then the setting (only with "at credits" on for the
     * first two). Movies: only a credits chapter, and only with the movie card turned on.
     */
    fun decide(
        durationMs: Long,
        episode: Boolean,
        creditsStartMs: Long?,
        learnedRemainingMs: Long?,
        creditsOn: Boolean,
        endSeconds: Int,
        movieCard: Boolean,
    ): PlayNextPoint {
        if (durationMs < CreditsChapter.MIN_DURATION_MS) return PlayNextPoint.NONE
        if (!episode) {
            return if (movieCard && creditsOn && creditsStartMs != null) PlayNextPoint(creditsStartMs, PlayNextSource.CHAPTER) else PlayNextPoint.NONE
        }
        if (creditsOn && creditsStartMs != null) return PlayNextPoint(creditsStartMs, PlayNextSource.CHAPTER)
        if (creditsOn && learnedRemainingMs != null && CreditsLearn.sampleOk(learnedRemainingMs, durationMs)) {
            return PlayNextPoint(durationMs - learnedRemainingMs, PlayNextSource.LEARNED)
        }
        val s = endSeconds * 1000L
        if (s > 0 && s <= durationMs * SETTING_MAX_FRACTION) return PlayNextPoint(durationMs - s, PlayNextSource.SETTING)
        return PlayNextPoint.NONE
    }
}

/**
 * The card's show / hide decisions for one playback. Shows when playback runs into [atMs] (not on a resume that
 * starts past it) or a seek lands inside the credits; hides on a seek back before it; never again once dismissed.
 */
class PlayNextTrigger(private val atMs: Long, private val durationMs: Long) {
    enum class Event { SHOW, HIDE }

    var showing = false
        private set
    var dismissed = false
        private set
    private var last = -1L
    /** A SHOW the screen could not use yet ([hold]): show on the next tick still inside the credits. */
    private var waiting = false

    fun tick(posMs: Long): Event? {
        if (atMs < 0 || dismissed) { last = posMs; return null }
        val prev = last
        last = posMs
        if (waiting) {
            if (posMs < atMs - BACK_SLACK_MS) waiting = false // went back: the next crossing shows it again
            else if (durationMs - posMs > END_SLACK_MS) { waiting = false; showing = true; return Event.SHOW }
        }
        if (prev < 0) return null // first look: a resume past the point is the viewer's choice
        if (!showing && prev < atMs && posMs >= atMs && posMs - prev <= NATURAL_STEP_MS) { showing = true; return Event.SHOW }
        if (showing && posMs < atMs - BACK_SLACK_MS) { showing = false; return Event.HIDE }
        return null
    }

    fun seek(fromMs: Long, toMs: Long): Event? {
        last = toMs
        if (atMs < 0 || dismissed) return null
        if (showing && toMs < atMs - BACK_SLACK_MS) { showing = false; return Event.HIDE }
        if (!showing && fromMs < atMs && toMs >= atMs && durationMs - toMs > END_SLACK_MS) { showing = true; return Event.SHOW }
        return null
    }

    fun dismiss() { dismissed = true; showing = false; waiting = false }

    /**
     * The screen did not show the card after SHOW (next episode not known yet, a failure, "Still watching?" due,
     * sleep timer at "end of this video"). Without this the trigger would think the card is up and never show it.
     */
    fun hold() {
        if (!showing) return
        showing = false
        waiting = true
    }

    companion object {
        const val NATURAL_STEP_MS = 5_000L
        const val BACK_SLACK_MS = 2_000L
        /** A seek into the last 3 s is a skip to the end: the end-of-episode card takes it. */
        const val END_SLACK_MS = 3_000L
    }
}

/** The episode after S[season]E[episode], from TMDB's season list. */
object NextEpisode {
    sealed interface Result
    data class Known(val season: Int, val episode: Int) : Result
    /** The last episode there is (or the next one has not aired): no "Up next". */
    data object None : Result
    /** TMDB did not answer: the end card works as before, but no early card. */
    data object Unknown : Result

    /** [seasons]: (season number, episode count) for seasons > 0. [nextToAir]: TMDB's next_episode_to_air. */
    fun after(season: Int, episode: Int, seasons: List<Pair<Int, Int>>, nextToAir: Pair<Int, Int>?): Result {
        if (seasons.isEmpty() || season <= 0) return Unknown
        val count = seasons.firstOrNull { it.first == season }?.second ?: return Unknown
        val cand = when {
            episode < count -> Known(season, episode + 1)
            else -> seasons.filter { it.first > season && it.second > 0 }.minByOrNull { it.first }?.let { Known(it.first, 1) } ?: return None
        }
        if (nextToAir != null && nextToAir.first == cand.season && nextToAir.second == cand.episode) return None
        return cand
    }
}

