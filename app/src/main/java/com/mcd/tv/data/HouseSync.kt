package com.mcd.tv.data

import android.content.SharedPreferences
import android.os.Build
import android.util.Base64
import com.mcd.tv.data.sync.Backoff
import com.mcd.tv.data.sync.ProfileSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Shared settings between your TVs, with no account.
 *
 * Every TV has a "house": a random id and a 256-bit key. TVs with the same house keep the same
 * settings (keys, addons, playlist, Real-Debrid, preferences). The Control page links a new TV to
 * another TV's house ("house_join"); after that, a change on any of them reaches all of them.
 *
 * How it works:
 *  - Each TV listens on the ntfy topic "mcdtv-<house id>-house". Every message is encrypted with the
 *    house key (same format as [Relay]), so the relay only ever sees random bytes.
 *  - When a shared setting is written (and once a minute as a fallback), a TV checks whether its shared settings changed (from the Control page, the TV's
 *    own Settings screen, a Real-Debrid token refresh...). If so, it sends them to the house ("sync"),
 *    stamped with the time of the change.
 *  - A TV takes a copy only when its stamp is newer than the one it has. A TV that just joined has stamp 0,
 *    so it takes the first copy it gets. It also asks the others to send theirs ("sync_req").
 *  - ntfy keeps messages for about 12 hours, so a TV that was off picks up changes when it opens.
 *
 * Profile data (history, progress, My List, favorites, Live TV favorites, profile names) travels on the same
 * topic as "pdelta", "pdigest" and "pstate" messages, handled by [ProfileSync]. It is paused while a TV is
 * joining (until the first settings copy arrives) and during "Copy once", so a friend's TV that copies
 * settings never gets or sends anyone's profiles.
 */
object HouseSync {
    private const val BASE = "https://ntfy.sh/"
    /** One encrypted message must stay under ntfy's 4 KB, so the settings are sent in parts of this size. */
    private const val CHUNK = 1800

    /**
     * The settings all linked TVs share. Profile data is not here: ProfileSync merges it entry by entry
     * (a whole-copy "newest wins" like this would lose changes made on two TVs at once).
     */
    private val SHARED = listOf(
        "addon_urls", "websites",
        "m3u_url", "custom_stream_url",
        "tmdb_key", "mdblist_key", "jarvis_key", "jarvis_model", "wall_url", "wall_key", "wall_due", "wall_baby",
        "origin_filter", "us_only", "slow_connection", "play_intro", "max_movie_gb", "max_episode_gb",
        "rd_client_id", "rd_client_secret", "rd_access", "rd_refresh", "rd_expires",
    )

    private val SHARED_SET = SHARED.toHashSet()

    /** How often watch() compares settings when nothing woke it. */
    private const val FALLBACK_CHECK_MS = 60_000L
    /** After a failed send, during a backoff or while joining: check again this soon (ntfy: 10 s or slower). */
    private const val RETRY_CHECK_MS = 10_000L
    /** After a wake-up, wait this long so a burst of writes (the Control page's "set") goes out as one copy. */
    private const val SETTLE_MS = 2_000L

    /** Wakes watch() when a shared setting is written. CONFLATED: many writes, one wake-up. */
    private val changed = Channel<Unit>(Channel.CONFLATED)

