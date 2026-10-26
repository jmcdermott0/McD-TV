package com.mcd.tv.data.sync

import java.security.MessageDigest

/*
 * Pure merge core for profile sync between TVs. No Android and no org.json here, so it runs (and is
 * tested) on a plain JVM. ProfileSync.kt is the Android glue (storage, relay messages, Library blobs).
 *
 * Model: a state-based CRDT per profile. A profile has a few named collections (history, episodes,
 * watchlist...). A collection is a map key -> Entry plus a "floor" time:
 *
 *  - Entry merge is last-writer-wins by the total order (ts, origin, deleted, value). Deletes are
 *    tombstones (deleted = true, value = null), so a delete beats any older add and a newer add beats the delete.
 *  - The floor is a max-register: everything with ts <= floor is forgotten, on every device, and any
 *    entry with ts <= floor that arrives later is ignored. Raising the floor is the ONLY way entries are
 *    pruned. Because the floor merges by max and the filter is applied uniformly, pruning can never
 *    bring back a deleted item (no resurrection) and every device ends with the same state.
 *
 * Why a floor instead of "drop tombstones older than 90 days": with per-entry time pruning, a device
 * that kept a live copy of an item deleted elsewhere would send it back after the tombstone was pruned,
 * and a receiving device cannot tell that apart from an old item it simply never had. Ignoring all old
 * incoming entries would also stop a new TV from ever getting an old My List item. The floor avoids both:
 * it is shared state, and for list collections it is never raised past the oldest live item the pruning
 * device has (so old items that device knows survive), nor past now minus [Policy.tombstoneTtlMs].
 *
 * The state after merging is a pure function of the union of all entries and the max floor, so merge is
 * commutative, associative and idempotent (see ProfileStoreTest).
 */

/** One versioned value. [value] is opaque (a JSON string for titles and history). Tombstones have value null. */
data class Entry(
    val key: String,
    val value: String?,
    val ts: Long,
    val deleted: Boolean,
    val origin: String,
) {
    init {
        require(!deleted || value == null) { "a tombstone has no value" }
    }

    companion object {
        /** Builds an entry from wire data; a tombstone's value is dropped. */
        fun of(key: String, value: String?, ts: Long, deleted: Boolean, origin: String) =
            Entry(key, if (deleted) null else value, ts, deleted || value == null, origin)
    }
}

/**
 * Total order of two versions of the same key: newer ts wins; same ts: higher origin (device id) wins;
 * then a tombstone wins; then the larger value. Every pair of distinct versions is ordered, so all
 * devices pick the same winner whatever order the versions arrive in.
 */
object EntryOrder : Comparator<Entry> {
    override fun compare(a: Entry, b: Entry): Int {
        if (a.ts != b.ts) return a.ts.compareTo(b.ts)
        val o = a.origin.compareTo(b.origin)
        if (o != 0) return o
        if (a.deleted != b.deleted) return if (a.deleted) 1 else -1
        val v = (a.value ?: "").compareTo(b.value ?: "")
        if (v != 0) return v
        return a.key.compareTo(b.key)
    }
}

/** Newest first (ties broken by the full [EntryOrder], so the order is the same on every device). */
val NEWEST_FIRST: Comparator<Entry> = EntryOrder.reversed()

/**
 * How a collection is pruned.
 * [maxEntries]: keep at most this many entries (live and tombstones); older ones go under the floor.
 * [tombstoneKeep]/[tombstoneTtlMs]: once there are more than [tombstoneKeep] tombstones, the floor may rise
 * to the newest pruned tombstone, but never to or past the oldest live entry and never past now - ttl.
 */
data class Policy(
    val maxEntries: Int = 0,
    val tombstoneKeep: Int = 0,
    val tombstoneTtlMs: Long = 0L,
)

object SyncSpec {
    const val DAY_MS = 24L * 60 * 60 * 1000
    /** Tombstones younger than this are never pruned: a TV can be off this long and still see every delete. */
    const val TOMBSTONE_TTL_MS = 180 * DAY_MS
    const val BUCKETS = 16
    /** Clocks before 2024-01-01 are treated as unset (TVs often boot before the network time arrives). */
    const val MIN_SANE_TIME = 1_704_067_200_000L
    /** ts for data that existed before sync (no timestamps): older than anything real, so any real change wins. */
    const val SEED_TS = 1_577_836_800_000L // 2020-01-01

