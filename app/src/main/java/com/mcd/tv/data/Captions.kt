package com.mcd.tv.data

import org.json.JSONArray

/** A subtitle file an addon sends with a stream (Stremio stream `subtitles`: url and lang). */
data class ExternalSub(val url: String, val lang: String, val label: String = "")

/**
 * Captions default to English (Settings > Playback > Captions, pref `captions_lang`, this TV only).
 *
 * One rule for every text track the player sees: tracks inside the file and subtitle files an addon sends with
 * a stream ([ExternalSub], added to the MediaItem). The player screen turns its tracks into [Track]s and does
 * what [CaptionDefault] answers. Pure Kotlin: no Android or Media3 types, so it runs in unit tests.
 */
object Captions {
    /** Pref values. A missing pref reads as [ENGLISH], so a TV that never saw this setting gets English. */
    const val ENGLISH = "en"
    const val OFF = "off"

    fun cleanMode(v: String?): String = if (v == OFF) OFF else ENGLISH

    fun modeLabel(mode: String): String = if (cleanMode(mode) == OFF) "Off" else "English"

    /** One text track as the player sees it. [order] is its place in the player's list. */
    data class Track(
        val order: Int,
        val language: String?,
        val label: String?,
        val forcedFlag: Boolean = false,
        val sdhFlag: Boolean = false,
        val defaultFlag: Boolean = false,
        val selected: Boolean = false,
        val supported: Boolean = true,
    ) {
        /** Forced: only the foreign-language lines (flag or a "Forced" label). */
        val forced: Boolean get() = forcedFlag || label?.contains("forced", ignoreCase = true) == true
        /** For the deaf and hard of hearing (flag or an SDH / CC label). */
        val sdh: Boolean get() = sdhFlag || SDH.containsMatchIn(label.orEmpty())
    }

    private val SDH = Regex("\\b(sdh|cc|hoh|hearing)\\b", RegexOption.IGNORE_CASE)
    private val ENGLISH_LABEL = Regex("^(en|eng|english)\\b", RegexOption.IGNORE_CASE)
    private val UNKNOWN = setOf("", "und", "unknown", "mul", "zxx", "mis")

    /**
     * English by language code: en, eng, english and regional forms (en-US, en_GB). A track with no code (or
     * "und") counts when its label starts with English. Any other code is not English, whatever the label says.
     */
    fun isEnglish(language: String?, label: String?): Boolean {
        val code = language?.trim()?.lowercase()?.split('-', '_')?.firstOrNull().orEmpty()
        if (code == "en" || code == "eng" || code == "english") return true
        if (code !in UNKNOWN) return false
        return ENGLISH_LABEL.containsMatchIn(label?.trim().orEmpty())
    }

    /** Lower is better: full English 0, English SDH 1, forced English 2. */
    private fun rank(t: Track): Int = when {
        t.forced -> 2
        t.sdh -> 1
        else -> 0
    }

    /**
     * The English track to show: a full track before SDH, and either before a forced one. Ties go to the track
     * the file marks as default, then to the first in the list. Null when no playable track is English.
     */
    fun pickEnglish(tracks: List<Track>): Track? =
        tracks.filter { it.supported && isEnglish(it.language, it.label) }
            .minWithOrNull(compareBy<Track>({ rank(it) }, { if (it.defaultFlag) 0 else 1 }, { it.order }))

    /** "English", "Spanish", "English SDH", "English forced": what the viewer sees in the caption note. */
    fun nameOf(t: Track): String {
        val base = languageName(t.language) ?: t.label?.trim()?.takeIf { it.isNotEmpty() } ?: "Unknown language"
        return when {
            t.forced && !base.contains("forced", true) -> "$base forced"
            t.sdh && !SDH.containsMatchIn(base) -> "$base SDH"
            else -> base
        }
    }

    /** English name of a language code (en, eng, es, spa, pt-BR), or null when the code is empty or unknown. */
    fun languageName(code: String?): String? {
        val c = code?.trim()?.replace('_', '-').orEmpty()
        if (c.lowercase() in UNKNOWN) return null
        if (isEnglish(c, null)) return "English"
        val name = runCatching { java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.ENGLISH) }.getOrNull()
        return name?.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
    }

    /** MIME type of an addon subtitle file from its link. Unknown endings are taken as SubRip (most addons send .srt). */
    fun mimeFor(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".vtt") || path.endsWith(".webvtt") -> "text/vtt"
            path.endsWith(".ass") || path.endsWith(".ssa") -> "text/x-ssa"
            path.endsWith(".ttml") || path.endsWith(".dfxp") -> "application/ttml+xml"
            else -> "application/x-subrip"
        }
    }

    /** Label shown in the player's caption menu for an addon subtitle. */
    fun externalLabel(s: ExternalSub): String {
        val name = s.label.ifBlank { languageName(s.lang) ?: s.lang.ifBlank { "Subtitles" } }
        return "$name (addon)"
    }

    /** Addon subtitles in a Stremio stream (`subtitles: [{id, url, lang}]`): web links only, no repeats, at most 20. */
    fun parseAddonSubs(arr: JSONArray?): List<ExternalSub> {
        if (arr == null) return emptyList()
        val out = LinkedHashMap<String, ExternalSub>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            if (!url.startsWith("https://") && !url.startsWith("http://")) continue
            if (url in out) continue
            out[url] = ExternalSub(url, o.optString("lang").trim(), o.optString("label").trim())
            if (out.size >= 20) break
        }
        return out.values.toList()
    }

    /** What the viewer picked for one title with the caption menu. */
    sealed interface Choice {
        data object Off : Choice
        data class Pick(val language: String?, val label: String?) : Choice
    }

    /**
     * The pick for the title playing now, so a new source or a reopened player for the same title keeps it.
     * Another title clears it: the next title starts from the default again. Memory only, main thread only.
     */
    private var viewing: Pair<String, Choice>? = null

    /** The pick carried for [titleKey], or null. Asking for another title forgets the old pick. */
    fun carried(titleKey: String): Choice? {
        val v = viewing ?: return null
        if (v.first == titleKey) return v.second
        viewing = null
        return null
    }

    fun rememberPick(titleKey: String, choice: Choice) {
        viewing = titleKey to choice
    }

    /** One line for the player: what the captions show and why. Null while the tracks are not known yet. */
    fun note(state: CaptionState, mode: String): String? = when (state) {
        CaptionState.Waiting -> null
        CaptionState.NoCaptions -> "No captions for this title."
        is CaptionState.English -> "Captions: ${state.name} (default)"
        is CaptionState.NoEnglish ->
            if (state.showing == null) "No English captions for this title. Captions are off."
            else "No English captions for this title. Showing ${state.showing}."
        is CaptionState.DefaultOff ->
            if (state.showing == null) "Captions off. Change this in Settings > Playback."
            else "Captions: ${state.showing}"
        is CaptionState.Chosen -> {
            val now = if (state.showing == null) "Captions off for this title." else "Captions: ${state.showing} for this title."
            if (cleanMode(mode) == ENGLISH) "$now Next title: English." else now
        }
    }
}

