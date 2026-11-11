package com.mcd.tv.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionsTest {
    private fun t(order: Int, lang: String?, label: String? = null, forced: Boolean = false, sdh: Boolean = false, def: Boolean = false, sel: Boolean = false, ok: Boolean = true) =
        Captions.Track(order, lang, label, forced, sdh, def, sel, ok)

    @Test fun englishByCodeAndVariants() {
        for (c in listOf("en", "eng", "EN", "en-US", "en_GB", "eng-CA", "english")) assertTrue(c, Captions.isEnglish(c, null))
        for (c in listOf("es", "spa", "fr", "de", "enm", "ne")) assertFalse(c, Captions.isEnglish(c, null))
        // No code: the label decides.
        assertTrue(Captions.isEnglish(null, "English"))
        assertTrue(Captions.isEnglish("und", "English (SDH)"))
        assertTrue(Captions.isEnglish("", "eng forced"))
        assertFalse(Captions.isEnglish(null, "Spanish"))
        assertFalse(Captions.isEnglish(null, null))
        // A code that is not English wins over the label.
        assertFalse(Captions.isEnglish("es", "English"))
    }

    @Test fun picksFullEnglishBeforeSdhAndForced() {
        val tracks = listOf(
            t(0, "spa", "Spanish", def = true, sel = true),
            t(1, "eng", "English Forced", forced = true),
            t(2, "eng", "English SDH", sdh = true),
            t(3, "eng", "English"),
        )
        assertEquals(3, Captions.pickEnglish(tracks)?.order)
        assertEquals(2, Captions.pickEnglish(tracks.take(3))?.order) // SDH before forced
        assertEquals(1, Captions.pickEnglish(tracks.take(2))?.order) // forced only when nothing else is English
        assertNull(Captions.pickEnglish(tracks.take(1)))
    }

    @Test fun forcedAndSdhFromLabels() {
        assertTrue(t(0, "en", "English (Forced)").forced)
        assertTrue(t(0, "en", "English [CC]").sdh)
        assertTrue(t(0, "en", "English SDH").sdh)
        assertFalse(t(0, "en", "English").sdh)
        // Forced by flag beats a plain label.
        assertEquals(1, Captions.pickEnglish(listOf(t(0, "en", "English", forced = true), t(1, "en", "English")))?.order)
    }

    @Test fun tiesGoToDefaultThenOrder() {
        assertEquals(1, Captions.pickEnglish(listOf(t(0, "en"), t(1, "en", def = true)))?.order)
        assertEquals(0, Captions.pickEnglish(listOf(t(0, "en"), t(1, "en")))?.order)
    }

    @Test fun unsupportedTracksAreSkipped() {
        assertEquals(1, Captions.pickEnglish(listOf(t(0, "en", ok = false), t(1, "en", "English SDH")))?.order)
    }

    @Test fun defaultSelectsEnglishOverAFileDefaultInSpanish() {
        val d = CaptionDefault(Captions.ENGLISH)
        val tracks = listOf(t(0, "spa", def = true, sel = true), t(1, "eng", "English forced", forced = true), t(2, "eng", "English"))
        val a = d.onTracks(tracks, loaded = true)
        assertEquals(CaptionAction.Select(tracks[2]), a)
        assertEquals(CaptionState.English("English"), d.state)
        // After the switch the player reports English selected: nothing more to do.
        val after = listOf(t(0, "spa", def = true), t(1, "eng", "English forced", forced = true), t(2, "eng", "English", sel = true))
        assertEquals(CaptionAction.None, d.onTracks(after, loaded = true))
        assertEquals("Captions: English (default)", Captions.note(d.state, Captions.ENGLISH))
    }

    @Test fun englishOnlyWhenAlone() {
        val d = CaptionDefault(Captions.ENGLISH)
        // English and a forced Spanish both on: select English alone.
        val both = listOf(t(0, "en", sel = true), t(1, "es", forced = true, sel = true))
        assertTrue(d.onTracks(both, loaded = true) is CaptionAction.Select)
    }

    @Test fun noEnglishSaysSoAndKeepsThePlayersOwnPick() {
        val d = CaptionDefault(Captions.ENGLISH)
        assertEquals(CaptionAction.None, d.onTracks(listOf(t(0, "spa", def = true, sel = true), t(1, "fre")), loaded = true))
        assertEquals(CaptionState.NoEnglish("Spanish"), d.state)
        assertEquals("No English captions for this title. Showing Spanish.", Captions.note(d.state, Captions.ENGLISH))
        val d2 = CaptionDefault(Captions.ENGLISH)
        d2.onTracks(listOf(t(0, "fre")), loaded = true)
        assertEquals("No English captions for this title. Captions are off.", Captions.note(d2.state, Captions.ENGLISH))
    }

    @Test fun noTracksWaitsThenSaysNone() {
        val d = CaptionDefault(Captions.ENGLISH)
        d.onTracks(emptyList(), loaded = false)
        assertEquals(CaptionState.Waiting, d.state)
        assertNull(Captions.note(d.state, Captions.ENGLISH))
        d.onTracks(emptyList(), loaded = true)
        assertEquals(CaptionState.NoCaptions, d.state)
    }

    @Test fun offModeLeavesThePlayerAlone() {
        val d = CaptionDefault(Captions.OFF)
        assertEquals(CaptionAction.None, d.onTracks(listOf(t(0, "en"), t(1, "es")), loaded = true))
        assertEquals(CaptionState.DefaultOff(null), d.state)
    }

    @Test fun userPickHoldsForThisPlayback() {
        val d = CaptionDefault(Captions.ENGLISH)
        d.onTracks(listOf(t(0, "en"), t(1, "es")), loaded = true)
        d.onUserChange(off = false)
        // The CC menu picked Spanish: Jarvis does not switch it back.
        assertEquals(CaptionAction.None, d.onTracks(listOf(t(0, "en"), t(1, "es", "Spanish", sel = true)), loaded = true))
        assertEquals(CaptionState.Chosen("Spanish"), d.state)
        assertEquals(Captions.Choice.Pick("es", "Spanish"), d.choice)
        assertEquals("Captions: Spanish for this title. Next title: English.", Captions.note(d.state, Captions.ENGLISH))
    }

    @Test fun userOffHoldsEvenWithAForcedTrackShowing() {
        val d = CaptionDefault(Captions.ENGLISH)
        d.onTracks(listOf(t(0, "en", sel = true)), loaded = true)
        d.onUserChange(off = true)
        assertEquals(CaptionAction.None, d.onTracks(listOf(t(0, "en"), t(1, "en", "Forced", forced = true, sel = true)), loaded = true))
        assertEquals(CaptionState.Chosen(null), d.state)
        assertEquals(Captions.Choice.Off, d.choice)
    }

    @Test fun pickCarriesToTheSameTitleOnlyThenEnglishAgain() {
        Captions.rememberPick("movie:1:0:0", Captions.Choice.Pick("es", "Spanish"))
        val same = Captions.carried("movie:1:0:0")
        assertEquals(Captions.Choice.Pick("es", "Spanish"), same)
        // A new source for the same title: the pick is applied once.
        val d = CaptionDefault(Captions.ENGLISH, same)
        val tracks = listOf(t(0, "en", sel = true), t(1, "es", "Spanish"))
        assertEquals(CaptionAction.Select(tracks[1]), d.onTracks(tracks, loaded = true))
        // The next title: the old pick is gone and English is the default.
        assertNull(Captions.carried("movie:2:0:0"))
        assertNull(Captions.carried("movie:1:0:0"))
        val d2 = CaptionDefault(Captions.ENGLISH, Captions.carried("movie:2:0:0"))
        assertEquals(CaptionAction.Select(t(0, "en")), d2.onTracks(listOf(t(0, "en"), t(1, "es", sel = true)), loaded = true))
    }

    @Test fun carriedOffTurnsCaptionsOff() {
        val d = CaptionDefault(Captions.ENGLISH, Captions.Choice.Off)
        assertEquals(CaptionAction.TurnOff, d.onTracks(listOf(t(0, "en", sel = true)), loaded = true))
        assertEquals(CaptionState.Chosen(null), d.state)
    }

    @Test fun carriedPickMissingFromThisSourceFallsBackToEnglish() {
        val d = CaptionDefault(Captions.ENGLISH, Captions.Choice.Pick("de", "German"))
        val tracks = listOf(t(0, "en"), t(1, "es", sel = true))
        assertEquals(CaptionAction.Select(tracks[0]), d.onTracks(tracks, loaded = true))
        assertEquals(CaptionState.English("English"), d.state)
        assertNull(d.choice)
    }

    @Test fun names() {
        assertEquals("English", Captions.nameOf(t(0, "eng")))
        assertEquals("English SDH", Captions.nameOf(t(0, "en", "English", sdh = true)))
        assertEquals("English forced", Captions.nameOf(t(0, "en", forced = true)))
        assertEquals("Spanish", Captions.nameOf(t(0, "es")))
        assertEquals("Klingon", Captions.nameOf(t(0, "und", "Klingon")))
        assertEquals("Unknown language", Captions.nameOf(t(0, null)))
    }

    @Test fun addonSubtitles() {
        val arr = JSONArray(
            """[{"id":"1","url":"https://subs.example/a.srt","lang":"eng"},
               {"id":"2","url":"https://subs.example/a.srt","lang":"eng"},
               {"id":"3","url":"ftp://bad/b.srt","lang":"spa"},
               {"id":"4","url":"https://subs.example/c.vtt?x=1","lang":"spa"}]""",
        )
        val subs = Captions.parseAddonSubs(arr)
        assertEquals(listOf("https://subs.example/a.srt", "https://subs.example/c.vtt?x=1"), subs.map { it.url })
        assertEquals("application/x-subrip", Captions.mimeFor(subs[0].url))
        assertEquals("text/vtt", Captions.mimeFor(subs[1].url))
        assertEquals("application/x-subrip", Captions.mimeFor("https://x/file/123"))
        assertEquals("English (addon)", Captions.externalLabel(subs[0]))
        assertTrue(Captions.parseAddonSubs(null).isEmpty())
    }

    @Test fun modePref() {
        assertEquals(Captions.ENGLISH, Captions.cleanMode(null))
        assertEquals(Captions.ENGLISH, Captions.cleanMode("garbage"))
        assertEquals(Captions.OFF, Captions.cleanMode("off"))
    }
}
