package com.mcd.tv.data

import org.json.JSONObject

/**
 * How a TV takes the house's copy of the shared settings (HouseSync). Pure, tests in HouseKeysTest.
 *
 * The old rule: every shared key missing from the copy is removed here. That is right for a key the other TV
 * removed on purpose, and wrong for a key the other TV has never heard of: a TV still on an older Jarvis leaves
 * a newer key out of its copy, and the newer TV would then erase it (a key J would have to paste again).
 *
 * Keys added after older TVs shipped are ADDITIVE: a copy that leaves one out keeps this TV's value, and only a
 * copy that holds the key overwrites it. Older TVs ignore these names, since they import only their own list.
 * Every key that existed before keeps the old rule, so nothing about today's sync changes.
 */
object HouseKeys {
    /** Jarvis voice settings (November 2026). Never removed by a house copy that does not carry them. */
    val ADDITIVE: Set<String> = setOf("voice_key", "voice_id", "voice_id_backup", "voice_model")

    /** The voice settings HouseSync shares, after the older shared keys. */
    val VOICE_SHARED: List<String> = listOf("voice_key", "voice_id", "voice_id_backup", "voice_model")

    /** A change to make to this TV's settings. */
    sealed interface Op {
        data class Write(val key: String, val item: JSONObject) : Op
        data class Remove(val key: String) : Op
    }

    /** What taking [incoming] does to [keys]: write the keys it holds, remove the others unless they are in [keepMissing]. */
    fun plan(keys: List<String>, incoming: JSONObject, keepMissing: Set<String> = ADDITIVE): List<Op> =
        keys.mapNotNull { k ->
            val item = incoming.optJSONObject(k)
            when {
                item != null -> Op.Write(k, item)
                k in keepMissing -> null
                else -> Op.Remove(k)
            }
        }

    /**
     * "Copy once" settings: everything shared except Real-Debrid (the other person keeps their own sign-in) and
     * the Jarvis voice key (it spends this house's voice credit).
     */
    fun once(shared: List<String>): List<String> = shared.filterNot { it.startsWith("rd_") || it == "voice_key" }
}
