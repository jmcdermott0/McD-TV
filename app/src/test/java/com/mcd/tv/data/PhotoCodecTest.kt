package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class PhotoCodecTest {
    /** A fake JPEG of [size] bytes (FF D8 FF, then noise, like real JPEG data), as standard base64. */
    private fun jpeg(size: Int, seed: Int = 1): String {
        val b = Random(seed).nextBytes(size)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte()
        return Base64.getEncoder().encodeToString(b)
    }

    @Test
    fun versionMatchesTheWebApp() {
        // The same value is checked in tools/webtest/qa.mjs (PH.version), so phone and TV agree.
        assertEquals("978d81e975db561c", PhotoCodec.version("/9j/AAAA"))
        assertTrue(PhotoCodec.isVersion(PhotoCodec.version(jpeg(9000))))
        assertFalse(PhotoCodec.isVersion("978D81E975DB561C"))
        assertFalse(PhotoCodec.isVersion("978d81e975db561"))
    }

    @Test
    fun onlySmallJpegsAreValid() {
        assertTrue(PhotoCodec.isValid(jpeg(12_000)))
        assertTrue(PhotoCodec.isValid(jpeg(12_001))) // padded
        // PNG, broken text, URL-safe base64, a data: URL, too big.
        assertFalse(PhotoCodec.isValid(Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2))))
        assertFalse(PhotoCodec.isValid("/9j/AA AA"))
        assertFalse(PhotoCodec.isValid("/9j/AA-_"))
        assertFalse(PhotoCodec.isValid("/9j/AAA"))
        assertFalse(PhotoCodec.isValid("data:image/jpeg;base64,/9j/AAAA"))
        assertFalse(PhotoCodec.isValid("/9j/A==="))
        assertFalse(PhotoCodec.isValid(jpeg(PhotoCodec.MAX_CHARS)))
        assertFalse(PhotoCodec.isValid(""))
    }

    @Test
    fun piecesJoinBackOnlyWhenComplete() {
        val b64 = jpeg(14_000)
        val v = PhotoCodec.version(b64)
        val pieces = PhotoCodec.split(b64)
        assertTrue(pieces.size > 1)
        assertTrue(pieces.all { it.length <= PhotoCodec.CHUNK })
        val stored = pieces.mapIndexed { i, p -> i to PhotoCodec.pieceValue(v, p) }.toMap()
        assertEquals(b64, PhotoCodec.join(v, pieces.size) { stored[it] })
        // One piece missing: nothing yet.
        assertNull(PhotoCodec.join(v, pieces.size) { if (it == 2) null else stored[it] })
        // A piece left from another photo: never mixed in.
        val other = PhotoCodec.version(jpeg(14_000, seed = 2))
        assertNull(PhotoCodec.join(v, pieces.size) { if (it == 1) PhotoCodec.pieceValue(other, pieces[1]) else stored[it] })
        // Pieces that don't add up to the version (a damaged piece).
        assertNull(PhotoCodec.join(v, pieces.size) { if (it == 0) PhotoCodec.pieceValue(v, pieces[0].reversed()) else stored[it] })
        // Header says fewer pieces than there are.
        assertNull(PhotoCodec.join(v, pieces.size - 1) { stored[it] })
    }

    @Test
    fun headerRoundTrip() {
        val v = PhotoCodec.version(jpeg(9000))
        assertEquals(v to 6, PhotoCodec.parseHeader(PhotoCodec.header(v, 6)))
        assertNull(PhotoCodec.parseHeader(null))
        assertNull(PhotoCodec.parseHeader("not json"))
        assertNull(PhotoCodec.parseHeader("""{"v":"$v","n":0}"""))
        assertNull(PhotoCodec.parseHeader("""{"v":"$v","n":${PhotoCodec.MAX_PARTS + 1}}"""))
        assertNull(PhotoCodec.parseHeader("""{"v":"nothex","n":3}"""))
    }

    @Test
    fun largestPhotoFitsTheLimits() {
        // The phone keeps photos under 30,000 characters; the TV accepts up to MAX_CHARS in MAX_PARTS pieces.
        val b64 = jpeg(PhotoCodec.MAX_CHARS / 4 * 3 - 2)
        assertTrue(PhotoCodec.isValid(b64))
        assertTrue(PhotoCodec.split(b64).size <= PhotoCodec.MAX_PARTS)
    }

    @Test
    fun assemblerTakesPiecesInAnyOrder() {
        val b64 = jpeg(10_000)
        val v = PhotoCodec.version(b64)
        val pieces = PhotoCodec.split(b64)
        val a = PhotoAssembler()
        val order = pieces.indices.shuffled(Random(7))
        order.dropLast(1).forEach { assertNull(a.add("p2", v, it, pieces.size, pieces[it], 1000L)) }
        // A repeat of a piece already here changes nothing.
        assertNull(a.add("p2", v, order.first(), pieces.size, pieces[order.first()], 1000L))
        assertEquals(b64, a.add("p2", v, order.last(), pieces.size, pieces[order.last()], 1000L))
        assertEquals(0, a.pendingCount())
    }

    @Test
    fun assemblerStartsOverForANewPhotoAndDropsOldPieces() {
        val first = jpeg(8000, seed = 3)
        val second = jpeg(8000, seed = 4)
        val v1 = PhotoCodec.version(first)
        val v2 = PhotoCodec.version(second)
        val p1 = PhotoCodec.split(first)
        val p2 = PhotoCodec.split(second)
        val a = PhotoAssembler(ttlMs = 60_000L)
        assertNull(a.add("p1", v1, 0, p1.size, p1[0], 0L))
        // The phone picked another photo before the first finished: the new one replaces it.
        p2.indices.drop(1).forEach { assertNull(a.add("p1", v2, it, p2.size, p2[it], 10L)) }
        assertEquals(second, a.add("p1", v2, 0, p2.size, p2[0], 20L))
        assertEquals(0, a.pendingCount())
        // A late piece of the first photo starts it over, alone; it never mixes with the second.
        assertNull(a.add("p1", v1, 1, p1.size, p1[1], 30L))
        assertEquals(1, a.pendingCount())
        // Old pieces expire.
        assertNull(a.add("p3", v1, 0, p1.size, p1[0], 200_000L))
        assertEquals(1, a.pendingCount())
        // Profiles are separate.
        val b = PhotoAssembler()
        p1.indices.forEach { if (it != 0) b.add("p1", v1, it, p1.size, p1[it], 0L) }
        assertNull(b.add("p2", v1, 0, p1.size, p1[0], 0L))
        assertNotNull(b.add("p1", v1, 0, p1.size, p1[0], 0L))
    }

    @Test
    fun assemblerRejectsBadPieces() {
        val b64 = jpeg(4000)
        val v = PhotoCodec.version(b64)
        val a = PhotoAssembler()
        assertNull(a.add("p1", "bad", 0, 1, b64.take(10), 0L))
        assertNull(a.add("p1", v, 3, 3, b64.take(10), 0L))
        assertNull(a.add("p1", v, -1, 3, b64.take(10), 0L))
        assertNull(a.add("p1", v, 0, PhotoCodec.MAX_PARTS + 1, b64.take(10), 0L))
        assertNull(a.add("p1", v, 0, 3, "x".repeat(PhotoCodec.CHUNK + 1), 0L))
        assertNull(a.add("p1", v, 0, 3, "", 0L))
        assertEquals(0, a.pendingCount())
        // Pieces that don't match the version they claim never produce a photo.
        val pieces = PhotoCodec.split(b64)
        val wrong = PhotoCodec.version(jpeg(4000, seed = 9))
        pieces.indices.forEach { assertNull(a.add("p1", wrong, it, pieces.size, pieces[it], 0L)) }
    }
}
