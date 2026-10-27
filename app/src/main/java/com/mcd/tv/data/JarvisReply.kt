package com.mcd.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** An Ask Jarvis failure whose message is ready to show on screen. */
class JarvisException(message: String) : Exception(message)

/** One guess from Jarvis's answer, before it is looked up on TMDB. */
internal data class JarvisGuess(val title: String, val year: Int?, val type: String?, val confidence: Int, val why: String)

/**
 * What Jarvis's reply held. [person] is the full name when the question was about a person, else "".
 * [problem] says why nothing usable came back (kept for diagnostics), "" when the reply was fine.
 */
internal data class JarvisReply(
    val guesses: List<JarvisGuess>,
    val clarify: String,
    val person: String = "",
    val problem: String = "",
) {
    val usable: Boolean get() = guesses.isNotEmpty() || person.isNotBlank()
}

/** The text of an Anthropic Messages reply, its stop reason, and why it is unusable ("" when it is fine). */
internal data class JarvisRaw(val text: String, val stopReason: String, val problem: String)

/**
 * Pure parsing for Ask Jarvis (no Android, no network), so tests can feed it broken replies.
 * The rule: never throw. A reply that cannot be read becomes an empty [JarvisReply] with a [JarvisReply.problem],
 * and the caller falls back to a plain TMDB person and title search.
 */
internal object JarvisReplyParser {
    const val MAX_GUESSES = 6

    /** Pulls the text blocks out of a Messages API body. Never throws. */
    fun content(body: String?): JarvisRaw {
        if (body.isNullOrBlank()) return JarvisRaw("", "", "empty reply body")
        val o = runCatching { JSONObject(body) }.getOrNull()
            ?: return JarvisRaw("", "", "reply body was not JSON (${body.length} chars)")
        val stop = o.optString("stop_reason")
        val arr = o.optJSONArray("content") ?: return JarvisRaw("", stop, "reply had no content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        val text = sb.toString()
        return JarvisRaw(text, stop, if (text.isBlank()) "reply had no text (stop: ${stop.ifBlank { "none" }})" else "")
    }

    /**
     * Reads Jarvis's answer [text]. Tries, in order: strict JSON, field-by-field salvage (cut-off JSON,
     * stray quotes inside "why"), then "Title (Year)" lines in prose. [stopReason] only feeds the problem note.
     */
    fun parse(text: String?, stopReason: String = ""): JarvisReply {
        val t = text.orEmpty().trim()
        if (t.isEmpty()) return JarvisReply(emptyList(), "", problem = "empty answer")
        val cut = if (stopReason == "max_tokens") " (cut off at the token limit)" else ""
        val json = firstJsonObject(t)
        val strict = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (strict != null) {
            val r = fromJson(strict)
            if (r.usable || r.clarify.isNotBlank()) return r
        }
        val arr = if (strict == null) firstJsonArray(t)?.let { runCatching { JSONArray(it) }.getOrNull() } else null
        if (arr != null) {
            val r = fromJson(JSONObject().put("guesses", arr))
            if (r.usable) return r
        }
        val salvaged = salvage(t)
        if (salvaged.usable) return salvaged.copy(problem = "")
        val prose = fromProse(t)
        if (prose.isNotEmpty()) return JarvisReply(prose, "", person = salvaged.person)
        val why = when {
            strict != null -> "answer had no titles"
            json == null && t.indexOf('{') < 0 -> "answer had no JSON"
            else -> "answer JSON did not parse"
        }
        return JarvisReply(emptyList(), salvaged.clarify, problem = why + cut)
    }

    private fun fromJson(o: JSONObject): JarvisReply {
        val arr = o.optJSONArray("guesses") ?: o.optJSONArray("titles") ?: o.optJSONArray("results")
            ?: o.optJSONArray("matches") ?: JSONArray()
        val guesses = (0 until arr.length()).mapNotNull { i ->
            val g = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).trim().takeIf { it.isNotEmpty() }
                ?.let { JarvisGuess(it, null, null, 50, "") }
            val title = (g.optString("title").ifBlank { g.optString("name") }).trim()
            if (title.isEmpty()) return@mapNotNull null
            JarvisGuess(
                title,
                year(g.opt("year")?.toString()),
                type(g.optString("type")),
                confidence(g.opt("confidence")?.toString()),
                g.optString("why").trim().take(200),
            )
        }.take(MAX_GUESSES)
        val person = (o.optString("person").ifBlank { o.optString("actor") }).trim().take(80).let { if (it.equals("null", true)) "" else it }
        return JarvisReply(guesses, clarify(o.optString("clarify")), person)
    }

