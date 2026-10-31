package com.mcd.tv.data

import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A screen the McD TV web app asked the TV to show ("open" and "play" relay commands). */
sealed interface RemoteNav {
    /** The title page. */
    data class Open(val type: String, val id: Int) : RemoteNav
    /** The source list for [meta], picking the best source and playing it. */
    data class Play(val meta: PlayMeta, val imdbId: String) : RemoteNav
    /** Jarvis Wall on or off (the Control page's Start Wall / Stop Wall). */
    data class Wall(val start: Boolean) : RemoteNav
}

/**
 * Setup over the internet, so it works from any phone or computer, at home or away,
 * whatever the home network allows.
 *
 * How it works:
 *  - The TV makes a random link id and a random 256-bit key, and shows them as a QR code.
 *  - The McD TV Control web page (GitHub Pages) reads them from the QR link. The key sits
 *    after the "#", so browsers never send it to any server.
 *  - Page and TV exchange messages through ntfy.sh, a free public message relay. Every message
 *    is compressed and encrypted (AES-256-GCM) first, so the relay only ever sees random bytes,
 *    and nobody without the key can read or forge a message.
 */
object Relay {
    private const val BASE = "https://ntfy.sh/"
    /** A torrent info hash: 40 hex or 32 base32 characters. */
    private val HASH = Regex("[A-Fa-f0-9]{40}|[A-Za-z2-7]{32}")
    const val CONTROL_PAGE = "https://jfortress0.github.io/McD-TV/"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var generation = 0
    private val rng = SecureRandom()

    var status by mutableStateOf("Starting…")
    var lastMessageAt by mutableStateOf(0L)

    /**
     * Screens the web app asked for. MainActivity's App() collects this and pushes the screen.
     * A Channel (not a SharedFlow) so a request made while nothing collects (activity being
     * recreated) is kept and applied once the app is back.
     */
    private val navChannel = Channel<RemoteNav>(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val navRequests: Flow<RemoteNav> = navChannel.receiveAsFlow()

    private val inTopic get() = "mcdtv-${Prefs.relayId}-in"
    private val outTopic get() = "mcdtv-${Prefs.relayId}-out"

    /** The link encoded in the QR code. */
    fun controlUrl(): String {
        ensureLink()
        return "$CONTROL_PAGE#${Prefs.relayId}.${Prefs.relayKey}"
    }

    private fun ensureLink() {
        if (Prefs.relayId.isBlank() || Prefs.relayKey.isBlank()) newLink()
    }

    /** Makes a fresh link. Phones and computers using the old link stop working. */
    @Synchronized
    fun newLink() {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        Prefs.relayId = (1..22).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
        val key = ByteArray(32).also { rng.nextBytes(it) }
        Prefs.relayKey = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        Prefs.relaySince = ""
        generation++
        if (started) scope.launch { listen(generation) }
        HouseSync.onNewLink()
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        ensureLink()
        scope.launch { listen(generation) }
        scope.launch { runCatching { publishState("TV started") } }
        HouseSync.start()
    }


    // ---------------- crypto: deflate, then AES-256-GCM; "v1:" + base64url(iv || ciphertext+tag) ----------------

    private fun keyOf(b64: String) = SecretKeySpec(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), "AES")

    fun encrypt(json: String): String = encryptWith(Prefs.relayKey, json)

    fun decrypt(msg: String): JSONObject? = decryptWith(Prefs.relayKey, msg)