    const val HISTORY = "history"
    const val EPISODES = "episodes"
    const val WATCHLIST = "watchlist"
    const val FAVORITES = "favorites"
    const val HIDDEN = "hidden"
    const val NOISE = "noise"
    const val LIVE_FAVORITES = "live_favorites"
    /** Titles marked "I like this" (taste). Older TVs ignore collections they don't know. */
    const val LIKES = "likes"
    /** Profile name ("name"), Settings > Taste choices ("taste") and the profile photo ("photo", "photo:N"; PhotoCodec). */
    const val META = "meta"

    val COLLECTIONS = listOf(HISTORY, EPISODES, WATCHLIST, FAVORITES, HIDDEN, NOISE, LIVE_FAVORITES, LIKES, META)

    private val LIST_POLICY = Policy(tombstoneKeep = 200, tombstoneTtlMs = TOMBSTONE_TTL_MS)

    fun policy(collection: String): Policy = when (collection) {
        HISTORY -> Policy(maxEntries = 300)
        EPISODES -> Policy(maxEntries = 3000)
        META -> Policy()
        else -> LIST_POLICY
    }

    /** Progress collections change every few seconds while playing; their deltas are batched longer. */
    fun isProgress(collection: String) = collection == HISTORY || collection == EPISODES
}

/** The changes one merge made to a collection: keys whose winning entry changed or that were pruned. */
data class MergeResult(val changedKeys: Set<String>, val floorRaised: Boolean) {
    val changed: Boolean get() = changedKeys.isNotEmpty() || floorRaised
}

class CollectionState(val name: String, val policy: Policy = SyncSpec.policy(name)) {
    private val map = HashMap<String, Entry>()
    var floor: Long = 0L
        private set
    private var bucketCache: Array<String>? = null
    private var digestCache: String? = null

    val size: Int get() = map.size
    fun get(key: String): Entry? = map[key]
    fun entries(): List<Entry> = map.values.sortedWith(NEWEST_FIRST)
    fun live(): List<Entry> = map.values.filter { !it.deleted }.sortedWith(NEWEST_FIRST)
    fun isEmpty(): Boolean = map.isEmpty() && floor == 0L

    private fun invalidate() {
        bucketCache = null
        digestCache = null
    }

    /** Takes [e] if it is newer than what we have and above the floor. Returns true when the state changed. */
    fun offer(e: Entry): Boolean {
        if (e.ts <= floor) return false
        val cur = map[e.key]
        if (cur != null && EntryOrder.compare(e, cur) <= 0) return false
        map[e.key] = e
        invalidate()
        return true
    }

