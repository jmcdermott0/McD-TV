package com.mcd.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

/** One Ask Jarvis result: the TMDB title, Jarvis's one-line reason and its confidence (0..100). */
data class JarvisMatch(val title: Title, val why: String, val confidence: Int)

/**
 * Everything Jarvis answered: matched titles (most likely first) and an optional spoken line.
 * [plain] = Jarvis's own answer could not be used, so these come from a plain name search.
 */
data class JarvisAnswer(val matches: List<JarvisMatch>, val clarify: String, val plain: Boolean = false)

/**
 * Ask Jarvis: the user describes a movie or show in plain words and gets back best guesses.
 * Calls the Anthropic Messages API directly with the key saved on this TV (Prefs.jarvisKey), then
 * resolves each guess to a TMDB title. The UI only ever calls this feature "Jarvis".
 */
object Jarvis {
    private const val API_URL = "https://api.anthropic.com/v1/messages"
    private const val API_VERSION = "2023-06-01"
    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    private const val FALLBACK_MODEL = "claude-haiku-5-5"
    private const val TIMEOUT_MS = 20_000
    /** The one retry only runs when the first try failed within this time, so the wait stays short. */
    private const val RETRY_WITHIN_MS = 12_000L
    private const val MAX_TOKENS = 1200
    private const val MAX_PLAIN = 12
    private const val MAX_QUESTION = 600

    const val NO_KEY = "Add your Jarvis key on the Control page to use Ask Jarvis."
    private const val BAD_KEY = "Your Jarvis key was rejected. Check it on the Control page."
    private const val BUSY = "Jarvis is busy. Try again in a moment."
    private const val OFFLINE = "Jarvis could not be reached. Check the TV's internet connection."
    private const val SLOW = "Jarvis took too long to answer. Try again."
    private const val DOWN = "Jarvis is unavailable right now. Try again in a moment."

    private val SYSTEM_PROMPT = """
        You are Jarvis, the user's home media assistant. You speak like a refined British butler who
        happens to be an AI: calm, precise, impeccably polite, with dry understated wit. You address the
        user as "sir". Never quote films or reuse famous movie lines; every line is your own.
        Your task: identify films and TV series from vague descriptions (half-remembered plots, actors,
        scenes, quotes, settings).
        Return ONLY a JSON object, no prose and no code fences, exactly in this shape:
        {"guesses":[{"title":"Iron Man","year":2008,"type":"movie","confidence":92,"why":"one short sentence"}],"person":"","clarify":"one short spoken line"}
        Rules:
        - Up to 6 guesses, ordered from most to least likely.
        - "title" is the official English release title. "year" is the release year (first air year for TV).
        - "type" is "movie" or "tv". "confidence" is an integer from 0 to 100.
        - "why" is one short sentence, in your voice, saying how the title matches the description.
        - Never invent titles. Only list real, released films and series.
        - If the description names a person or asks for a genre or mood, return the most fitting titles.
        - If the question is just a person's name (actor, director), set "person" to their full, correctly
          spelled name and list their best-known titles. Otherwise "person" is "".
        - Never put double quotes inside a "why" or "clarify" text.
        - "clarify" is ALWAYS one short spoken line in your voice (under 25 words). If you are confident,
          present your top pick with a touch of wit. If the description is too vague, ask one polite
          follow-up question instead.
    """.trimIndent()

    /** True when a key is saved on this TV. */
    val configured: Boolean get() = Prefs.jarvisKey.isNotBlank()

    /** Set after the configured model was refused once, so later questions go straight to the fallback. */
    @Volatile private var modelOverride: String? = null

