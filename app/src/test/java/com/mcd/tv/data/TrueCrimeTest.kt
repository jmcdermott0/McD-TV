package com.mcd.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The True Crime category ([TrueCrime]) against real TMDB data: tools/truecrime/fixture.txt holds the
 * candidates its discover queries returned on 2026-10-28, the marquee titles and crime-fiction controls.
 * The shelf must equal tools/truecrime/expected.json, which the web app's tools/webtest/truecrime-test.mjs
 * checks too, so the TV and the phone show the same category. UPDATE=1 rewrites the file (review the diff).
 */
class TrueCrimeTest {

    private fun find(rel: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("$rel not found above " + System.getProperty("user.dir"))
    }

    /** key|name|year|genres|language|rating|votes|keywords, as the hub builds CatalogTitle (American = English). */
    private val pool: List<CatalogTitle> by lazy {
        find("tools/truecrime/fixture.txt").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val p = line.split("|")
            CatalogTitle(
                key = p[0], name = p[1], year = p[2].toInt(),
                genres = p[3].split(",").filter { it.isNotBlank() }.map { it.toInt() }.toSet(),
                keywords = p[7].split(";").map(HistoryEras::norm).filter { it.isNotEmpty() },
                language = p[4], votes = p[6].toInt(), rating = p[5].toDouble(), american = p[4] == "en",
            )
        }
    }
    private val byKey by lazy { pool.associateBy { it.key } }
    private val shelf: List<String> by lazy { TrueCrime.shelf(pool) }
    private fun keyOf(name: String) = pool.first { it.name == name }.key

    @Test fun fixtureIsTheRealCapture() {
        assertEquals(206, pool.size)
        assertEquals(pool.size, byKey.size)
    }

    @Test fun shelfMatchesExpected() {
        val f = find("tools/truecrime/expected.json")
        if (System.getenv("UPDATE") == "1") {
            f.writeText("{\"shelf\": [\n" + shelf.joinToString(",\n") { "  \"$it\"" } + "\n]}\n")
        }
        val want = JSONObject(f.readText()).getJSONArray("shelf")
        assertEquals(List(want.length()) { want.getString(it) }, shelf)
    }

    @Test fun marqueeDocumentariesLeadInOrder() {
        val docs = TrueCrime.MARQUEE.filter { it.doc }
        val lead = shelf.take(docs.size).map { byKey.getValue(it).name }
        assertEquals(docs.map { it.name }, lead)
        assertEquals("tv:64439", shelf.first()) // Making a Murderer
    }

    @Test fun solidSlateOfRecognizableTitles() {
        assertTrue("shelf has ${shelf.size}", shelf.size >= 120)
        // Home shows the first 20: every one is a documentary or a marquee drama.
        shelf.take(20).forEach { k ->
            val t = byKey.getValue(k)
            assertTrue("${t.name} on Home", TrueCrime.DOCUMENTARY in t.genres || TrueCrime.marqueeFor(t.type, t.name, t.year) != null)
        }
        val docs = shelf.take(60).count { TrueCrime.DOCUMENTARY in byKey.getValue(it).genres }
        assertTrue("documentaries in the first 60: $docs", docs >= 45)
        listOf(
            "Zodiac", "Killers of the Flower Moon", "In Cold Blood", "MINDHUNTER", "When They See Us", "American Crime Story",
            "DAHMER - Monster: The Jeffrey Dahmer Story", "The Act", "Unbelievable", "Dog Day Afternoon", "Catch Me If You Can",
            "Capote", "Tiger King", "The Tinder Swindler", "Amanda Knox", "Dateline", "The First 48", "West of Memphis",
        ).forEach { assertTrue("$it on the shelf", keyOf(it) in shelf) }
    }

    @Test fun crimeFictionAndOffTopicDocumentariesStayOff() {
        listOf(
            // Crime fiction, even with serial killer, mafia or "based on true story" keywords
            "Dexter", "Criminal Minds", "Law & Order: Special Victims Unit", "Breaking Bad", "The Godfather", "GoodFellas",
            "The Irishman", "Se7en", "The Silence of the Lambs", "Black Mass", "American Gangster", "The Iceman",
            // Fiction TMDB tags true crime but is not a real case
            "Bodkin", "Based on a True Story", "Manhattan",
            // Documentaries TMDB files under Crime that are about something else
            "Mayday", "Seaspiracy", "The 11th Hour", "Rotten", "Inside Job", "Auschwitz: The Nazis and the Final Solution",
            "The Devil Next Door", "The Invisible War", "The Martha Mitchell Effect", "JFK Revisited: Through the Looking Glass",
            "Dirty Wars",
            // Short films
            "Long Shot", "Detainment",
        ).forEach { assertFalse("$it is not true crime", keyOf(it) in shelf) }
    }

    @Test fun sameTitleTwiceShowsOnce() {
        // O.J.: Made in America is on TMDB as a movie and a series: the marquee movie shows, once.
        val oj = shelf.filter { byKey.getValue(it).name == "O.J.: Made in America" }
        assertEquals(listOf("movie:377462"), oj)
        assertEquals(shelf.size, shelf.toSet().size)
    }

    @Test fun reasons() {
        fun t(name: String) = byKey.getValue(keyOf(name))
        assertEquals("marquee", TrueCrime.reason(t("Making a Murderer")))
        assertEquals("doc", TrueCrime.reason(t("Tiger King")))
        assertEquals("doc", TrueCrime.reason(t("The Vow"))) // Documentary and Crime, no keywords yet
        assertEquals("drama", TrueCrime.reason(t("Woman of the Hour")))
        assertEquals("drama", TrueCrime.reason(t("Yara"))) // Drama only, but a real case
        assertNull(TrueCrime.reason(t("Dexter")))
        assertNull(TrueCrime.reason(t("Mayday")))
    }

    @Test fun marqueeMatchIsByTypeNameAndYear() {
        assertNotNull(TrueCrime.marqueeFor("tv", "MINDHUNTER", 2017))
        assertNotNull(TrueCrime.marqueeFor("tv", "DAHMER - Monster: The Jeffrey Dahmer Story", 2022))
        assertNull(TrueCrime.marqueeFor("tv", "O.J.: Made in America", 2016)) // the marquee entry is the movie
        assertNull(TrueCrime.marqueeFor("tv", "The Staircase", 2022)) // the 2022 drama, not the 2004 documentary
        assertNull(TrueCrime.marqueeFor("movie", "Zodiac", 0))
    }

    @Test fun categoryIdentityIsShared() {
        assertEquals("truecrime", TrueCrime.ID)
        assertEquals("True Crime", TrueCrime.LABEL)
    }

    private val mainSrc by lazy {
        File(System.getProperty("jarvis.mainSrc") ?: listOf("src/main/java", "app/src/main/java").first { File(it).isDirectory })
    }
    private fun src(rel: String) = File(mainSrc, rel).readText()

    @Test fun homeRowAndBrowseCategoryUseOneSource() {
        // Home's row is the first 20 of TrueCrimeHub.shelf; Browse > True Crime and the row's See all open the
        // grid of kind TrueCrime.ID, which loads the same TrueCrimeHub.shelf. No second pipeline anywhere.
        val hub = src("com/mcd/tv/data/TrueCrimeHub.kt")
        val homeRow = hub.substringAfter("suspend fun homeRow(")
        assertTrue("homeRow is the shelf", homeRow.substringBefore("\n\n").contains("runCatching { shelf(profile, 20) }"))
        val home = src("com/mcd/tv/ui/HomeScreen.kt")
        val row = Regex("val trueCrimeRow by rememberLoad[^}]*\\}").find(home)?.value ?: ""
        assertTrue("Home's True Crime row found", row.contains("TrueCrimeHub.homeRow()"))
        assertTrue("Home's See all opens the category", home.contains("Screen.BrowseGrid(com.mcd.tv.data.TrueCrime.ID, \"\", com.mcd.tv.data.TrueCrime.LABEL)"))
        val browse = src("com/mcd/tv/ui/CatalogScreens.kt")
        assertTrue("Browse menu entry", browse.contains("MenuEntry(TrueCrime.LABEL, TrueCrime.SUBTITLE, Screen.BrowseGrid(TrueCrime.ID, \"\", TrueCrime.LABEL))"))
        assertTrue("Browse grid loads the shelf", browse.contains("TrueCrime.ID -> if (page == 1) com.mcd.tv.data.TrueCrimeHub.shelf() else emptyList()"))
        assertTrue("QA screen", src("com/mcd/tv/MainActivity.kt").contains("com.mcd.tv.data.TrueCrime.ID -> Screen.BrowseGrid(com.mcd.tv.data.TrueCrime.ID"))
    }

    @Test fun homeRowSurvivesARestart() {
        // Emulator QA restarts the app and expects the Home row: the built shelf is kept on the TV
        // ("truecrime_shelf", like the History hub's lookups) and a failed first load is tried once more.
        val hub = src("com/mcd/tv/data/TrueCrimeHub.kt")
        assertTrue("shelf kept on the TV", hub.contains("STORED_KEY = \"truecrime_shelf\"") && hub.contains("storeShelf(it, now)"))
        assertTrue("stored shelf read before a rebuild", hub.contains("?: storedShelf(now, STORED_TTL_MS)"))
        assertTrue("older shelf when TMDB fails", hub.contains("storedShelf(now, Long.MAX_VALUE) ?: throw e"))
        val homeRow = hub.substringAfter("suspend fun homeRow(").substringBefore("\n    }\n")
        assertTrue("one retry", homeRow.contains("for (attempt in 0..1)") && homeRow.contains("delay(RETRY_MS)"))
    }

    @Test fun categoryReadsNoViewingHistory() {
        val forbidden = listOf(
            "Library.history", "Library.likes", "Library.favorites", "Library.isWatched", "Recommender.moreLikeThis",
            "Recommender.features", "Recommender.forYou", "becauseYouWatched", "moreLike",
        )
        listOf("com/mcd/tv/data/TrueCrimeHub.kt", "com/mcd/tv/data/TrueCrime.kt").forEach { f ->
            val code = src(f)
            forbidden.forEach { w -> assertFalse("$f must not use $w", code.contains(w)) }
        }
    }
}