    /** Raises the floor to [f] (never lowers it) and forgets every entry at or below it. Returns the removed keys. */
    fun raiseFloor(f: Long): Set<String>? {
        if (f <= floor) return null
        floor = f
        val removed = HashSet<String>()
        val it = map.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.ts <= f) {
                removed.add(e.key)
                it.remove()
            }
        }
        invalidate()
        return removed
    }

    /** Merges another device's entries (all of a collection or any part of it) and its floor. */
    fun merge(entries: Iterable<Entry>, otherFloor: Long = 0L): MergeResult {
        val changed = HashSet<String>()
        val removed = raiseFloor(otherFloor)
        if (removed != null) changed.addAll(removed)
        for (e in entries) if (offer(e)) changed.add(e.key)
        return MergeResult(changed, removed != null)
    }

    fun mergeFrom(other: CollectionState): MergeResult = merge(other.map.values, other.floor)

    /**
     * A local write: [value] null deletes. The ts is at least [now], above the current version and above
     * the floor, so a local change always wins over what this device has seen (even with a slow clock).
     * Returns the new entry, or null when nothing changed (same value already live, or already deleted).
     */
    fun localWrite(key: String, value: String?, now: Long, origin: String): Entry? {
        val cur = map[key]
        if (value == null && cur != null && cur.deleted) return null
        if (value != null && cur != null && !cur.deleted && cur.value == value) return null
        val ts = maxOf(now, (cur?.ts ?: 0L) + 1, floor + 1)
        val e = Entry.of(key, value, ts, value == null, origin)
        map[key] = e
        invalidate()
        return e
    }

    /**
     * Adds data that predates sync (or reappeared in a Library blob) with a given old [ts]. Never replaces
     * an existing version (live or deleted) and never adds anything at or under the floor.
     */
    fun seed(key: String, value: String, ts: Long, origin: String): Boolean {
        if (map.containsKey(key) || ts <= floor) return false // at or under the floor: already forgotten by the house
        return offer(Entry.of(key, value, ts, false, origin))
    }

    /** Entries changed after [since] (by ts). */
    fun since(since: Long): List<Entry> = map.values.filter { it.ts > since }.sortedWith(NEWEST_FIRST)

    /** Applies [policy]: may raise the floor. Deterministic for a given state and [now]. Returns the removed keys. */
    fun compact(now: Long): Set<String> {
        var f = floor
        if (policy.maxEntries > 0 && map.size > policy.maxEntries) {
            val sorted = map.values.sortedWith(NEWEST_FIRST)
            f = maxOf(f, sorted[policy.maxEntries].ts)
        }
        if (policy.tombstoneKeep > 0) {
            val tombs = map.values.filter { it.deleted }
            if (tombs.size > policy.tombstoneKeep) {
                var cand = tombs.sortedWith(NEWEST_FIRST)[policy.tombstoneKeep].ts
                val oldestLive = map.values.filter { !it.deleted }.minOfOrNull { it.ts }
                if (oldestLive != null) cand = minOf(cand, oldestLive - 1)
                if (policy.tombstoneTtlMs > 0) cand = minOf(cand, now - policy.tombstoneTtlMs)
                f = maxOf(f, cand)
            }
        }
        return raiseFloor(f) ?: emptySet()
    }

    // ---------------- digests ----------------

    /** Hashes of the [SyncSpec.BUCKETS] buckets (8 hex chars each). Equal hashes mean equal entries in that bucket. */
    fun bucketHashes(): Array<String> {
        bucketCache?.let { return it }
        val groups = Array(SyncSpec.BUCKETS) { ArrayList<Entry>() }
        map.values.forEach { groups[bucketOf(it.key)].add(it) }
        val out = Array(SyncSpec.BUCKETS) { b ->
            val sb = StringBuilder()
            groups[b].sortedBy { it.key }.forEach { e ->
                sb.append(e.key).append('\u0001').append(e.ts).append('\u0001').append(e.origin)
                    .append('\u0001').append(if (e.deleted) '1' else '0').append('\n')
            }
            sha256Hex(sb.toString()).take(8)
        }
        bucketCache = out
        return out
    }

    /** One hash for the whole collection (16 hex chars), including the floor. */
    fun digest(): String {
        digestCache?.let { return it }
        val d = sha256Hex("f=$floor;" + bucketHashes().joinToString(",")).take(16)
        digestCache = d
        return d
    }

    /** The entries of the given buckets, newest first. */
    fun entriesIn(buckets: Set<Int>): List<Entry> =
        map.values.filter { bucketOf(it.key) in buckets }.sortedWith(NEWEST_FIRST)

    companion object {
        /** Stable across devices and versions: String.hashCode is fixed by the Java spec. */
        fun bucketOf(key: String): Int = (key.hashCode() and 0x7fffffff) % SyncSpec.BUCKETS

        fun sha256Hex(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xff
                sb.append(hex[v shr 4]).append(hex[v and 0x0f])
            }
            return sb.toString()
        }
    }
}

/** All synced collections of one profile ("p1", "p2", "p3"). Not thread safe: callers lock. */
class ProfileStore(val profile: String) {
    private val cols = LinkedHashMap<String, CollectionState>().apply {
        SyncSpec.COLLECTIONS.forEach { put(it, CollectionState(it)) }
    }

    fun collection(name: String): CollectionState? = cols[name]
    fun collections(): Map<String, CollectionState> = cols

    /** Merges every collection of [other] into this one. Returns the collections that changed. */
    fun mergeFrom(other: ProfileStore): Map<String, MergeResult> {
        val out = LinkedHashMap<String, MergeResult>()
        cols.forEach { (name, c) ->
            val r = c.mergeFrom(other.cols.getValue(name))
            if (r.changed) out[name] = r
        }
        return out
    }

