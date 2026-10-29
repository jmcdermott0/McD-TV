package com.mcd.tv.data

// LOCAL-ONLY compile stub for tools/run-unit-tests.sh. The real Prefs (data/Prefs.kt) needs android.content.
// Only the members referenced by the main files the local run compiles (Live.kt, StreamInfo.kt, RecentErrors.kt, Wall.kt).
// Unit tests must not depend on Prefs: in Gradle the real Prefs is uninitialized (lateinit SharedPreferences).
object Prefs {
    var m3uUrl: String = ""
    var slowConnection: Boolean = false
    var guideExtra: Boolean = true
    var maxMovieGb: Int = 0
    var maxEpisodeGb: Int = 0
    var wallUrl: String = ""
    var wallKey: String = ""
    var wallMode: String = ""
    var wallCycle: String = ""
    var wallCache: String = ""
    private val blobs = HashMap<String, String>()
    fun json(key: String): String = blobs[key] ?: ""
    fun putJson(key: String, value: String) { blobs[key] = value }
}