    /** Field-by-field reading for JSON that will not parse: one guess per "title" key. */
    private fun salvage(t: String): JarvisReply {
        val titles = TITLE_RX.findAll(t).toList()
        val guesses = titles.mapIndexedNotNull { i, m ->
            val title = unescape(m.groupValues[1]).trim()
            if (title.isEmpty()) return@mapIndexedNotNull null
            val end = if (i + 1 < titles.size) titles[i + 1].range.first else t.length
            val seg = t.substring(m.range.last + 1, end)
            JarvisGuess(
                title,
                year(YEAR_RX.find(seg)?.groupValues?.get(1)),
                type(TYPE_RX.find(seg)?.groupValues?.get(1).orEmpty()),
                confidence(CONF_RX.find(seg)?.groupValues?.get(1)),
                WHY_RX.find(seg)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(200).orEmpty(),
            )
        }.take(MAX_GUESSES)
        val person = PERSON_RX.find(t)?.groupValues?.get(1)?.let(::unescape)?.trim()?.take(80).orEmpty()
        val clarify = CLARIFY_RX.find(t)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        return JarvisReply(guesses, clarify(clarify), person)
    }

    /** "1. There Will Be Blood (2007)" list lines, or "*Lincoln* (2012)" anywhere, in a prose answer. */
    private fun fromProse(t: String): List<JarvisGuess> =
        (PROSE_LIST_RX.findAll(t) + PROSE_EM_RX.findAll(t)).mapNotNull { m ->
            val title = m.groupValues[1].trim().trim('*', '_', '"', ' ').trim()
            if (title.length < 2) null else JarvisGuess(title, year(m.groupValues[2]), null, 50, "")
        }.distinctBy { nameKey(it.title) }.take(MAX_GUESSES).toList()

    private fun year(s: String?): Int? = s?.trim()?.take(4)?.toIntOrNull()?.takeIf { it in 1870..2100 }

    private fun type(s: String): String? = when (s.trim().lowercase(Locale.US)) {
        "movie", "film" -> "movie"
        "tv", "show", "series", "tv series", "tv show", "miniseries" -> "tv"
        else -> null
    }

    private fun confidence(s: String?): Int {
        val raw = s?.trim()?.toDoubleOrNull()?.takeIf { !it.isNaN() } ?: 50.0
        return (if (raw > 0.0 && raw <= 1.0) raw * 100.0 else raw).toInt().coerceIn(0, 100)
    }

    private fun clarify(s: String): String = s.trim().take(240).let { if (it.equals("null", ignoreCase = true)) "" else it }

