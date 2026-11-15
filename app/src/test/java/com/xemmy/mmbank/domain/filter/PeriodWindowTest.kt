package com.xemmy.mmbank.domain.filter

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodWindowTest {
    private val today = LocalDate.of(2026, 3, 18) // Wednesday

    @Test
    fun `week starts on the given day`() {
        val mon = PeriodWindow.of(PeriodUnit.WEEK, today, DayOfWeek.MONDAY).range()!!
        assertEquals(LocalDate.of(2026, 3, 16), mon.start)
        assertEquals(LocalDate.of(2026, 3, 22), mon.end)
        val sun = PeriodWindow.of(PeriodUnit.WEEK, today, DayOfWeek.SUNDAY).range()!!
        assertEquals(LocalDate.of(2026, 3, 15), sun.start)
        assertEquals(LocalDate.of(2026, 3, 21), sun.end)
    }

    @Test
    fun `month and year ranges`() {
        assertEquals(DateWindow(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)), PeriodWindow.of(PeriodUnit.MONTH, today).range())
        assertEquals(DateWindow(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), PeriodWindow.of(PeriodUnit.YEAR, today).range())
        assertNull(PeriodWindow.of(PeriodUnit.ALL, today).range())
    }

    @Test
    fun `stepping a month back from 31 Mar clamps to February and returns forward`() {
        val w = PeriodWindow.of(PeriodUnit.MONTH, LocalDate.of(2026, 3, 31)).previous()
        assertEquals(DateWindow(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), w.range())
        assertEquals(DateWindow(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31)), w.next().range())
    }

    @Test
    fun `stepping across a year boundary`() {
        val jan = PeriodWindow.of(PeriodUnit.MONTH, LocalDate.of(2026, 1, 10)).previous()
        assertEquals(DateWindow(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)), jan.range())
        val week = PeriodWindow.of(PeriodUnit.WEEK, LocalDate.of(2026, 1, 2)).previous().range()!!
        assertEquals(LocalDate.of(2025, 12, 22), week.start)
        assertEquals(2025, PeriodWindow.of(PeriodUnit.YEAR, LocalDate.of(2026, 5, 5)).previous().range()!!.start.year)
    }

    @Test
    fun `leap year February has 29 days`() {
        val r = PeriodWindow.of(PeriodUnit.MONTH, LocalDate.of(2028, 2, 10)).range()!!
        assertEquals(LocalDate.of(2028, 2, 29), r.end)
    }

    @Test
    fun `cannot step into the future`() {
        assertFalse(PeriodWindow.of(PeriodUnit.MONTH, today).canGoNext(today))
        assertTrue(PeriodWindow.of(PeriodUnit.MONTH, today).previous().canGoNext(today))
        assertFalse(PeriodWindow.of(PeriodUnit.WEEK, today).canGoNext(today))
        assertFalse(PeriodWindow.of(PeriodUnit.YEAR, today).canGoNext(today))
        assertFalse(PeriodWindow.of(PeriodUnit.ALL, today).canGoNext(today))
    }

    @Test
    fun `labels`() {
        assertEquals("This week", PeriodWindow.of(PeriodUnit.WEEK, today).label(today))
        assertEquals("Last week", PeriodWindow.of(PeriodUnit.WEEK, today).previous().label(today))
        assertEquals("This month", PeriodWindow.of(PeriodUnit.MONTH, today).label(today))
        assertEquals("Last month", PeriodWindow.of(PeriodUnit.MONTH, today).previous().label(today))
        assertEquals("Jan 2026", PeriodWindow.of(PeriodUnit.MONTH, today).previous().previous().label(today))
        assertEquals("This year", PeriodWindow.of(PeriodUnit.YEAR, today).label(today))
        assertEquals("Last year", PeriodWindow.of(PeriodUnit.YEAR, today).previous().label(today))
        assertEquals("2024", PeriodWindow.of(PeriodUnit.YEAR, today).previous().previous().label(today))
        assertEquals("All time", PeriodWindow.of(PeriodUnit.ALL, today).label(today))
        assertEquals("2 Mar 2026 – 9 Mar 2026", PeriodWindow.custom(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 9)).label(today))
        assertEquals("2 Mar – 8 Mar", PeriodWindow.of(PeriodUnit.WEEK, today).previous().previous().label(today))
    }

    @Test
    fun `last N months spans whole months ending this month`() {
        val r = PeriodWindow.lastMonths(3, today).range()!!
        assertEquals(LocalDate.of(2026, 1, 1), r.start)
        assertEquals(LocalDate.of(2026, 3, 31), r.end)
    }

    @Test
    fun `custom steps by its own length`() {
        val w = PeriodWindow.custom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10)).next().range()!!
        assertEquals(LocalDate.of(2026, 3, 11), w.start)
        assertEquals(LocalDate.of(2026, 3, 20), w.end)
    }

    @Test
    fun `reversed custom dates are normalised`() {
        val r = PeriodWindow.custom(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 3, 2)).range()!!
        assertEquals(LocalDate.of(2026, 3, 2), r.start)
    }

    @Test
    fun `bucket counts`() {
        assertEquals(7, PeriodWindow.of(PeriodUnit.WEEK, today).buckets(today, null).size)
        assertEquals(31, PeriodWindow.of(PeriodUnit.MONTH, today).buckets(today, null).size)
        val year = PeriodWindow.of(PeriodUnit.YEAR, today).buckets(today, null)
        assertEquals(12, year.size)
        assertEquals(LocalDate.of(2026, 2, 28), year[1].end)
        val all = PeriodWindow.of(PeriodUnit.ALL, today).buckets(today, LocalDate.of(2025, 11, 20))
        assertEquals(5, all.size)
        assertEquals(LocalDate.of(2025, 11, 20), all.first().start)
        assertEquals(today, all.last().end)
        assertTrue(PeriodWindow.of(PeriodUnit.ALL, today).buckets(today, null).isEmpty())
        assertEquals(10, PeriodWindow.custom(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 10)).buckets(today, null).size)
        assertEquals(3, PeriodWindow.lastMonths(3, today).buckets(today, null).size)
    }

    @Test
    fun `stored unit only remembers week month year`() {
        assertEquals(PeriodUnit.YEAR, PeriodUnit.fromStored("YEAR"))
        assertEquals(PeriodUnit.MONTH, PeriodUnit.fromStored("ALL"))
        assertEquals(PeriodUnit.MONTH, PeriodUnit.fromStored(null))
    }
}
