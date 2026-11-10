package com.mcd.tv.data

/**
 * Chapter markers read from the first bytes of a video file. Media3 1.5.1 ignores them (its Matroska and MP4
 * extractors have no chapter code), so Jarvis reads them itself from the bytes the player already loaded
 * (player/HeaderTap.kt): no extra request, no extra connection to a one-stream IPTV account.
 *
 * Matroska: Segment > Chapters > EditionEntry > ChapterAtom (mkvmerge and ffmpeg both write Chapters before the
 * first Cluster). MP4: moov > udta > chpl (Nero chapters; ffmpeg and HandBrake write it) when moov is at the front.
 * Pure: no Android, tests in ChaptersTest on files made with ffmpeg and mkvmerge.
 */
object Chapters {
    data class Chapter(val startMs: Long, val endMs: Long, val title: String)

    /**
     * What the header held. [container] is "mkv", "mp4" or "other". [laterAt] >= 0: the file says its chapters
     * sit at that byte, past the bytes read. [note] says why nothing was read ("moov at end", "cut off").
     */
    data class Scan(
        val container: String,
        val chapters: List<Chapter> = emptyList(),
        val laterAt: Long = -1L,
        val note: String = "",
    )

    fun scan(b: ByteArray): Scan = runCatching {
        when {
            b.size >= 4 && u32(b, 0) == 0x1A45DFA3L -> Mkv(b).scan()
            b.size >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> mp4(b)
            else -> Scan("other")
        }
    }.getOrElse { Scan("other", note = "unreadable") }

    // ---------------------------------------------------------------- Matroska (EBML)

    private const val ID_SEGMENT = 0x18538067L
    private const val ID_SEEKHEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POS = 0x53ACL
    private const val ID_CHAPTERS = 0x1043A770L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_EDITION = 0x45B9L
    private const val ID_EDITION_HIDDEN = 0x45BDL
    private const val ID_EDITION_DEFAULT = 0x45DBL
    private const val ID_EDITION_ORDERED = 0x45DDL
    private const val ID_ATOM = 0xB6L
    private const val ID_TIME_START = 0x91L
    private const val ID_TIME_END = 0x92L
    private const val ID_FLAG_HIDDEN = 0x98L
    private const val ID_FLAG_ENABLED = 0x4598L
    private const val ID_DISPLAY = 0x80L
    private const val ID_STRING = 0x85L

    private class El(val id: Long, val dataStart: Int, val size: Long) {
        /** Unknown size (live muxers): runs to the end of the parent. */
        val unknown get() = size < 0
        fun end(limit: Int): Int = if (unknown) limit else (dataStart + size).coerceAtMost(limit.toLong()).toInt()
        fun complete(limit: Int): Boolean = !unknown && dataStart + size <= limit
    }

    private class Mkv(val b: ByteArray) {
        /** Reads one element header at [p], or null when the bytes run out. */
        fun el(p: Int): El? {
            if (p >= b.size) return null
            val first = b[p].toInt() and 0xFF
            val idLen = Integer.numberOfLeadingZeros(first) - 23 // 1..4
            if (idLen !in 1..4 || p + idLen > b.size) return null
            var id = 0L
            for (i in 0 until idLen) id = (id shl 8) or (b[p + i].toLong() and 0xFF)
            val q = p + idLen
            if (q >= b.size) return null
            val s0 = b[q].toInt() and 0xFF
            if (s0 == 0) return null
            val sLen = Integer.numberOfLeadingZeros(s0) - 23 // 1..8
            if (q + sLen > b.size) return null
            var size = (s0 and (0xFF shr sLen)).toLong()
            var allOnes = size == (0xFF shr sLen).toLong()
            for (i in 1 until sLen) {
                val v = b[q + i].toLong() and 0xFF
                if (v != 0xFFL) allOnes = false
                size = (size shl 8) or v
            }
            return El(id, q + sLen, if (allOnes) -1L else size)
        }

        fun uint(e: El): Long {
            var v = 0L
            for (i in 0 until e.size.toInt()) v = (v shl 8) or (b[e.dataStart + i].toLong() and 0xFF)
            return v
        }