    /** Collection name -> digest. */
    fun digests(): Map<String, String> = cols.mapValues { it.value.digest() }

    /** One hash for the whole profile. */
    fun digest(): String = CollectionState.sha256Hex(cols.entries.joinToString(";") { "${it.key}=${it.value.digest()}" }).take(16)

    fun compact(now: Long): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        cols.forEach { (name, c) -> c.compact(now).takeIf { it.isNotEmpty() }?.let { out[name] = it } }
        return out
    }
}

/**
 * Splits [items] into message parts. Items are taken in order (callers sort newest first, so if the tail
 * of a long transfer is lost, the most useful data has already arrived). Each part is the longest run of
 * the next items that passes [fits] (the real encrypted size test), found by a binary search that starts
 * from a guess of about [rawTarget] characters (by [rawSize]). An item that does not fit even alone is
 * skipped and counted in [Packed.skipped]. At most [maxParts] parts; the rest counts as skipped.
 */
object Packer {
    data class Packed<T>(val parts: List<List<T>>, val skipped: Int)

    fun <T> pack(items: List<T>, rawSize: (T) -> Int, rawTarget: Int, fits: (List<T>) -> Boolean, maxParts: Int = Int.MAX_VALUE): Packed<T> {
        val parts = ArrayList<List<T>>()
        var skipped = 0
        var i = 0
        while (i < items.size) {
            if (parts.size >= maxParts) {
                skipped += items.size - i
                break
            }
            // Guess from raw sizes, then search: lo always fits (0 = nothing yet), hi never fits.
            var guess = 0
            var size = 0
            while (i + guess < items.size && (guess == 0 || size + rawSize(items[i + guess]) <= rawTarget)) {
                size += rawSize(items[i + guess])
                guess++
            }
            val n = items.size - i
            var lo = 0 // the most items known to fit
            var hi = n + 1 // the fewest items known not to fit
            var probe = guess.coerceIn(1, n)
            while (true) {
                if (fits(items.subList(i, i + probe))) lo = probe else hi = probe
                if (hi - lo <= 1) break
                probe = if (hi == n + 1) minOf(n, lo * 2) else (lo + hi) / 2
            }
            if (lo == 0) {
                skipped++
                i++
                continue
            }
            parts.add(items.subList(i, i + lo).toList())
            i += lo
        }
        return Packed(parts, skipped)
    }
}

/**
 * Anti-entropy decisions, kept pure so they can be tested: given my collection and a peer's digest
 * (whole-collection hash plus optional bucket hashes), which buckets should I send?
 */
object AntiEntropy {
    /** Collections with more entries than this also advertise bucket hashes in a digest. */
    const val BUCKET_THRESHOLD = 24

    /** Bucket hashes as one string (8 hex chars per bucket), or "" when the collection is small. */
    fun bucketString(c: CollectionState): String =
        if (c.size > BUCKET_THRESHOLD) c.bucketHashes().joinToString("") else ""

    fun parseBuckets(s: String): Array<String>? {
        if (s.length != SyncSpec.BUCKETS * 8) return null
        return Array(SyncSpec.BUCKETS) { s.substring(it * 8, it * 8 + 8) }
    }

    /**
     * Buckets to send to a peer that advertised [theirDigest] and [theirBuckets] ("" if none).
     * Empty set: in sync. A non-empty result may still be all buckets when the peer gave no bucket hashes.
     * A floor-only difference returns an empty set with [floorOnly] true in [Plan].
     */
    data class Plan(val buckets: Set<Int>, val floorOnly: Boolean) {
        val needed: Boolean get() = buckets.isNotEmpty() || floorOnly
    }

    fun plan(mine: CollectionState, theirDigest: String, theirBuckets: String): Plan {
        if (mine.digest() == theirDigest) return Plan(emptySet(), false)
        val theirs = parseBuckets(theirBuckets)
        val all = (0 until SyncSpec.BUCKETS).toSet()
        if (theirs == null) {
            // Peer gave no bucket hashes (its collection is small or empty): send everything, with all 16 bucket
            // hashes in the header, so the peer can see which buckets it has that we lack and send those back.
            return Plan(all, false)
        }
        val mineB = mine.bucketHashes()
        val diff = all.filter { mineB[it] != theirs[it] }.toSet()
        return Plan(diff, diff.isEmpty())
    }
}