    // ---- Small LRU of the last 20 questions -> answers (in memory only) ----
    private const val CACHE_MAX = 20
    private val cache = object : LinkedHashMap<String, JarvisAnswer>(CACHE_MAX + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JarvisAnswer>?): Boolean = size > CACHE_MAX
    }

    private fun cacheKey(q: String) = q.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()

    /**
     * Asks Jarvis about [question]. Throws [JarvisException] with a friendly message only when nothing at all
     * can be shown. An answer Jarvis cannot read, or no answer, falls back to a plain TMDB search: a person's
     * best-known titles, else titles by name. The reason is kept in Settings > About and help.
     * The origin setting is not applied to the results (the user asked for them); adult titles never appear.
     */
    suspend fun ask(question: String): JarvisAnswer {
        val q = question.trim().take(MAX_QUESTION)
        if (q.isEmpty()) throw JarvisException("Describe a movie or show first.")
        val key = Prefs.jarvisKey
        if (key.isBlank()) throw JarvisException(NO_KEY)
        val ck = cacheKey(q)
        synchronized(cache) { cache[ck] }?.let { return it }

        val out = JarvisFlow.run(
            q,
            askAi = { call(key, q) },
            resolve = { resolve(it) },
            plain = { hint -> plainSearch(q, hint) },
            note = { msg ->
                android.util.Log.w("Jarvis", msg)
                RecentErrors.note(msg)
            },
        )
        val answer = JarvisAnswer(out.matches, out.clarify, out.fallback)
        // Only a real answer is kept: a fallback should try Jarvis again next time.
        if (!out.fallback && (out.matches.isNotEmpty() || out.clarify.isNotBlank())) synchronized(cache) { cache[ck] = answer }
        return answer
    }

    /** The plain search behind Ask Jarvis: the person [hint] (or [q]) names, else titles by name. */
    private suspend fun plainSearch(q: String, hint: String): List<JarvisMatch> {
        val hits = Tmdb.searchAll(q)
        val who = hint.ifBlank { q }
        val person = Tmdb.personByName(who, hits.people)
            ?: if (hint.isNotBlank()) Tmdb.personByName(q, hits.people) else null
        if (person != null) {
            val p = Tmdb.person(person.id)
            val films = Tmdb.knownFor(p, MAX_PLAIN)
            if (films.isNotEmpty()) return films.map { JarvisMatch(it, "One of ${p.name}'s best-known titles.", 70) }
        }
        return hits.titles.distinctBy { "${it.type}:${it.id}" }.take(MAX_PLAIN).map { JarvisMatch(it, "Matches the name you typed.", 60) }
    }

    // ---------------- API call ----------------

    /**
     * One API call with one retry: on a timeout, a dropped connection, a busy or failing server, or a reply that
     * cannot be read. A bad key is never retried. The retry is skipped when the first try already took long.
     */
    private suspend fun call(key: String, q: String): JarvisRaw {
        val started = System.currentTimeMillis()
        val first = try {
            callOnce(key, q)
        } catch (e: RetryableException) {
            if (System.currentTimeMillis() - started > RETRY_WITHIN_MS) throw JarvisException(e.message.orEmpty())
            return retry(key, q, e.message.orEmpty())
        }
        if (first.problem.isBlank()) {
            val r = JarvisReplyParser.parse(first.text, first.stopReason)
            if (r.usable || r.clarify.isNotBlank()) return first
        }
        if (System.currentTimeMillis() - started > RETRY_WITHIN_MS) return first
        return runCatching { retry(key, q, first.problem) }.getOrElse { e ->
            if (e is CancellationException) throw e
            first
        }.let { second -> if (second.problem.isBlank()) second else first }
    }

    private suspend fun retry(key: String, q: String, why: String): JarvisRaw {
        android.util.Log.w("Jarvis", "retrying once: $why")
        return try {
            callOnce(key, q)
        } catch (e: RetryableException) {
            throw JarvisException(e.message.orEmpty())
        }
    }

    /** A failure worth one more try (timeout, network, busy or failing server). The message is ready to show. */
    private class RetryableException(message: String) : Exception(message)

    private suspend fun callOnce(key: String, q: String): JarvisRaw {
        var model = modelOverride ?: Prefs.jarvisModel.ifBlank { DEFAULT_MODEL }
        var withTemperature = true
        var triedFallback = false
        var triedNoTemp = false
        while (true) {
            try {
                return JarvisReplyParser.content(post(key, q, model, withTemperature))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                val msg = e.message.orEmpty().lowercase(Locale.US)
                // Unknown / retired model: fall back once to the smaller model.
                if ((e.code == 404 || e.code == 400) && "model" in msg && !triedFallback && model != FALLBACK_MODEL) {
                    triedFallback = true
                    model = FALLBACK_MODEL
                    modelOverride = FALLBACK_MODEL
                    continue
                }
                // A model that does not take a temperature setting: retry once without it.
                if (e.code == 400 && "temperature" in msg && !triedNoTemp) {
                    triedNoTemp = true
                    withTemperature = false
                    continue
                }
                if (e.code == 429 || e.code == 529 || e.code in 500..599) throw RetryableException(friendly(e.code))
                throw JarvisException(friendly(e.code))
            } catch (e: SocketTimeoutException) {
                throw RetryableException(SLOW)
            } catch (e: UnknownHostException) {
                throw JarvisException(OFFLINE)
            } catch (e: IOException) {
                throw RetryableException(OFFLINE)
            }
        }
    }

    private fun friendly(code: Int): String = when (code) {
        401, 403 -> BAD_KEY
        429, 529 -> BUSY
        in 500..599 -> DOWN
        else -> "Jarvis could not answer that (error $code). Try describing it another way."
    }

    /** The raw reply body. */
    private suspend fun post(key: String, q: String, model: String, withTemperature: Boolean): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", MAX_TOKENS)
            .put("system", SYSTEM_PROMPT)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", q)))
        if (withTemperature) body.put("temperature", 0.2)
        return Http.postJson(
            API_URL,
            body.toString(),
            headers = mapOf("x-api-key" to key, "anthropic-version" to API_VERSION),
            timeoutMs = TIMEOUT_MS,
        )
    }

    // ---------------- TMDB lookup ----------------

    private suspend fun resolve(guesses: List<JarvisGuess>): List<JarvisMatch> {
        if (guesses.isEmpty()) return emptyList()
        var firstError: Throwable? = null
        val found = coroutineScope {
            guesses.map { g ->
                async {
                    try {
                        val types = if (g.type != null) listOf(g.type) else listOf("movie", "tv")
                        var t: Title? = null
                        for (type in types) {
                            t = Tmdb.findTitle(type, g.title, g.year)
                            if (t != null) break
                        }
                        t?.let { JarvisMatch(it, g.why, g.confidence) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        synchronized(this@Jarvis) { if (firstError == null) firstError = e }
                        null
                    }
                }
            }.awaitAll()
        }
        val matches = found.filterNotNull().distinctBy { "${it.title.type}:${it.title.id}" }
        // Every lookup failed (no TMDB key, no internet): say why instead of "no matches".
        if (matches.isEmpty()) firstError?.let { throw JarvisException(it.message ?: OFFLINE) }
        return matches
    }
}
