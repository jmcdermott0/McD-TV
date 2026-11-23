package com.mcd.tv.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/** One shared setup page for the whole app, running while Jarvis is open. */
object LocalWeb {
    var lastMessage by androidx.compose.runtime.mutableStateOf("Waiting for a browser…")
    var error by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** How many browser requests reached the TV. If this stays 0, the network is blocking the phone. */
    var requests by androidx.compose.runtime.mutableIntStateOf(0)
    val running: Boolean get() = server != null
    @Volatile private var server: PhoneSetupServer? = null

    /**
     * Automated QA only: lets /qa-relay (loopback, via adb forward) return the internet setup link.
     * Off by default. MainActivity turns it on when launched with the QA intent extra.
     * The persisted Prefs.qaMode switch works the same way.
     */
    @Volatile var qaMode = false

    /** Starts the page. Safe to call any number of times: does nothing while it is already running. */
    @Synchronized
    fun start() {
        if (server != null) return
        val s = PhoneSetupServer { lastMessage = it }
        val failure = runCatching { s.start() }.exceptionOrNull()
        error = failure?.let { it.message ?: it.javaClass.simpleName }
        if (failure != null) s.stop() // release anything half-opened; try again next time
        server = if (failure == null) s else null
    }

    /** Stops the page and closes its server socket. Safe to call when not running. */
    @Synchronized
    fun stop() {
        server?.stop()
        server = null
    }

    fun addresses(): List<String> = PhoneSetupServer.addresses()
}

/**
 * Typing long keys and URLs with a TV remote is painful. While the Phone Setup screen is open,
 * the TV runs this tiny web page on your home Wi-Fi. Open it on your phone and paste
 * the TMDB key, addon URLs, playlist URL or a stream link. Only reachable on your own network.
 */
class PhoneSetupServer(private val onChange: (String) -> Unit) {
    val port = PORT
    @Volatile private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val PORT = 8642
        private const val MAX_BODY = 65_536
        private const val MAX_HEADERS = 100

