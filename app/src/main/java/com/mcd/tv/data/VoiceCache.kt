package com.mcd.tv.data

import java.io.File
import java.security.MessageDigest

/**
 * Jarvis voice: spoken lines saved on this TV, so a line is paid for once. One MP3 per line in
 * `filesDir/voice/`, named by a SHA-256 of what makes the audio (model, voice, speed, format, text).
 * Never in the repo or the APK. Plain java.io only; tests in VoiceCacheTest use a temp folder.
 */
object VoiceCache {
    /** Oldest files go first past this size. About 5,000 short lines. */
    const val CAP_BYTES = 40L * 1024 * 1024

    fun key(model: String, voiceId: String, speed: Double, text: String): String {
        val src = "v1|$model|$voiceId|$speed|mp3-64|$text"
        return MessageDigest.getInstance("SHA-256").digest(src.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun file(dir: File, key: String) = File(dir, "$key.mp3")

    /** The saved audio for [key], or null. A hit is marked as recently used. */
    fun get(dir: File, key: String): File? {
        val f = file(dir, key)
        if (!f.isFile || f.length() == 0L) return null
        runCatching { f.setLastModified(System.currentTimeMillis()) }
        return f
    }

    fun has(dir: File, key: String): Boolean = file(dir, key).let { it.isFile && it.length() > 0L }

    /** Saves [bytes] for [key]: a temp file, then a rename, so a half write is never played. */
    fun put(dir: File, key: String, bytes: ByteArray): File {
        dir.mkdirs()
        val tmp = File(dir, "$key.tmp")
        tmp.writeBytes(bytes)
        val f = file(dir, key)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        trim(dir, CAP_BYTES)
        return f
    }

    data class Entry(val name: String, val size: Long, val modified: Long)

    /** Names to delete, oldest first, until the total fits under [cap]. */
    fun evictPlan(entries: List<Entry>, cap: Long): List<String> {
        var total = entries.sumOf { it.size }
        if (total <= cap) return emptyList()
        val out = mutableListOf<String>()
        for (e in entries.sortedBy { it.modified }) {
            if (total <= cap) break
            out += e.name
            total -= e.size
        }
        return out
    }

    fun trim(dir: File, cap: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".tmp") && System.currentTimeMillis() - it.lastModified() > 60_000L }.forEach { it.delete() }
        val mp3 = files.filter { it.name.endsWith(".mp3") }
        evictPlan(mp3.map { Entry(it.name, it.length(), it.lastModified()) }, cap).forEach { File(dir, it).delete() }
    }
}
