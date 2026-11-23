package com.mcd.tv.data

import com.mcd.tv.data.Wall.Day
import org.json.JSONObject

/**
 * Jarvis voice on the Wall: runs [WallVoice] once a minute while the Wall is up. The rules live in WallVoice
 * (tests in WallVoiceTest); this keeps the frozen texts for this app run and the spoken keys in the pref
 * `voice_wall_said` (this TV only), so a restart never repeats a line.
 */
object WallSpeaker {
    private var frozen: Map<String, WallVoice.Item> = emptyMap()

    fun tick(feed: Wall.Feed?, now: Long) {
        // No key or voice off: nothing is fetched or spoken, and nothing is marked as said.
        if (!Voice.keySet || !Prefs.voiceOn) return
        val today = Day.ofMillis(now)
        val said = WallVoice.pruneSaid(loadSaid(), today).toMutableMap()
        val due = BabyCountdown.parse(Prefs.wallDue)
        val items = WallVoice.items(feed, now, Prefs.voiceBriefAt, due, Prefs.wallBaby)
        val step = WallVoice.step(items, now, frozen, said.keys)
        frozen = step.frozen
        val off = Prefs.voiceUsesOff
        step.prefetch.filter { it.speakAt > now && it.kind.key !in off && !VoicePolicy.quiet(hourOf(it.speakAt)) }
            .forEach { Voice.prefetch(it.text) }
        step.say.forEach { item ->
            Voice.say(item.kind, item.text, item.until)
            said[item.key] = today
        }
        if (step.say.isNotEmpty()) saveSaid(said)
    }

    private fun hourOf(ms: Long) = java.util.Calendar.getInstance().apply { timeInMillis = ms }.get(java.util.Calendar.HOUR_OF_DAY)

    private fun loadSaid(): Map<String, Int> = runCatching {
        val o = JSONObject(Prefs.json("voice_wall_said").ifBlank { "{}" })
        o.keys().asSequence().associateWith { o.optInt(it) }
    }.getOrDefault(emptyMap())

    private fun saveSaid(m: Map<String, Int>) {
        Prefs.putJson("voice_wall_said", JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString())
    }
}
