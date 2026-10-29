package com.mcd.tv.data

import android.content.Context
import android.content.SharedPreferences
import com.mcd.tv.data.sync.ProfileSync
import org.json.JSONArray

/**
 * All app settings. MainActivity calls [init] once, so the rest of the app can read
 * settings without passing a Context around.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.applicationContext.getSharedPreferences("mcdtv_settings", Context.MODE_PRIVATE)
        migrateToProfiles()
        ProfileSync.init(context)
        ProfilePhotos.init(context)
    }

    // ---- Profiles (no login): Dad, Mom, Kids on this TV ----
    /** Fixed profile ids. Names can be changed in Settings; the ids never change. */
    val PROFILE_IDS = listOf("p1", "p2", "p3")
    private val PROFILE_DEFAULT_NAMES = mapOf("p1" to "Dad", "p2" to "Mom", "p3" to "Kids")
    /** The kids profile: Home shows family and animation rows. */
    const val KIDS_PROFILE = "p3"

    /**
     * Settings stored once per profile (watch history, progress, My List, favorites, Live TV favorites,
     * recents and last channel). The stored key is "<key>@<profile id>". Everything else is shared by the whole TV.
     * All of these except Live TV recents and the last channel also sync between the house's TVs (ProfileSync),
     * and so do the profile names.
     */
    private val PROFILE_KEYS = listOf(
        "lib_favorites", "lib_watchlist", "lib_noise", "lib_hidden", "lib_history", "lib_episodes",
        "live_favorites", "live_recents", "live_last_url", "lib_likes", "live_stats",
    )

    /** The profile watching now (this TV only, never synced). */
    var activeProfile: String
        get() = str("active_profile").takeIf { it in PROFILE_IDS } ?: PROFILE_IDS[0]
        set(v) = put("active_profile", if (v in PROFILE_IDS) v else PROFILE_IDS[0])

    val isKidsProfile: Boolean get() = activeProfile == KIDS_PROFILE

    fun profileName(id: String): String = str("profile_name_$id").ifBlank { PROFILE_DEFAULT_NAMES[id] ?: "Profile" }

    /** Renames a profile here and on the other TVs of the house. */
    fun setProfileName(id: String, name: String) {
        put("profile_name_$id", name.take(20))
        ProfileSync.onProfileName(id, str("profile_name_$id"))
    }

    /** The name typed for [id] ("" when it still has its default name). */
    fun customProfileName(id: String): String = str("profile_name_$id")

    /** A name that came from another TV (not sent back). */
    internal fun applySyncedProfileName(id: String, name: String) = put("profile_name_$id", name.take(20))

    val activeProfileName: String get() = profileName(activeProfile)

    /** The storage key for [key] in the active profile. */
    fun profileKey(key: String): String = "$key@$activeProfile"

    /**
     * Data saved before profiles existed (or copied in from an older TV through the account) has no
     * "@profile" suffix: it becomes the first profile's data, so nothing is lost. Safe to run any time.
     */
    private fun migrateToProfiles() { synchronized(listLock) {
        val all = sp.all
        val e = sp.edit()
        var changed = false
        PROFILE_KEYS.forEach { k ->
            val v = all[k] as? String ?: return@forEach
            val target = "$k@${PROFILE_IDS[0]}"
            if (!all.containsKey(target)) e.putString(target, v)
            e.remove(k)
            changed = true
        }
        if (changed) e.apply()
    } }

    private fun str(key: String) = sp.getString(key, "") ?: ""
    private fun put(key: String, value: String) = sp.edit().putString(key, value.trim()).apply()

    // ---- General ----
    var customUrl: String
        get() = str("custom_stream_url")
        set(v) = put("custom_stream_url", v)

    var playIntro: Boolean
        get() = sp.getBoolean("play_intro", true)
        set(v) = sp.edit().putBoolean("play_intro", v).apply()

    /** Like HuberTV's "US Only": limit lists to US releases / US streaming providers. */
    var usOnly: Boolean
        get() = sp.getBoolean("us_only", true)
        set(v) = sp.edit().putBoolean("us_only", v).apply()

    /** Which countries' titles to show (see OriginFilter). Default hides Asian-made titles. */
    var origin: OriginFilter
        get() = OriginFilter.entries.firstOrNull { it.key == str("origin_filter") } ?: OriginFilter.NO_ASIA
        set(v) = put("origin_filter", v.key)

    /** Like HuberTV's "Slow connection": prefer smaller files and 720p when picking a source. */
    var slowConnection: Boolean
        get() = sp.getBoolean("slow_connection", false)
        set(v) = sp.edit().putBoolean("slow_connection", v).apply()

    /**
     * Settings > Addons and Live TV > Guide for free channels: the extra guide for free streaming channels
     * (GuideExtra). On by default. This TV only, never synced; nothing else changes when it is turned off.
     */
    var guideExtra: Boolean
        get() = sp.getBoolean("guide_extra", true)
        set(v) = sp.edit().putBoolean("guide_extra", v).apply()

    /** Settings > Playback > Quality: "auto" (step down from 4K when it struggles), "4k" or "1080". This TV only, never synced. */
    var qualityMode: String
        get() = QualityMode.clean(sp.getString("quality_mode", QualityMode.AUTO))
        set(v) = put("quality_mode", QualityMode.clean(v))

    /** Source list: movie files bigger than this many GB are hidden (0 = no limit). Synced with the account. */
    var maxMovieGb: Int
        get() = sp.getInt("max_movie_gb", 40)
        set(v) = sp.edit().putInt("max_movie_gb", v).apply()

    /** Source list: episode files bigger than this many GB are hidden (0 = no limit). Synced with the account. */
    var maxEpisodeGb: Int
        get() = sp.getInt("max_episode_gb", 12)
        set(v) = sp.edit().putInt("max_episode_gb", v).apply()

    // ---- Metadata ----
    var tmdbKey: String
        get() = str("tmdb_key").ifBlank { com.mcd.tv.BuildConfig.TMDB_KEY }
        set(v) = put("tmdb_key", v)

    /** MDBList key for IMDb / Rotten Tomatoes scores. A key saved in the app wins over the built-in one. */
    var mdblistKey: String
        get() = str("mdblist_key").ifBlank { com.mcd.tv.BuildConfig.MDBLIST_KEY }
        set(v) = put("mdblist_key", v)

    // ---- Ask Jarvis ----
    /** API key for Ask Jarvis. Shared (encrypted) with TVs linked by [HouseSync] and with the paired web app (web_init). */
    var jarvisKey: String
        get() = str("jarvis_key")
        set(v) = put("jarvis_key", v)

    /** Model id for Ask Jarvis. Blank = the default in [Jarvis]. */
    var jarvisModel: String
        get() = str("jarvis_model").ifBlank { "claude-sonnet-5-5" }
        set(v) = put("jarvis_model", v)

    // ---- Jarvis Wall (calendar and photo frame) ----
    /** Address of the jarvis-wall Netlify site (default: the family's site). Shared with linked TVs. */
    var wallUrl: String
        get() = str("wall_url").ifBlank { "https://jarvis-wall.netlify.app" }
        set(v) = put("wall_url", v)

    /** Read-only wall key (WALL_KEY on Netlify). Shared with linked TVs. */
    var wallKey: String
        get() = str("wall_key")
        set(v) = put("wall_key", v)

    /** Baby due date as "yyyy-mm-dd" ("" = none): the Wall shows a countdown. Shared with linked TVs. */
    var wallDue: String
        get() = str("wall_due")
        set(v) = put("wall_due", v)

    /** Baby's first name for the Wall countdown ("" = "BABY"). Kept out of the repo. Shared with linked TVs. */
    var wallBaby: String
        get() = str("wall_baby")
        set(v) = put("wall_baby", v)

    /** This TV opens straight into the Wall (no intro, no profile picker). This TV only, never synced. */
    var wallStart: Boolean
        get() = sp.getBoolean("wall_start", false)
        set(v) = sp.edit().putBoolean("wall_start", v).apply()

    /** Wall view on this TV: "" = photos and calendar, "photos" = photos only (set from the Control page or Settings). This TV only. */
    var wallMode: String
        get() = str("wall_mode")
        set(v) = put("wall_mode", if (v == "photos") "photos" else "")

    /** How often the Wall shows the month calendar, as "every/stays" minutes ("30/1"; "0/0" = never). This TV only. */
    var wallCycle: String
        get() = WallCycle.clean(str("wall_cycle"))
        set(v) = put("wall_cycle", WallCycle.clean(v))

    /** The name J gave this TV ("Living Room TV", "The Wall"). This TV only, never shared through the house. */
    var tvLabel: String
        get() = str("tv_label")
        set(v) = put("tv_label", TvIdentity.cleanLabel(v))

    /** Last calendar feed, shown at once after a restart or while offline. This TV only. */
    var wallCache: String
        get() = str("wall_cache")
        set(v) = sp.edit().putString("wall_cache", v).apply()

    // ---- Live TV ----
    var m3uUrl: String
        get() = str("m3u_url")
        set(v) = put("m3u_url", v)

    /** Guards the JSON list settings, which are read-modify-written from several threads. */
    private val listLock = Any()

    private fun strList(key: String): List<String> = synchronized(listLock) {
        runCatching {
            val a = JSONArray(sp.getString(key, "[]"))
            List(a.length()) { a.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun putStrList(key: String, v: List<String>) = synchronized(listLock) {
        sp.edit().putString(key, JSONArray(v.distinct()).toString()).apply()
    }

    /** Favorite Live TV channels, by stream URL. */
    val liveFavorites: List<String> get() = strList(profileKey("live_favorites"))

    /** Adds or removes a favorite channel. Returns true when it is now a favorite. */
    fun toggleLiveFavorite(url: String): Boolean = synchronized(listLock) {
        val profile = activeProfile
        val k = "live_favorites@$profile"
        val cur = strList(k)
        val nowFav = url !in cur
        putStrList(k, if (nowFav) cur + url else cur - url)
        ProfileSync.onLiveFavorite(profile, url, nowFav)
        ResumeCloud.onLiveFavorites(profile)
        nowFav
    }

    /**
     * Sets a favorite channel of [profile] (any profile, not only the active one): the web app's star.
     * [share] false: the change came from the shared copy (HouseCloud), so it is not sent back there.
     */
    fun setLiveFavorite(profile: String, url: String, on: Boolean, share: Boolean = true) = synchronized(listLock) {
        if (profile !in PROFILE_IDS || url.isBlank()) return@synchronized
        val k = "live_favorites@$profile"
        val cur = strList(k)
        if ((url in cur) == on) return@synchronized
        putStrList(k, if (on) cur + url else cur - url)
        ProfileSync.onLiveFavorite(profile, url, on)
        if (share) ResumeCloud.onLiveFavorites(profile)
    }

    /** For ProfileSync: runs [block] under the lock of the JSON list settings (Live TV favorites). */
    internal fun <T> withListLock(block: () -> T): T = synchronized(listLock) { block() }

    /** Recently watched Live TV channels (stream URLs), most recent first, at most 20. */
    val liveRecents: List<String> get() = strList(profileKey("live_recents"))

    /** Records a channel as watched: moves it to the front of Recent and makes it the last channel. */
    fun addLiveRecent(url: String) = synchronized(listLock) {
        val k = profileKey("live_recents")
        putStrList(k, (listOf(url) + strList(k).filter { it != url }).take(20))
        put(profileKey("live_last_url"), url)
    }

    /**
     * League names hidden from Live Games for [profile] (this TV only), or null when never set (then
     * [League.DEFAULT_HIDDEN] applies). Hiding a league never touches the playlist.
     */
    fun hiddenLeagueNames(profile: String = activeProfile): List<String>? =
        if (sp.contains("live_leagues_hidden@$profile")) strList("live_leagues_hidden@$profile") else null

    fun setHiddenLeagueNames(names: List<String>, profile: String = activeProfile) = putStrList("live_leagues_hidden@$profile", names)

    /**
     * Favorite teams of [profile] as "NFL:Eagles" (this TV only), or null when never set (then
     * [FavoriteTeam.DEFAULTS] applies).
     */
    fun favoriteTeamNames(profile: String = activeProfile): List<String>? =
        if (sp.contains("live_fav_teams@$profile")) strList("live_fav_teams@$profile") else null

    fun setFavoriteTeamNames(names: List<String>, profile: String = activeProfile) = putStrList("live_fav_teams@$profile", names)

    /** The Live TV channel watched last ("" if none). */
    var liveLastUrl: String
        get() = str(profileKey("live_last_url"))
        set(v) = put(profileKey("live_last_url"), v)

    // ---- Taste (Settings > Taste): genres and topics a profile likes or avoids ----
    /**
     * JSON {"likeGenres":[ids],"avoidGenres":[ids],"likeWords":["…"],"avoidWords":["…"]}, or "" when never set.
     * Synced between the house's TVs with the profile (ProfileSync, META "taste").
     */
    fun tasteJson(profile: String = activeProfile): String = str("taste@$profile")

    fun setTasteJson(profile: String, json: String) {
        put("taste@$profile", json)
        ProfileSync.onTaste(profile, str("taste@$profile"))
    }

    internal fun applySyncedTaste(profile: String, json: String) = put("taste@$profile", json)

    // ---- Live TV minutes per channel (Live TV > For You), this TV only ----
    /** Minutes watched and the last time, per stream URL, for [profile]. */
    fun liveStats(profile: String = activeProfile): Map<String, Pair<Double, Long>> = synchronized(listLock) {
        val o = runCatching { org.json.JSONObject(str("live_stats@$profile").ifBlank { "{}" }) }.getOrNull() ?: return@synchronized emptyMap()
        val out = HashMap<String, Pair<Double, Long>>()
        o.keys().forEach { k -> o.optJSONArray(k)?.let { a -> out[k] = a.optDouble(0, 0.0) to a.optLong(1, 0L) } }
        out
    }

    /** Adds [minutes] of watching to a channel (keeps the 300 most watched). */
    fun addLiveMinutes(url: String, minutes: Double) = synchronized(listLock) {
        val k = profileKey("live_stats")
        val o = runCatching { org.json.JSONObject(str(k).ifBlank { "{}" }) }.getOrNull() ?: org.json.JSONObject()
        val cur = o.optJSONArray(url)
        val m = (cur?.optDouble(0, 0.0) ?: 0.0) + minutes
        o.put(url, JSONArray().put(m).put(System.currentTimeMillis()))
        if (o.length() > 300) {
            val keep = o.keys().asSequence().toList().sortedByDescending { o.optJSONArray(it)?.optDouble(0, 0.0) ?: 0.0 }.take(250).toSet()
            o.keys().asSequence().toList().filter { it !in keep }.forEach { o.remove(it) }
        }
        sp.edit().putString(k, o.toString()).apply()
    }

    // ---- Addons (Stremio protocol): list of manifest URLs ----
    var addonUrls: List<String>
        get() = synchronized(listLock) {
            runCatching {
                val a = JSONArray(sp.getString("addon_urls", "[]"))
                List(a.length()) { a.getString(it) }
            }.getOrDefault(emptyList())
        }
        set(v) = synchronized(listLock) { sp.edit().putString("addon_urls", JSONArray(v.distinct()).toString()).apply() }

    /** Atomically changes the addon list (no lost updates between threads). Returns the new list. */
    fun updateAddonUrls(change: (List<String>) -> List<String>): List<String> = synchronized(listLock) {
        val next = change(addonUrls).distinct()
        addonUrls = next
        next
    }

    // ---- Websites you add (opened in the built-in browser): list of name|url ----
    var websites: List<Pair<String, String>>
        get() = synchronized(listLock) {
            runCatching {
                val a = JSONArray(sp.getString("websites", "[]"))
                List(a.length()) { a.getString(it) }.map { it.substringBefore("|") to it.substringAfter("|") }
            }.getOrDefault(emptyList())
        }
        set(v) = synchronized(listLock) {
            sp.edit().putString("websites", JSONArray(v.distinctBy { it.second }.map { "${it.first}|${it.second}" }).toString()).apply()
        }

    /** Atomically changes the website list. Returns the new list. */
    fun updateWebsites(change: (List<Pair<String, String>>) -> List<Pair<String, String>>): List<Pair<String, String>> =
        synchronized(listLock) {
            val next = change(websites).distinctBy { it.second }
            websites = next
            next
        }

    /**
     * Automated-QA switch. When true, the home-network page answers /qa-relay on loopback (adb forward)
     * with the internet setup link. Off by default; never synced to the account.
     */
    var qaMode: Boolean
        get() = sp.getBoolean("qa_mode", false)
        set(v) = sp.edit().putBoolean("qa_mode", v).apply()

    // ---- Real-Debrid (device-code OAuth; nothing typed on the TV) ----
    var rdClientId: String
        get() = str("rd_client_id")
        set(v) = put("rd_client_id", v)
    var rdClientSecret: String
        get() = str("rd_client_secret")
        set(v) = put("rd_client_secret", v)
    var rdAccessToken: String
        get() = str("rd_access")
        set(v) = put("rd_access", v)
    var rdRefreshToken: String
        get() = str("rd_refresh")
        set(v) = put("rd_refresh", v)
    var rdExpiresAt: Long
        get() = sp.getLong("rd_expires", 0L)
        set(v) = sp.edit().putLong("rd_expires", v).apply()
    /** When this TV last sent a torrent to Real-Debrid (RdAddGate). This TV only. */
    var rdLastAdd: Long
        get() = sp.getLong("rd_last_add", 0L)
        set(v) = sp.edit().putLong("rd_last_add", v).apply()

    fun clearRealDebrid() {
        sp.edit().remove("rd_client_id").remove("rd_client_secret").remove("rd_access")
            .remove("rd_refresh").remove("rd_expires").apply()
    }

    // ---- McD TV account server (optional) ----
    var serverUrl: String
        get() = str("server_url")
        set(v) = put("server_url", v.trimEnd('/'))
    var accountToken: String
        get() = str("account_token")
        set(v) = put("account_token", v)
    var accountName: String
        get() = str("account_name")
        set(v) = put("account_name", v)
    var lastSyncAt: Long
        get() = sp.getLong("last_sync_at", 0L)
        set(v) = sp.edit().putLong("last_sync_at", v).apply()

    /** Keys that belong to this TV only and never sync to the account. */
    private val localOnly = setOf("server_url", "account_token", "account_name", "last_sync_at", "relay_id", "relay_key", "relay_since", "qa_mode", "jarvis_key",
        "house_id", "house_key", "house_since", "house_stamp", "device_id", "active_profile", "sync_peers", "sync_budget", "sync_pause",
        "tv_link_sig", "tv_link_at", "rd_last_add", "tv_label", "wall_mode", "wall_cycle", "wall_start", "wall_cache",
        "update_check", "recent_errors")

    // ---- Internet setup link (ntfy relay) ----
    var relayId: String
        get() = str("relay_id")
        set(v) = put("relay_id", v)
    var relayKey: String
        get() = str("relay_key")
        set(v) = put("relay_key", v)
    var relaySince: String
        get() = str("relay_since")
        set(v) = put("relay_since", v)

    // ---- Shared settings between TVs (HouseSync). This TV only. ----
    var houseId: String
        get() = str("house_id")
        set(v) = put("house_id", v)
    var houseKey: String
        get() = str("house_key")
        set(v) = put("house_key", v)
    var houseSince: String
        get() = str("house_since")
        set(v) = put("house_since", v)
    /** Version (time) of the shared settings this TV has. Newer copies from other TVs replace them. */
    var houseStamp: Long
        get() = sp.getLong("house_stamp", 0L)
        set(v) = sp.edit().putLong("house_stamp", v).apply()
    /** Random id for this TV, so it can ignore its own shared-settings messages. */
    var deviceId: String
        get() = str("device_id")
        set(v) = put("device_id", v)

    private fun typed(v: Any): org.json.JSONObject {
        val t = when (v) { is Boolean -> "b"; is Long -> "l"; is Int -> "i"; is Float -> "f"; else -> "s" }
        return org.json.JSONObject().put("t", t).put("v", v.toString())
    }

    private fun putTyped(e: SharedPreferences.Editor, k: String, item: org.json.JSONObject) {
        val v = item.optString("v")
        when (item.optString("t")) {
            "b" -> e.putBoolean(k, v.toBoolean())
            "l" -> e.putLong(k, v.toLongOrNull() ?: 0L)
            "i" -> e.putInt(k, v.toIntOrNull() ?: 0)
            "f" -> e.putFloat(k, v.toFloatOrNull() ?: 0f)
            else -> e.putString(k, v)
        }
    }

    /** The given settings, in the given order (so equal settings give equal text). Missing keys are left out. */
    fun exportKeys(keys: List<String>): org.json.JSONObject = synchronized(listLock) {
        val all = sp.all
        val o = org.json.JSONObject()
        keys.forEach { k -> all[k]?.let { o.put(k, typed(it)) } }
        o
    }

    /** Sets the given settings to [o]'s copy: keys in [o] are written, the other [keys] are removed. */
    fun importKeys(keys: List<String>, o: org.json.JSONObject) = synchronized(listLock) {
        val e = sp.edit()
        keys.forEach { k ->
            val item = o.optJSONObject(k)
            if (item == null) e.remove(k) else putTyped(e, k, item)
        }
        e.apply()
    }

    /** Every synced setting and list, as JSON, for the account server. */
    fun exportAll(): org.json.JSONObject {
        val o = org.json.JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in localOnly || v == null) return@forEach
            val t = when (v) { is Boolean -> "b"; is Long -> "l"; is Int -> "i"; is Float -> "f"; else -> "s" }
            o.put(k, org.json.JSONObject().put("t", t).put("v", v.toString()))
        }
        return o
    }

    /** Replaces this TV's synced settings and lists with the account's copy. */
    fun importAll(o: org.json.JSONObject) = synchronized(listLock) {
        val e = sp.edit()
        sp.all.keys.filter { it !in localOnly }.forEach { e.remove(it) }
        o.keys().forEach { k ->
            if (k in localOnly) return@forEach
            val item = o.getJSONObject(k)
            val v = item.optString("v")
            when (item.optString("t")) {
                "b" -> e.putBoolean(k, v.toBoolean())
                "l" -> e.putLong(k, v.toLongOrNull() ?: 0L)
                "i" -> e.putInt(k, v.toIntOrNull() ?: 0)
                "f" -> e.putFloat(k, v.toFloatOrNull() ?: 0f)
                else -> e.putString(k, v)
            }
        }
        e.apply()
        // A copy saved by an older version has data without a profile: it goes to the first profile.
        migrateToProfiles()
        // Profile data from the copy is added to the house's (never deletes): see ProfileSync.onBulkImport.
        ProfileSync.onBulkImport()
    }

    /** Calls [l] after any setting changes (on the main thread). Keep a strong reference: Android holds it weakly. */
    fun addChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = sp.registerOnSharedPreferenceChangeListener(l)

    // ---- Raw JSON blobs used by Library ----
    fun json(key: String): String = sp.getString(key, "") ?: ""
    fun putJson(key: String, value: String) = sp.edit().putString(key, value).apply()
    /** Like [putJson], but written before it returns (for a process that is about to end). */
    fun putJsonNow(key: String, value: String) = sp.edit().putString(key, value).commit()
}