        /** Every likely address of this TV on the home network (Wi-Fi first, then Ethernet). */
        fun addresses(): List<String> {
            val all = runCatching {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("eth") -> 1; else -> 2 } }
                    .flatMap { it.inetAddresses.toList() }
                    .filter { it is Inet4Address && !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
            }.getOrDefault(emptyList())
            return all.distinct().map { "http://$it:$PORT" }.ifEmpty { listOf("http://this-tv-ip:$PORT") }
        }
    }

    fun addresses(): List<String> = Companion.addresses()

    fun address(): String = addresses().first()

    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        // Bind to the wildcard address (IPv4 and IPv6). v0.2.2 used ServerSocket(port), which worked;
        // binding to "0.0.0.0" on Android's dual-stack sockets is what broke phone access in v0.2.4.
        try {
            s.bind(java.net.InetSocketAddress(port))
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
        server = s
        scope.launch {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                launch {
                    try {
                        client.soTimeout = 10_000 // a stalled browser cannot hold a thread forever
                        handle(client)
                    } catch (e: Exception) {
                        // bad request or dropped connection: nothing to do
                    } finally {
                        runCatching { client.close() }
                    }
                }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        scope.cancel()
    }

    private fun respond(c: Socket, status: String, type: String, body: ByteArray) {
        val out = c.getOutputStream()
        out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }

    private suspend fun handle(c: Socket) {
        LocalWeb.requests++
        val input = c.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        var length = 0
        var headerCount = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (++headerCount > MAX_HEADERS) {
                respond(c, "431 Request Header Fields Too Large", "text/plain", "Too many headers".toByteArray())
                return
            }
            if (h.lowercase().startsWith("content-length:")) {
                val n = h.substringAfter(":").trim().toLongOrNull()
                if (n == null || n < 0 || n > MAX_BODY) {
                    respond(c, "413 Payload Too Large", "text/plain", "Request too large".toByteArray())
                    return
                }
                length = n.toInt()
            }
        }
        if (requestLine.startsWith("GET /qa-relay")) {
            // QA only: loopback (adb forward) AND QA mode switched on. Otherwise it does not exist.
            if (c.inetAddress.isLoopbackAddress && (LocalWeb.qaMode || runCatching { Prefs.qaMode }.getOrDefault(false))) {
                respond(c, "200 OK", "text/plain", Relay.controlUrl().toByteArray())
            } else {
                respond(c, "404 Not Found", "text/plain", "Not found".toByteArray())
            }
            return
        }
        var message = ""
        if (requestLine.startsWith("POST")) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(buf, read, length - read)
                if (n < 0) break
                read += n
            }
            message = save(parseForm(String(buf, 0, read)))
            onChange(message)
        }
        respond(c, "200 OK", "text/html; charset=utf-8", page(message).toByteArray())
    }

    private fun parseForm(s: String): Map<String, String> = s.split("&").filter { it.contains("=") }.associate {
        URLDecoder.decode(it.substringBefore("="), "UTF-8") to URLDecoder.decode(it.substringAfter("="), "UTF-8").trim()
    }

    private suspend fun save(f: Map<String, String>): String {
        val done = mutableListOf<String>()
        f["tmdb"]?.takeIf { it.isNotBlank() }?.let { Prefs.tmdbKey = it; done += "TMDB key saved" }
        f["jarvis"]?.takeIf { it.isNotBlank() }?.let { Prefs.jarvisKey = it; done += "Jarvis key saved" }
        f["voice"]?.takeIf { it.isNotBlank() }?.let { Prefs.voiceKey = it; done += "Jarvis voice key saved" }
        f["magnet"]?.takeIf { it.isNotBlank() }?.let { m ->
            done += runCatching { "Added to Real-Debrid: " + RdCloud.addMagnet(m) }
                .getOrElse { "Real-Debrid add failed: ${it.message}" }
        }
        f["m3u"]?.takeIf { it.isNotBlank() }?.let { Prefs.m3uUrl = it; done += "Playlist saved" }
        f["stream"]?.takeIf { it.isNotBlank() }?.let { Prefs.customUrl = it; done += "Stream link saved" }
        f["addon"]?.takeIf { it.isNotBlank() }?.let { url ->
            done += runCatching { "Addon added: " + Addons.install(url).name }
                .getOrElse { "Addon failed: ${it.message}" }
        }
        f["site"]?.takeIf { it.isNotBlank() }?.let { raw ->
            val url = if (raw.startsWith("http")) raw else "https://$raw"
            val name = f["siteName"]?.takeIf { it.isNotBlank() } ?: url.removePrefix("https://").removePrefix("http://").substringBefore("/")
            Prefs.updateWebsites { it + (name to url) }
            done += "Website added: $name"
        }
        f["removeSite"]?.takeIf { it.isNotBlank() }?.let { u -> Prefs.updateWebsites { l -> l.filterNot { it.second == u } }; done += "Website removed" }
        f["remove"]?.takeIf { it.isNotBlank() }?.let { key ->
            // The page sends a short id, never the full addon URL (those can contain API keys).
            val url = Prefs.addonUrls.firstOrNull { addonKey(it) == key || it == key }
            if (url != null) { Addons.remove(url); done += "Addon removed" } else done += "Addon not found"
        }
        return done.joinToString(" • ").ifBlank { "Nothing to save" }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** Short stable id for an addon URL, so the page never has to contain the URL itself. */
    private fun addonKey(url: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
            .take(8).joinToString("") { "%02x".format(it) }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "addon"

    private fun page(msg: String): String {
        val addons = Prefs.addonUrls.joinToString("") { u ->
            "<li><code>${esc(hostOf(u))}</code>" +
                "<form method=post style='display:inline'><input type=hidden name=remove value=\"${addonKey(u)}\"><button>Remove</button></form></li>"
        }.ifBlank { "<li>None yet</li>" }
        val sites = Prefs.websites.joinToString("") { (n, u) ->
            "<li>${esc(n)} <code>${esc(u.take(50))}</code>" +
                "<form method=post style='display:inline'><input type=hidden name=removeSite value=\"${esc(u)}\"><button>Remove</button></form></li>"
        }.ifBlank { "<li>None yet</li>" }
        return """
<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Jarvis setup</title>
<link rel=stylesheet href="https://fonts.googleapis.com/css2?family=Orbitron:wght@500;700&family=Rajdhani:wght@500;600;700&display=swap">
<style>body{font-family:Rajdhani,"Arial Narrow",Arial,sans-serif;font-size:17px;background:#02060A radial-gradient(ellipse at top,#031018,#02060A 70%);color:#EAFBFF;margin:0;padding:20px}
h1,h3{font-family:Orbitron,Arial,sans-serif;text-transform:uppercase;letter-spacing:2px}h1{font-size:22px;text-shadow:0 0 12px rgba(0,229,255,.7)}h1 span{color:#00E5FF}h3{font-size:13px;color:#00E5FF}
label{display:block;margin:18px 0 6px;color:#8FB8C4;font-size:15px}
input{width:100%;box-sizing:border-box;padding:12px;border:1px solid #0E6A7A;background:rgba(6,23,34,.85);color:#EAFBFF;font-size:16px;font-family:inherit;clip-path:polygon(8px 0,100% 0,100% calc(100% - 8px),calc(100% - 8px) 100%,0 100%,0 8px)}
input:focus{outline:none;border-color:#00E5FF}
button{margin-top:12px;background:#00E5FF;color:#02060A;border:0;padding:12px 18px;font-weight:700;font-size:16px;font-family:inherit;text-transform:uppercase;letter-spacing:1px;clip-path:polygon(6px 0,100% 0,100% calc(100% - 6px),calc(100% - 6px) 100%,0 100%,0 6px)}
.msg{background:#0A2230;border:1px solid #0E6A7A;padding:10px}code{font-size:12px;color:#7DF9FF}li{margin:8px 0}</style></head><body>
<h1><span>&#9673;</span> Jarvis setup</h1>${if (msg.isNotBlank()) "<p class=msg>${esc(msg)}</p>" else ""}
<form method=post>
<label>TMDB API key (posters and info). ${if (Prefs.tmdbKey.isNotBlank()) "✅ set" else "Not set"}</label><input name=tmdb placeholder="Paste key or read token">
<label>Jarvis key (powers Ask Jarvis). ${if (Prefs.jarvisKey.isNotBlank()) "✅ set" else "Not set"}</label><input name=jarvis type=password autocomplete=off placeholder="Paste to replace">
<label>Jarvis voice key (spoken lines on the TV). ${if (Prefs.voiceKey.isNotBlank()) "✅ Jarvis voice key set" else "Not set"}</label><input name=voice type=password autocomplete=off placeholder="Paste to replace">
<label>Add an addon (manifest URL or stremio:// link)</label><input name=addon placeholder="https://…/manifest.json">
<label>Live TV playlist (M3U URL). ${if (Prefs.m3uUrl.isNotBlank()) "✅ set" else "Not set"}</label><input name=m3u placeholder="https://…/playlist.m3u">
<label>Add a magnet link to your Real-Debrid cloud (plays under My List &gt; Real-Debrid Cloud)</label><input name=magnet placeholder="magnet:?xt=urn:btih:…">
<label>Direct stream link (shows as My Stream)</label><input name=stream placeholder="https://…/video.m3u8">
<label>Add a website (opens in the TV's built-in browser, under Home &gt; More &gt; Websites)</label><input name=site placeholder="https://example.com">
<label>Name for that website (optional)</label><input name=siteName placeholder="My site">
<button>Save to TV</button></form>
<h3>Websites</h3><ul>$sites</ul>
<h3>Installed addons</h3><ul>$addons</ul>
<p style="color:#8FB8C4;font-size:14px">Real-Debrid connects on the TV itself: Settings &gt; Connect Real-Debrid.</p>
</body></html>""".trimIndent()
    }
}