    /** Same format as the Control page, with any 256-bit key (base64url). Also used by [HouseSync]. */
    fun encryptWith(keyB64: String, json: String): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(json.toByteArray())
        deflater.finish()
        val zipped = ByteArrayOutputStream().also { out ->
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        }.toByteArray()
        deflater.end()
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyOf(keyB64), GCMParameterSpec(128, iv))
        val sealed = iv + c.doFinal(zipped)
        return "v1:" + Base64.encodeToString(sealed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decryptWith(keyB64: String, msg: String): JSONObject? = runCatching {
        if (!msg.startsWith("v1:")) return null
        val sealed = Base64.decode(msg.removePrefix("v1:"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyOf(keyB64), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
        val zipped = c.doFinal(sealed.copyOfRange(12, sealed.size))
        val inflater = Inflater(true)
        inflater.setInput(zipped)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, n)
        }
        inflater.end()
        JSONObject(out.toString("UTF-8"))
    }.getOrNull()

    // ---------------- listening for commands from the Control page ----------------

    private suspend fun listen(gen: Int) {
        var backoff = 2000L
        while (gen == generation) {
            var c: HttpURLConnection? = null
            try {
                val since = Prefs.relaySince.ifBlank { "12h" }
                c = URL("$BASE$inTopic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000 // ntfy sends a keepalive every ~45 s
                c.inputStream.bufferedReader().use { reader ->
                    status = "Linked. Waiting for your phone or computer."
                    backoff = 2000L
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (ev.optString("event") != "message") continue
                        Prefs.relaySince = ev.optString("id")
                        val cmd = decrypt(ev.optString("message")) ?: continue // not ours: ignore
                        lastMessageAt = System.currentTimeMillis()
                        handle(cmd)
                    }
                }
                delay(1000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                status = "No internet connection to the setup relay. Retrying…"
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { c?.disconnect() }
            }
        }
    }

    private suspend fun handle(cmd: JSONObject) {
        // A command aimed at one TV ("to": its device id) is ignored by every other TV. No "to": for this TV.
        val to = cmd.optString("to")
        if (to.isNotBlank() && to != Prefs.deviceId) return
        when (cmd.optString("cmd")) {
            "hello" -> publishState("Connected")
            // How this TV's last guide downloads went (GuideDiag), as its own small message: read it from a phone or
            // a browser without adb. Hosts only, never a link, user name or password.
            "guide_diag" -> runCatching {
                val o = JSONObject().put("type", "guide_diag").put("req", cmd.optString("req")).put("ts", System.currentTimeMillis())
                    .put("version", com.mcd.tv.BuildConfig.VERSION_NAME).put("model", android.os.Build.MODEL.orEmpty())
                    .put("guide_extra_on", Prefs.guideExtra).put("diag", GuideDiag.json())
                    .put("coverage", Epg.lastStats?.let { JSONObject().put("listed", it.listed).put("channels", it.channels).put("with_id", it.withId) } ?: JSONObject.NULL)
                Http.postText(BASE + outTopic, encrypt(o.toString()))
            }
            // Jarvis Wall on this TV, from the Control page. An optional "mode": "photos" (photos only) or "" (photos and calendar).
            "wall_start", "wall_stop" -> {
                if (isStale(cmd)) return
                if (cmd.has("mode")) Wall.setMode(cmd.optString("mode"))
                if (cmd.has("cycle")) Wall.setCycle(cmd.optString("cycle"))
                val start = cmd.optString("cmd") == "wall_start"
                navChannel.trySend(RemoteNav.Wall(start))
                publishAck(cmd, (if (start) "Wall started on " else "Wall stopped on ") + HouseSync.deviceName())
                publishState(status)
            }
            "wall_mode" -> {
                if (cmd.has("mode")) Wall.setMode(cmd.optString("mode"))
                if (cmd.has("cycle")) Wall.setCycle(cmd.optString("cycle"))
                publishAck(cmd, if (Prefs.wallMode == "photos") "Wall shows photos only" else "Wall shows photos. " + WallCycle.label(WallCycle.parse(Prefs.wallCycle)) + ".")
                publishState(status)
            }
            "set" -> {
                apply(cmd.optJSONObject("data") ?: JSONObject())
                status = "Saved changes from the Control page"
                LocalWeb.lastMessage = "Saved changes from the Control page"
                publishState("Saved on the TV")
            }
            // Control page: share settings with another TV (its shared-settings link is "id"."key").
            "house_join" -> {
                val id = cmd.optString("id")
                val k = cmd.optString("key")
                if (!Regex("[a-z0-9]{10,40}").matches(id) || !Regex("[A-Za-z0-9_-]{40,50}").matches(k)) return
                val once = cmd.optBoolean("once", false)
                HouseSync.join(id, k, once)
                publishState(if (once) "Copying settings from the other TV…" else "Linked. Copying settings from your other TV…")
            }
            "house_leave" -> {
                HouseSync.leave()
                publishState("This TV no longer shares settings")
            }
            "magnet" -> {
                val note = runCatching { "Added to Real-Debrid: " + RdCloud.addMagnet(cmd.optString("magnet")) }
                    .getOrElse { "Real-Debrid add failed: ${it.message}" }
                publishState(note)
            }
            // ---- McD TV web app (docs/app) ----
            // Optional "profile" (p1/p2/p3): that profile's lists, without changing the TV's active profile.
            "web_init" -> runCatching { publishWebInit(cmd.optString("req"), Library.validProfile(cmd.optString("profile"))) }
            // The web app's "Play here": the TV resolves the source through Real-Debrid (browsers can't call RD's API)
            // and sends back links the phone can play. A cached torrent takes a few seconds, so it runs on its own.
            "rd_resolve" -> {
                val ts = cmd.optLong("ts", 0L)
                if (ts > 0L && System.currentTimeMillis() - ts > 2 * 60_000L) return // the page stopped waiting long ago
                scope.launch { runCatching { publishResolve(cmd) } }
            }
            // The web app's ☆ on a Live TV channel: add or remove that profile's favorite. The phone may use a
            // different copy of the link (M3U vs. the provider's app API), so it is matched by stream number.
            "live_fav" -> {
                val pid = Library.validProfile(cmd.optString("profile")) ?: return
                val url = cmd.optString("url")
                if (!url.startsWith("http")) return
                val on = cmd.optBoolean("on", true)
                scope.launch {
                    runCatching {
                        val sid = streamNumber(url)
                        val same = { u: String -> u == url || (sid != null && streamNumber(u) == sid) }
                        if (on) {
                            val mine = runCatching { M3u.load() }.getOrDefault(emptyList()).firstOrNull { same(it.url) }?.url ?: url
                            Prefs.setLiveFavorite(pid, mine, true)
                        } else {
                            liveFavoritesOf(pid).map { it.toString() }.filter(same).forEach { Prefs.setLiveFavorite(pid, it, false) }
                        }
                    }
                }
            }
            // Profile photos from the web app (Settings > Profile > Profile photos): {profile, v, part, parts, data},
            // pieces of one JPEG (PhotoCodec). "v" "" removes the photo. Saved here and sent to the house's other TVs.
            "profile_photo" -> {
                val pid = Library.validProfile(cmd.optString("profile")) ?: return
                val v = cmd.optString("v")
                if (v.isEmpty()) {
                    ProfilePhotos.remove(pid)
                    publishAck(cmd, "Photo removed for ${Prefs.profileName(pid)}")
                    return
                }
                val b64 = photoParts.add(pid, v, cmd.optInt("part", -1), cmd.optInt("parts", 0), cmd.optString("data"), System.currentTimeMillis()) ?: return
                if (ProfilePhotos.set(pid, b64)) publishAck(cmd, "Photo saved for ${Prefs.profileName(pid)}")
                else publishAck(cmd, "The TV could not read that photo. Try another one.")
            }
            // A phone that lacks a photo the TV has (set from another phone, or on another TV) asks for it.
            "profile_photo_get" -> {
                val pid = Library.validProfile(cmd.optString("profile")) ?: return
                scope.launch { runCatching { publishPhoto(cmd.optString("req"), pid) } }
            }
            // Progress from "Play here", so Continue Watching on the TV stays in sync.
            "progress" -> runCatching { recordWebProgress(cmd) }
            // Ask Jarvis from the web app. The key stays on the TV; the answer can take up to 30 s,
            // so it runs on its own and doesn't hold up other commands.
            "ask" -> {
                val ts = cmd.optLong("ts", 0L)
                if (ts > 0L && System.currentTimeMillis() - ts > 2 * 60_000L) return // the page stopped waiting long ago
                scope.launch { runCatching { publishAsk(cmd.optString("req"), cmd.optString("q")) } }
            }
            "open" -> {
                val type = cmd.optString("type")
                val id = cmd.optInt("id")
                if ((type != "movie" && type != "tv") || id <= 0 || isStale(cmd)) return
                navChannel.trySend(RemoteNav.Open(type, id))
                publishAck(cmd, "Opened ${nameFor(type, id, cmd)} on the TV")
            }
            "play" -> {
                val type = cmd.optString("type")
                val id = cmd.optInt("id")
                if ((type != "movie" && type != "tv") || id <= 0 || isStale(cmd)) return
                val d = runCatching { Tmdb.details(type, id) }.getOrNull()
                val imdb = d?.imdbId
                if (d == null || imdb == null) {
                    navChannel.trySend(RemoteNav.Open(type, id))
                    publishAck(cmd, "Opened ${nameFor(type, id, cmd)} on the TV. Pick a source there.")
                    return
                }
                var season = cmd.optInt("season", 0)
                var episode = cmd.optInt("episode", 0)
                if (type == "tv" && (season <= 0 || episode <= 0)) {
                    // No episode picked: resume the last one watched, else start at the beginning.
                    val last = Library.history().firstOrNull { it.meta.historyKey == "tv:$id" }?.meta
                    if (last != null && last.season > 0 && last.episode > 0) {
                        season = last.season
                        episode = last.episode
                    } else {
                        season = d.seasons.firstOrNull()?.number ?: 1
                        episode = 1
                    }
                }
                if (type == "movie") {
                    season = 0
                    episode = 0
                }
                val t = d.title
                val meta = PlayMeta(type, id, t.name, t.poster, t.backdrop, season, episode)
                navChannel.trySend(RemoteNav.Play(meta, imdb))
                publishAck(cmd, "Starting ${meta.label} on the TV")
            }
            "watchlist" -> {
                val type = cmd.optString("type")
                val id = cmd.optInt("id")
                if ((type != "movie" && type != "tv") || id <= 0) return
                val on = cmd.optBoolean("on", true)
                val t = runCatching { Tmdb.details(type, id).title }.getOrNull() ?: Title(
                    id = id,
                    type = type,
                    name = cmd.optString("name").ifBlank { "Untitled" },
                    overview = "",
                    poster = cmd.optString("poster").ifBlank { null },
                    backdrop = cmd.optString("backdrop").ifBlank { null },
                    rating = 0.0,
                    year = cmd.optString("year"),
                )
                // Optional "profile": that profile's watchlist (default: the active profile).
                Library.setWatchlist(t, on, Library.validProfile(cmd.optString("profile")))
                publishAck(cmd, if (on) "Added ${t.name} to your watchlist" else "Removed ${t.name} from your watchlist")
            }
        }
    }

    private val photoParts = PhotoAssembler()

    /** [profile]'s photo for the web app, in pieces: {type:"profile_photo", req, profile, v, part, parts, data}. */
    private suspend fun publishPhoto(req: String, profile: String) {
        val b64 = ProfilePhotos.base64(profile) ?: return
        val v = PhotoCodec.version(b64)
        val pieces = PhotoCodec.split(b64)
        pieces.forEachIndexed { i, piece ->
            val o = JSONObject()
                .put("type", "profile_photo")
                .put("req", req)
                .put("ts", System.currentTimeMillis())
                .put("profile", profile)
                .put("v", v)
                .put("part", i)
                .put("parts", pieces.size)
                .put("data", piece)
            Http.postText(BASE + outTopic, encrypt(o.toString()))
        }
    }

    /** "open" and "play" sent more than 10 minutes ago (TV was off) are dropped, so the TV doesn't jump screens later. */
    private fun isStale(cmd: JSONObject): Boolean {
        val ts = cmd.optLong("ts", 0L)
        return ts > 0L && System.currentTimeMillis() - ts > 10 * 60_000L
    }

    private fun nameFor(type: String, id: Int, cmd: JSONObject): String =
        cmd.optString("name").ifBlank { if (type == "tv") "the show" else "the movie" }

    /** A short reply to one web app command; [cmd]'s "req" is echoed so the page can match it. */
    private suspend fun publishAck(cmd: JSONObject, note: String) {
        status = note
        val o = JSONObject()
            .put("type", "ack")
            .put("note", note)
            .put("req", cmd.optString("req"))
            .put("ts", System.currentTimeMillis())
        runCatching { Http.postText(BASE + outTopic, encrypt(o.toString())) }
    }

    /**
     * Keys, filters and list ids for the web app. ids only, at most 60 per list, and the whole
     * encrypted message stays under 3500 characters (ntfy's limit is 4 KB per message).
     * [profile]: whose lists to send (null = the active profile). The TV's active profile is not changed.
     * Long lists (addon URLs, websites, Live TV favorites) that don't fit go in follow-up messages.
     */
    private suspend fun publishWebInit(req: String, profile: String?) {
        val pid = profile ?: Prefs.activeProfile
        fun ref(type: String, id: Int) = JSONObject().put("type", type).put("id", id)
        var watch = Library.watchlist(pid).take(60).map { ref(it.type, it.id) }
        var favs = Library.favorites(pid).take(60).map { ref(it.type, it.id) }
        var cont = Library.continueWatching(pid).take(60).map {
            ref(it.meta.type, it.meta.tmdbId)
                .put("season", it.meta.season)
                .put("episode", it.meta.episode)
                .put("progress", Math.round(it.progress * 100) / 100.0)
        }
        // Real-Debrid access for "Play here" in the browser: the access token only (refreshed first if near expiry).
        val addons = Prefs.addonUrls
        val sites: List<Any> = Prefs.websites.map { JSONArray().put(it.first).put(it.second) }
        val liveFavs: List<Any> = liveFavoritesOf(pid)
        val profiles = JSONArray().apply {
            Prefs.PROFILE_IDS.forEach { id -> put(JSONObject().put("id", id).put("name", Prefs.profileName(id))) }
        }
        // Photo versions only ("" = none). A phone that lacks one asks with "profile_photo_get".
        val photos = JSONObject().apply { Prefs.PROFILE_IDS.forEach { id -> put(id, ProfilePhotos.version(id)) } }
        // Profiles whose photo was removed on purpose. "" without this means "not here yet", so the phone sends its copy.
        val photosRemoved = JSONArray().apply { Prefs.PROFILE_IDS.forEach { id -> if (ProfilePhotos.version(id).isEmpty() && com.mcd.tv.data.sync.ProfileSync.photoRemoved(id)) put(id) } }
        // Addon URLs can be long (their config is in the URL): if they don't fit, they go in follow-up "addons" messages.
        var withAddons = true
        var withSites = true
        var withLiveFavs = true
        fun build(): String = encrypt(
            JSONObject()
                .put("type", "web_init")
                .put("req", req)
                .put("ts", System.currentTimeMillis())
                .put("profile", pid)
                .put("profiles", profiles)
                .put("photos", photos)
                .put("photos_removed", photosRemoved)
                // Who this TV is, so the phone can name it and aim commands at it.
                .put("device_id", Prefs.deviceId)
                .put("tv_label", Prefs.tvLabel)
                .put("platform", HouseSync.platform())
                .put("version", com.mcd.tv.BuildConfig.VERSION_NAME)
                .put("now_playing", NowPlaying.json(NowPlaying.current) ?: JSONObject.NULL)
                .put("tmdb_key", Prefs.tmdbKey)
                .put("mdblist_key", Prefs.mdblistKey)
                .put("jarvis_key", Prefs.jarvisKey)
                .put("m3u_url", Prefs.m3uUrl)
                .put("origin_filter", Prefs.origin.key)
                .put("us_only", Prefs.usOnly)
                .put("slow_connection", Prefs.slowConnection)
                .put("max_movie_gb", Prefs.maxMovieGb)
                .put("max_episode_gb", Prefs.maxEpisodeGb)
                .put("rd_connected", RealDebrid.connected)
                // House link: the web app encrypts resume positions with it (ResumeCloud, shared with all the TVs).
                .put("house", HouseSync.houseLink())
                .put("addons_separate", !withAddons)
                .apply { if (withAddons) put("addon_urls", JSONArray(addons)) }
                .put("websites_separate", !withSites)
                .apply { if (withSites) put("websites", JSONArray(sites)) }
                // My teams for this profile ("NFL:Eagles"), so the phone's Live TV pins the same games.
                .put("fav_teams", JSONArray(FavoriteTeam.listFrom(Prefs.favoriteTeamNames(pid), pid).map { it.stored }))
                .put("live_favorites_separate", !withLiveFavs)
                .apply { if (withLiveFavs) put("live_favorites", JSONArray(liveFavs)) }
                .put("watchlist", JSONArray(watch))
                .put("favorites", JSONArray(favs))
                .put("continue", JSONArray(cont))
                .toString(),
        )
        var msg = build()
        if (msg.length > 3500 && addons.isNotEmpty()) {
            withAddons = false
            msg = build()
        }
        if (msg.length > 3500 && liveFavs.isNotEmpty()) {
            withLiveFavs = false
            msg = build()
        }
        if (msg.length > 3500 && sites.isNotEmpty()) {
            withSites = false
            msg = build()
        }
        while (msg.length > 3500 && (watch.size + favs.size + cont.size) > 0) {
            // Drop from the end of the longest list, a few at a time.
            val longest = maxOf(watch.size, favs.size, cont.size)
            when (longest) {
                watch.size -> watch = watch.dropLast(5)
                favs.size -> favs = favs.dropLast(5)
                else -> cont = cont.dropLast(5)
            }
            msg = build()
        }
        status = "Connected to the Jarvis web app"
        Http.postText(BASE + outTopic, msg)
        if (!withAddons) publishAddons(req, addons)
        if (!withSites) publishWebList(req, pid, "websites", sites)
        if (!withLiveFavs) publishWebList(req, pid, "live_favorites", liveFavs)
    }

    /** The stream number at the end of a live link (".../live/user/pass/123.m3u8" or ".../user/pass/123"). */
    private fun streamNumber(url: String): String? =
        Regex("""/(\d+)(?:\.[A-Za-z0-9]+)?(?:\?.*)?$""").find(url)?.groupValues?.get(1)

    /** [profile]'s favorite Live TV channels (stream URLs), read without changing the active profile. */
    private fun liveFavoritesOf(profile: String): List<Any> = runCatching {
        val a = JSONArray(Prefs.json("live_favorites@$profile").ifBlank { "[]" })
        List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * A web_init list that didn't fit, in as few messages as fit the 3500-character budget.
     * Each message: {type:"web_list", req, profile, field, part, parts, items}; the page joins the parts of one req and field.
     * [items] are JSON values (strings, or [name, url] arrays for websites).
     */
    private suspend fun publishWebList(req: String, profile: String, field: String, items: List<Any>) {
        fun build(chunk: List<Any>, part: Int, parts: Int): String = encrypt(
            JSONObject()
                .put("type", "web_list")
                .put("req", req)
                .put("ts", System.currentTimeMillis())
                .put("profile", profile)
                .put("field", field)
                .put("part", part)
                .put("parts", parts)
                .put("items", JSONArray(chunk))
                .toString(),
        )
        val chunks = mutableListOf<List<Any>>()
        var cur = mutableListOf<Any>()
        for (item in items) {
            val next = cur + item
            if (cur.isNotEmpty() && build(next, 99, 99).length > 3500) {
                chunks.add(cur)
                cur = mutableListOf(item)
            } else if (cur.isEmpty() && build(next, 99, 99).length > 3500) {
                continue // one item alone is too big to send: skip it
            } else {
                cur = next.toMutableList()
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        if (chunks.isEmpty()) chunks.add(emptyList())
        chunks.forEachIndexed { i, c -> Http.postText(BASE + outTopic, build(c, i, chunks.size)) }
    }

    /**
     * Addon URLs for the web app, in as few messages as fit the 3500-character budget.
     * Each message: {type:"addons", req, part, parts, addon_urls}; the page joins the parts of one req.
     */
    private suspend fun publishAddons(req: String, urls: List<String>) {
        fun build(chunk: List<String>, part: Int, parts: Int): String = encrypt(
            JSONObject()
                .put("type", "addons")
                .put("req", req)
                .put("ts", System.currentTimeMillis())
                .put("part", part)
                .put("parts", parts)
                .put("addon_urls", JSONArray(chunk))
                .toString(),
        )
        // Greedy split; "parts" is filled in afterwards (a few more digits never matter at this size).
        val chunks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        for (u in urls) {
            val next = cur + u
            if (cur.isNotEmpty() && build(next, 99, 99).length > 3500) {
                chunks.add(cur)
                cur = mutableListOf(u)
            } else {
                cur = next.toMutableList()
            }
        }
        if (cur.isNotEmpty()) chunks.add(cur)
        chunks.forEachIndexed { i, c -> Http.postText(BASE + outTopic, build(c, i, chunks.size)) }
    }

    /**
     * Reply to "ask": {type:"ask", req, ts, guesses:[{type, id, why, confidence}], clarify, error}.
     * TMDB ids only (the web app loads posters itself), so the message stays small.
     */
    private suspend fun publishAsk(req: String, q: String) {
        val o = JSONObject()
            .put("type", "ask")
            .put("req", req)
            .put("ts", System.currentTimeMillis())
        try {
            val a = Jarvis.ask(q.take(600))
            val arr = JSONArray()
            a.matches.take(6).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("type", m.title.type)
                        .put("id", m.title.id)
                        .put("why", m.why.take(160))
                        .put("confidence", m.confidence),
                )
            }
            o.put("guesses", arr).put("clarify", a.clarify.take(200)).put("error", "")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            o.put("guesses", JSONArray()).put("clarify", "")
                .put("error", (if (e is JarvisException) e.message else null) ?: "Jarvis could not answer. Try again.")
        }
        Http.postText(BASE + outTopic, encrypt(o.toString()))
    }

    /**
     * Reply to "rd_resolve" {req, url?, infoHash?, fileIdx?, season?, episode?}:
     * {type, req, direct, rd_id, mime, filename, transcode?, error}. "error" is blank on success.
     */
    private suspend fun publishResolve(cmd: JSONObject) {
        val o = JSONObject()
            .put("type", "rd_resolve")
            .put("req", cmd.optString("req"))
            .put("ts", System.currentTimeMillis())
        try {
            val url = cmd.optString("url").trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }
            val hash = cmd.optString("infoHash").trim().takeIf { HASH.matches(it) }
            val fileIdx = if (cmd.has("fileIdx") && !cmd.isNull("fileIdx")) cmd.optInt("fileIdx", -1).takeIf { it >= 0 } else null
            val season = cmd.optInt("season", 0).takeIf { it > 0 }
            val episode = cmd.optInt("episode", 0).takeIf { it > 0 }
            val r = RealDebrid.resolveForWeb(url, hash, fileIdx, season, episode)
            o.put("direct", r.direct).put("rd_id", r.rdId).put("mime", r.mime).put("filename", r.filename.take(200))
            r.transcode?.let { o.put("transcode", it) }
            o.put("error", "")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            o.put("error", e.message?.take(200) ?: "The TV could not get this stream")
        }
        var msg = encrypt(o.toString())
        if (msg.length > 3500 && o.has("transcode")) { o.remove("transcode"); msg = encrypt(o.toString()) } // too big: file only
        Http.postText(BASE + outTopic, msg)
    }

    /**
     * "progress" from the web app's player: {type, id, season, episode, position_ms, duration_ms, name, poster, backdrop, profile}.
     * Optional "profile" (p1/p2/p3): recorded in that profile's history (default: the active profile).
     * Recorded like the TV player does, so Continue Watching stays in sync. Ignored when fields are missing,
     * or when the TV already has newer progress for this title (an old message replayed after the TV was off).
     */
    private fun recordWebProgress(cmd: JSONObject) {
        val type = cmd.optString("type")
        val id = cmd.optInt("id")
        val pos = cmd.optLong("position_ms", -1L)
        val dur = cmd.optLong("duration_ms", -1L)
        val name = cmd.optString("name")
        if ((type != "movie" && type != "tv") || id <= 0 || pos < 0 || dur <= 0 || name.isBlank()) return
        val season = if (type == "tv") cmd.optInt("season", 0) else 0
        val episode = if (type == "tv") cmd.optInt("episode", 0) else 0
        if (type == "tv" && (season <= 0 || episode <= 0)) return
        val meta = PlayMeta(
            type, id, name,
            cmd.optString("poster").ifBlank { null },
            cmd.optString("backdrop").ifBlank { null },
            season, episode,
        )
        val ts = cmd.optLong("ts", 0L)
        val profile = Library.validProfile(cmd.optString("profile"))
        val had = Library.history(profile).firstOrNull { it.meta.historyKey == meta.historyKey }
        // Only for messages over 10 minutes old, so a phone clock a little behind the TV doesn't drop live updates.
        if (isStale(cmd) && (had?.updatedAt ?: 0L) > ts) return
        // The length never shrinks to a cut-off file's (ProgressMerge rule 3).
        val keepDur = had != null && had.meta.season == season && had.meta.episode == episode && SourceHealth.shortSource(dur, had.durationMs)
        Library.record(meta, pos.coerceAtMost(dur), if (keepDur) had!!.durationMs else dur, profile)
    }

    private fun apply(d: JSONObject) {
        d.optJSONArray("addon_urls")?.let { a ->
            val urls = List(a.length()) { a.getString(it) }
            Prefs.updateAddonUrls { urls }
        }
        d.optJSONArray("websites")?.let { a ->
            val sites = List(a.length()) { a.getJSONArray(it) }.map { it.optString(0) to it.optString(1) }
            Prefs.updateWebsites { sites }
        }
        if (d.has("m3u_url")) Prefs.m3uUrl = d.optString("m3u_url")
        if (d.has("custom_stream_url")) Prefs.customUrl = d.optString("custom_stream_url")
        d.optString("tmdb_key").takeIf { it.isNotBlank() }?.let { Prefs.tmdbKey = it }
        d.optString("mdblist_key").takeIf { it.isNotBlank() }?.let { Prefs.mdblistKey = it }
        d.optString("jarvis_key").takeIf { it.isNotBlank() }?.let { Prefs.jarvisKey = it }
        d.optString("origin_filter").takeIf { it.isNotBlank() }?.let { k ->
            OriginFilter.entries.firstOrNull { it.key == k }?.let { Prefs.origin = it }
        }
        if (d.has("us_only")) Prefs.usOnly = d.optBoolean("us_only")
        if (d.has("slow_connection")) Prefs.slowConnection = d.optBoolean("slow_connection")
        if (d.has("play_intro")) Prefs.playIntro = d.optBoolean("play_intro")
        if (d.has("wall_url")) Prefs.wallUrl = d.optString("wall_url")
        d.optString("wall_key").takeIf { it.isNotBlank() }?.let { Prefs.wallKey = it }
        if (d.has("wall_start")) Prefs.wallStart = d.optBoolean("wall_start")
        // Baby due date for the Wall countdown: a real "yyyy-mm-dd", or "" to turn it off.
        if (d.has("wall_due")) d.optString("wall_due").trim().let { v ->
            if (v.isEmpty() || BabyCountdown.parse(v) != null) Prefs.wallDue = v
        }
        if (d.has("wall_baby")) Prefs.wallBaby = d.optString("wall_baby").trim().take(20)
        if (d.has("wall_mode")) Wall.setMode(d.optString("wall_mode"))
        if (d.has("wall_cycle")) Wall.setCycle(d.optString("wall_cycle"))
        // This TV's name. Per TV: not a shared setting, so the Control page sends it only to the TV it is linked to.
        if (d.has("tv_label")) {
            val label = TvIdentity.cleanLabel(d.optString("tv_label"))
            if (label != Prefs.tvLabel) { Prefs.tvLabel = label; HouseSync.onLabelChanged() }
        }
    }

    /** Sends the TV's current setup to the Control page (encrypted). */
    suspend fun publishState(note: String) {
        val names = JSONObject()
        Prefs.addonUrls.forEach { u -> names.put(u, Addons.nameOf(u, fallback = "")) }
        val state = JSONObject()
            .put("type", "state")
            .put("note", note)
            .put("ts", System.currentTimeMillis())
            .put("addon_urls", JSONArray(Prefs.addonUrls))
            .put("addon_names", names)
            .put("websites", JSONArray(Prefs.websites.map { JSONArray(listOf(it.first, it.second)) }))
            .put("m3u_url", Prefs.m3uUrl)
            .put("custom_stream_url", Prefs.customUrl)
            .put("tmdb_set", Prefs.tmdbKey.isNotBlank())
            .put("mdblist_set", Prefs.mdblistKey.isNotBlank())
            .put("jarvis_set", Prefs.jarvisKey.isNotBlank())
            .put("origin_filter", Prefs.origin.key)
            .put("us_only", Prefs.usOnly)
            .put("slow_connection", Prefs.slowConnection)
            .put("play_intro", Prefs.playIntro)
            .put("wall_url", Prefs.wallUrl)
            .put("wall_key_set", Prefs.wallKey.isNotBlank())
            .put("wall_start", Prefs.wallStart)
            .put("wall_due", Prefs.wallDue)
            .put("wall_baby", Prefs.wallBaby)
            .put("rd_connected", RealDebrid.connected)
            .put("version", com.mcd.tv.BuildConfig.VERSION_NAME)
            .put("device_name", HouseSync.deviceName())
            .put("device_id", Prefs.deviceId)
            .put("tv_label", Prefs.tvLabel)
            .put("platform", HouseSync.platform())
            .put("maker", android.os.Build.MANUFACTURER.orEmpty())
            .put("model", android.os.Build.MODEL.orEmpty())
            .put("wall_mode", Prefs.wallMode)
            .put("wall_cycle", Prefs.wallCycle)
            .put("now_playing", NowPlaying.json(NowPlaying.current) ?: JSONObject.NULL)
            .put("profile", Prefs.activeProfileName)
            .put("house", HouseSync.houseLink())
            .put("house_joining", HouseSync.joining())
        Http.postText(BASE + outTopic, encrypt(state.toString()))
    }
}
