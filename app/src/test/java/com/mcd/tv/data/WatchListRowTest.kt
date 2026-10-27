package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Home's My List row: the active profile's own list, live, and gone when the list is empty. */
class WatchListRowTest {
    private data class T(val type: String, val id: Int)
    private val key: (T) -> String = { "${it.type}-${it.id}" }

    @Test fun rowShowsTheListNewestFirst() {
        val list = listOf(T("movie", 3), T("tv", 2), T("movie", 1))
        assertEquals(list, WatchListRow.items(list, key, emptySet()))
    }

    @Test fun emptyListMeansNoRow() {
        assertTrue(WatchListRow.items(emptyList(), key, emptySet()).isEmpty())
        // Everything on the list marked "Not for me": no row either, never an empty shelf.
        assertTrue(WatchListRow.items(listOf(T("movie", 1)), key, setOf("movie-1")).isEmpty())
    }

    @Test fun rowIsCappedAndHasNoDuplicates() {
        val list = (1..40).map { T("movie", it) } + T("movie", 1)
        val row = WatchListRow.items(list, key, emptySet())
        assertEquals(WatchListRow.MAX, row.size)
        assertEquals(row.size, row.distinct().size)
    }

    @Test fun rowFollowsTheListAsItChanges() {
        val before = LibraryEvents.version.value
        var list = listOf(T("movie", 1))
        assertEquals(1, WatchListRow.items(list, key, emptySet()).size)
        list = listOf(T("tv", 9)) + list
        LibraryEvents.changed()
        assertEquals(before + 1, LibraryEvents.version.value)
        assertEquals(listOf(T("tv", 9), T("movie", 1)), WatchListRow.items(list, key, emptySet()))
        list = emptyList()
        LibraryEvents.changed()
        assertEquals(before + 2, LibraryEvents.version.value)
        assertTrue(WatchListRow.items(list, key, emptySet()).isEmpty())
    }

    @Test fun homeReadsTheActiveProfilesListAndRereadsOnEveryChange() {
        val root = File(System.getProperty("jarvis.mainSrc") ?: listOf("src/main/java", "app/src/main/java").first { File(it).isDirectory })
        val home = File(root, "com/mcd/tv/ui/HomeScreen.kt").readText()
        // The row is keyed on LibraryEvents and reads the active profile's list (Library.watchlist() with no profile).
        assertTrue("Home collects LibraryEvents", home.contains("LibraryEvents.version.collectAsState"))
        assertTrue("Home reads Library.watchlist()", Regex("""remember\([^)]*libVersion[^)]*\)\s*\{[^}]*Library\.watchlist\(\)""").containsMatchIn(home))
        assertTrue("Home shows the row only when it has titles", home.contains("myList.isNotEmpty()"))
        // It sits right after Continue Watching.
        val cont = home.indexOf("item(key = \"continue\")")
        val mine = home.indexOf("item(key = \"mylist\")")
        assertTrue("My List follows Continue Watching", cont in 0 until mine)
        val between = home.substring(cont + 1, mine)
        assertTrue("nothing else between them", !between.contains("item(key ="))
        // Every local change and every change from another TV bumps the version.
        val library = File(root, "com/mcd/tv/data/Library.kt").readText()
        assertTrue("Library.toggle reports changes", Regex("""private fun toggle[\s\S]*?LibraryEvents\.changed\(\)""").containsMatchIn(library))
        val sync = File(root, "com/mcd/tv/data/sync/ProfileSync.kt").readText()
        assertTrue("ProfileSync reports lists from other TVs", Regex("""private fun writeBlob[\s\S]*?LibraryEvents\.changed\(\)""").containsMatchIn(sync))
    }
}