    /** Held here: SharedPreferences keeps its listeners only weakly. A null key means "everything cleared". */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in SHARED_SET) changed.trySend(Unit)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rng = SecureRandom()
    private val lock = Any()
    @Volatile private var started = false
    @Volatile private var generation = 0
    @Volatile private var lastText = ""
    @Volatile private var lastReplyAt = 0L
    /** Last answer to a phone's "tv_who" (separate from [lastReplyAt], which is for "sync_req"). */
    @Volatile private var lastLinkReplyAt = 0L
    /** Parts of "sync" messages being put back together, by message id. */
    private val pending = LinkedHashMap<String, Array<String?>>()

    private val topic get() = "mcdtv-${Prefs.houseId}-house"

    fun houseLink(): String = "${Prefs.houseId}.${Prefs.houseKey}"

    /**
     * True right after joining, until the first copy from another TV arrives (stamp 0). Kept in settings,
     * so a restart in between never sends this TV's empty settings over the other TV's.
     */
    fun joining(): Boolean = Prefs.houseStamp <= 0L

    /** The platform ("Fire TV", "Google TV"), from the real maker and model. */
    fun platform(): String = TvIdentity.platform(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())

    /** What phones show for this TV: the label J gave it, else the platform. */
    fun deviceName(): String = TvIdentity.displayName(Prefs.tvLabel, platform())

    /** Who this TV is, for "tv_link" and "state". */
    fun who(): TvLink.Who = TvLink.Who(
        label = Prefs.tvLabel,
        platform = platform(),
        maker = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        version = com.mcd.tv.BuildConfig.VERSION_NAME,
    )

    private fun randomId(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..22).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun randomKey(): String {
        val key = ByteArray(32).also { rng.nextBytes(it) }
        return Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun newHouse() {
        Prefs.houseId = randomId()
        Prefs.houseKey = randomKey()
        Prefs.houseSince = ""
        Prefs.houseStamp = System.currentTimeMillis()
    }

    private fun snapshot(): String = Prefs.exportKeys(SHARED).toString()

    /** Profile sync runs only in a house this TV fully belongs to: not while joining, not during "Copy once". */
    fun profileSyncAllowed(): Boolean = !joining() && !copyOnce()

    /** For ProfileSync: [json] encrypted with the house key (the length is what counts against ntfy's limit). */
    internal fun sealForHouse(json: String): String = Relay.encryptWith(Prefs.houseKey, json)

    /**
     * Backoff for every post to the house topic (settings and profile sync). ntfy.sh limits messages per day
     * per home IP, shared by all TVs: after a 429 this TV stops posting for 30 minutes (doubling up to 6 hours)
     * instead of retrying in a loop; after a 5xx or a network error it waits 30 s, doubling up to 30 minutes.
     * Saved, so a restart does not start hammering again. Nothing is dropped: it is sent after the pause.
     */
    private val backoff = Backoff()
    @Volatile private var backoffLoaded = false
    @Volatile private var lastRequestAt = 0L

    private fun loadBackoffLocked() {
        if (backoffLoaded) return
        backoffLoaded = true
        val p = Prefs.json("sync_pause").split(",").mapNotNull { it.trim().toLongOrNull() }
        if (p.size == 3) backoff.restore(p[0], p[1], p[2])
    }

    /** False while posts are paused after a 429 or errors. */
    fun canPublish(now: Long = System.currentTimeMillis()): Boolean = synchronized(backoff) {
        loadBackoffLocked()
        backoff.canSend(now)
    }

    /** Posts one sealed message to the house and updates the backoff. Returns the HTTP status (-1: no answer). */
    internal suspend fun postSealed(sealed: String): Int {
        val code = try {
            Http.postText(BASE + topic, sealed)
            200
        } catch (e: HttpException) {
            e.code
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
        val now = System.currentTimeMillis()
        synchronized(backoff) {
            loadBackoffLocked()
            val before = backoff.pausedUntil
            // Jitter keeps the TVs of a house from all coming back at the same moment.
            val jitter = when {
                code == 429 -> rng.nextInt(5 * 60_000).toLong()
                code !in 200..299 -> rng.nextInt(10_000).toLong()
                else -> 0L
            }
            backoff.onResult(code, now, jitter)
            if (backoff.pausedUntil != before || code == 429) {
                Prefs.putJson("sync_pause", "${backoff.pausedUntil},${backoff.rateWindowMs},${backoff.lastRateLimitAt}")
            }
        }
        return code
    }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        if (Prefs.deviceId.isBlank()) Prefs.deviceId = randomId()
        if (Prefs.houseId.isBlank() || Prefs.houseKey.isBlank()) newHouse()
        lastText = snapshot()
        Prefs.addChangeListener(prefsListener)
        val gen = generation
        scope.launch { listen(gen) }
        scope.launch { watch() }
        scope.launch { if (canPublish()) runCatching { publishRequest() } }
        scope.launch {
            delay(LINK_ANNOUNCE_DELAY_MS) // after the start-up sync_req, so the house's burst limit is not hit
            runCatching { announceLinkIfDue() }
        }
        NowPlaying.onChange { s -> onPlayback(s) }
        ProfileSync.start()
    }

    private val statusLock = Any()
    private var statusLast: NowPlaying.State? = null
    private var statusLastAt = 0L
    private var statusJob: Job? = null

    /**
     * The player changed state. "tv_status" goes out on play, pause, stop or a new title only, at most once per
     * 10 s: a change inside the gap is sent when the gap ends (the newest state at that time). Position updates
     * alone never send; phones take the position from the last event.
     */
    private fun onPlayback(s: NowPlaying.State?) {
        synchronized(statusLock) {
            if (!NowPlaying.changed(statusLast, s)) return
            val wait = NowPlaying.waitMs(statusLastAt, System.currentTimeMillis())
            statusJob?.cancel()
            statusJob = scope.launch {
                if (wait > 0) delay(wait)
                runCatching { publishStatus() }
            }
        }
    }

    /** Posts this TV's playback state to the house. Phones read the newest one per TV. */
    private suspend fun publishStatus() {
        if (Prefs.houseId.isBlank() || Prefs.houseKey.isBlank() || !linkAllowed() || !canPublish()) return
        val s = NowPlaying.current
        val now = System.currentTimeMillis()
        synchronized(statusLock) { statusLast = s; statusLastAt = now }
        post(
            JSONObject()
                .put("type", "tv_status")
                .put("from", Prefs.deviceId)
                .put("name", deviceName())
                .put("label", Prefs.tvLabel)
                .put("platform", platform())
                .put("now", NowPlaying.json(s) ?: JSONObject.NULL)
                .put("ts", now),
        )
    }

    /** Wait after start before announcing this TV's link. */
    private const val LINK_ANNOUNCE_DELAY_MS = 8_000L

    /** Link announces only from a TV fully in the house: not while joining, not during "Copy once". */
    private fun linkAllowed(): Boolean = !joining() && !copyOnce()

    /** At start: tells the house this TV's link if it changed since the last announce, or 11 hours passed. */
    private suspend fun announceLinkIfDue() {
        if (!linkAllowed() || !canPublish()) return
        val now = System.currentTimeMillis()
        val sig = TvLink.signature(Prefs.relayId, Prefs.tvLabel)
        val lastAt = Prefs.json("tv_link_at").toLongOrNull() ?: 0L
        if (!TvLink.announceDue(sig, Prefs.json("tv_link_sig"), now, lastAt)) return
        publishLink()
    }

    /** Called by [Relay.newLink]: phones on the old link can find the new one through the house. */
    fun onNewLink() {
        if (!started || Prefs.houseId.isBlank() || Prefs.houseKey.isBlank()) return // start() announces it
        scope.launch {
            delay(2_000L)
            if (linkAllowed() && canPublish()) runCatching { publishLink() }
        }
    }

    /** Called when J renames this TV (Settings or the Control page): phones learn the new name. */
    fun onLabelChanged() {
        if (!started || Prefs.houseId.isBlank() || Prefs.houseKey.isBlank()) return // start() announces it
        scope.launch {
            delay(2_000L)
            if (linkAllowed() && canPublish()) runCatching { publishLink() }
        }
    }

    /**
     * "tv_guide": how this TV's Live TV guide loaded, sent with each answer to "tv_who", so the guide can be checked
     * from a phone without the TV in hand. Counts and the failure reason only: never the guide link or a login.
     */
    private fun guideReport(now: Long): JSONObject {
        val st = Epg.lastStats
        return JSONObject()
            .put("type", "tv_guide")
            .put("from", Prefs.deviceId)
            .put("ts", now)
            .put("version", com.mcd.tv.BuildConfig.VERSION_NAME)
            .put("model", Build.MODEL.orEmpty())
            .put("guide_url", M3u.guideUrl.isNotBlank())
            .put("at", Epg.lastAt)
            .put("ms", Epg.lastMs)
            .put("err", Epg.lastError)
            .put("xml_channels", Epg.lastXmlChannels)
            .put("xml_programmes", Epg.lastXmlProgrammes)
            .put("kept", Epg.lastKept)
            .put("channels", st?.channels ?: 0)
            .put("with_id", st?.withId ?: 0)
            .put("listed", st?.listed ?: 0)
            .put("current", Epg.current.size)
            .put("extra_on", GuideExtra.enabled())
            .put("extra", GuideExtra.loadedCount())
    }

    /** Sends "tv_link" (this TV's setup link and who it is) to the house. Never logged: it holds the relay key. */
    private suspend fun publishLink(): Boolean {
        val id = Prefs.relayId
        val key = Prefs.relayKey
        if (id.isBlank() || key.isBlank()) return false
        val now = System.currentTimeMillis()
        val ok = post(TvLink.message(Prefs.deviceId, deviceName(), id, key, now, who()))
        if (ok) {
            Prefs.putJson("tv_link_sig", TvLink.signature(id, Prefs.tvLabel))
            Prefs.putJson("tv_link_at", now.toString())
        }
        return ok
    }

    /** Settings copied by "Copy once": everything shared except Real-Debrid, so the other person keeps their own. */
    private val ONCE = SHARED.filterNot { it.startsWith("rd_") }

    /** True while waiting for a one-time copy (kept in settings, so it survives a restart). */
    private fun copyOnce(): Boolean = Prefs.json("house_once") == "1"

    /**
     * Joins another TV's house and takes its settings. With [once], this TV takes one copy (keeping its own
     * Real-Debrid), then leaves at once: later changes on either side never reach the other.
     */
    @Synchronized
    fun join(id: String, key: String, once: Boolean = false) {
        Prefs.putJson("house_once", if (once) "1" else "")
        if (id == Prefs.houseId && key == Prefs.houseKey) {
            scope.launch { runCatching { publishRequest() } }
            return
        }
        synchronized(lock) {
            Prefs.houseId = id
            Prefs.houseKey = key
            Prefs.houseSince = ""
            Prefs.houseStamp = 0L // take the first copy from the other TVs
            lastText = snapshot()
        }
        ProfileSync.onHouseChanged()
        val gen = ++generation
        scope.launch { listen(gen) }
        scope.launch {
            delay(1500)
            if (canPublish()) runCatching { publishRequest() } // else watch() asks again after the pause
        }
    }

    /** Stops sharing: this TV keeps its current settings in a house of its own. */
    @Synchronized
    fun leave() {
        synchronized(lock) {
            newHouse()
            lastText = snapshot()
        }
        ProfileSync.onHouseChanged()
        val gen = ++generation
        scope.launch { listen(gen) }
    }

    /**
     * Sends this TV's settings to the house whenever they change. Wakes when a shared setting is written,
     * else once a minute (so a missed wake-up, a backoff that ended or a failed send is still handled).
     * A failed send is retried after the backoff (the change is not lost). While joining, asks the house
     * again every 5 minutes.
     */
    private suspend fun watch() {
        var retrySoon = false
        while (true) {
            // A send that failed or could not go out yet (backoff, joining) is checked again sooner.
            val wait = if (retrySoon || joining()) RETRY_CHECK_MS else FALLBACK_CHECK_MS
            retrySoon = false
            val woke = withTimeoutOrNull(wait) { changed.receive() } != null
            if (woke) {
                delay(SETTLE_MS)
                changed.tryReceive() // writes during the wait are in this round's snapshot
            }
            if (!canPublish()) { retrySoon = true; continue }
            if (joining()) {
                val t = System.currentTimeMillis()
                if (t - lastRequestAt > 5 * 60_000L) runCatching { publishRequest() }
                continue
            }
            var previous = ""
            var current = ""
            val stamp = synchronized(lock) {
                if (joining()) {
                    0L // wait for the other TV's copy before sending ours
                } else {
                    val now = snapshot()
                    if (now == lastText) {
                        0L
                    } else {
                        previous = lastText
                        current = now
                        lastText = now
                        System.currentTimeMillis().also { Prefs.houseStamp = it }
                    }
                }
            }
            if (stamp > 0L) {
                val ok = runCatching { publishSync(stamp) }.getOrDefault(false)
                // Not sent: mark it unsent again, so the next round (after the backoff) sends it.
                if (!ok) {
                    synchronized(lock) { if (lastText == current) lastText = previous }
                    retrySoon = true
                }
            }
        }
    }

    private suspend fun post(o: JSONObject): Boolean = postSealed(Relay.encryptWith(Prefs.houseKey, o.toString())) in 200..299

    private suspend fun publishRequest() {
        lastRequestAt = System.currentTimeMillis()
        // "once": a friend's TV doing "Copy once". The house does not count it as one of its TVs.
        post(JSONObject().put("type", "sync_req").put("from", Prefs.deviceId).put("ts", System.currentTimeMillis()).put("once", copyOnce()))
    }

    /** True when every part was sent. */
    private suspend fun publishSync(stamp: Long): Boolean {
        val text = snapshot()
        val parts = text.chunked(CHUNK).ifEmpty { listOf("{}") }
        val sid = randomId()
        parts.forEachIndexed { i, chunk ->
            if (i > 0) delay(500L) // ntfy's burst limit is shared by the whole house
            val ok = post(
                JSONObject()
                    .put("type", "sync")
                    .put("from", Prefs.deviceId)
                    .put("sid", sid)
                    .put("part", i)
                    .put("parts", parts.size)
                    .put("ts", stamp)
                    .put("chunk", chunk),
            )
            if (!ok) return false
        }
        return true
    }

    private suspend fun listen(gen: Int) {
        var backoff = 2000L
        while (gen == generation) {
            var c: HttpURLConnection? = null
            try {
                val since = Prefs.houseSince.ifBlank { "12h" }
                c = URL("$BASE$topic/json?since=$since").openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 120_000
                c.inputStream.bufferedReader().use { reader ->
                    backoff = 2000L
                    while (gen == generation) {
                        val line = reader.readLine() ?: break
                        val ev = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (ev.optString("event") != "message") continue
                        if (gen != generation) break
                        Prefs.houseSince = ev.optString("id")
                        val msg = Relay.decryptWith(Prefs.houseKey, ev.optString("message")) ?: continue
                        handle(msg)
                    }
                }
                delay(1000)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000L)
            } finally {
                runCatching { c?.disconnect() }
            }
        }
    }

    private suspend fun handle(m: JSONObject) {
        val from = m.optString("from")
        if (from == Prefs.deviceId) return
        when (m.optString("type")) {
            // Phones and TVs finding each other's setup link, and playback state. Not peers for profile sync, never applied.
            "tv_link", "tv_status", "tv_guide" -> return
            "tv_who" -> {
                if (!linkAllowed()) return
                val now = System.currentTimeMillis()
                if (!TvLink.replyDue(now, lastLinkReplyAt) || !canPublish(now)) return
                lastLinkReplyAt = now
                runCatching { publishLink() }
                runCatching { post(guideReport(now)) }
                return
            }
        }
        if (profileSyncAllowed() && !m.optBoolean("once", false)) ProfileSync.notePeer(from)
        when (m.optString("type")) {
            "pdelta", "pstate", "pdigest" -> if (profileSyncAllowed()) runCatching { ProfileSync.onMessage(m) }
            "sync_req" -> {
                // A TV joined or opened: send ours, unless we have nothing yet ourselves.
                if (joining()) return
                val now = System.currentTimeMillis()
                if (now - lastReplyAt < 20_000L || !canPublish(now)) return
                lastReplyAt = now
                runCatching { publishSync(Prefs.houseStamp) }
            }
            "sync" -> {
                val sid = m.optString("sid")
                val parts = m.optInt("parts", 0)
                val part = m.optInt("part", -1)
                if (sid.isBlank() || parts !in 1..40 || part !in 0 until parts) return
                val full: String = synchronized(pending) {
                    val arr = pending.getOrPut(sid) { arrayOfNulls(parts) }
                    if (arr.size != parts) return
                    arr[part] = m.optString("chunk")
                    while (pending.size > 8) pending.remove(pending.keys.first())
                    if (arr.any { it == null }) return
                    pending.remove(sid)
                    arr.joinToString("")
                }
                val data = runCatching { JSONObject(full) }.getOrNull() ?: return
                val ts = m.optLong("ts", 0L)
                val once = copyOnce()
                val wasJoining = joining()
                val applied = synchronized(lock) {
                    if (ts <= Prefs.houseStamp) return@synchronized false
                    Prefs.importKeys(if (once) ONCE else SHARED, data)
                    Prefs.houseStamp = ts
                    lastText = snapshot()
                    true
                }
                if (applied && once) {
                    Prefs.putJson("house_once", "")
                    leave()
                    LocalWeb.lastMessage = "Settings copied from the other TV"
                    runCatching { Relay.publishState("Settings copied. This TV keeps its own settings from now on.") }
                    return
                }
                if (applied && wasJoining) ProfileSync.onJoinedHouse() // now compare profiles with the house
                if (applied) {
                    LocalWeb.lastMessage = "Settings updated from your other TV"
                    runCatching { Relay.publishState("Settings copied from your other TV") }
                }
            }
        }
    }
}
