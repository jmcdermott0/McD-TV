package com.mcd.tv.data

// Holiday mode (Thanksgiving and Christmas 2026): one seasonal layer over the normal app.
//  - The season: November 1, 2026 to January 6, 2027. Outside it, Holiday mode is off whatever the switch says,
//    so January needs no code change.
//  - The switch: Settings > This TV > Start-up > "Holiday mode" (pref "holiday_mode", default on, this TV only).
//  - What it adds: the normal intro video with its own holiday track in place of its sound, and two layers of snow
//    (HolidayIntro.kt), a Santa hat over each profile circle in
//    Who's watching, and the Holiday Movies shelf (Home row and Browse entry) on every profile, Kids included.
//    Off: the intro, the profile circles and Home look exactly as before. Nothing else is touched.
// The shelf rules below are pure Kotlin (no Android, no Prefs, no network), unit tested in HolidayTest.
// HolidayHub.kt gathers the candidates from TMDB (the same catalog pipeline as History and True Crime).

/** A title the shelf always shows. [thanksgiving] = a Thanksgiving title (it leads the shelf until Thanksgiving). */
data class HolidayMarquee(val type: String, val name: String, val year: Int, val thanksgiving: Boolean = false)

object Holiday {
    /** The category's identity (Home row, Browse entry, See all grid, QA screen). */
    const val ID = "holiday"
    const val LABEL = "Holiday Movies"
    const val SUBTITLE = "Thanksgiving and Christmas movies for the whole family"

    /** The season, as yyyymmdd local dates (both days included). */
    const val SEASON_START = 20261101
    const val SEASON_END = 20270106

    /** Thanksgiving 2026 (the fourth Thursday of November). Thanksgiving titles lead the shelf up to this day. */
    const val THANKSGIVING = 20261126

    fun inSeason(day: Int): Boolean = day in SEASON_START..SEASON_END

    /** Holiday mode is on when the switch is on and the date is in the season. */
    fun active(switchOn: Boolean, day: Int): Boolean = switchOn && inSeason(day)

    /** yyyymmdd for a local date. */
    fun dayOf(year: Int, month: Int, dayOfMonth: Int): Int = year * 10_000 + month * 100 + dayOfMonth

    // ---------------------------------------------------------------- what counts

    private const val ANIMATION = 16
    private const val FAMILY = 10751

    /**
     * Never on the shelf: horror, crime, thriller, war, documentary, music concert films (TV Movie stays: many
     * classic specials are filed there). A family shelf, safe on the Kids profile.
     */
    private val VETO_GENRES = setOf(27, 80, 53, 10752, 99, 10402)

    /** Keywords that make a title a Christmas title (whole words, HistoryEras.norm form). */
    private val CHRISTMAS_TERMS = listOf(
        "christmas", "xmas", "santa claus", "santa", "north pole", "reindeer", "christmas eve", "christmas tree",
        "christmas carol", "nativity", "holiday season",
    )

    /** Keywords that make a title a Thanksgiving title. */
    private val THANKSGIVING_TERMS = listOf("thanksgiving", "pilgrims", "macy s thanksgiving day parade")

    /**
     * Keywords that keep a title off a family shelf even when its genres look fine (TMDB files a few holiday
     * slashers and raunchy comedies under Comedy or Fantasy).
     */
    private val ADULT_TERMS = listOf(
        "slasher", "serial killer", "murder", "killer santa", "christmas horror", "holiday horror", "gore", "demon",
        "krampus", "sex", "sex comedy", "nudity", "drugs", "drug use", "stoner", "stoner comedy", "marijuana",
        "erotic", "erotica", "r rated", "profanity", "infidelity", "affair", "strip club", "bachelor party",
        "hangover", "drunkenness",
    )

    /** Short films are left out; the classic short specials are marquee titles. */
    private const val SHORT_FILM = "short film"

    /** The discover terms (exact TMDB keyword names) HolidayHub looks up to find candidates. */
    val DISCOVER_TERMS = listOf("christmas", "santa claus", "thanksgiving")

