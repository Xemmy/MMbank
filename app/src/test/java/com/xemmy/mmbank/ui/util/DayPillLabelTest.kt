package com.xemmy.mmbank.ui.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * dayPillLabel feeds the Activity feed's floating sticky-header pills
 * (Phase 5 spec 5.3): "TODAY"/"YESTERDAY" call out the two most recent days,
 * everything older just shows the date.
 */
class DayPillLabelTest {

    private val today = LocalDate.of(2026, 7, 10)

    @Test
    fun `today reads TODAY with the date`() {
        assertEquals("TODAY · 10 JUL", dayPillLabel(today, today))
    }

    @Test
    fun `yesterday reads YESTERDAY with the date`() {
        assertEquals("YESTERDAY · 9 JUL", dayPillLabel(today.minusDays(1), today))
    }

    @Test
    fun `older days show only the date`() {
        assertEquals("3 JUL", dayPillLabel(today.minusDays(7), today))
    }

    @Test
    fun `year boundary does not affect the label`() {
        val newYearToday = LocalDate.of(2026, 1, 1)
        assertEquals("YESTERDAY · 31 DEC", dayPillLabel(LocalDate.of(2025, 12, 31), newYearToday))
    }

    @Test
    fun `toLocalDate truncates a timestamp to its calendar day`() {
        val zone = java.time.ZoneOffset.UTC
        val millis = LocalDate.of(2026, 7, 10).atTime(23, 45).toInstant(zone).toEpochMilli()
        assertEquals(LocalDate.of(2026, 7, 10), millis.toLocalDate(zone))
    }
}
