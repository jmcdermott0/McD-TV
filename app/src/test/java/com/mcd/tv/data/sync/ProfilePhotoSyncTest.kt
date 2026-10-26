package com.mcd.tv.data.sync

import com.mcd.tv.data.PhotoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/**
 * Profile photos between TVs: the META entries ProfileSync writes (PhotoCodec.writes) merged into another TV's
 * store in pieces and in any order, read back with PhotoCodec.read, the same calls ProfileSync makes.
 */
class ProfilePhotoSyncTest {
    private val t0 = 1_760_000_000_000L

    private fun jpeg(size: Int, seed: Int): String {
        val b = Random(seed).nextBytes(size)
        b[0] = 0xFF.toByte(); b[1] = 0xD8.toByte(); b[2] = 0xFF.toByte()
        return Base64.getEncoder().encodeToString(b)
    }

    /** One TV's META collection for one profile. */
    private class Tv(val id: String) {
        val meta = CollectionState(SyncSpec.META)
        var clock = 0L

        fun setPhoto(b64: String?, now: Long): List<Entry> {
            val v = b64?.let(PhotoCodec::version)
            val pieces = b64?.let(PhotoCodec::split).orEmpty()
            clock = maxOf(clock + 1, now)
            return PhotoCodec.writes(v, pieces) { meta.get(PhotoCodec.pieceKey(it))?.deleted == false }
                .mapNotNull { (k, value) -> meta.localWrite(k, value, clock, id) }
        }

        fun photo(): String? = PhotoCodec.read(meta.get(PhotoCodec.HEADER_KEY)?.deleted == true) { k -> meta.get(k)?.takeIf { !it.deleted }?.value }
    }

    @Test
    fun photoArrivesInPiecesInAnyOrder() {
        val a = Tv("A")
        val b = Tv("B")
        val photo = jpeg(12_000, 1)
        val sent = a.setPhoto(photo, t0)
        assertEquals(photo, a.photo())
        assertTrue(sent.size == PhotoCodec.split(photo).size + 1)
        // Messages arrive in any order; B shows nothing until the last piece is in.
        val order = sent.shuffled(Random(3))
        order.dropLast(1).forEach { b.meta.merge(listOf(it)); assertNull(b.photo()) }
        b.meta.merge(listOf(order.last()))
        assertEquals(photo, b.photo())
    }

    @Test
    fun shorterNewPhotoReplacesTheOldOneCleanly() {
        val a = Tv("A")
        val b = Tv("B")
        val big = jpeg(20_000, 2)
        val small = jpeg(6_000, 3)
        b.meta.merge(a.setPhoto(big, t0))
        assertEquals(big, b.photo())
        val second = a.setPhoto(small, t0 + 60_000)
        // The old photo's extra pieces are deleted, not left behind.
        val deletes = second.filter { it.deleted }.map { it.key }.toSet()
        val expected = (PhotoCodec.split(small).size until PhotoCodec.split(big).size).map(PhotoCodec::pieceKey).toSet()
        assertEquals(expected, deletes)
        // Only the header so far: B keeps showing nothing new until the pieces are in, never a mix.
        b.meta.merge(second.filter { it.key == PhotoCodec.HEADER_KEY })
        assertNull(b.photo())
        b.meta.merge(second)
        assertEquals(small, b.photo())
        assertEquals(a.meta.digest(), b.meta.digest())
    }

    @Test
    fun removalReachesTheOtherTv() {
        val a = Tv("A")
        val b = Tv("B")
        b.meta.merge(a.setPhoto(jpeg(9_000, 4), t0))
        b.meta.merge(a.setPhoto(null, t0 + 1000))
        assertEquals("", b.photo())
        assertTrue(b.meta.live().none { it.key.startsWith("photo") })
        // An older photo from a TV that was off arrives late: the removal still wins.
        b.meta.merge(Tv("D").setPhoto(jpeg(9_000, 8), t0 + 500))
        assertEquals("", b.photo())
    }

    @Test
    fun twoTvsChangingAtOnceEndUpWithTheSamePhoto() {
        val a = Tv("A")
        val b = Tv("B")
        val pa = jpeg(15_000, 5)
        val pb = jpeg(7_000, 6)
        val fromA = a.setPhoto(pa, t0 + 10)
        val fromB = b.setPhoto(pb, t0 + 20)
        a.meta.merge(fromB)
        b.meta.merge(fromA)
        assertEquals(a.meta.digest(), b.meta.digest())
        // The newer change (B's) wins on both, whole.
        assertEquals(pb, a.photo())
        assertEquals(pb, b.photo())
    }

    @Test
    fun namesAndTasteAreUntouched() {
        val a = Tv("A")
        a.meta.localWrite("name", "Dad", t0, "A")
        a.meta.localWrite("taste", "{\"like\":[\"History\"]}", t0, "A")
        a.setPhoto(jpeg(8_000, 7), t0 + 5)
        a.setPhoto(null, t0 + 10)
        assertEquals("Dad", a.meta.get("name")?.value)
        assertEquals("{\"like\":[\"History\"]}", a.meta.get("taste")?.value)
    }
}