    private fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    private val TITLE_RX = Regex("\"(?:title|name)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val YEAR_RX = Regex("\"year\"\\s*:\\s*\"?(\\d{4})")
    private val TYPE_RX = Regex("\"type\"\\s*:\\s*\"([A-Za-z ]+)\"")
    private val CONF_RX = Regex("\"confidence\"\\s*:\\s*\"?([0-9.]+)")
    // A "why" may hold stray unescaped quotes: read up to the quote that ends the field.
    private val WHY_RX = Regex("\"why\"\\s*:\\s*\"(.*?)\"\\s*(?:,\\s*\"[a-z]+\"\\s*:|\\}|$)", RegexOption.DOT_MATCHES_ALL)
    private val PERSON_RX = Regex("\"(?:person|actor)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)+)\"")
    private val CLARIFY_RX = Regex("\"clarify\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val PROSE_LIST_RX = Regex("(?m)^[ \\t]*(?:[-*•]|\\d{1,2}[.)])[ \\t]+\\**([^\\n(*]{2,80}?)\\**[ \\t]*\\((\\d{4})")
    private val PROSE_EM_RX = Regex("(?:\\*{1,2}|_|\"|“)([^*_\"“”\\n]{2,80}?)(?:\\*{1,2}|_|\"|”)[ \\t]*\\((\\d{4})")

    /** The first balanced {...} block in [s] (braces inside JSON strings are skipped), or null. */
    fun firstJsonObject(s: String): String? = firstBalanced(s, '{', '}')

    private fun firstJsonArray(s: String): String? = firstBalanced(s, '[', ']')

    private fun firstBalanced(s: String, open: Char, close: Char): String? {
        val start = s.indexOf(open)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
        }
        return null
    }

    // ---------------- Plain search helpers ----------------

    /** Letters and digits only, lower case: "Daniel day Lewis" and "Daniel Day-Lewis" both read "danieldaylewis". */
    fun nameKey(s: String): String = s.lowercase(Locale.US).filter { it.isLetterOrDigit() }

    /**
     * Spellings to try for a person search: the question as typed, then with hyphens between the last words
     * ("Daniel day Lewis" -> "Daniel Day-Lewis"), since TMDB matches hyphenated names only loosely.
     */
    fun nameVariants(q: String): List<String> {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        val out = linkedSetOf(words.joinToString(" "))
        if (words.size in 3..4) {
            out += (words.dropLast(2) + (words[words.size - 2] + "-" + words.last())).joinToString(" ")
        }
        return out.toList()
    }

    /**
     * Which of TMDB's people (names, most popular first) [q] names, or -1. A name that matches once spaces,
     * hyphens and case are ignored wins; else, for a name-like [q], the first person whose name holds every
     * word of [q] (so "day lewis" finds Daniel Day-Lewis).
     */
    fun pickPerson(q: String, names: List<String>): Int {
        val want = nameKey(q)
        if (want.isEmpty()) return -1
        names.indexOfFirst { nameKey(it) == want }.let { if (it >= 0) return it }
        if (!looksLikeName(q)) return -1
        val words = q.trim().split(Regex("[\\s-]+")).map(::nameKey).filter { it.isNotEmpty() }
        return names.indexOfFirst { n ->
            val parts = n.split(Regex("[\\s-]+")).map(::nameKey)
            words.all { it in parts }
        }
    }

    /** True when [q] could be a person's name: 2 to 4 words, letters (and . ' -) only. */
    fun looksLikeName(q: String): Boolean {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 2..4 && words.all { w -> w.all { it.isLetter() || it == '.' || it == '\'' || it == '-' } }
    }
}

/**
 * The Ask Jarvis flow, free of Android and TMDB so tests can drive it. [M] is a found title.
 *  - [askAi] returns the raw API reply, or throws [JarvisException] (bad key, offline, busy).
 *  - [resolve] turns guesses into titles.
 *  - [plain] is the plain search: a person's best-known titles, else titles by name. [plain] gets the
 *    person Jarvis named, or "" to use the question itself.
 *  - [note] records why the AI answer was not used (Settings > About and help).
 * A readable answer wins. Otherwise the plain search answers, so a name never ends on an error screen.
 * An AI error is still shown (a rejected key needs fixing), next to the plain results.
 */
internal object JarvisFlow {
    data class Outcome<M>(val matches: List<M>, val clarify: String, val fallback: Boolean, val problem: String)

    const val FELL_BACK = "I could not work that one out, sir, so here are direct matches."

    suspend fun <M> run(
        q: String,
        askAi: suspend () -> JarvisRaw,
        resolve: suspend (List<JarvisGuess>) -> List<M>,
        plain: suspend (personHint: String) -> List<M>,
        note: (String) -> Unit = {},
    ): Outcome<M> {
        var problem: String
        var hint = ""
        var aiClarify = ""
        var aiError: JarvisException? = null
        try {
            val raw = askAi()
            val reply = if (raw.problem.isNotBlank()) JarvisReply(emptyList(), "", problem = raw.problem)
            else JarvisReplyParser.parse(raw.text, raw.stopReason)
            hint = reply.person
            aiClarify = reply.clarify
            if (reply.guesses.isNotEmpty()) {
                val found = resolve(reply.guesses)
                if (found.isNotEmpty()) return Outcome(found, reply.clarify, fallback = false, problem = "")
            }
            problem = reply.problem.ifBlank { if (reply.guesses.isEmpty()) "answer had no titles" else "no guess matched a title" }
            // A polite question back about a description: keep it. A name always gets the plain search.
            if (hint.isBlank() && reply.guesses.isEmpty() && reply.clarify.isNotBlank() && !JarvisReplyParser.looksLikeName(q)) {
                return Outcome(emptyList(), reply.clarify, fallback = false, problem = "")
            }
        } catch (e: JarvisException) {
            aiError = e
            problem = e.message.orEmpty()
        }
        note("Ask Jarvis used plain search for \"${q.take(60)}\": $problem")
        val found = try {
            plain(hint)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw aiError ?: JarvisException(e.message ?: "Jarvis could not search right now. Try again.")
        }
        if (found.isEmpty()) {
            aiError?.let { throw it }
            return Outcome(emptyList(), aiClarify, fallback = true, problem = problem)
        }
        val line = when {
            aiError != null -> "${aiError.message} Here are direct matches."
            hint.isNotBlank() && aiClarify.isNotBlank() -> aiClarify
            else -> FELL_BACK
        }
        return Outcome(found, line, fallback = true, problem = problem)
    }
}
