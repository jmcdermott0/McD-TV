package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Holiday mode ([Holiday]): the season, the switch, and the Holiday Movies shelf rules.
 * The pool below is a small hand-made stand-in catalog (made-up keys and numbers, real-looking keywords),
 * built to hit each rule once. It is not TMDB data.
 */
class HolidayTest {

    private fun t(key: String, name: String, year: Int, genres: Set<Int>, keywords: List<String>, votes: Int = 500, rating: Double = 7.0, american: Boolean = true) =
        CatalogTitle(key, name, year, genres, keywords.map(HistoryEras::norm), votes = votes, rating = rating, american = american)

    private val FAMILY = 10751
    private val COMEDY = 35
    private val ANIMATION = 16
    private val HORROR = 27
    private val CRIME = 80
    private val TV_MOVIE = 10770

    private val pool = listOf(
        // Marquee classics (looked up by name in the app)
        t("movie:771", "Home Alone", 1990, setOf(COMEDY, FAMILY), listOf("christmas", "burglar"), votes = 11000),
        t("movie:10719", "Elf", 2003, setOf(COMEDY, FAMILY), listOf("christmas", "santa claus"), votes = 4000),
        t("movie:13187", "A Charlie Brown Christmas", 1965, setOf(ANIMATION, FAMILY, TV_MOVIE), listOf("christmas", "short film"), votes = 900),
        t("movie:13256", "A Charlie Brown Thanksgiving", 1973, setOf(ANIMATION, FAMILY, TV_MOVIE), listOf("thanksgiving"), votes = 200),
        // Discovered titles
        t("movie:900", "Snowy Sleigh Ride", 2015, setOf(FAMILY), listOf("christmas eve", "reindeer"), votes = 800, rating = 6.5),
        t("movie:901", "Turkey Trouble", 2019, setOf(ANIMATION, FAMILY), listOf("thanksgiving", "turkey"), votes = 300, rating = 6.0),
        t("movie:902", "North Pole Postman", 2010, setOf(COMEDY), listOf("north pole"), votes = 120, rating = 5.5, american = false),
        // Never on the shelf
        t("movie:950", "Silent Night Slasher", 1984, setOf(HORROR), listOf("christmas", "slasher"), votes = 700),
        t("movie:951", "Santa Heist", 2001, setOf(CRIME, COMEDY), listOf("christmas", "heist"), votes = 600),
        t("movie:952", "Office Party Hangover", 2016, setOf(COMEDY), listOf("christmas party", "drunkenness"), votes = 2000),
        t("movie:953", "Tiny Tinsel", 2020, setOf(ANIMATION), listOf("christmas", "short film"), votes = 100),
        t("movie:954", "Summer Camp Capers", 1999, setOf(COMEDY, FAMILY), listOf("summer camp"), votes = 3000),
        // The same film listed twice: shows once, the better known copy
        t("movie:960", "Snowy Sleigh Ride", 2015, setOf(FAMILY), listOf("christmas"), votes = 20),
    )

    @Test fun seasonRunsFromNovemberFirstToJanuarySixth() {
        assertFalse(Holiday.inSeason(20261031))
        assertTrue(Holiday.inSeason(20261101))
        assertTrue(Holiday.inSeason(20261225))
        assertTrue(Holiday.inSeason(20270106))
        assertFalse(Holiday.inSeason(20270107))
        assertFalse(Holiday.inSeason(20271201)) // scope is the 2026 season only
        assertEquals(20261126, Holiday.dayOf(2026, 11, 26))
    }

    @Test fun switchOffOrOutOfSeasonMeansOff() {
        assertTrue(Holiday.active(true, 20261110))
        assertFalse(Holiday.active(false, 20261110))
        assertFalse(Holiday.active(true, 20270115))
    }

    @Test fun rulesKeepFamilyHolidayTitlesOnly() {
        val by = pool.associateBy { it.key }
        assertEquals("marquee", Holiday.reason(by.getValue("movie:771")))
        assertEquals("marquee", Holiday.reason(by.getValue("movie:13187"))) // a short special, kept as a marquee title
        assertEquals("christmas", Holiday.reason(by.getValue("movie:900")))
        assertEquals("thanksgiving", Holiday.reason(by.getValue("movie:901")))
        assertEquals("christmas", Holiday.reason(by.getValue("movie:902")))
        assertNull("horror", Holiday.reason(by.getValue("movie:950")))
        assertNull("crime", Holiday.reason(by.getValue("movie:951")))
        assertNull("adult keyword", Holiday.reason(by.getValue("movie:952")))
        assertNull("short film", Holiday.reason(by.getValue("movie:953")))
        assertNull("not a holiday title", Holiday.reason(by.getValue("movie:954")))
    }