        fun children(from: Int, to: Int): List<El> {
            val out = ArrayList<El>()
            var p = from
            while (p < to) {
                val e = el(p) ?: break
                out += e
                if (e.unknown) break
                p = (e.dataStart + e.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return out
        }

        fun scan(): Scan {
            val header = el(0) ?: return Scan("mkv", note = "cut off")
            if (header.unknown) return Scan("mkv", note = "unreadable")
            val seg = el(header.dataStart + header.size.toInt()) ?: return Scan("mkv", note = "cut off")
            if (seg.id != ID_SEGMENT) return Scan("mkv", note = "no segment")
            val segEnd = seg.end(b.size)
            var later = -1L
            var p = seg.dataStart
            while (p < segEnd) {
                val e = el(p) ?: return Scan("mkv", laterAt = later, note = if (later >= 0) "" else "cut off")
                when (e.id) {
                    ID_CHAPTERS -> {
                        if (!e.complete(b.size)) return Scan("mkv", laterAt = (e.dataStart - 4).toLong(), note = "cut off")
                        return Scan("mkv", chapters(e))
                    }
                    ID_SEEKHEAD -> if (e.complete(b.size)) {
                        children(e.dataStart, e.end(b.size)).filter { it.id == ID_SEEK }.forEach { s ->
                            val kids = children(s.dataStart, s.end(b.size))
                            val sid = kids.firstOrNull { it.id == ID_SEEK_ID }?.let { uint(it) }
                            val pos = kids.firstOrNull { it.id == ID_SEEK_POS }?.let { uint(it) }
                            if (sid == ID_CHAPTERS && pos != null) later = seg.dataStart + pos
                        }
                    }
                    ID_CLUSTER -> return Scan("mkv", laterAt = later)
                }
                if (e.unknown) break
                p = (e.dataStart + e.size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            return Scan("mkv", laterAt = later, note = if (later >= 0) "" else "cut off")
        }

        fun chapters(c: El): List<Chapter> {
            val editions = children(c.dataStart, c.end(b.size)).filter { it.id == ID_EDITION }
            data class Ed(val hidden: Boolean, val default: Boolean, val ordered: Boolean, val atoms: List<Chapter>)
            val eds = editions.map { ed ->
                val kids = children(ed.dataStart, ed.end(b.size))
                fun flag(id: Long) = kids.firstOrNull { it.id == id }?.let { uint(it) == 1L } ?: false
                Ed(flag(ID_EDITION_HIDDEN), flag(ID_EDITION_DEFAULT), flag(ID_EDITION_ORDERED), kids.filter { it.id == ID_ATOM }.mapNotNull(::atom))
            }
            // Ordered editions play parts of other files (segment linking): their times are not this file's.
            val pick = eds.firstOrNull { it.default && !it.hidden } ?: eds.firstOrNull { !it.hidden } ?: return emptyList()
            if (pick.ordered) return emptyList()
            return pick.atoms.sortedBy { it.startMs }
        }

        fun atom(a: El): Chapter? {
            val kids = children(a.dataStart, a.end(b.size))
            if (kids.firstOrNull { it.id == ID_FLAG_HIDDEN }?.let { uint(it) == 1L } == true) return null
            if (kids.firstOrNull { it.id == ID_FLAG_ENABLED }?.let { uint(it) == 0L } == true) return null
            val start = kids.firstOrNull { it.id == ID_TIME_START }?.let { uint(it) } ?: return null
            val end = kids.firstOrNull { it.id == ID_TIME_END }?.let { uint(it) }
            val title = kids.filter { it.id == ID_DISPLAY }.firstNotNullOfOrNull { d ->
                children(d.dataStart, d.end(b.size)).firstOrNull { it.id == ID_STRING }
                    ?.let { String(b, it.dataStart, it.size.toInt(), Charsets.UTF_8).trimEnd('\u0000').trim() }
            } ?: ""
            return Chapter(start / 1_000_000, end?.let { it / 1_000_000 } ?: -1L, title)
        }
    }

    // ---------------------------------------------------------------- MP4 (ISO BMFF)

    private fun mp4(b: ByteArray): Scan {
        var p = 0
        while (p + 8 <= b.size) {
            var size = u32(b, p)
            val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var head = 8
            if (size == 1L && p + 16 <= b.size) { size = u64(b, p + 8); head = 16 }
            if (size == 0L) size = (b.size - p).toLong()
            if (size < head) return Scan("mp4", note = "unreadable")
            when (type) {
                "moov" -> {
                    if (p + size > b.size) return Scan("mp4", note = "cut off")
                    return Scan("mp4", chpl(b, p + head, (p + size).toInt()))
                }
                "mdat" -> return Scan("mp4", note = "moov at end")
            }
            p += size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return Scan("mp4", note = "cut off")
    }

    private fun box(b: ByteArray, from: Int, to: Int, want: String): IntArray? {
        var p = from
        while (p + 8 <= to) {
            val size = u32(b, p).toInt()
            if (size < 8 || p + size > to) return null
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == want) return intArrayOf(p + 8, p + size)
            p += size
        }
        return null
    }

    private fun chpl(b: ByteArray, from: Int, to: Int): List<Chapter> {
        val udta = box(b, from, to, "udta") ?: return emptyList()
        val c = box(b, udta[0], udta[1], "chpl") ?: return emptyList()
        var p = c[0]
        val version = b[p].toInt() and 0xFF
        p += 4 // version + flags
        if (version == 1) p += 4 // reserved
        if (p >= c[1]) return emptyList()
        val n = b[p].toInt() and 0xFF
        p += 1
        val out = ArrayList<Chapter>()
        repeat(n) {
            if (p + 9 > c[1]) return@repeat
            val start100ns = u64(b, p)
            val len = b[p + 8].toInt() and 0xFF
            p += 9
            if (p + len > c[1]) return@repeat
            out += Chapter(start100ns / 10_000, -1L, String(b, p, len, Charsets.UTF_8).trim())
            p += len
        }
        return out.sortedBy { it.startMs }
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
}