/** What the captions show for this playback. One value, replaced whole (STATE-MACHINES.md, "Captions default"). */
sealed interface CaptionState {
    /** The player has not listed its tracks yet. */
    data object Waiting : CaptionState
    /** The title has no text tracks at all. */
    data object NoCaptions : CaptionState
    /** The default: this English track is on. */
    data class English(val name: String) : CaptionState
    /** No English track. [showing] is what the player's own rules turned on (null: nothing). */
    data class NoEnglish(val showing: String?) : CaptionState
    /** Settings > Playback > Captions is Off: the player's own rules. */
    data class DefaultOff(val showing: String?) : CaptionState
    /** The viewer picked with the caption menu (null: off). Holds for this title only. */
    data class Chosen(val showing: String?) : CaptionState
}

/** What the player must do after [CaptionDefault.onTracks]. */
sealed interface CaptionAction {
    data object None : CaptionAction
    /** Turn on this track (and only this one). */
    data class Select(val track: Captions.Track) : CaptionAction
    /** Turn text off, the way the player's own "None" item does. */
    data object TurnOff : CaptionAction
}

/**
 * The caption decider for one playback. [mode] is the pref (Captions.ENGLISH or Captions.OFF). [carried] is
 * this title's pick from an earlier player (Captions.carried), applied once when the tracks are known.
 * The screen calls [onTracks] on every track change and [onUserChange] when the caption menu changed the
 * selection, then does the [CaptionAction] and shows [state].
 */
class CaptionDefault(mode: String, carried: Captions.Choice? = null) {
    private val mode = Captions.cleanMode(mode)
    private var pending: Captions.Choice? = carried
    private var user = carried != null
    private var userOff = carried == Captions.Choice.Off

    var state: CaptionState = CaptionState.Waiting
        private set

    /** The viewer's pick for this title (to carry to the next player of the same title), null while none. */
    var choice: Captions.Choice? = carried
        private set

    /** The caption menu changed the selection. [off]: the viewer chose no captions. */
    fun onUserChange(off: Boolean) {
        user = true
        userOff = off
        pending = null
        if (off) {
            choice = Captions.Choice.Off
            state = CaptionState.Chosen(null)
        }
    }

    /** [loaded]: the player has listed the media's tracks (any type), so "no text tracks" is final. */
    fun onTracks(tracks: List<Captions.Track>, loaded: Boolean): CaptionAction {
        val usable = tracks.filter { it.supported }
        val showing = usable.firstOrNull { it.selected }
        if (usable.isEmpty()) {
            state = if (!loaded) CaptionState.Waiting else if (user) CaptionState.Chosen(null) else CaptionState.NoCaptions
            return CaptionAction.None
        }
        if (user) {
            val p = pending
            if (p != null) {
                pending = null
                when (p) {
                    Captions.Choice.Off -> {
                        state = CaptionState.Chosen(null)
                        return if (showing == null) CaptionAction.None else CaptionAction.TurnOff
                    }
                    is Captions.Choice.Pick -> {
                        val t = usable.firstOrNull { it.language == p.language && it.label == p.label }
                            ?: usable.firstOrNull { it.language == p.language }
                        if (t != null) {
                            state = CaptionState.Chosen(Captions.nameOf(t))
                            return if (t.selected && usable.count { it.selected } == 1) CaptionAction.None else CaptionAction.Select(t)
                        }
                        // This source has no such track: the default applies.
                        user = false
                        choice = null
                    }
                }
            } else {
                if (userOff) {
                    state = CaptionState.Chosen(null)
                } else {
                    choice = if (showing == null) Captions.Choice.Off else Captions.Choice.Pick(showing.language, showing.label)
                    state = CaptionState.Chosen(showing?.let(Captions::nameOf))
                }
                return CaptionAction.None
            }
        }
        if (mode == Captions.OFF) {
            state = CaptionState.DefaultOff(showing?.let(Captions::nameOf))
            return CaptionAction.None
        }
        val en = Captions.pickEnglish(usable)
        if (en == null) {
            // No silent fallback: the player's own rules stay, and the note says what is showing.
            state = CaptionState.NoEnglish(showing?.let(Captions::nameOf))
            return CaptionAction.None
        }
        state = CaptionState.English(Captions.nameOf(en))
        val onlyIt = en.selected && usable.count { it.selected } == 1
        return if (onlyIt) CaptionAction.None else CaptionAction.Select(en)
    }
}
