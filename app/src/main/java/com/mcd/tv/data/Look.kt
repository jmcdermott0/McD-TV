package com.mcd.tv.data

/**
 * The app's look, chosen per TV in Settings > This TV > Look (pref [PREF_KEY], this TV only, never synced).
 * JARVIS is the cyan heads-up display and the default. CALM is the soft "dusk lake" look (same palette as
 * the web app's Calm theme). Pure: ui/Theme.kt maps each value to its palette, shapes and type.
 */
enum class Look(val key: String, val label: String, val blurb: String) {
    JARVIS("jarvis", "Jarvis", "Cyan heads-up display. The classic Jarvis look."),
    CALM("calm", "Calm", "Soft dusk colors, rounded shapes and no glow."),
    ;

    companion object {
        const val PREF_KEY = "ui_look"
        val DEFAULT = JARVIS

        /** The look saved under [key]. Anything unknown, blank or missing is the default. */
        fun fromKey(key: String?): Look {
            val k = key?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.key == k } ?: DEFAULT
        }
    }
}
