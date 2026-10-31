package com.mcd.tv.data

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

/**
 * Finds McD-Server on the home network by its Bonjour name, so the house guide keeps working when the router
 * hands the Mac a new address. J does not want to touch the Mac or the router for this (Oct 31, 2026), and the
 * planned fixed address 192.168.1.50 was never set.
 *
 * One multicast DNS question ("A record of johns-macbook-air-2.local?") goes to 224.0.0.251:5353 from an
 * ordinary port; macOS answers such a query straight back to that port (RFC 6762, section 6.7), so no
 * multicast lock or extra permission is needed. Fire OS (Android 9 and 11) does not resolve ".local" names
 * itself. The answer is kept for 10 minutes. Packet building and reading are pure and tested (HouseHostTest).
 */
object HouseHost {
    /** McD-Server's Bonjour name (System Settings > General > Sharing > Local hostname). */
    const val NAME = "johns-macbook-air-2.local"
    const val PORT = 8787

    private const val CACHE_MS = 10 * 60_000L
    @Volatile private var cached: String? = null
    @Volatile private var cachedAt = 0L

    /** The house guide's address now: McD-Server's current IPv4 address, or null when it does not answer. */
    fun guideUrl(timeoutMs: Int = 1500): String? = resolve(timeoutMs)?.let { "http://$it:$PORT/huntsville.xml.gz" }

    /** McD-Server's IPv4 address from multicast DNS, or null. Blocking; call it off the main thread. */
    fun resolve(timeoutMs: Int = 1500): String? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < CACHE_MS) return it }
        val ip = runCatching { ask(NAME, timeoutMs) }.getOrNull()
        if (ip != null) { cached = ip; cachedAt = now }
        return ip
    }

    private fun ask(name: String, timeoutMs: Int): String? = askAt(name, InetAddress.getByName("224.0.0.251"), 5353, timeoutMs)

    /** One query to [to]:[port] (the mDNS group in use; a local responder in tests). */
    internal fun askAt(name: String, to: InetAddress, port: Int, timeoutMs: Int): String? {
        val id = (System.nanoTime() and 0xFFFF).toInt()
        val q = query(name, id)
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(q, q.size, to, port))
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1500)
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { return null }
                answer(buf.copyOf(p.length), name)?.let { return it }
            }
        }
        return null
    }

    // ---------------------------------------------------------------- pure parts

    /** A one-question DNS query (type A, class IN) for [name] with message [id]. */
    fun query(name: String, id: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8 and 0xFF); out.write(id and 0xFF)
        out.write(0); out.write(0) // flags: standard query
        out.write(0); out.write(1) // one question
        repeat(6) { out.write(0) } // no answers, authority or additional records
        for (label in name.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1) // type A
        out.write(0); out.write(1) // class IN
        return out.toByteArray()
    }

    /** The IPv4 address an mDNS or DNS response gives [name] (first A record), or null. */
    fun answer(msg: ByteArray, name: String): String? {
        if (msg.size < 12) return null
        val qd = u16(msg, 4)
        val records = u16(msg, 6) + u16(msg, 8) + u16(msg, 10)
        var pos = 12
        repeat(qd) {
            pos = skipName(msg, pos) ?: return null
            pos += 4
        }
        val want = name.trimEnd('.').lowercase(Locale.ROOT)
        repeat(records) {
            val (n, after) = readName(msg, pos) ?: return null
            pos = after
            if (pos + 10 > msg.size) return null
            val type = u16(msg, pos)
            val len = u16(msg, pos + 8)
            pos += 10
            if (pos + len > msg.size) return null
            if (type == 1 && len == 4 && n.lowercase(Locale.ROOT) == want) {
                return (0 until 4).joinToString(".") { (msg[pos + it].toInt() and 0xFF).toString() }
            }
            pos += len
        }
        return null
    }

    private fun u16(b: ByteArray, i: Int): Int = if (i + 1 < b.size) (b[i].toInt() and 0xFF shl 8) or (b[i + 1].toInt() and 0xFF) else 0

    private fun skipName(b: ByteArray, start: Int): Int? = readName(b, start)?.second

    /** A (possibly compressed) DNS name at [start]: the name and the position after it. */
    private fun readName(b: ByteArray, start: Int): Pair<String, Int>? {
        val parts = ArrayList<String>()
        var pos = start
        var after = -1
        var jumps = 0
        while (true) {
            if (pos >= b.size) return null
            val len = b[pos].toInt() and 0xFF
            when {
                len == 0 -> { if (after < 0) after = pos + 1; break }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= b.size || ++jumps > 16) return null
                    if (after < 0) after = pos + 2
                    pos = ((len and 0x3F) shl 8) or (b[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + len > b.size) return null
                    parts.add(String(b, pos + 1, len, Charsets.US_ASCII))
                    pos += 1 + len
                }
            }
        }
        return parts.joinToString(".") to after
    }
}