    /**
     * Famous family titles placed by hand, all rated G or PG in the US (names and years as TMDB lists them).
     * Looked up by name on TMDB, so they stay current; the order here is the shelf's lead order.
     */
    val MARQUEE: List<HolidayMarquee> = listOf(
        HolidayMarquee("movie", "A Charlie Brown Thanksgiving", 1973, thanksgiving = true),
        HolidayMarquee("movie", "Free Birds", 2013, thanksgiving = true),
                HolidayMarquee("movie", "Home Alone", 1990),
        HolidayMarquee("movie", "Miracle on 34th Street", 1947),
        HolidayMarquee("movie", "Elf", 2003),
        HolidayMarquee("movie", "The Polar Express", 2004),
        HolidayMarquee("movie", "How the Grinch Stole Christmas!", 1966),
        HolidayMarquee("movie", "A Charlie Brown Christmas", 1965),
        HolidayMarquee("movie", "The Muppet Christmas Carol", 1992),
        HolidayMarquee("movie", "It's a Wonderful Life", 1946),
        HolidayMarquee("movie", "A Christmas Story", 1983),
        HolidayMarquee("movie", "The Santa Clause", 1994),
        HolidayMarquee("movie", "Klaus", 2019),
        HolidayMarquee("movie", "Arthur Christmas", 2011),
        HolidayMarquee("movie", "Rudolph the Red-Nosed Reindeer", 1964),
        HolidayMarquee("movie", "Frosty the Snowman", 1969),
        HolidayMarquee("movie", "Home Alone 2: Lost in New York", 1992),
        HolidayMarquee("movie", "The Grinch", 2018),
    )

    fun marqueeFor(type: String, name: String, year: Int): HolidayMarquee? {
        if (year <= 0) return null
        val n = HistoryEras.normName(name)
        return MARQUEE.firstOrNull { it.type == type && HistoryEras.normName(it.name) == n && kotlin.math.abs(it.year - year) <= 1 }
    }

    private fun marqueeFor(t: CatalogTitle) = marqueeFor(t.type, t.name, t.year)

    private fun matches(kw: String, term: String) = kw == term || " $kw ".contains(" $term ")
    private fun anyMatch(kws: List<String>, terms: List<String>) = kws.any { k -> terms.any { matches(k, it) } }
    private fun kwsOf(t: CatalogTitle) = t.keywords.map(HistoryEras::norm).filter { it.isNotEmpty() }

    /**
     * Why a title belongs on the shelf, or null when it does not:
     *  - "marquee": placed by hand.
     *  - Never: a vetoed genre, an adult keyword, or a short film.
     *  - "thanksgiving": a Thanksgiving keyword.
     *  - "christmas": a Christmas keyword.
     * HolidayHub's discover queries ask TMDB for US ratings G and PG only, so the pool is family-rated before
     * these rules run; the rules are a second gate for titles TMDB files oddly.
     */
    fun reason(t: CatalogTitle): String? {
        if (marqueeFor(t) != null) return "marquee"
        if (t.genres.any { it in VETO_GENRES }) return null
        val kws = kwsOf(t)
        if (SHORT_FILM in kws) return null
        if (anyMatch(kws, ADULT_TERMS)) return null
        if (anyMatch(kws, THANKSGIVING_TERMS)) return "thanksgiving"
        if (anyMatch(kws, CHRISTMAS_TERMS)) return "christmas"
        return null
    }

    fun isHoliday(t: CatalogTitle): Boolean = reason(t) != null

    private fun isThanksgiving(t: CatalogTitle): Boolean =
        marqueeFor(t)?.thanksgiving ?: anyMatch(kwsOf(t), THANKSGIVING_TERMS)

    /** Within the shelf: votes, rating, American-made, then family and animation a step up. */
    fun score(t: CatalogTitle): Double =
        kotlin.math.log10(t.votes + 1.0) + t.rating / 5.0 + (if (t.american) 1.0 else 0.0) +
            (if (FAMILY in t.genres) 0.75 else 0.0) + (if (ANIMATION in t.genres) 0.25 else 0.0)

    /**
     * The shelf from a pool of candidates, as title keys, for the local date [day]:
     *  - Up to Thanksgiving: Thanksgiving titles first (marquee order, then score), then Christmas.
     *  - After Thanksgiving: Christmas first; the Thanksgiving titles move to the end.
     * Christmas = the marquee titles in MARQUEE order, then every other title by [score]. A title listed
     * twice (same name and year) shows once: the marquee entry, else the better known one.
     */
    fun shelf(pool: List<CatalogTitle>, day: Int): List<String> {
        val kept = pool.distinctBy { it.key }
            .groupBy { HistoryEras.normName(it.name) + "|" + it.year }.values
            .map { same -> same.firstOrNull { marqueeFor(it) != null } ?: same.maxByOrNull { it.votes }!! }
            .filter(::isHoliday)
        fun ordered(list: List<CatalogTitle>): List<String> {
            val (led, rest) = list.partition { marqueeFor(it) != null }
            return led.sortedBy { MARQUEE.indexOf(marqueeFor(it)) }.map { it.key } +
                rest.sortedWith(compareByDescending<CatalogTitle> { score(it) }.thenBy { it.key }).map { it.key }
        }
        val (thanks, christmas) = kept.partition(::isThanksgiving)
        return if (day <= THANKSGIVING) ordered(thanks) + ordered(christmas) else ordered(christmas) + ordered(thanks)
    }
}