    @Test fun thanksgivingLeadsUntilThanksgivingThenChristmas() {
        val before = Holiday.shelf(pool, 20261110)
        assertEquals(
            listOf("movie:13256", "movie:901", "movie:771", "movie:10719", "movie:13187", "movie:900", "movie:902"),
            before,
        )
        assertEquals(before, Holiday.shelf(pool, Holiday.THANKSGIVING))
        val after = Holiday.shelf(pool, 20261127)
        assertEquals(
            listOf("movie:771", "movie:10719", "movie:13187", "movie:900", "movie:902", "movie:13256", "movie:901"),
            after,
        )
        assertFalse("duplicate copy left out", "movie:960" in after)
    }

    @Test fun marqueeIsFamilyRatedAndUnique() {
        val names = Holiday.MARQUEE.map { HistoryEras.normName(it.name) + it.year }
        assertEquals(names.size, names.toSet().size)
        assertTrue(Holiday.MARQUEE.all { it.type == "movie" })
        assertNull(Holiday.marqueeFor("movie", "Home Alone", 0))
        assertEquals("Home Alone", Holiday.marqueeFor("movie", "Home Alone", 1991)?.name)
    }

    // ---------------------------------------------------------------- wiring (source checks)

    private val mainSrc by lazy {
        File(System.getProperty("jarvis.mainSrc") ?: listOf("src/main/java", "app/src/main/java").first { File(it).isDirectory })
    }
    private fun src(rel: String) = File(mainSrc, rel).readText()

    @Test fun homeRowAndBrowseUseOneShelfOnEveryProfile() {
        val hub = src("com/mcd/tv/data/HolidayHub.kt")
        assertTrue("homeRow is the shelf", hub.substringAfter("suspend fun homeRow(").substringBefore("\n\n").contains("runCatching { shelf(profile, 20) }"))
        assertTrue("candidates are rated G or PG", hub.contains("\"certification.lte\" to \"PG\""))
        assertFalse("the shelf reads no viewing history", Regex("Library\\.(history|continueWatching|favorites)|Recommender\\.").containsMatchIn(hub))
        assertFalse("no profile is left out", hub.contains("KIDS_PROFILE"))
        val home = src("com/mcd/tv/ui/HomeScreen.kt")
        val row = Regex("val holidayRow by rememberLoad[^}]*\\}").find(home)?.value ?: ""
        assertTrue("Home's row loads for every profile", row.contains("HolidayHub.homeRow()") && !row.contains("kids"))
        // The emulator QA reads Home's first screen: the row must not push Picked for <name> below it.
        val picked = home.indexOf("item(key = \"suggested\")")
        val holiday = home.indexOf("item(key = com.mcd.tv.data.Holiday.ID)")
        assertTrue("Holiday Movies comes after Picked for <name>", picked in 0 until holiday)
        val browse = src("com/mcd/tv/ui/CatalogScreens.kt")
        assertTrue("Browse entry", browse.contains("MenuEntry(Holiday.LABEL, Holiday.SUBTITLE, Screen.BrowseGrid(Holiday.ID, \"\", Holiday.LABEL))"))
        assertTrue("Browse grid loads the shelf", browse.contains("Holiday.ID -> if (page == 1) com.mcd.tv.data.HolidayHub.shelf() else emptyList()"))
    }

    @Test fun holidayModeOffLeavesIntroAndPickerAsBefore() {
        val intro = src("com/mcd/tv/ui/IntroScreen.kt")
        assertTrue("the original video still plays", intro.contains("R.raw.intro_video"))
        assertTrue("jingle only in Holiday mode", intro.contains("if (!holiday) null else"))
        assertTrue("skin only in Holiday mode", intro.contains("if (holiday) HolidaySkin("))
        val picker = src("com/mcd/tv/ui/ProfilesScreen.kt")
        assertTrue("hat only in Holiday mode", picker.contains("if (hat) SantaHat("))
        assertTrue("hat comes from Holiday mode", picker.contains("HolidayHub.active()"))
        assertFalse("the photo bitmap is never drawn on", Regex("Canvas\\(photo|asAndroidBitmap|ProfilePhotos\\.(save|put|set)").containsMatchIn(picker))
    }
}
