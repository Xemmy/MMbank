package com.xemmy.mmbank.domain.account

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WealthRangeTest {
    private val today = LocalDate.of(2026, 3, 18)

    @Test
    fun `each range starts the right number of days back`() {
        assertEquals(today.minusDays(6), WealthDays.start(WealthRange.WEEK, today, null))
        assertEquals(today.minusDays(29), WealthDays.start(WealthRange.MONTH, today, null))
        assertEquals(today.minusDays(364), WealthDays.start(WealthRange.YEAR, today, null))
    }

    @Test
    fun `max starts at the oldest transaction, never in the future, and falls back to a month`() {
        assertEquals(LocalDate.of(2024, 5, 2), WealthDays.start(WealthRange.MAX, today, LocalDate.of(2024, 5, 2)))
        assertEquals(today, WealthDays.start(WealthRange.MAX, today, today.plusDays(3)))
        assertEquals(today.minusDays(29), WealthDays.start(WealthRange.MAX, today, null))
    }

    @Test
    fun `a short span plots every day`() {
        val days = WealthDays.sample(today.minusDays(6), today)
        assertEquals(7, days.size)
        assertEquals(today, days.last())
    }

    @Test
    fun `a long span is thinned, keeps both ends, stays ordered and unique`() {
        val start = today.minusDays(1500)
        val days = WealthDays.sample(start, today, maxPoints = 60)
        assertEquals(60, days.size)
        assertEquals(start, days.first())
        assertEquals(today, days.last())
        assertEquals(days.sorted(), days)
        assertEquals(days.size, days.toSet().size)
        assertTrue(days.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `a single day and an inverted span give one point`() {
        assertEquals(listOf(today), WealthDays.sample(today, today))
        assertEquals(listOf(today), WealthDays.sample(today.plusDays(2), today))
    }
}
