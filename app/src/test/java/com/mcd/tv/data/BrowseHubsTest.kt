package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseHubsTest {
    private fun kinds(kids: Boolean, holiday: Boolean, tc: Boolean = true) = BrowseHubs.hubs(kids, holiday, tc).map { it.kind }

    @Test
    fun firstRowHoldsTrueCrimeAndCollections() {
        val first = kinds(kids = false, holiday = true).take(BrowseHubs.PER_ROW)
        assertEquals(listOf(HubKind.TRENDING, HubKind.TRUE_CRIME, HubKind.COLLECTIONS, HubKind.NEW), first)
    }

    @Test
    fun kidsSeeNoTrueCrimeOrHistory() {
        val k = kinds(kids = true, holiday = true)
        assertFalse(HubKind.TRUE_CRIME in k)
        assertFalse(HubKind.HISTORY in k)
        assertTrue(HubKind.COLLECTIONS in k.take(BrowseHubs.PER_ROW))
        assertTrue(HubKind.HOLIDAY in k)
    }

    @Test
    fun holidayOnlyInHolidayMode() {
        assertFalse(HubKind.HOLIDAY in kinds(kids = false, holiday = false))
        assertTrue(HubKind.HOLIDAY in kinds(kids = false, holiday = true))
    }

    @Test
    fun everyTileHasAPlainLabelAndNoDuplicates() {
        val all = BrowseHubs.hubs(kids = false, holiday = true, trueCrime = true)
        assertEquals(all.size, all.map { it.kind }.toSet().size)
        all.forEach {
            assertTrue(it.label.isNotBlank())
            assertFalse(it.label + it.subtitle, (it.label + it.subtitle).contains('—'))
        }
    }

    @Test
    fun artIsNeverRepeated() {
        val order = listOf(HubKind.TRENDING, HubKind.NEW, HubKind.GENRES, HubKind.YEAR)
        val art = BrowseHubs.assignArt(
            order,
            mapOf(
                HubKind.TRENDING to listOf("/odyssey.jpg", "/b.jpg"),
                HubKind.NEW to listOf("/new.jpg"),
                HubKind.GENRES to listOf("/odyssey.jpg", "/action2.jpg"),
                HubKind.YEAR to listOf("/new.jpg"),
            ),
        )
        assertEquals("/odyssey.jpg", art[HubKind.TRENDING])
        assertEquals("/action2.jpg", art[HubKind.GENRES])
        assertFalse(HubKind.YEAR in art)
        assertEquals(art.size, art.values.toSet().size)
    }
}
