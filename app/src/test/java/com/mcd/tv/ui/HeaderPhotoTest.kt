package com.mcd.tv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The circle at the right of the top bar shows the active profile's photo (set from the phone, v0.2.212), not the
 * person icon, whenever the photo store has one. It reads the same store as Who's watching (ProfilePhotos), redraws
 * when a photo arrives (ProfilePhotos.revision), and follows the active profile. Compose can't run on the plain JVM,
 * so this reads the source, like HistorySectionsTest.
 */
class HeaderPhotoTest {
    private val root = File(System.getProperty("jarvis.mainSrc") ?: listOf("src/main/java", "app/src/main/java").first { File(it).isDirectory })
    private val common = File(root, "com/mcd/tv/ui/Common.kt").readText()

    private fun body(name: String): String {
        val start = common.indexOf("fun $name(")
        assertTrue("$name exists", start >= 0)
        val next = common.indexOf("\n@Composable", start).let { if (it < 0) common.length else it }
        return common.substring(start, next)
    }

    @Test fun topBarReadsTheActiveProfilesPhotoFromTheStore() {
        val nav = body("TopNav")
        assertTrue("redraws when a photo arrives", nav.contains("ProfilePhotos.revision.collectAsState()"))
        assertTrue("reads the active profile", nav.contains("Prefs.activeProfile"))
        assertTrue(
            "the photo is keyed on the revision and the active profile",
            Regex("""remember\(\s*photoRev\s*,\s*active\s*\)\s*\{\s*ProfilePhotos\.bitmap\(active\)""").containsMatchIn(nav),
        )
        assertTrue("passes the photo to the circle", Regex("""ProfileCircle\([\s\S]*?photo\s*=""").containsMatchIn(nav))
    }

    @Test fun circleDrawsThePhotoAndThePersonIconOnlyWithoutOne() {
        val circle = body("ProfileCircle")
        assertTrue("takes a photo", Regex("""photo:\s*ImageBitmap\?""").containsMatchIn(circle))
        val photoAt = circle.indexOf("if (photo != null)")
        assertTrue("draws the photo when there is one", photoAt >= 0 && circle.indexOf("Image(", photoAt) > photoAt)
        // The person icon (head circle and shoulders arc) is drawn only in the no-photo branch.
        val iconAt = circle.indexOf("drawArc(")
        assertTrue("person icon still drawn", iconAt >= 0)
        assertTrue("person icon only without a photo", Regex("""if \(photo == null\)[\s\S]*drawArc\(""").containsMatchIn(circle))
        assertFalse("no new storage: no file or prefs reads in the bar", body("TopNav").contains("Prefs.json") || circle.contains("File("))
    }
}