// =====================================================================================
// Message budget policies (pure, so they are unit tested). ProfileSync and HouseSync use them.
//
// ntfy.sh (free, no account) allows about 250 messages per day per public IP, plus a burst limit of
// 60 requests refilled 1 per 5 s. All TVs of a house share one IP, and also send settings and Control
// page messages. Profile sync aims for well under 150 messages per day for a 10 TV house.
// =====================================================================================

/**
 * Per-TV token bucket: up to [capacity] messages at once, then one more every [refillEveryMs].
 * A send of n messages needs min(n, capacity) tokens and may leave a debt (so one big transfer can
 * still go out, and then nothing else for a while).
 */
class TokenBucket(val capacity: Double, val refillEveryMs: Long) {
    var tokens: Double = capacity
        private set
    private var at: Long = 0L

    private fun refill(now: Long) {
        if (at == 0L) at = now
        if (now > at) tokens = minOf(capacity, tokens + (now - at).toDouble() / refillEveryMs)
        at = maxOf(at, now)
    }

    fun available(now: Long): Double {
        refill(now)
        return tokens
    }

    fun canTake(n: Int, now: Long): Boolean = available(now) >= minOf(n.toDouble(), capacity)

    fun tryTake(n: Int, now: Long): Boolean {
        if (!canTake(n, now)) return false
        tokens -= n
        return true
    }

    /** Restores saved state (tokens may be negative: a debt). */
    fun restore(tokens: Double, at: Long) {
        this.tokens = tokens.coerceAtMost(capacity)
        this.at = at
    }

    fun savedAt(): Long = at
}

/**
 * Backoff after failed publishes. 429 (rate limited): pause all non-urgent sync for [rateMinMs], doubling
 * up to [rateMaxMs] while 429s keep coming; it resets after a day without one. 5xx or no network: retry
 * after [errorMinMs], doubling up to [errorMaxMs]; reset on the next success. Local data is never dropped:
 * it stays in the store and goes out later.
 */
class Backoff(
    val rateMinMs: Long = 30 * 60_000L,
    val rateMaxMs: Long = 6 * 60 * 60_000L,
    val errorMinMs: Long = 30_000L,
    val errorMaxMs: Long = 30 * 60_000L,
) {
    var pausedUntil: Long = 0L
        private set
    var rateWindowMs: Long = 0L
        private set
    var lastRateLimitAt: Long = 0L
        private set
    private var errorWindowMs: Long = 0L

    fun canSend(now: Long): Boolean = now >= pausedUntil

    /** [code]: HTTP status, or -1 for no answer (network error). [jitterMs] spreads TVs apart. Returns true on success. */
    fun onResult(code: Int, now: Long, jitterMs: Long = 0L): Boolean {
        when {
            code in 200..299 -> {
                errorWindowMs = 0L
                if (lastRateLimitAt > 0L && now - lastRateLimitAt > SyncSpec.DAY_MS) rateWindowMs = 0L
                return true
            }
            code == 429 -> {
                rateWindowMs = if (rateWindowMs == 0L) rateMinMs else minOf(rateMaxMs, rateWindowMs * 2)
                lastRateLimitAt = now
                pausedUntil = maxOf(pausedUntil, now + rateWindowMs + jitterMs)
            }
            else -> {
                errorWindowMs = if (errorWindowMs == 0L) errorMinMs else minOf(errorMaxMs, errorWindowMs * 2)
                pausedUntil = maxOf(pausedUntil, now + errorWindowMs + jitterMs)
            }
        }
        return false
    }

    fun restore(pausedUntil: Long, rateWindowMs: Long, lastRateLimitAt: Long) {
        this.pausedUntil = pausedUntil
        this.rateWindowMs = rateWindowMs
        this.lastRateLimitAt = lastRateLimitAt
    }
}

/**
 * When to send the pending local changes (always all of them together, in one pdelta).
 * List changes (My List, favorites, names...): [listDebounceMs] after the last one, at most [listMaxWaitMs]
 * after the first. Progress: once playback has been quiet for [progressQuietMs] (pause, stop, exit), and at
 * most every [progressMaxWaitMs] while it keeps playing.
 */
