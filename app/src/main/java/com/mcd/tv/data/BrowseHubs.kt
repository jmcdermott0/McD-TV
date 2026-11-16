package com.mcd.tv.data

/**
 * The Explore tiles at the top of Browse, in order. Pure, so the order and what each profile sees are tested
 * (BrowseHubsTest). The screen (ui/BrowseHub.kt) gives each tile art from its first title.
 *
 * Patterns taken from open-source TV apps, not their code: JetStream (Google's Compose for TV sample, Apache 2.0)
 * for art-backed category cards over a 2:3 grid, Arctic Fuse 2 (Kodi) for a hub of shortcuts under a spotlight,
 * Wholphin and NuvioTV for a backdrop that follows focus.
 *
 * The first row (4 tiles) keeps the most used ones, True Crime and Collections among them, so they are on screen
 * as soon as Browse opens (the emulator QA reads both there).
 */
enum class HubKind { TRENDING, TRUE_CRIME, COLLECTIONS, NEW, TOP, HISTORY, HOLIDAY, GENRES, YEAR, LANGUAGE, NOISE, WEBSITES }

data class Hub(val kind: HubKind, val label: String, val subtitle: String)

object BrowseHubs {
    /** Tiles per row of the Explore grid. */
    const val PER_ROW = 4

    fun hubs(kids: Boolean, holiday: Boolean, trueCrime: Boolean): List<Hub> = buildList {
        add(Hub(HubKind.TRENDING, "Trending", "This week's most watched"))
        if (trueCrime && !kids) add(Hub(HubKind.TRUE_CRIME, TrueCrime.LABEL, TrueCrime.SUBTITLE))
        add(Hub(HubKind.COLLECTIONS, "Collections", "Movie franchises"))
        add(Hub(HubKind.NEW, "New Releases", "In theaters now"))
        if (holiday) add(Hub(HubKind.HOLIDAY, Holiday.LABEL, Holiday.SUBTITLE))
        add(Hub(HubKind.TOP, "Top Rated", "Best reviewed of all time"))
        if (!kids) add(Hub(HubKind.HISTORY, "History", "Ken Burns and every era"))
        add(Hub(HubKind.GENRES, "Genres", "Action, Comedy, Drama, Horror"))
        add(Hub(HubKind.YEAR, "By Year", "Pick any year"))
        add(Hub(HubKind.LANGUAGE, "By Language", "Spanish, Korean, Japanese, Hindi"))
        add(Hub(HubKind.NOISE, "Background Noise", "Random episodes of your shows"))
        add(Hub(HubKind.WEBSITES, "Websites", "Sites you added"))
    }

    /**
     * One backdrop per tile, never the same picture twice: in [order], each tile takes its first candidate that
     * no earlier tile took. A tile whose candidates are all taken (or that has none) gets no art.
     * Example: the top trending film is also the top action film, so Genres takes the next action film.
     */
    fun assignArt(order: List<HubKind>, candidates: Map<HubKind, List<String>>): Map<HubKind, String> {
        val used = HashSet<String>()
        val out = LinkedHashMap<HubKind, String>()
        for (k in order) {
            val pick = candidates[k].orEmpty().firstOrNull { it.isNotBlank() && it !in used } ?: continue
            used.add(pick)
            out[k] = pick
        }
        return out
    }
}
