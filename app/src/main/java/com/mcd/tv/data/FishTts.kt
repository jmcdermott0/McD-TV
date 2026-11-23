package com.mcd.tv.data

import com.mcd.tv.data.VoiceMachine.Failure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jarvis voice: the speech service client (REST, one whole MP3 per line). The request and the error mapping are
 * pure and tested in FishTtsTest; [fetch] does the network call. The key is sent only in the Authorization
 * header and is never logged.
 */
object FishTts {
    const val URL_TTS = "https://api.fish.audio/v1/tts"
    const val DEFAULT_MODEL = "s2.1-pro"
    const val SPEED = 1.1
    private const val CONNECT_MS = 3_000
    private const val READ_MS = 8_000
    /** A spoken line of 300 characters at 64 kbps is well under this. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    fun body(text: String, voiceId: String, speed: Double = SPEED): String = JSONObject()
        .put("text", text)
        .put("reference_id", voiceId)
        .put("format", "mp3")
        .put("mp3_bitrate", 64)
        .put("latency", "balanced")
        .put("normalize", true)
        .put("prosody", JSONObject().put("speed", speed).put("volume", 0))
        .toString()

    fun headers(key: String, model: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $key",
        "model" to model.ifBlank { DEFAULT_MODEL },
        "Content-Type" to "application/json",
        "Accept" to "audio/mpeg",
    )

    /** What an error reply means for the speaker. */
    fun failure(code: Int, body: String): Failure {
        val b = body.lowercase()
        return when {
            code == 401 || code == 403 -> Failure.KEY
            code == 402 -> Failure.CREDIT
            code == 404 -> Failure.VOICE
            (code == 400 || code == 422) && ("reference" in b || "voice" in b || "model not found" in b) -> Failure.VOICE
            code == 429 || code >= 500 -> Failure.BUSY
            else -> Failure.OTHER
        }
    }

    /** True when [bytes] look like MP3 audio (an ID3 tag or an MPEG frame sync), not a JSON error with status 200. */
    fun looksLikeMp3(bytes: ByteArray): Boolean {
        if (bytes.size < 256) return false
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return true
        return bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0
    }

    sealed interface Result {
        class Ok(val mp3: ByteArray) : Result
        data class Fail(val failure: Failure, val code: Int) : Result
    }

    /** One line of speech. Never throws: every problem comes back as [Result.Fail]. */
    suspend fun fetch(key: String, model: String, voiceId: String, text: String): Result = withContext(Dispatchers.IO) {
        try {
            val c = URL(URL_TTS).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = CONNECT_MS
                c.readTimeout = READ_MS
                c.doOutput = true
                headers(key, model).forEach { (k, v) -> c.setRequestProperty(k, v) }
                c.outputStream.use { it.write(body(text, voiceId).toByteArray()) }
                val code = c.responseCode
                if (code !in 200..299) {
                    val err = runCatching { c.errorStream?.use { String(it.readBytes().take(4096).toByteArray()) } }.getOrNull().orEmpty()
                    return@withContext Result.Fail(failure(code, err), code)
                }
                val out = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return@withContext Result.Fail(Failure.OTHER, code)
                    }
                }
                val bytes = out.toByteArray()
                if (looksLikeMp3(bytes)) Result.Ok(bytes) else Result.Fail(Failure.OTHER, code)
            } finally {
                c.disconnect()
            }
        } catch (_: IOException) {
            Result.Fail(Failure.OFFLINE, 0)
        } catch (_: Exception) {
            Result.Fail(Failure.OTHER, 0)
        }
    }
}