class FlushPolicy(
    val listDebounceMs: Long = 5_000L,
    val listMaxWaitMs: Long = 30_000L,
    val progressQuietMs: Long = 60_000L,
    val progressMaxWaitMs: Long = 25 * 60_000L,
) {
    private var listFirst = 0L
    private var listLast = 0L
    private var progressFirst = 0L
    private var progressLast = 0L

    val pending: Boolean get() = listFirst > 0L || progressFirst > 0L
    /** True when a list change is waiting (the send counts as urgent for the house budget). */
    val hasListChange: Boolean get() = listFirst > 0L

    fun noteList(now: Long) {
        if (listFirst == 0L) listFirst = now
        listLast = now
    }

    fun noteProgress(now: Long) {
        if (progressFirst == 0L) progressFirst = now
        progressLast = now
    }

    fun due(now: Long): Boolean {
        val listDue = listFirst > 0L && (now - listLast >= listDebounceMs || now - listFirst >= listMaxWaitMs)
        val progressDue = progressFirst > 0L && (now - progressLast >= progressQuietMs || now - progressFirst >= progressMaxWaitMs)
        return listDue || progressDue
    }

    /** Everything pending was sent. */
    fun sent() {
        listFirst = 0L; listLast = 0L; progressFirst = 0L; progressLast = 0L
    }
}

/**
 * The house's profile-sync messages in the last 24 hours, as seen by this TV (its own sends plus every
 * message it hears from the others on the house topic). Above [softLimit] only urgent sends (list changes)
 * go out; above [hardLimit] nothing does until the count drops.
 */
class HouseBudget(val softLimit: Int = 150, val hardLimit: Int = 200, val windowMs: Long = SyncSpec.DAY_MS) {
    private val times = ArrayDeque<Long>()

    private fun trim(now: Long) {
        while (times.isNotEmpty() && now - times.first() >= windowMs) times.removeFirst()
    }

    fun record(now: Long, n: Int = 1) {
        repeat(n) { times.addLast(now) }
        while (times.size > hardLimit * 4) times.removeFirst()
    }

    fun count(now: Long): Int {
        trim(now)
        return times.size
    }

    /** Can [n] more messages go out now? [urgent]: a list change (still allowed between the soft and hard limits). */
    fun allows(n: Int, urgent: Boolean, now: Long): Boolean {
        val c = count(now)
        return if (urgent) c + n <= hardLimit else c + n <= softLimit
    }

    fun snapshot(now: Long): List<Long> {
        trim(now)
        return times.toList()
    }

    fun restore(saved: List<Long>) {
        times.clear()
        saved.sorted().forEach { times.addLast(it) }
    }
}

/**
 * Spreading an answer (pstate) over time. A unit is one bucket of one profile's collection (bucket -1:
 * the floor only). Each round sends the units that fit in ONE message, most useful first: profile names,
 * then Continue Watching (newest first), then My List, and episode marks last.
 */
object Fill {
    val PRIORITY = listOf(
        SyncSpec.META, SyncSpec.HISTORY, SyncSpec.WATCHLIST, SyncSpec.FAVORITES,
        SyncSpec.LIVE_FAVORITES, SyncSpec.HIDDEN, SyncSpec.NOISE, SyncSpec.EPISODES,
    )

    data class Unit(val pcKey: String, val collection: String, val bucket: Int, val newestTs: Long, val rawSize: Int)

    fun order(units: List<Unit>): List<Unit> = units.sortedWith(
        compareBy<Unit> { PRIORITY.indexOf(it.collection).let { i -> if (i < 0) PRIORITY.size else i } }
            .thenByDescending { it.newestTs } // newest first across all profiles (everyone's latest shows first)
            .thenBy { it.pcKey }
            .thenBy { it.bucket },
    )

    /**
     * The units for the next round: the longest prefix of [order]ed units that fits in one message.
     * When even the first unit needs more than one message, it goes alone (in several parts).
     */
    fun pickRound(units: List<Unit>, rawTarget: Int, fitsOne: (List<Unit>) -> Boolean): List<Unit> {
        if (units.isEmpty()) return emptyList()
        val ordered = order(units)
        val first = Packer.pack(ordered, { it.rawSize }, rawTarget, fitsOne, maxParts = 1).parts.firstOrNull()
        return if (first.isNullOrEmpty()) listOf(ordered.first()) else first
    }
}
