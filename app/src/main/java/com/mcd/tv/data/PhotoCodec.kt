package com.mcd.tv.data

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Profile photos (Settings > Profile > Profile photos in the web app). A photo is a small square JPEG (192 px,
 * made on the phone) kept as standard base64 text. It is never stored in the repo or on a server: each device
 * keeps its own copy, and copies travel in pieces, encrypted, over the relay (phone to TV) and ProfileSync
 * (TV to TV, META entries [HEADER_KEY] and [pieceKey]).
 *
 * Pure logic (no Android), so it is unit tested. Storage and decoding are in ProfilePhotos.kt. The web app
 * (PH in docs/app/index.html) uses the same piece size and the same version.
 */
object PhotoCodec {
    /** Largest photo accepted, in base64 characters (about 24 KB of JPEG; a 192 px photo is usually 8 to 18 KB). */
    const val MAX_CHARS = 32_000
    /** Characters per piece. One piece fits in one ntfy message (4 KB) with room to spare. */
    const val CHUNK = 1600
    const val MAX_PARTS = (MAX_CHARS + CHUNK - 1) / CHUNK

    /** ProfileSync META key of the photo header: {"v": version, "n": pieces}. Deleted = no photo. */
    const val HEADER_KEY = "photo"

    /** ProfileSync META key of piece [i]. Its value is "<version>:<base64 piece>". */
    fun pieceKey(i: Int) = "photo:$i"

    fun pieceValue(v: String, piece: String) = "$v:$piece"

    /** The photo's version: the first 16 hex characters of the SHA-256 of its base64 text. */
    fun version(b64: String): String =
        MessageDigest.getInstance("SHA-256").digest(b64.toByteArray(Charsets.US_ASCII))
            .take(8).joinToString("") { "%02x".format(it) }

    fun isVersion(v: String): Boolean = v.length == 16 && v.all { it in '0'..'9' || it in 'a'..'f' }

    private fun isB64Char(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'

    /** Standard base64 of a JPEG ("/9j/" is FF D8 FF) within [MAX_CHARS]. */
    fun isValid(b64: String): Boolean {
        if (b64.length !in 8..MAX_CHARS || b64.length % 4 != 0 || !b64.startsWith("/9j/")) return false
        val body = b64.trimEnd('=')
        return b64.length - body.length <= 2 && body.all(::isB64Char)
    }

    fun split(b64: String): List<String> = b64.chunked(CHUNK)

    fun header(v: String, n: Int): String = JSONObject().put("v", v).put("n", n).toString()

    /** (version, pieces) from a header value, or null when it is not a valid header. */
    fun parseHeader(s: String?): Pair<String, Int>? = runCatching {
        val o = JSONObject(s ?: return null)
        val v = o.optString("v")
        val n = o.optInt("n", 0)
        if (isVersion(v) && n in 1..MAX_PARTS) v to n else null
    }.getOrNull()

    /**
     * The photo of version [v] in [n] pieces, from [piece] (index to stored piece value, null when missing).
     * Null until every piece of that version is there and the joined text checks out.
     */
    fun join(v: String, n: Int, piece: (Int) -> String?): String? {
        if (!isVersion(v) || n !in 1..MAX_PARTS) return null
        val sb = StringBuilder()
        for (i in 0 until n) {
            val s = piece(i) ?: return null
            if (!s.startsWith("$v:")) return null
            sb.append(s, v.length + 1, s.length)
        }
        val b64 = sb.toString()
        return b64.takeIf { isValid(it) && version(it) == v }
    }

    /**
     * The META writes (key, value or null to delete) for a new photo of version [v] in [pieces], or for a removal
     * ([v] null). Pieces first, then pieces an older, longer photo left behind ([live]: piece i holds a value now)
     * are deleted, then the header.
     */
    fun writes(v: String?, pieces: List<String>, live: (Int) -> Boolean): List<Pair<String, String?>> {
        val keep = if (v == null) 0 else pieces.size
        val out = ArrayList<Pair<String, String?>>()
        if (v != null) pieces.forEachIndexed { i, s -> out.add(pieceKey(i) to pieceValue(v, s)) }
        (keep until MAX_PARTS).filter(live).forEach { out.add(pieceKey(it) to null) }
        out.add(HEADER_KEY to v?.let { header(it, pieces.size) })
        return out
    }

    /**
     * The photo the META entries hold: its base64 text, "" when the header is deleted (photo removed), or null
     * when there is nothing to show yet (no header, or pieces of its version still missing).
     * [headerDeleted]: the header is a tombstone. [get]: a key's value, null when missing or deleted.
     */
    fun read(headerDeleted: Boolean, get: (String) -> String?): String? {
        if (headerDeleted) return ""
        val (v, n) = parseHeader(get(HEADER_KEY)) ?: return null
        return join(v, n) { get(pieceKey(it)) }
    }
}

/**
 * Pieces of photos coming from the phone ("profile_photo" relay commands), one photo per profile at a time.
 * A new version for a profile starts over; pieces older than [ttlMs] are dropped.
 */
class PhotoAssembler(private val ttlMs: Long = 10 * 60_000L) {
    private class Pending(val v: String, val parts: Array<String?>, var at: Long)
    private val pending = HashMap<String, Pending>()

    /** Adds one piece. Returns the whole photo (base64) when the last piece of a valid photo arrives, else null. */
    @Synchronized
    fun add(profile: String, v: String, part: Int, parts: Int, data: String, now: Long): String? {
        if (!PhotoCodec.isVersion(v) || parts !in 1..PhotoCodec.MAX_PARTS || part !in 0 until parts) return null
        if (data.isEmpty() || data.length > PhotoCodec.CHUNK) return null
        pending.entries.removeAll { now - it.value.at > ttlMs }
        var p = pending[profile]
        if (p == null || p.v != v || p.parts.size != parts) {
            p = Pending(v, arrayOfNulls(parts), now)
            pending[profile] = p
        }
        p.parts[part] = data
        p.at = now
        if (p.parts.any { it == null }) return null
        pending.remove(profile)
        val b64 = p.parts.joinToString("")
        return b64.takeIf { PhotoCodec.isValid(it) && PhotoCodec.version(it) == v }
    }

    @Synchronized
    fun pendingCount(): Int = pending.size
}
