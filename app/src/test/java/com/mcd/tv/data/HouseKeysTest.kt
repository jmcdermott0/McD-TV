package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The house copy rule (HouseKeys.plan) as Prefs.importKeys applies it. A small map stands in for SharedPreferences,
 * so the test runs the same plan the TV runs and reads the settings left afterwards.
 */
class HouseKeysTest {
    // The shared list as an older Jarvis knows it (no voice keys), and as this Jarvis knows it.
    private val oldShared = listOf("addon_urls", "m3u_url", "tmdb_key", "jarvis_key", "rd_access", "rd_refresh")
    private val newShared = oldShared + HouseKeys.VOICE_SHARED

    private fun item(v: String) = JSONObject().put("t", "s").put("v", v)

    /** What a TV sends: its own values for its own shared list (missing keys are left out, as in exportKeys). */
    private fun copyOf(keys: List<String>, values: Map<String, String>): JSONObject {
        val o = JSONObject()
        keys.forEach { k -> values[k]?.let { o.put(k, item(it)) } }
        return o
    }

    /** Applies [incoming] to [prefs] the way Prefs.importKeys does. */
    private fun take(prefs: MutableMap<String, String>, keys: List<String>, incoming: JSONObject) {
        HouseKeys.plan(keys, incoming).forEach { op ->
            when (op) {
                is HouseKeys.Op.Write -> prefs[op.key] = op.item.optString("v")
                is HouseKeys.Op.Remove -> prefs.remove(op.key)
            }
        }
    }

    private fun newTv() = mutableMapOf(
        "addon_urls" to "[\"https://addon.example/manifest.json\"]",
        "m3u_url" to "https://iptv.example/get.php",
        "tmdb_key" to "tmdb-test-value",
        "jarvis_key" to "jarvis-test-value",
        "rd_access" to "rd-a",
        "rd_refresh" to "rd-r",
        "voice_key" to "voice-test-value",
        "voice_id" to "voice-id-test",
        "voice_id_backup" to "voice-backup-test",
    )

    @Test
    fun olderTvCopyNeverErasesTheVoiceKey() {
        val tv = newTv()
        // An older TV changed its playlist and sent its copy: it has no voice keys at all.
        val older = copyOf(oldShared, newTv() + ("m3u_url" to "https://iptv.example/new.php"))
        assertFalse(older.has("voice_key"))
        take(tv, newShared, older)
        assertEquals("voice-test-value", tv["voice_key"])
        assertEquals("voice-id-test", tv["voice_id"])
        assertEquals("voice-backup-test", tv["voice_id_backup"])
        assertEquals("https://iptv.example/new.php", tv["m3u_url"]) // the real change still lands
    }

    @Test
    fun existingKeysAreUntouchedByAnOlderCopyThatCarriesThem() {
        val tv = newTv()
        val before = tv.filterKeys { it in oldShared }
        take(tv, newShared, copyOf(oldShared, newTv()))
        assertEquals(before, tv.filterKeys { it in oldShared })
    }

    @Test
    fun aNewerCopyWritesTheVoiceKey() {
        val tv = newTv().apply { remove("voice_key") }
        take(tv, newShared, copyOf(newShared, newTv() + ("voice_key" to "voice-replaced")))
        assertEquals("voice-replaced", tv["voice_key"])
    }

    @Test
    fun oldKeysKeepTheOldRemoveRule() {
        // A key the other TV removed on purpose (not additive) is still removed here, as before this change.
        val tv = newTv()
        take(tv, newShared, copyOf(newShared, newTv().apply { remove("m3u_url") }))
        assertFalse(tv.containsKey("m3u_url"))
        assertEquals("voice-test-value", tv["voice_key"])
    }

    @Test
    fun anOlderTvIgnoresVoiceKeysItDoesNotKnow() {
        // The older TV imports only its own list: the voice names in a newer copy change nothing there.
        val older = newTv().filterKeys { it in oldShared }.toMutableMap()
        val before = older.toMap()
        take(older, oldShared, copyOf(newShared, newTv()))
        assertEquals(before, older)
        assertFalse(older.containsKey("voice_key"))
    }

    @Test
    fun additiveSetCoversEveryVoiceKey() {
        assertEquals(HouseKeys.VOICE_SHARED.toSet(), HouseKeys.ADDITIVE)
        HouseKeys.ADDITIVE.forEach { assertFalse(it in oldShared) }
    }

    @Test
    fun copyOnceLeavesOutRealDebridAndTheVoiceKey() {
        val once = HouseKeys.once(newShared)
        assertFalse("voice_key" in once)
        assertFalse(once.any { it.startsWith("rd_") })
        assertTrue("voice_id" in once)
        assertTrue("tmdb_key" in once)
        assertTrue("jarvis_key" in once)
    }

    @Test
    fun emptyCopyRemovesOnlyOldKeys() {
        val tv = newTv()
        take(tv, newShared, JSONObject())
        assertEquals(setOf("voice_key", "voice_id", "voice_id_backup"), tv.keys)
    }
}
