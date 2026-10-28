package com.mcd.tv.data

import com.mcd.tv.data.LiveOrganizer.ENTERTAINMENT
import com.mcd.tv.data.LiveOrganizer.EVENTS
import com.mcd.tv.data.LiveOrganizer.H24
import com.mcd.tv.data.LiveOrganizer.INTERNATIONAL
import com.mcd.tv.data.LiveOrganizer.KIDS
import com.mcd.tv.data.LiveOrganizer.LOCAL
import com.mcd.tv.data.LiveOrganizer.MOVIES
import com.mcd.tv.data.LiveOrganizer.MUSIC
import com.mcd.tv.data.LiveOrganizer.NEWS
import com.mcd.tv.data.LiveOrganizer.SPORTS
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOrganizerTest {

    // ------------------------------------------------------------------ names

    @Test
    fun cleanNameStripsCountryProviderAndQualityTags() {
        assertEquals("ESPN", LiveOrganizer.cleanName("US| ESPN HD"))
        assertEquals("Sky Sports Main Event", LiveOrganizer.cleanName("UK: Sky Sports Main Event FHD"))
        assertEquals("SKY SPORTS F1", LiveOrganizer.cleanName("|UK| SKY SPORTS F1 ᴴᴰ"))
        assertEquals("CNN", LiveOrganizer.cleanName("ᴿᴬᵂ CNN"))
        assertEquals("123 GO!", LiveOrganizer.cleanName("AT&T: 123 GO! RAW"))
        assertEquals("BBC One", LiveOrganizer.cleanName("[UK] BBC One 1080p"))
        assertEquals("HBO", LiveOrganizer.cleanName("US: HBO 60FPS (backup)"))
        assertEquals("Discovery", LiveOrganizer.cleanName("US - Discovery UHD"))
    }

    @Test
    fun cleanNameKeepsEventTitlesAndNumbers() {
        assertEquals("NFL 03: Packers @ Bears 1:00 PM", LiveOrganizer.cleanName("NFL 03: Packers @ Bears 1:00 PM"))
        assertEquals("24/7: Brooklyn Nine Nine", LiveOrganizer.cleanName("24/7: Brooklyn Nine Nine"))
        // "HD" inside a word is not a tag.
        assertEquals("HDNet Movies", LiveOrganizer.cleanName("US| HDNet Movies"))
    }

    @Test
    fun cleanNameIsNeverEmpty() {
        assertEquals("HD", LiveOrganizer.cleanName("HD"))
        assertEquals("Channel", LiveOrganizer.cleanName("   "))
        // Empty brackets left after removing tags are dropped (this regex once crashed on Android's ICU engine).
        assertEquals("Fox Sports 1", LiveOrganizer.cleanName("US| Fox Sports 1 (HD) [ ] { }"))
    }

    @Test
    fun cleanGroupMakesChipLabels() {
        assertEquals("AT&T", LiveOrganizer.cleanGroup("US| AT&T RAW 60fps"))
        assertEquals("Sports · UFC", LiveOrganizer.cleanGroup("SPORTS| UFC"))
        assertEquals("NEWS", LiveOrganizer.cleanGroup("US| NEWS"))
        assertEquals("Entertainment", LiveOrganizer.cleanGroup("US| ENTERTAINMENT"))
        assertEquals("Other", LiveOrganizer.cleanGroup("US| "))
    }

    @Test
    fun separatorsAreDetected() {
        assertTrue(LiveOrganizer.isSeparator("##### AT&T RAW 60fps #####"))
        assertTrue(LiveOrganizer.isSeparator("===== SPORTS ====="))
        assertTrue(LiveOrganizer.isSeparator("|US| ------"))
        assertTrue(LiveOrganizer.isSeparator("★★★★★"))
        assertFalse(LiveOrganizer.isSeparator("US| ESPN HD"))
        assertFalse(LiveOrganizer.isSeparator("NFL 03: Packers @ Bears 1:00 PM"))
        assertFalse(LiveOrganizer.isSeparator("E!"))
        // Two-mark headers some providers use between blocks.
        assertTrue(LiveOrganizer.isSeparator("## ENTERTAINMENT ᴿᴬᵂ/⁶⁰ᶠᵖˢ ##"))
        assertTrue(LiveOrganizer.isSeparator("## 24/7 COMEDY ᴿᴬᵂ ##"))
        assertFalse(LiveOrganizer.isSeparator("#1 Hits"))
    }

    @Test
    fun nameOnlyCountryCodesNeedADifferentGroupTag() {
        // A mixed 8K sports group: the name prefix says where the channel is from.
        assertEquals("Bulgaria", LiveOrganizer.country("8K| SPORT ON AIR", "BG: DIEMA SPORT 1"))
        assertEquals("Balkans", LiveOrganizer.country("8K| SPORT ON AIR", "HR: MAX SPORT 1"))
        assertEquals("Baltics", LiveOrganizer.country("8K| SPORT ON AIR", "LV: GO3 SPORT 1"))
        // "HR" here is the group's own horse racing tag, not Croatia.
        assertEquals(LiveOrganizer.UNKNOWN_COUNTRY, LiveOrganizer.country("HR| HORSE RACING HD/4K", "HR: ATG Live 4K"))
    }

    // ------------------------------------------------------------------ countries and sections

    @Test
    fun countryComesFromGroupPrefixGroupWordOrNamePrefix() {
        assertEquals("US", LiveOrganizer.country("US| SPORTS", "ESPN"))
        assertEquals("UK", LiveOrganizer.country("|UK| ENTERTAINMENT", "Dave"))
        assertEquals("Latino", LiveOrganizer.country("LATINO SPORTS", "TUDN"))
        assertEquals("France", LiveOrganizer.country("FRANCE", "TF1"))
        assertEquals("UK", LiveOrganizer.country("Sports", "UK: Sky Sports Main Event FHD"))
        assertEquals(LiveOrganizer.UNKNOWN_COUNTRY, LiveOrganizer.country("Sports", "ESPN"))
    }

    @Test
    fun sectionsForRealisticIptvNames() {
        assertEquals(SPORTS, LiveOrganizer.section("US| SPORTS", "US| ESPN HD"))
        assertEquals(SPORTS, LiveOrganizer.section("NFL GAME PASS", "NFL 03: Packers @ Bears 1:00 PM"))
        assertEquals(SPORTS, LiveOrganizer.section("UK| SPORTS", "UK: Sky Sports Main Event FHD"))
        assertEquals(NEWS, LiveOrganizer.section("US| NEWS", "ᴿᴬᵂ CNN"))
        assertEquals(NEWS, LiveOrganizer.section("US| LOCALS", "US| FOX NEWS HD")) // News wins over the local "FOX"
        assertEquals(H24, LiveOrganizer.section("24/7 CHANNELS", "24/7: Brooklyn Nine Nine"))
        assertEquals(H24, LiveOrganizer.section("US| SPORTS", "24/7 NFL Films")) // 24/7 wins over Sports
        assertEquals(EVENTS, LiveOrganizer.section("PPV EVENTS", "UFC 310: Main Card"))
        assertEquals(KIDS, LiveOrganizer.section("US| KIDS", "US| Cartoon Network HD"))
        assertEquals(KIDS, LiveOrganizer.section("US| ENTERTAINMENT", "Nick Jr."))
        assertEquals(MOVIES, LiveOrganizer.section("US| PREMIUM", "US| HBO 2 FHD"))
        assertEquals(MUSIC, LiveOrganizer.section("US| MUSIC", "MTV Live HD"))
        assertEquals(LOCAL, LiveOrganizer.section("US| LOCALS", "US| ABC 7 WABC New York"))
        assertEquals(LOCAL, LiveOrganizer.section("USA", "WPIX 11")) // call sign
        assertEquals(ENTERTAINMENT, LiveOrganizer.section("US| ENTERTAINMENT", "US| Bravo HD"))
        assertEquals(INTERNATIONAL, LiveOrganizer.section("FRANCE", "TF1 HD"))
    }

    @Test
    fun huntsvilleStationsAreLocalEvenWithNewsInTheName() {
        assertEquals(LOCAL, LiveOrganizer.section("USA", "WHNT News 19"))
        assertEquals(LOCAL, LiveOrganizer.section("US| NEWS", "WAAY 31 News"))
        assertEquals(LOCAL, LiveOrganizer.section("US| LOCALS", "WAFF 48 NBC"))
        assertEquals(LOCAL, LiveOrganizer.section("", "WZDX-TV FOX 54"))
        assertEquals(NEWS, LiveOrganizer.section("US| NEWS", "WHNTX News")) // not a call sign
        assertEquals(NEWS, LiveOrganizer.section("US| NEWS", "CNN"))
    }

    @Test
    fun sportChips() {
        assertEquals("Football", LiveOrganizer.sport("US| SPORTS", "NFL 03: Packers @ Bears 1:00 PM"))
        assertEquals("Football", LiveOrganizer.sport("US| SPORTS", "SEC Network"))
        assertEquals("Basketball", LiveOrganizer.sport("US| SPORTS", "NBA TV"))
        assertEquals("Baseball", LiveOrganizer.sport("US| SPORTS", "MLB Network"))
        assertEquals("Hockey", LiveOrganizer.sport("US| SPORTS", "NHL Network"))
        assertEquals("Soccer", LiveOrganizer.sport("US| SPORTS", "beIN Sports LaLiga"))
        // "FOOTBALL" outside North America is soccer.
        assertEquals("Soccer", LiveOrganizer.sport("UK| SPORTS", "Sky Sports Football"))
        assertEquals("Combat", LiveOrganizer.sport("PPV", "UFC Fight Pass"))
        assertEquals("Motorsports", LiveOrganizer.sport("UK| SPORTS", "Sky Sports F1"))
        assertEquals("Networks", LiveOrganizer.sport("US| SPORTS", "US| ESPN HD"))
        assertEquals("Networks", LiveOrganizer.sport("UK| SPORTS", "UK: Sky Sports Main Event FHD"))
    }

    // ------------------------------------------------------------------ index

    private fun ch(name: String, group: String, url: String = "http://x/${name.hashCode()}.ts") = Channel(name, null, group, url)

    @Test
    fun buildDropsSeparatorsVodAndDuplicateUrls() {
        val list = listOf(
            ch("##### US SPORTS #####", "US| SPORTS"),
            ch("US| ESPN HD", "US| SPORTS", "http://x/1.ts"),
            ch("US| ESPN FHD (copy)", "US| SPORTS", "http://x/1.ts"), // same URL
            ch("Some Movie (2024)", "VOD", "http://x/movie/u/p/9.mp4"),
            ch("Some Show S01E01", "VOD", "http://x/series/u/p/10.mp4"),
            ch("ᴿᴬᵂ CNN", "US| NEWS", "http://x/2.ts"),
        )
        val idx = LiveOrganizer.build(list)
        assertEquals(2, idx.size)
        assertEquals(listOf("ESPN", "CNN"), idx.names.toList())
        assertEquals(0, idx.indexOf("http://x/1.ts"))
        assertEquals(1, idx.indexOf("http://x/2.ts"))
        assertEquals(-1, idx.indexOf("http://x/movie/u/p/9.mp4"))
        assertArrayEquals(intArrayOf(1, 0), idx.indicesOf(listOf("http://x/2.ts", "gone", "http://x/1.ts")))
        assertTrue(idx.source === list)
    }

    @Test
    fun buildOrdersSectionsAndSportChips() {
        val list = listOf(
            ch("ᴿᴬᵂ CNN", "US| NEWS"),
            ch("NBA TV", "US| SPORTS"),
            ch("NFL 03: Packers @ Bears 1:00 PM", "US| NFL GAME PASS"),
            ch("24/7: Brooklyn Nine Nine", "24/7"),
            ch("NFL RedZone", "US| SPORTS"),
            ch("TF1 HD", "FRANCE"),
            ch("RTL", "GERMANY"),
            ch("Pro7", "GERMANY"),
        )
        val idx = LiveOrganizer.build(list)
        assertEquals(listOf(SPORTS, NEWS, H24, INTERNATIONAL), idx.sections.map { it.name })

        val sports = idx.sections.first { it.name == SPORTS }
        assertEquals(3, sports.count)
        // Football before Basketball (SPORT_ORDER), playlist order inside a chip.
        assertEquals(listOf("Football", "Basketball"), sports.subgroups.map { it.label })
        assertEquals(
            listOf("NFL 03: Packers @ Bears 1:00 PM", "NFL RedZone"),
            idx.channelsOf(sports.subgroups[0].items).map { it.name },
        )

        // International chips are countries, biggest first.
        val intl = idx.sections.first { it.name == INTERNATIONAL }
        assertEquals(listOf("Germany", "France"), intl.subgroups.map { it.label })

        // A section with a single chip shows no chips.
        assertTrue(idx.sections.first { it.name == NEWS }.subgroups.isEmpty())
    }

    @Test
    fun chipRowIsCappedWithMore() {
        // Movies (Entertainment browses by network instead of group chips).
        val list = (1..30).map { ch("Cinema $it", "US| GROUP$it") }
        val idx = LiveOrganizer.build(list)
        val ent = idx.sections.single()
        assertEquals(MOVIES, ent.name)
        assertEquals(24, ent.subgroups.size)
        assertEquals("More", ent.subgroups.last().label)
        assertEquals(30, ent.subgroups.sumOf { it.items.size })
    }

    @Test
    fun searchMatchesCleanAndOriginalNames() {
        val idx = LiveOrganizer.build(
            listOf(
                ch("US| ESPN HD", "US| SPORTS"),
                ch("US| ESPN2 HD", "US| SPORTS"),
                ch("ᴿᴬᵂ CNN", "US| NEWS"),
            ),
        )
        assertArrayEquals(intArrayOf(0, 1), idx.search("espn"))
        assertArrayEquals(intArrayOf(2), idx.search("  cnn "))
        assertArrayEquals(intArrayOf(0, 1), idx.search("US|")) // original name
        assertArrayEquals(intArrayOf(0), idx.search("espn", max = 1))
        assertArrayEquals(intArrayOf(), idx.search("   "))
    }
}
