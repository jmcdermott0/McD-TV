package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamDiagTest {
    private fun cls(s: String) = s.toByteArray(Charsets.ISO_8859_1).let { StreamDiag.classify(it.copyOf(512), it.size) }

    @Test fun playlist() {
        assertEquals("playlist", cls("#EXTM3U\n#EXT-X-VERSION:3\n"))
        assertEquals("playlist", cls("﻿".toByteArray(Charsets.UTF_8).toString(Charsets.ISO_8859_1) + "#EXTM3U\n"))
        assertEquals("playlist", cls("\r\n  #EXTM3U\n"))
    }

    @Test fun notPlaylist() {
        assertEquals("HTML page", cls("<!DOCTYPE html><html><body>Blocked</body></html>"))
        assertEquals("HTML page", cls("<html><head><title>404</title>"))
        assertEquals("JSON", cls("{\"error\":\"limit\"}"))
        assertEquals("plain text", cls("Connection limit reached"))
        assertEquals("empty", StreamDiag.classify(ByteArray(512), 0))
        assertEquals("blank", cls("  \n"))
    }

    @Test fun mpegTs() {
        val b = ByteArray(512)
        b[0] = 0x47; b[188] = 0x47; b[376] = 0x47
        assertEquals("MPEG-TS video", StreamDiag.classify(b, 512))
        val one = ByteArray(512); one[0] = 0x47
        assertEquals("MPEG-TS video", StreamDiag.classify(one, 100))
    }

    @Test fun tsUrl() {
        assertEquals("http://h/live/u/p/325020.ts", StreamDiag.tsUrl("http://h/live/u/p/325020.m3u8"))
        assertEquals("http://h:8080/live/u/p/1.ts?x=1", StreamDiag.tsUrl("http://h:8080/live/u/p/1.m3u8?x=1"))
        assertNull(StreamDiag.tsUrl("http://h/live/u/p/1.ts"))
        assertNull(StreamDiag.tsUrl("https://cdn.example/stream/index.m3u8"))
    }

    @Test fun hostOnly() {
        assertEquals("h:8080", StreamDiag.hostOf("http://h:8080/live/u/p/1.m3u8?token=abc"))
        assertEquals("h", StreamDiag.hostOf("http://h/live/u/p/1.m3u8"))
        val w = StreamDiag.words(java.io.IOException("Unable to connect to http://h/live/user/pass/1.m3u8?t=9"))
        assertEquals(false, w.contains("user/pass"))
    }

    @Test fun probeFollowsRedirectsAndKeepsNoLink() {
        // A plain socket server (com.sun.net.httpserver is not on Android's unit test classpath).
        // "/live/..." answers 302 to "/blocked?token=secret"; "/blocked" answers an HTML page to
        // IPTVSmartersPro and a playlist to any other user agent.
        val srv = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        val serve = Thread {
            while (!srv.isClosed) {
                val s = runCatching { srv.accept() }.getOrNull() ?: break
                s.use { sock ->
                    val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = (inp.readLine() ?: "").split(" ").getOrElse(1) { "" }
                    var ua = ""
                    while (true) {
                        val h = inp.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("User-Agent:", ignoreCase = true)) ua = h.substringAfter(':').trim()
                    }
                    val out = sock.getOutputStream()
                    if (path.startsWith("/live/")) {
                        out.write("HTTP/1.1 302 Found\r\nLocation: /blocked?token=secret\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    } else {
                        val body = if (ua == "IPTVSmartersPro") "<!DOCTYPE html><p>Access denied</p>" else "#EXTM3U\n"
                        val b = body.toByteArray()
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        out.write(b)
                    }
                    out.flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val url = "http://127.0.0.1:${srv.localPort}/live/user/pass/1.m3u8"
            val p = StreamDiag.probe(url, "IPTVSmartersPro", "US: ESPN HD", "m3u8")
            assertEquals(2, p.hops.size)
            assertEquals(302, p.hops[0].http)
            assertEquals("HTML page", p.hops[1].body)
            assertEquals("text/html", p.hops[1].type)
            assertEquals("HTTP 302 redirect, then HTTP 200, text/html, HTML page", p.words())
            assertEquals(false, p.json().toString().contains("pass") || p.json().toString().contains("secret"))
            assertEquals("playlist", StreamDiag.probe(url, "VLC/3.0.20 LibVLC/3.0.20", "x", "m3u8").hops.last().body)
        } finally { srv.close(); serve.join(2_000) }
    }
}
