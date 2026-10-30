package com.mcd.tv.data

// True Crime category rules: what counts as true crime, the hand-placed marquee titles, and the shelf order.
// One category with one identity (TrueCrime.ID): the Home row and Browse > True Crime show the same shelf.
// Built the way the History hub is (HistoryEras.kt): catalog metadata only (TMDB genres and plot keywords),
// never what a profile watched, so every profile sees the same titles.
// Pure Kotlin (no Android, no Prefs, no network), so every rule is unit tested on the JVM against real TMDB
// data (tools/truecrime/fixture.txt). The web app's TC module applies the same rules to the same fixture.
// TrueCrimeHub.kt gathers the data (TMDB) and calls into this file.

/** A title the category always shows. [doc] = a documentary; documentaries lead the shelf, in list order. */
data class CrimeMarquee(val type: String, val name: String, val year: Int, val doc: Boolean)

object TrueCrime {
    /** The category's identity on both surfaces (Home row, Browse entry, See all grid, QA screen). */
    const val ID = "truecrime"
    const val LABEL = "True Crime"
    const val SUBTITLE = "Documentaries, docuseries and true-crime films"

    const val DOCUMENTARY = 99
    const val CRIME = 80
    private const val COMEDY = 35
    private const val ROMANCE = 10749
    private const val HISTORY = 36
    private const val WAR = 10752
    private const val WAR_POLITICS = 10768
    private const val ANIMATION = 16

    /** Never true crime: family, kids, fantasy, sci-fi, talk and soap. */
    private val VETO_GENRES = setOf(10751, 10762, 14, 878, 10765, 10767, 10766)

    /** Short films are left out: the shelf is for full documentaries, series and films. */
    private const val SHORT_FILM = "short film"

    /** TMDB's own true-crime keyword. */
    const val TRUE_CRIME = "true crime"

    /**
     * Keywords that make a documentary true crime. A term matches a keyword that equals it or contains it
     * as whole words (HistoryEras.norm form).
     */
    private val CRIME_TERMS = listOf(
        TRUE_CRIME, "serial killer", "murder", "murders", "homicide", "unsolved murder", "cold case", "wrongful conviction",
        "wrongful imprisonment", "miscarriage of justice", "death row", "criminal trial", "kidnapping", "abduction",
        "missing person", "missing child", "con man", "con artist", "conman", "fraud", "ponzi scheme", "cult", "heist",
        "bank robbery", "forensic science", "criminal investigation", "crime investigation", "crime documentary", "mafia",
        "organized crime", "drug lord", "cartel", "police corruption", "serial rapist", "sex crime",
    )

    /**
     * A documentary about one of these is not true crime even when TMDB files it under Crime (war, the
     * Holocaust, the environment, disasters, the food industry, the economy). TMDB's true-crime keyword wins.
     */
    private val OFF_TOPIC = listOf(
        "holocaust", "world war", "nazi", "war crime", "concentration camp", "environmental", "ecology", "climate",
        "climate change", "pollution", "global warming", "disaster", "plane crash", "airplane accident", "airplane crash",
        "food industry", "factory farming", "financial crisis", "global economy", "covert operation",
    )

    /** A drama counts as true crime only when it tells a real story. */
    private val REAL_STORY = listOf(
        "based on true story", "based on a true story", "based on real events", "based on real person", "biography",
        "historical figure", "based on magazine newspaper or article",
    )

    /** Discover terms: TMDB keyword names that find true-crime documentaries (exact names). */
    val DOC_DISCOVER_TERMS = listOf(
        "serial killer", "murder investigation", "unsolved murder", "unsolved murders", "cold case", "wrongful conviction",
        "wrongful imprisonment", "murder trial", "crime documentary",
    )

    // ---------------------------------------------------------------- marquee titles

    /**
     * Famous titles placed by hand (names and years as TMDB lists them, read on 2026-10-28). The
     * documentaries lead the shelf in this order; the dramas are always kept and ranked with the rest.
     */
    val MARQUEE: List<CrimeMarquee> = listOf(
        CrimeMarquee("tv", "Making a Murderer", 2015, doc = true),
        CrimeMarquee("tv", "The Jinx", 2015, doc = true),
        CrimeMarquee("tv", "The Staircase", 2004, doc = true),
        CrimeMarquee("tv", "Don't F**k with Cats: Hunting an Internet Killer", 2019, doc = true),
        CrimeMarquee("tv", "I'll Be Gone in the Dark", 2020, doc = true),
        CrimeMarquee("tv", "Conversations with a Killer: The Ted Bundy Tapes", 2019, doc = true),
        CrimeMarquee("tv", "Night Stalker: The Hunt for a Serial Killer", 2021, doc = true),
        CrimeMarquee("movie", "O.J.: Made in America", 2016, doc = true),
        CrimeMarquee("tv", "The Keepers", 2017, doc = true),
        CrimeMarquee("tv", "Evil Genius", 2018, doc = true),
        CrimeMarquee("tv", "Wild Wild Country", 2018, doc = true),
        CrimeMarquee("tv", "Unsolved Mysteries", 2020, doc = true),
        CrimeMarquee("movie", "The Thin Blue Line", 1988, doc = true),
        CrimeMarquee("movie", "Paradise Lost: The Child Murders at Robin Hood Hills", 1996, doc = true),
        CrimeMarquee("movie", "American Murder: The Family Next Door", 2020, doc = true),
        CrimeMarquee("movie", "The Imposter", 2012, doc = true),
        CrimeMarquee("tv", "Murdaugh Murders: A Southern Scandal", 2023, doc = true),
        CrimeMarquee("tv", "Forensic Files", 1996, doc = true),
        // Dramas of real cases
        CrimeMarquee("movie", "Zodiac", 2007, doc = false),
        CrimeMarquee("movie", "Killers of the Flower Moon", 2023, doc = false),
        CrimeMarquee("movie", "In Cold Blood", 1967, doc = false),
        CrimeMarquee("tv", "Mindhunter", 2017, doc = false),
        CrimeMarquee("tv", "When They See Us", 2019, doc = false),
        CrimeMarquee("tv", "American Crime Story", 2016, doc = false),
        CrimeMarquee("tv", "Dahmer - Monster: The Jeffrey Dahmer Story", 2022, doc = false),
        CrimeMarquee("tv", "The Act", 2019, doc = false),
        CrimeMarquee("tv", "Unbelievable", 2019, doc = false),
        CrimeMarquee("movie", "Dog Day Afternoon", 1975, doc = false),
        CrimeMarquee("movie", "Catch Me If You Can", 2002, doc = false),
        CrimeMarquee("movie", "Capote", 2005, doc = false),
    )

