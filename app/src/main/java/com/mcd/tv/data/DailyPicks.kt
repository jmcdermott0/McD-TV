package com.mcd.tv.data

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.ln

/**
 * Picked for <name> changes once a day. Recommender ranks a wide pool by taste; this picks the day's row from it.
 * Pure (no Android, no Prefs), tests in DailyPicksTest.
 *
 * - The same day and profile always give the same row: the draw is seeded from the date and the profile id only,
 *   so a relaunch or another TV in the house with the same synced lists shows the same titles.
 * - Yesterday's row is left out when the pool has enough other titles; otherwise they fill the end of the row.
 * - Better-ranked titles are drawn more often, so taste still leads.
 * Days are local yyyymmdd Ints (no java.time: minSdk is 24).
 */
object DailyPicks {
    /** How deep into the ranking a day may draw, as a multiple of the row length. */
    private const val DEPTH = 4

    /** The local calendar day of [epochMs] as yyyymmdd. */
    fun dayOf(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): Int {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = epochMs
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    /** Milliseconds from [epochMs] to the next local midnight (at least 1). */
    fun msUntilNextDay(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val c = Calendar.getInstance(tz)
        c.timeInMillis = epochMs
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return (c.timeInMillis - epochMs).coerceAtLeast(1L)
    }

    /** A fixed number for [day] and [profile] (FNV-1a), the same on every device. */
    fun seed(day: Int, profile: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64-bit offset basis
        for (ch in "$day|$profile") {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /**
     * The day's row: up to [max] keys from [ranked] (best first), none in [exclude] (My List, watched, "Not for me").
     * Keys in [yesterday] come last, and only when there are not enough others.
     */
    fun choose(ranked: List<String>, day: Int, profile: String, exclude: Set<String>, yesterday: Set<String>, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val pool = ranked.asSequence().distinct().filter { it !in exclude }.take(max * DEPTH).toList()
        val fresh = pool.filter { it !in yesterday }
        val rng = SplitMix(seed(day, profile))
        // Weighted draw without replacement (Efraimidis-Spirakis): rank i has weight 1 / (1 + i / max).
        val drawn = fresh.mapIndexed { i, k ->
            val w = 1.0 / (1.0 + i.toDouble() / max)
            k to ln(rng.nextUnit()) / w
        }.sortedByDescending { it.second }.map { it.first }
        if (drawn.size >= max) return drawn.take(max)
        return drawn + pool.filter { it in yesterday }.take(max - drawn.size)
    }

    /** SplitMix64: a tiny generator whose output is fixed by its seed, whatever the JVM or Kotlin version. */
    private class SplitMix(private var state: Long) {
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        /** In (0, 1]: never 0, so ln() stays finite. */
        fun nextUnit(): Double = ((nextLong() ushr 11) + 1).toDouble() / (1L shl 53).toDouble()
    }
}
