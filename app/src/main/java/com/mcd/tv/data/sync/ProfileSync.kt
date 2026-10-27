package com.mcd.tv.data.sync

import android.content.Context
import com.mcd.tv.data.HouseSync
import com.mcd.tv.data.Library
import com.mcd.tv.data.LibraryEvents
import com.mcd.tv.data.PhotoCodec
import com.mcd.tv.data.Prefs
import com.mcd.tv.data.ProfilePhotos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * Profile sync between the TVs of one house (see docs/ROADMAP-sync.md).
 *
 * Library and Prefs keep their JSON blobs (what the screens read). This object keeps a parallel
 * [ProfileStore] per profile (a CRDT, see ProfileStore.kt), saved as small files in filesDir/profile_sync.
 *
 *  - Local change: Library/Prefs call a hook (onTitleList, onHistory...). The change becomes a CRDT entry.
 *    All pending changes go out together in one "pdelta" ([FlushPolicy]: lists 5 s after the last change,
 *    progress a minute after playback goes quiet and at most every 25 minutes while playing).
 *  - Remote change: entries are merged into the store, then the affected blob is rebuilt from the store.
 *    Rebuilding never calls the hooks, so nothing is sent back (no echo).
 *  - Anti-entropy: "pdigest" (hashes of every collection) 5 to 60 s after start and about every 12 hours,
 *    skipped when any TV of the house sent one in the last 6 hours. If hashes differ, ONE TV answers with
 *    "pstate" (the differing buckets): each waits 0 to 10 s and drops its answer when another TV's answer
 *    to the same digest arrives. Answers go out one message per 20 s, most useful first ([Fill]). This is
 *    what makes TVs that were off for days (longer than ntfy's 12 hour message cache) catch up.
 *  - Budgets: [TokenBucket] per TV, [HouseBudget] for the whole house, and HouseSync's 429 backoff. When a
 *    budget says no, the work waits; local data is never dropped.
 *
 * Transport: the house topic of [HouseSync] (same listener, same key). One connection per TV, and the
 * same 12 hour replay on reconnect, at no extra cost.
 *
 * Locking: [lock] guards all state here. Lock order is Library.lock or Prefs list lock first, then [lock].
 * Code holding [lock] never calls into Library or Prefs list functions, and never touches the network.
 */
object ProfileSync {
    private const val BUDGET = 3500 // encrypted characters per ntfy message (ntfy's limit is 4 KB)
    /** Packing checks against a little less, so the final part numbers ("3" instead of "99") can never push a part over. */
    private const val PACK_LIMIT = BUDGET - 40
    private const val RAW_TARGET = 9000 // first guess of raw JSON per message; the packer then searches for the real fit
    private const val MAX_PARTS = 40
    private const val RETRY_MS = 60_000L
    /** Digests: about 12 hours apart, skipped when any TV of the house checked in the last 6 hours. */
    private const val DIGEST_EVERY_MS = 12 * 60 * 60_000L
    private const val DIGEST_JITTER_MS = 60 * 60_000L
    private const val CHECK_SKIP_MS = 6 * 60 * 60_000L
    /** Wait before answering a digest; the first TV to answer wins and the others drop their answer. */
    private const val REPLY_JITTER_MS = 10_000L
    /** One answer message per 20 s, so a new TV's fill is spread out. */
    private const val ROUND_GAP_MS = 20_000L
    /** The same bucket is sent at most once every 10 minutes (stops reply loops). */
    private const val RESEND_GAP_MS = 10 * 60_000L
    private const val PEER_TTL_MS = 30 * SyncSpec.DAY_MS
    private const val COMPACT_EVERY_MS = 10 * 60_000L
    private const val PROGRESS_SAVE_DELAY_MS = 20_000L
    private const val REMOTE_SAVE_DELAY_MS = 2_000L
    private const val STATE_SAVE_EVERY_MS = 5 * 60_000L
    /**
     * Per-TV message budget: a burst of 25, then one every 20 minutes (at most 97 a day). On top of it the
     * house budget ([HouseBudget]): above 150 profile-sync messages seen in 24 hours, only list changes go;
     * above 200, nothing. See docs/ROADMAP-sync.md for the household math.
     */
    private const val TOKENS_MAX = 25.0
    private const val TOKEN_EVERY_MS = 20 * 60_000L
    private const val HISTORY_BLOB_MAX = 200
    private const val EPISODES_BLOB_MAX = 3000

    /** Library/Prefs storage key (before "@profile") of each synced collection. META: name, taste and photo. */
    private val BLOB = mapOf(
        SyncSpec.HISTORY to "lib_history",
        SyncSpec.EPISODES to "lib_episodes",
        SyncSpec.WATCHLIST to "lib_watchlist",
        SyncSpec.FAVORITES to "lib_favorites",
        SyncSpec.HIDDEN to "lib_hidden",
        SyncSpec.NOISE to "lib_noise",
        SyncSpec.LIVE_FAVORITES to "live_favorites",
        SyncSpec.LIKES to "lib_likes",
    )
    private val TITLE_LISTS = mapOf(
        "lib_watchlist" to SyncSpec.WATCHLIST,
        "lib_favorites" to SyncSpec.FAVORITES,
        "lib_hidden" to SyncSpec.HIDDEN,
        "lib_noise" to SyncSpec.NOISE,
        "lib_likes" to SyncSpec.LIKES,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** All file writes run here, one at a time. */
    private val files = Executors.newSingleThreadExecutor { r -> Thread(r, "profile-sync-files").apply { isDaemon = true } }
        .asCoroutineDispatcher()

    @Volatile private var dir: File? = null
    @Volatile private var loaded = false
    @Volatile private var started = false

    // ---- everything below is guarded by [lock] ----
    private val stores = LinkedHashMap<String, ProfileStore>()
    /** Local changes not sent yet: "p1/watchlist" -> keys. */
    private val outbox = LinkedHashMap<String, MutableSet<String>>()
    private val flush = FlushPolicy()
    private var flushNotBefore = 0L
    private val saveDue = HashMap<String, Long>()
    private val replies = LinkedHashMap<String, Reply>()
    private val lastSent = HashMap<String, Long>()
    private val incoming = LinkedHashMap<String, Incoming>()
    private val peers = LinkedHashMap<String, Long>()
    private var peersSavedAt = 0L
    private var nextDigestAt = 0L
    private var forceDigest = false
    /** Last time any TV of the house (this one included) sent a digest. Saved, so restarts do not resend. */
    private var lastCheckAt = 0L
    private var nextRoundAt = 0L
    private var lastCompactAt = 0L
    private var stateSavedAt = 0L
    private val bucket = TokenBucket(TOKENS_MAX, TOKEN_EVERY_MS)
    private val house = HouseBudget()

    /** [rq]: the digest this answers (null: an answer to a pstate, "I have more"). */
    private class Reply(val buckets: MutableSet<Int>, var floorOnly: Boolean, var dueAt: Long, var rq: String?)
    private class GroupMeta(val floor: Long, val buckets: List<Int>, val hashes: List<String>)
    private class Incoming(val parts: Int, val got: MutableSet<Int>, val groups: MutableMap<String, GroupMeta>, val at: Long)
    private class Item(val pc: String, val entry: Entry?)
    private class Group(val p: String, val c: String, val floor: Long, val entries: List<Entry>, val meta: GroupMeta?)

    private fun pc(p: String, c: String) = "$p/$c"

    // =====================================================================================
    // Lifecycle
    // =====================================================================================

    /** Called by Prefs.init. Only remembers where the files go; loading happens in [start] (off the main thread). */
    fun init(context: Context) {
        if (dir == null) dir = File(context.applicationContext.filesDir, "profile_sync")
    }

    /** Called by HouseSync.start (after the device id exists). */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            runCatching { ensureLoaded() }
            runCatching { materializeAll() }
            synchronized(lock) {
                loadBudgetLocked()
                // Let the house listener replay the last 12 hours first, then compare with the other TVs
                // (5 to 60 s, so TVs switched on together do not all send at once).
                nextDigestAt = System.currentTimeMillis() + Random.nextLong(5_000L, 60_000L)
            }
            while (true) {
                delay(1_000L)
                try {
                    tick()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
    }

    /** True when this TV may send and take profile data (not while joining a house or doing "Copy once"). */
    private fun active(): Boolean = loaded && HouseSync.profileSyncAllowed()

    /** HouseSync: this TV moved to another house (join or leave). Old peers and pending answers no longer apply. */
    fun onHouseChanged() {
        synchronized(lock) {
            replies.clear()
            incoming.clear()
            peers.clear()
            lastCheckAt = 0L
            Prefs.putJson("sync_peers", "")
        }
    }

    /** HouseSync: this TV just took its first settings copy from the house. Compare profile data soon. */
    fun onJoinedHouse() {
        synchronized(lock) {
            nextDigestAt = System.currentTimeMillis() + 2_000L + Random.nextLong(0, 3_000L)
            forceDigest = true
        }
    }

    /** Prefs.importAll replaced the stored blobs (account copy): add anything new, then rebuild the blobs. */
    fun onBulkImport() {
        if (dir == null) return
        scope.launch {
            ensureLoaded()
            val changed = synchronized(lock) { reconcileLocked() }
            changed.forEach { scheduleSave(it, 0L) }
            materializeAll()
        }
    }

    /** A message from another TV in the house (any type). */
    fun notePeer(id: String) {
        if (id.isBlank() || id == Prefs.deviceId) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val prev = peers.put(id, now)
            if (prev == null || now - peersSavedAt > 6 * 60 * 60_000L) {
                peersSavedAt = now
                runCatching { Prefs.putJson("sync_peers", JSONObject(peers.mapValues { it.value }).toString()) }
            }
        }
    }

    /** Other TVs heard from in the last 30 days. */
    fun peerCount(): Int {
        val now = System.currentTimeMillis()
        return synchronized(lock) { peers.values.count { now - it < PEER_TTL_MS } }
    }

    /** One line for the profile screens, or "" when this TV has no linked TVs. */
    fun statusLine(): String {
        val n = peerCount()
        return when {
            !loaded || n == 0 -> ""
            n == 1 -> "Profiles sync with 1 other TV in your house."
            else -> "Profiles sync with $n other TVs in your house."
        }
    }

    // =====================================================================================
    // Hooks: local changes (called by Library and Prefs right after they save)
    // =====================================================================================

    /** A title was added to (titleJson) or removed from (null) a Library list such as "lib_watchlist". */
    fun onTitleList(profile: String, libKey: String, titleKey: String, titleJson: String?) {
        val c = TITLE_LISTS[libKey] ?: return
        recordLocal(profile, c, titleKey, titleJson)
    }

    /** Library.record: the history entry for one title (latest episode for shows). */
    fun onHistory(profile: String, historyKey: String, entryJson: String) = recordLocal(profile, SyncSpec.HISTORY, historyKey, entryJson)

    /** Library.record: one episode's watched fraction. [epKey] is "tmdbId:season:episode". */
    fun onEpisode(profile: String, epKey: String, progress: Double) = recordLocal(profile, SyncSpec.EPISODES, "tv:$epKey", progress.toString())

    fun onLiveFavorite(profile: String, url: String, on: Boolean) = recordLocal(profile, SyncSpec.LIVE_FAVORITES, url, if (on) "1" else null)

    fun onProfileName(profile: String, name: String) = recordLocal(profile, SyncSpec.META, "name", name)

    /** Settings > Taste: the profile's liked and avoided genres and topics (JSON, see Prefs.tasteJson). */
    fun onTaste(profile: String, json: String) = recordLocal(profile, SyncSpec.META, "taste", json.ifBlank { null })

    /**
     * ProfilePhotos: [profile]'s photo changed to version [v] in [pieces], or was removed ([v] null). Pieces are
     * META entries next to a header; pieces a longer old photo left behind are deleted. A TV shows a photo only
     * once the header and every piece of that version have arrived (PhotoCodec.join).
     */
    fun onProfilePhoto(profile: String, v: String?, pieces: List<String>) {
        if (profile !in Prefs.PROFILE_IDS || dir == null) return
        ensureLoaded()
        val writes = synchronized(lock) {
            val col = stores[profile]?.collection(SyncSpec.META) ?: return
            PhotoCodec.writes(v, pieces) { col.get(PhotoCodec.pieceKey(it))?.deleted == false }
        }
        writes.forEach { (k, value) -> recordLocal(profile, SyncSpec.META, k, value) }
    }

    private fun recordLocal(p: String, c: String, key: String, value: String?) {
        if (p !in Prefs.PROFILE_IDS || dir == null) return
        ensureLoaded()
        val now = System.currentTimeMillis()
        val pcKey = pc(p, c)
        synchronized(lock) {
            val col = stores[p]?.collection(c) ?: return
            col.localWrite(key, value, clockLocked(now), origin()) ?: return
            outbox.getOrPut(pcKey) { LinkedHashSet() }.add(key)
            if (SyncSpec.isProgress(c)) {
                flush.noteProgress(now)
                saveDue.putIfAbsent(pcKey, now + PROGRESS_SAVE_DELAY_MS)
            } else {
                flush.noteList(now)
                saveDue.remove(pcKey)
            }
        }
        // Lists are small and a lost delete would come back from the blob, so they are saved at once.
        if (!SyncSpec.isProgress(c)) scope.launch(files) { saveNow(pcKey) }
    }

    private fun origin(): String {
        var id = Prefs.deviceId
        if (id.isBlank()) {
            val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
            id = (1..22).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
            Prefs.deviceId = id
        }
        return id
    }

    /** The time for a local write. A clock that is clearly unset (before 2024) uses the newest time seen instead. */
    private fun clockLocked(now: Long): Long {
        if (now >= SyncSpec.MIN_SANE_TIME) return now
        var max = 0L
        stores.values.forEach { s -> s.collections().values.forEach { c -> c.entries().firstOrNull()?.let { max = maxOf(max, it.ts) } } }
        return maxOf(now, max + 1)
    }

    // =====================================================================================
    // Loading, migration and saving
    // =====================================================================================

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val d = dir ?: return
            Prefs.PROFILE_IDS.forEach { p ->
                val s = ProfileStore(p)
                SyncSpec.COLLECTIONS.forEach { c -> readFile(d, p, c)?.let { (f, entries) -> s.collection(c)?.merge(entries, f) } }
                stores[p] = s
            }
            val changed = reconcileLocked()
            val now = System.currentTimeMillis()
            changed.forEach { saveDue[it] = now }
            runCatching {
                val o = JSONObject(Prefs.json("sync_peers").ifBlank { "{}" })
                o.keys().forEach { k -> peers[k] = o.optLong(k) }
            }
            loaded = true
        }
    }

    /**
     * Adds what the Library blobs have and the store lacks (add only, never deletes). On the first run this is
     * the migration: existing lists get old "seed" times in their current order, history keeps its own times,
     * so the first sync between two TVs is a union and real changes made later always win.
     * Returns the changed "p/c" keys.
     */
    private fun reconcileLocked(): Set<String> {
        val me = origin()
        val changed = LinkedHashSet<String>()
        for (p in Prefs.PROFILE_IDS) {
            val s = stores[p] ?: continue
            // Title lists: newest first in the blob.
            for ((lib, c) in TITLE_LISTS) {
                val arr = parseArray(Prefs.json("$lib@$p")) ?: continue
                val col = s.collection(c) ?: continue
                val n = arr.length()
                for (i in 0 until n) {
                    val o = arr.optJSONObject(i) ?: continue
                    val key = "${o.optString("type")}:${o.optInt("id")}"
                    if (col.seed(key, o.toString(), SyncSpec.SEED_TS + (n - i), me)) changed.add(pc(p, c))
                }
            }
            // History: newest first, each entry has its own time ("at").
            parseArray(Prefs.json("lib_history@$p"))?.let { arr ->
                val col = s.collection(SyncSpec.HISTORY)!!
                val n = arr.length()
                for (i in 0 until n) {
                    val o = arr.optJSONObject(i) ?: continue
                    val meta = o.optJSONObject("meta") ?: continue
                    val key = "${meta.optString("type")}:${meta.optInt("tmdbId")}"
                    val at = o.optLong("at", 0L)
                    val cur = col.get(key)
                    if (cur == null) {
                        if (col.seed(key, o.toString(), if (at > 0L) at else SyncSpec.SEED_TS + (n - i), me)) changed.add(pc(p, SyncSpec.HISTORY))
                    } else if (cur.origin == me && !cur.deleted && at > 0L && at > atOf(cur.value)) {
                        // Played here after the store was last saved (app closed before the save): record it again.
                        // Only over our own last write, so a stale blob never beats another TV's newer entry.
                        if (col.localWrite(key, o.toString(), at, me) != null) changed.add(pc(p, SyncSpec.HISTORY))
                    }
                }
            }
            // Episodes: oldest first, no times.
            parseObject(Prefs.json("lib_episodes@$p"))?.let { o ->
                val col = s.collection(SyncSpec.EPISODES)!!
                var i = 0
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    i++
                    val v = o.optDouble(k, Double.NaN)
                    if (v.isNaN()) continue
                    val key = "tv:$k"
                    val cur = col.get(key)
                    if (cur == null) {
                        if (col.seed(key, v.toString(), SyncSpec.SEED_TS + i, me)) changed.add(pc(p, SyncSpec.EPISODES))
                    } else if (cur.origin == me && !cur.deleted && cur.value != v.toString()) {
                        // Our own last write did not reach the store file: the blob has the newer value.
                        if (col.localWrite(key, v.toString(), System.currentTimeMillis(), me) != null) changed.add(pc(p, SyncSpec.EPISODES))
                    }
                }
            }
            // Live TV favorites: oldest first.
            parseArray(Prefs.json("live_favorites@$p"))?.let { arr ->
                val col = s.collection(SyncSpec.LIVE_FAVORITES)!!
                for (i in 0 until arr.length()) {
                    val url = arr.optString(i)
                    if (url.isBlank()) continue
                    if (col.seed(url, "1", SyncSpec.SEED_TS + i + 1, me)) changed.add(pc(p, SyncSpec.LIVE_FAVORITES))
                }
            }
            // Profile name (only a name someone typed; the defaults are not data).
            val name = Prefs.customProfileName(p)
            if (name.isNotBlank()) {
                if (s.collection(SyncSpec.META)!!.seed("name", name, SyncSpec.SEED_TS, me)) changed.add(pc(p, SyncSpec.META))
            }
            val taste = Prefs.tasteJson(p)
            if (taste.isNotBlank()) {
                if (s.collection(SyncSpec.META)!!.seed("taste", taste, SyncSpec.SEED_TS, me)) changed.add(pc(p, SyncSpec.META))
            }
        }
        return changed
    }

    private fun atOf(value: String?): Long = runCatching { JSONObject(value ?: "{}").optLong("at", 0L) }.getOrDefault(0L)

    /** null when [s] is not a JSON array (a damaged blob is skipped, never treated as empty). "" is an empty list. */
    private fun parseArray(s: String): JSONArray? = if (s.isBlank()) JSONArray() else runCatching { JSONArray(s) }.getOrNull()

    private fun parseObject(s: String): JSONObject? = if (s.isBlank()) JSONObject() else runCatching { JSONObject(s) }.getOrNull()

    private fun fileOf(d: File, p: String, c: String) = File(d, "$p.$c.json")

    private fun readFile(d: File, p: String, c: String): Pair<Long, List<Entry>>? = runCatching {
        val f = fileOf(d, p, c)
        if (!f.exists()) return null
        val o = JSONObject(f.readText())
        val arr = o.optJSONArray("e") ?: JSONArray()
        o.optLong("f", 0L) to (0 until arr.length()).mapNotNull { decodeEntry(arr.optJSONArray(it)) }
    }.getOrNull()

    /** Writes one collection's file (temp file + rename, so a crash never leaves half a file). Runs on [files]. */
    private fun saveNow(pcKey: String) {
        val d = dir ?: return
        val p = pcKey.substringBefore('/')
        val c = pcKey.substringAfter('/')
        val text = synchronized(lock) {
            saveDue.remove(pcKey)
            val col = stores[p]?.collection(c) ?: return
            val arr = JSONArray()
            col.entries().forEach { arr.put(encodeEntry(it)) }
            JSONObject().put("v", 1).put("f", col.floor).put("e", arr).toString()
        }
        runCatching {
            d.mkdirs()
            val tmp = File(d, "$p.$c.json.tmp")
            tmp.writeText(text)
            val target = fileOf(d, p, c)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    private fun scheduleSave(pcKey: String, delayMs: Long) {
        val at = System.currentTimeMillis() + delayMs
        synchronized(lock) {
            val cur = saveDue[pcKey]
            if (cur == null || at < cur) saveDue[pcKey] = at
        }
    }

    // =====================================================================================
    // Rebuilding Library / Prefs blobs from the store (no hooks, so nothing is re-sent)
    // =====================================================================================

    private fun materializeAll() {
        Prefs.PROFILE_IDS.forEach { p -> SyncSpec.COLLECTIONS.forEach { c -> runCatching { materialize(p, c) } } }
    }

    private fun materialize(p: String, c: String) {
        when (c) {
            SyncSpec.META -> {
                val (e, t) = synchronized(lock) {
                    val col = stores[p]?.collection(SyncSpec.META)
                    col?.get("name") to col?.get("taste")
                }
                if (e != null) {
                    val name = if (e.deleted) "" else e.value.orEmpty()
                    if (Prefs.customProfileName(p) != name) Prefs.applySyncedProfileName(p, name)
                }
                if (t != null) {
                    val taste = if (t.deleted) "" else t.value.orEmpty()
                    if (Prefs.tasteJson(p) != taste) Prefs.applySyncedTaste(p, taste)
                }
                materializePhoto(p)
            }
            SyncSpec.LIVE_FAVORITES -> Prefs.withListLock { writeBlob(p, c) }
            else -> Library.withLock { writeBlob(p, c) }
        }
    }

    /** [p]'s photo from the store: saved once every piece of the newest version is here, removed when deleted. */
    private fun materializePhoto(p: String) {
        val h = synchronized(lock) { stores[p]?.collection(SyncSpec.META)?.get(PhotoCodec.HEADER_KEY) } ?: return
        if (h.deleted) {
            if (ProfilePhotos.version(p).isNotEmpty()) ProfilePhotos.remove(p, share = false)
            return
        }
        val (v, _) = PhotoCodec.parseHeader(h.value) ?: return
        if (ProfilePhotos.version(p) == v) return
        val b64 = synchronized(lock) {
            val col = stores[p]?.collection(SyncSpec.META) ?: return
            PhotoCodec.read(false) { k -> col.get(k)?.takeIf { !it.deleted }?.value }
        }
        if (!b64.isNullOrEmpty()) ProfilePhotos.set(p, b64, share = false)
    }

    /** Caller holds the Library or Prefs list lock, so no local write can slip in between render and save. */
    private fun writeBlob(p: String, c: String) {
        val key = "${BLOB[c] ?: return}@$p"
        val text = synchronized(lock) { render(p, c) } ?: return
        val cur = Prefs.json(key)
        if (cur == text) return
        if (cur.isBlank() && (text == "[]" || text == "{}")) return
        Prefs.putJson(key, text)
        LibraryEvents.changed() // Home's My List row re-reads
    }

    /** The blob text for one collection, in the order Library uses. */
    private fun render(p: String, c: String): String? {
        val col = stores[p]?.collection(c) ?: return null
        return when (c) {
            SyncSpec.HISTORY -> JSONArray().apply {
                col.live().take(HISTORY_BLOB_MAX).forEach { e -> runCatching { put(JSONObject(e.value!!)) } }
            }.toString()
            SyncSpec.EPISODES -> JSONObject().apply {
                // Oldest first: Library drops from the front when it is full.
                col.live().take(EPISODES_BLOB_MAX).asReversed().forEach { e ->
                    val v = e.value?.toDoubleOrNull() ?: return@forEach
                    runCatching { put(e.key.removePrefix("tv:"), v) }
                }
            }.toString()
            SyncSpec.LIVE_FAVORITES -> JSONArray().apply { col.live().asReversed().forEach { put(it.key) } }.toString()
            SyncSpec.META -> null
            else -> JSONArray().apply { col.live().forEach { e -> runCatching { put(JSONObject(e.value!!)) } } }.toString()
        }
    }

    // =====================================================================================
    // Wire format
    // =====================================================================================

    /** [key, value or null, ts, deleted 0/1, origin] */
    private fun encodeEntry(e: Entry): JSONArray =
        JSONArray().put(e.key).put(e.value ?: JSONObject.NULL).put(e.ts).put(if (e.deleted) 1 else 0).put(e.origin)

    private fun decodeEntry(a: JSONArray?): Entry? {
        if (a == null || a.length() < 5) return null
        val key = a.optString(0)
        val value = if (a.isNull(1)) null else a.optString(1)
        val ts = a.optLong(2, 0L)
        val deleted = a.optInt(3, 0) == 1
        val origin = a.optString(4)
        if (key.isBlank() || key.length > 600 || ts <= 0L || origin.isBlank() || origin.length > 64) return null
        if (value != null && value.length > 20_000) return null
        return Entry.of(key, value, ts, deleted, origin)
    }

    private fun decodeGroups(m: JSONObject): List<Group> {
        val g = m.optJSONArray("g") ?: return emptyList()
        val out = ArrayList<Group>()
        for (i in 0 until g.length()) {
            val o = g.optJSONObject(i) ?: continue
            val p = o.optString("p")
            val c = o.optString("c")
            if (p !in Prefs.PROFILE_IDS || c !in SyncSpec.COLLECTIONS) continue
            val arr = o.optJSONArray("e") ?: JSONArray()
            val entries = (0 until arr.length()).mapNotNull { decodeEntry(arr.optJSONArray(it)) }
            val bk = o.optJSONArray("bk")
            val bh = o.optJSONArray("bh")
            val meta = if (bk != null && bh != null && bk.length() == bh.length()) {
                val buckets = (0 until bk.length()).map { bk.optInt(it, -1) }
                if (buckets.any { it !in 0 until SyncSpec.BUCKETS }) null
                else GroupMeta(o.optLong("f", 0L), buckets, (0 until bh.length()).map { bh.optString(it) })
            } else null
            out.add(Group(p, c, o.optLong("f", 0L).coerceAtLeast(0L), entries, meta))
        }
        return out
    }

    private fun buildMessage(
        type: String, items: List<Item>, floors: Map<String, Long>, headers: Map<String, GroupMeta>,
        sid: String, part: Int, parts: Int, truncated: Boolean, rqs: Set<String> = emptySet(),
    ): String {
        val groups = LinkedHashMap<String, JSONArray>()
        items.forEach { item ->
            val arr = groups.getOrPut(item.pc) { JSONArray() }
            item.entry?.let { arr.put(encodeEntry(it)) }
        }
        val g = JSONArray()
        groups.forEach { (pcKey, arr) ->
            val o = JSONObject()
                .put("p", pcKey.substringBefore('/'))
                .put("c", pcKey.substringAfter('/'))
                .put("f", floors[pcKey] ?: 0L)
                .put("e", arr)
            headers[pcKey]?.let { h -> o.put("bk", JSONArray(h.buckets)).put("bh", JSONArray(h.hashes)) }
            g.put(o)
        }
        return JSONObject()
            .put("type", type)
            .put("v", 1)
            .put("from", origin())
            .put("ts", System.currentTimeMillis())
            .put("sid", sid)
            .put("part", part)
            .put("parts", parts)
            .apply { if (truncated) put("tr", 1) } // the tail did not fit: receivers must not compare hashes
            .apply { if (rqs.isNotEmpty()) put("rq", JSONArray(rqs.toList())) } // the digests this answers
            .put("g", g)
            .toString()
    }

    private fun randomSid(): String {
        val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
        return (1..12).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    /** Packs [items] (newest first) into encrypted messages under the budget. Empty when nothing fits. */
    private fun seal(
        type: String, items: List<Item>, floors: Map<String, Long>, headers: Map<String, GroupMeta>, rqs: Set<String> = emptySet(),
    ): List<String> {
        if (items.isEmpty()) return emptyList()
        val sid = randomSid()
        val packed = Packer.pack(
            items,
            rawSize = { it.entry?.let { e -> e.key.length + (e.value?.length ?: 0) * 6 / 5 + e.origin.length + 40 } ?: 260 },
            rawTarget = RAW_TARGET,
            fits = { part -> HouseSync.sealForHouse(buildMessage(type, part, floors, headers, sid, 99, 99, true, rqs)).length <= PACK_LIMIT },
            maxParts = MAX_PARTS,
        )
        val n = packed.parts.size
        val truncated = packed.skipped > 0
        return packed.parts.mapIndexed { i, part -> HouseSync.sealForHouse(buildMessage(type, part, floors, headers, sid, i, n, truncated, rqs)) }
    }

    /**
     * Sends sealed messages in order through HouseSync (which applies the shared 429 / error backoff).
     * False when any send failed; the caller keeps its work (nothing local is ever dropped).
     */
    private suspend fun post(messages: List<String>): Boolean {
        for ((i, msg) in messages.withIndex()) {
            if (i > 0) delay(1_000L) // ntfy's burst limit is shared by the whole house
            val code = HouseSync.postSealed(msg)
            if (code !in 200..299) return false
            synchronized(lock) { house.record(System.currentTimeMillis()) }
        }
        return true
    }

    /** Per-TV and house budgets for [n] messages; takes the tokens when allowed. */
    private fun allowSend(n: Int, urgent: Boolean, now: Long): Boolean = synchronized(lock) {
        if (!house.allows(n, urgent, now) || !bucket.canTake(n, now)) return false
        bucket.tryTake(n, now)
    }

    private fun loadBudgetLocked() {
        runCatching {
            val o = JSONObject(Prefs.json("sync_budget").ifBlank { "{}" })
            if (o.has("tokens")) bucket.restore(o.optDouble("tokens", TOKENS_MAX), o.optLong("tokens_at", 0L))
            lastCheckAt = o.optLong("check_at", 0L)
            val h = o.optJSONArray("house") ?: JSONArray()
            house.restore((0 until h.length()).map { h.optLong(it, 0L) }.filter { it > 0L })
        }
    }

    private fun saveBudgetLocked(now: Long) {
        stateSavedAt = now
        val h = JSONArray()
        house.snapshot(now).forEach { h.put(it) }
        runCatching {
            Prefs.putJson(
                "sync_budget",
                JSONObject()
                    .put("tokens", bucket.available(now))
                    .put("tokens_at", bucket.savedAt())
                    .put("check_at", lastCheckAt)
                    .put("house", h)
                    .toString(),
            )
        }
    }

    // =====================================================================================
    // Incoming messages (HouseSync listener thread)
    // =====================================================================================

    fun onMessage(m: JSONObject) {
        ensureLoaded()
        if (!loaded) return
        synchronized(lock) { house.record(System.currentTimeMillis()) }
        when (m.optString("type")) {
            "pdelta" -> mergeGroups(decodeGroups(m))
            "pstate" -> onState(m)
            "pdigest" -> onDigest(m)
        }
    }

    /** Merges groups into the stores and rebuilds the blobs that changed. */
    private fun mergeGroups(groups: List<Group>) {
        if (groups.isEmpty()) return
        val changed = LinkedHashSet<Pair<String, String>>()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            groups.forEach { g ->
                val col = stores[g.p]?.collection(g.c) ?: return@forEach
                if (col.merge(g.entries, g.floor).changed) {
                    changed.add(g.p to g.c)
                    val pcKey = pc(g.p, g.c)
                    val at = now + if (SyncSpec.isProgress(g.c)) PROGRESS_SAVE_DELAY_MS else REMOTE_SAVE_DELAY_MS
                    val cur = saveDue[pcKey]
                    if (cur == null || at < cur) saveDue[pcKey] = at
                }
            }
        }
        changed.forEach { (p, c) -> runCatching { materialize(p, c) } }
    }

    private fun onState(m: JSONObject) {
        val groups = decodeGroups(m)
        mergeGroups(groups)
        val now = System.currentTimeMillis()
        val sid = m.optString("sid")
        val parts = m.optInt("parts", 0)
        val part = m.optInt("part", -1)
        val answered = HashSet<String>()
        m.optJSONArray("rq")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { answered.add(it) } }
        synchronized(lock) {
            // Another TV is answering the same digest: only one TV answers, so drop ours (all of it).
            if (answered.isNotEmpty()) replies.entries.removeAll { it.value.rq != null && it.value.rq in answered }
            // Another TV already sent the same data: our pending answer for those buckets is not needed.
            groups.forEach { g ->
                val meta = g.meta ?: return@forEach
                val pcKey = pc(g.p, g.c)
                val r = replies[pcKey] ?: return@forEach
                val col = stores[g.p]?.collection(g.c) ?: return@forEach
                val mine = col.bucketHashes()
                meta.buckets.forEachIndexed { i, b -> if (meta.hashes.getOrNull(i) == mine[b]) r.buckets.remove(b) }
                if (g.floor >= col.floor) r.floorOnly = false
                if (r.buckets.isEmpty() && !r.floorOnly) replies.remove(pcKey)
            }
            // Once every part arrived: if we still differ in the buckets it sent, we have data it lacks. Send it back.
            if (m.optInt("tr", 0) == 1) return
            if (sid.isBlank() || parts !in 1..MAX_PARTS || part !in 0 until parts) return
            incoming.entries.removeAll { now - it.value.at > 5 * 60_000L }
            val inc = incoming.getOrPut(sid) { Incoming(parts, HashSet(), LinkedHashMap(), now) }
            if (inc.parts != parts) return
            inc.got.add(part)
            groups.forEach { g -> g.meta?.let { inc.groups[pc(g.p, g.c)] = it } }
            while (incoming.size > 16) incoming.remove(incoming.keys.first())
            if (inc.got.size < inc.parts) return
            incoming.remove(sid)
            inc.groups.forEach { (pcKey, meta) ->
                val col = stores[pcKey.substringBefore('/')]?.collection(pcKey.substringAfter('/')) ?: return@forEach
                val mine = col.bucketHashes()
                val diff = meta.buckets.filterIndexed { i, b -> meta.hashes.getOrNull(i) != mine[b] }.toSet()
                val floorOnly = col.floor > meta.floor
                if (diff.isNotEmpty() || floorOnly) scheduleReplyLocked(pcKey, diff, floorOnly, now, null)
            }
        }
    }

    private fun onDigest(m: JSONObject) {
        val d = m.optJSONObject("d") ?: return
        val rq = m.optString("rq").ifBlank { m.optString("from") + ":" + m.optLong("ts") }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            // A TV of the house just checked: ours can wait (an answer from us, if needed, keeps us in sync).
            lastCheckAt = now
            for (p in Prefs.PROFILE_IDS) {
                val s = stores[p] ?: continue
                for (c in SyncSpec.COLLECTIONS) {
                    val k = pc(p, c)
                    if (!d.has(k)) continue // an older version without this collection
                    val v = d.optString(k)
                    val col = s.collection(c) ?: continue
                    val plan = AntiEntropy.plan(col, v.substringBefore(':'), v.substringAfter(':', ""))
                    if (plan.needed) scheduleReplyLocked(k, plan.buckets, plan.floorOnly, now, rq)
                }
            }
        }
    }

    private fun scheduleReplyLocked(pcKey: String, buckets: Set<Int>, floorOnly: Boolean, now: Long, rq: String?) {
        val r = replies.getOrPut(pcKey) { Reply(HashSet(), false, now + Random.nextLong(0, REPLY_JITTER_MS + 1), rq) }
        r.buckets.addAll(buckets)
        r.floorOnly = r.floorOnly || floorOnly
        if (rq == null) r.rq = null // also needed for our own reasons: another TV's answer must not cancel it
    }

    // =====================================================================================
    // Timer: saves, deltas, answers, digests, pruning
    // =====================================================================================

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val dueSaves = synchronized(lock) { saveDue.filterValues { it <= now }.keys.toList() }
        if (dueSaves.isNotEmpty()) withContext(files) { dueSaves.forEach { saveNow(it) } }
        if (now >= SyncSpec.MIN_SANE_TIME && now - synchronized(lock) { lastCompactAt } > COMPACT_EVERY_MS) compact(now)
        synchronized(lock) { if (now - stateSavedAt > STATE_SAVE_EVERY_MS) saveBudgetLocked(now) }
        if (!active()) return
        // After a 429 (or errors), everything here waits; local changes stay in the outbox and the store.
        if (!HouseSync.canPublish(now)) return
        flushOutbox(now)
        if (!HouseSync.canPublish(now)) return
        sendReplies(now)
        if (!HouseSync.canPublish(now)) return
        sendDigestIfDue(now)
    }

    private fun compact(now: Long) {
        val changed = synchronized(lock) {
            lastCompactAt = now
            val out = ArrayList<Pair<String, String>>()
            stores.forEach { (p, s) -> s.compact(now).keys.forEach { c -> out.add(p to c); saveDue[pc(p, c)] = now } }
            out
        }
        changed.forEach { (p, c) -> runCatching { materialize(p, c) } }
    }

    /** Sends every pending local change (lists and progress together) as one pdelta, when [FlushPolicy] says so. */
    private suspend fun flushOutbox(now: Long) {
        var keys: Map<String, Set<String>> = emptyMap()
        var urgent = false
        val items = ArrayList<Item>()
        val floors = HashMap<String, Long>()
        synchronized(lock) {
            if (outbox.isEmpty() || now < flushNotBefore || !flush.due(now)) return
            urgent = flush.hasListChange
            // Nobody to tell (a TV on its own): skip. A TV that links later gets everything through pdigest/pstate.
            if (peers.values.none { now - it < PEER_TTL_MS }) {
                outbox.clear()
                flush.sent()
                return
            }
            keys = outbox.mapValues { it.value.toSet() }
            val entries = ArrayList<Pair<String, Entry>>()
            keys.forEach { (pcKey, ks) ->
                val col = stores[pcKey.substringBefore('/')]?.collection(pcKey.substringAfter('/')) ?: return@forEach
                floors[pcKey] = col.floor
                ks.forEach { k -> col.get(k)?.let { entries.add(pcKey to it) } }
            }
            entries.sortWith { a, b -> NEWEST_FIRST.compare(a.second, b.second) }
            entries.forEach { items.add(Item(it.first, it.second)) }
        }
        val messages = runCatching { seal("pdelta", items, floors, emptyMap()) }.getOrDefault(emptyList())
        if (messages.isEmpty()) {
            synchronized(lock) { keys.forEach { (k, ks) -> outbox[k]?.removeAll(ks) }; outbox.entries.removeAll { it.value.isEmpty() }; if (outbox.isEmpty()) flush.sent() }
            return
        }
        if (!allowSend(messages.size, urgent, now)) {
            synchronized(lock) { flushNotBefore = now + RETRY_MS }
            return
        }
        val sent = post(messages)
        synchronized(lock) {
            if (sent) {
                // Keys changed again while sending stay in the outbox (their new version goes next time).
                keys.forEach { (pcKey, ks) ->
                    val pending = outbox[pcKey] ?: return@forEach
                    val col = stores[pcKey.substringBefore('/')]?.collection(pcKey.substringAfter('/'))
                    ks.forEach { k ->
                        val sentEntry = items.firstOrNull { it.pc == pcKey && it.entry?.key == k }?.entry
                        if (sentEntry == null || col?.get(k) == sentEntry) pending.remove(k)
                    }
                }
                outbox.entries.removeAll { it.value.isEmpty() }
                if (outbox.isEmpty()) flush.sent()
            } else {
                flushNotBefore = now + RETRY_MS
            }
            saveBudgetLocked(now)
        }
    }

    /**
     * Answers (pstate), one message per [ROUND_GAP_MS], most useful first (see [Fill]): a new TV gets names,
     * Continue Watching and My List in the first minutes, episode marks later.
     */
    private suspend fun sendReplies(now: Long) {
        val units = ArrayList<Fill.Unit>()
        val unitEntries = HashMap<Pair<String, Int>, List<Entry>>()
        synchronized(lock) {
            if (replies.isEmpty() || now < nextRoundAt) return
            for ((pcKey, r) in replies) {
                if (r.dueAt > now) continue
                val c = pcKey.substringAfter('/')
                val col = stores[pcKey.substringBefore('/')]?.collection(c) ?: continue
                for (b in r.buckets) {
                    if ((lastSent["$pcKey#$b"] ?: 0L) + RESEND_GAP_MS > now) continue
                    val es = col.entriesIn(setOf(b))
                    unitEntries[pcKey to b] = es
                    units.add(Fill.Unit(pcKey, c, b, es.firstOrNull()?.ts ?: 0L, es.sumOf { it.key.length + (it.value?.length ?: 0) * 6 / 5 + it.origin.length + 40 } + 30))
                }
                if (r.floorOnly && (lastSent["$pcKey#f"] ?: 0L) + RESEND_GAP_MS <= now) {
                    units.add(Fill.Unit(pcKey, c, -1, Long.MAX_VALUE, 60))
                    unitEntries[pcKey to -1] = emptyList()
                }
            }
        }
        if (units.isEmpty()) return
        // Build what a set of units looks like on the wire (under the lock: reads the stores).
        fun build(chosen: List<Fill.Unit>): Triple<List<Item>, Map<String, Long>, Map<String, GroupMeta>> = synchronized(lock) {
            val items = ArrayList<Item>()
            val floors = HashMap<String, Long>()
            val headers = HashMap<String, GroupMeta>()
            val entries = ArrayList<Pair<String, Entry>>()
            chosen.groupBy { it.pcKey }.forEach { (pcKey, us) ->
                val col = stores[pcKey.substringBefore('/')]?.collection(pcKey.substringAfter('/')) ?: return@forEach
                val bs = us.map { it.bucket }.filter { it >= 0 }.sorted()
                val mine = col.bucketHashes()
                floors[pcKey] = col.floor
                headers[pcKey] = GroupMeta(col.floor, bs, bs.map { mine[it] })
                items.add(Item(pcKey, null)) // header, even when the buckets are empty here
                bs.forEach { b -> unitEntries[pcKey to b].orEmpty().forEach { entries.add(pcKey to it) } }
            }
            entries.sortWith { a, b -> NEWEST_FIRST.compare(a.second, b.second) }
            entries.forEach { items.add(Item(it.first, it.second)) }
            Triple(items, floors, headers)
        }
        val rqs = synchronized(lock) { units.mapNotNull { replies[it.pcKey]?.rq }.toSet() }
        val chosen = Fill.pickRound(units, RAW_TARGET) { part ->
            val (items, floors, headers) = build(part)
            HouseSync.sealForHouse(buildMessage("pstate", items, floors, headers, "abcdefghijkm", 99, 99, true, rqs)).length <= PACK_LIMIT
        }
        val (items, floors, headers) = build(chosen)
        val messages = runCatching { seal("pstate", items, floors, headers, rqs) }.getOrDefault(emptyList())
        if (messages.isEmpty()) return
        if (!allowSend(messages.size, false, now)) {
            synchronized(lock) { nextRoundAt = now + RETRY_MS }
            return
        }
        val sent = post(messages)
        synchronized(lock) {
            nextRoundAt = now + ROUND_GAP_MS * messages.size
            if (!sent) return
            chosen.forEach { u ->
                if (u.bucket >= 0) lastSent["${u.pcKey}#${u.bucket}"] = now else lastSent["${u.pcKey}#f"] = now
                val r = replies[u.pcKey] ?: return@forEach
                if (u.bucket >= 0) r.buckets.remove(u.bucket) else r.floorOnly = false
                if (r.buckets.isEmpty() && !r.floorOnly) replies.remove(u.pcKey)
            }
            if (lastSent.size > 2000) lastSent.entries.removeAll { now - it.value > RESEND_GAP_MS }
            saveBudgetLocked(now)
        }
    }

    private suspend fun sendDigestIfDue(now: Long) {
        val d = synchronized(lock) {
            if (nextDigestAt == 0L || now < nextDigestAt) return
            val forced = forceDigest
            if (!forced && lastCheckAt > 0L && now - lastCheckAt < CHECK_SKIP_MS) {
                // A TV of the house checked recently (or we did, before a restart): wait for the next slot.
                nextDigestAt = lastCheckAt + DIGEST_EVERY_MS + Random.nextLong(-DIGEST_JITTER_MS, DIGEST_JITTER_MS)
                return
            }
            val out = LinkedHashMap<String, String>()
            stores.forEach { (p, s) ->
                s.collections().forEach { (c, col) ->
                    val b = AntiEntropy.bucketString(col)
                    out[pc(p, c)] = if (b.isEmpty()) col.digest() else col.digest() + ":" + b
                }
            }
            out
        }
        val rq = randomSid()
        // Usually one message; split by collection if it ever gets too big.
        val keys = d.keys.toList()
        val packed = Packer.pack(
            keys,
            rawSize = { it.length + d.getValue(it).length + 8 },
            rawTarget = RAW_TARGET,
            fits = { part -> HouseSync.sealForHouse(digestJson(part, d, rq)).length <= PACK_LIMIT },
        )
        val messages = packed.parts.map { HouseSync.sealForHouse(digestJson(it, d, rq)) }
        val sent = messages.isNotEmpty() && allowSend(messages.size, false, now) && post(messages)
        synchronized(lock) {
            if (sent) {
                lastCheckAt = now
                forceDigest = false
                nextDigestAt = now + DIGEST_EVERY_MS + Random.nextLong(-DIGEST_JITTER_MS, DIGEST_JITTER_MS)
                saveBudgetLocked(now)
            } else {
                nextDigestAt = now + 30 * 60_000L
            }
        }
    }

    private fun digestJson(keys: List<String>, d: Map<String, String>, rq: String): String {
        val o = JSONObject()
        keys.forEach { o.put(it, d.getValue(it)) }
        return JSONObject().put("type", "pdigest").put("v", 1).put("from", origin()).put("rq", rq)
            .put("ts", System.currentTimeMillis()).put("d", o).toString()
    }
}
