package com.mcd.tv.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

/** HouseHost: finding McD-Server by its Bonjour name (multicast DNS). */
class HouseHostTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A reply as macOS sends it to a one-shot query: the question echoed, then the A record by pointer. */
    private fun reply(q: ByteArray, ip: IntArray, name: String = HouseHost.NAME, cacheFlush: Boolean = true): ByteArray {
        val head = bytes(q[0].toInt(), q[1].toInt(), 0x84, 0x00, 0, 1, 0, 1, 0, 0, 0, 0)
        val question = q.copyOfRange(12, q.size)
        val owner = if (name == HouseHost.NAME) bytes(0xC0, 12) else HouseHost.query(name, 0).copyOfRange(12, HouseHost.query(name, 0).size - 4)
        val rr = owner + bytes(0, 1, if (cacheFlush) 0x80 else 0, 1, 0, 0, 0, 120, 0, 4) + bytes(*ip)
        return head + question + rr
    }

    @Test fun queryIsOneTypeAQuestion() {
        val q = HouseHost.query("johns-macbook-air-2.local", 0x1234)
        val expected = bytes(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
            byteArrayOf(19) + "johns-macbook-air-2".toByteArray() + byteArrayOf(5) + "local".toByteArray() + bytes(0, 0, 1, 0, 1)
        assertArrayEquals(expected, q)
    }

    @Test fun readsTheAddressFromACompressedAnswer() {
        val q = HouseHost.query(HouseHost.NAME, 7)
        assertEquals("192.168.1.73", HouseHost.answer(reply(q, intArrayOf(192, 168, 1, 73)), HouseHost.NAME))
        // Bonjour names compare without case ("Johns-MacBook-Air-2.local").
        assertEquals("192.168.1.73", HouseHost.answer(reply(q, intArrayOf(192, 168, 1, 73), cacheFlush = false), "Johns-MacBook-Air-2.local"))
    }

    @Test fun ignoresOtherNamesAndBrokenPackets() {
        val q = HouseHost.query(HouseHost.NAME, 7)
        assertNull(HouseHost.answer(reply(q, intArrayOf(10, 0, 0, 9), name = "office-printer.local"), HouseHost.NAME))
        assertNull(HouseHost.answer(ByteArray(5), HouseHost.NAME))
        val r = reply(q, intArrayOf(10, 0, 0, 9))
        assertNull(HouseHost.answer(r.copyOf(r.size - 2), HouseHost.NAME))
        val loop = bytes(0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0xC0, 12) // a pointer to itself
        assertNull(HouseHost.answer(loop, HouseHost.NAME))
    }

    @Test fun asksOverUdpAndReadsTheReply() {
        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { server ->
            val t = thread {
                val buf = ByteArray(1500)
                val p = DatagramPacket(buf, buf.size)
                server.receive(p)
                val r = reply(buf.copyOf(p.length), intArrayOf(192, 168, 1, 88))
                server.send(DatagramPacket(r, r.size, p.address, p.port))
            }
            assertEquals("192.168.1.88", HouseHost.askAt(HouseHost.NAME, InetAddress.getLoopbackAddress(), server.localPort, 2000))
            t.join(2000)
        }
    }
}
