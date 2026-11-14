package com.mcd.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LookTest {
    @Test fun missingOrUnknownIsJarvis() {
        assertEquals(Look.JARVIS, Look.fromKey(null))
        assertEquals(Look.JARVIS, Look.fromKey(""))
        assertEquals(Look.JARVIS, Look.fromKey("   "))
        assertEquals(Look.JARVIS, Look.fromKey("neon"))
        assertEquals(Look.JARVIS, Look.DEFAULT)
    }

    @Test fun savedKeysRoundTrip() {
        Look.entries.forEach { assertEquals(it, Look.fromKey(it.key)) }
        assertEquals(Look.CALM, Look.fromKey("calm"))
        assertEquals(Look.CALM, Look.fromKey(" CALM "))
    }

    @Test fun keysAndLabelsAreStable() {
        // Saved on the TV: changing these would reset everyone's choice.
        assertEquals("ui_look", Look.PREF_KEY)
        assertEquals(listOf("jarvis", "calm"), Look.entries.map { it.key })
        assertEquals(listOf("Jarvis", "Calm"), Look.entries.map { it.label })
        Look.entries.forEach { assert(!it.blurb.contains('—')) { "no em dashes in user text" } }
    }
}