    /** The marquee entry for a title: same type, same name (letters and digits), a year apart at most. */
    fun marqueeFor(type: String, name: String, year: Int): CrimeMarquee? {
        if (year <= 0) return null
        val n = HistoryEras.normName(name)
        return MARQUEE.firstOrNull { it.type == type && HistoryEras.normName(it.name) == n && kotlin.math.abs(it.year - year) <= 1 }
    }

    private fun marqueeFor(t: CatalogTitle) = marqueeFor(t.type, t.name, t.year)

    // ---------------------------------------------------------------- what counts

    private fun matches(kw: String, term: String) = kw == term || " $kw ".contains(" $term ")
    private fun anyMatch(kws: List<String>, terms: List<String>) = kws.any { k -> terms.any { matches(k, it) } }
    private fun kwsOf(t: CatalogTitle) = t.keywords.map(HistoryEras::norm).filter { it.isNotEmpty() }

    /**
     * Why a title is true crime, or null when it is not:
     *  - "marquee": placed by hand.
     *  - Never: family, kids, fantasy, sci-fi, talk or soap genres, or a short film.
     *  - "doc": a documentary with TMDB's true-crime keyword, or a crime keyword or the Crime genre (unless it
     *    is about war, the Holocaust, the environment, a disaster or the economy). History and war documentaries
     *    need the true-crime keyword: they belong to the History hub.
     *  - "drama": not a documentary, with TMDB's true-crime keyword, and either the Crime genre or a real-story
     *    keyword (no comedy or romance then). Crime fiction (Dexter, The Godfather, Se7en) never qualifies.
     */
    fun reason(t: CatalogTitle): String? {
        if (marqueeFor(t) != null) return "marquee"
        if (t.genres.any { it in VETO_GENRES }) return null
        val kws = kwsOf(t)
        if (SHORT_FILM in kws) return null
        val tagged = TRUE_CRIME in kws
        if (DOCUMENTARY in t.genres) {
            if (tagged) return "doc"
            if (t.genres.any { it == HISTORY || it == WAR || it == WAR_POLITICS }) return null
            if (anyMatch(kws, OFF_TOPIC)) return null
            return if (CRIME in t.genres || anyMatch(kws, CRIME_TERMS)) "doc" else null
        }
        if (!tagged || ANIMATION in t.genres) return null
        if (CRIME in t.genres) return "drama"
        val real = anyMatch(kws, REAL_STORY)
        return if (real && t.genres.none { it == COMEDY || it == ROMANCE }) "drama" else null
    }

    fun isTrueCrime(t: CatalogTitle): Boolean = reason(t) != null

    /** Within the shelf: votes, rating, American-made, then documentaries and marquee dramas a step up. */
    fun score(t: CatalogTitle): Double {
        val m = marqueeFor(t)
        val doc = DOCUMENTARY in t.genres || m?.doc == true
        return kotlin.math.log10(t.votes + 1.0) + t.rating / 5.0 + (if (t.american) 1.0 else 0.0) +
            (if (doc) 1.5 else 0.0) + (if (m != null) 1.0 else 0.0) + (if (TRUE_CRIME in kwsOf(t)) 0.5 else 0.0)
    }

    /**
     * The True Crime shelf from a pool of candidates, as title keys: the marquee documentaries first (in
     * MARQUEE order), then every other true-crime title by [score]. A title listed twice (the same name and
     * year as a movie and a series) shows once: the marquee entry, else the better known one.
     */
    fun shelf(pool: List<CatalogTitle>): List<String> {
        val kept = pool.distinctBy { it.key }
            .groupBy { HistoryEras.normName(it.name) + "|" + it.year }.values
            .map { same -> same.firstOrNull { marqueeFor(it) != null } ?: same.maxByOrNull { it.votes }!! }
            .filter(::isTrueCrime)
        fun lead(t: CatalogTitle): Int? = marqueeFor(t)?.takeIf { it.doc }?.let(MARQUEE::indexOf)
        val (led, rest) = kept.partition { lead(it) != null }
        return led.sortedBy { lead(it) }.map { it.key } +
            rest.sortedWith(compareByDescending<CatalogTitle> { score(it) }.thenBy { it.key }).map { it.key }
    }
}
