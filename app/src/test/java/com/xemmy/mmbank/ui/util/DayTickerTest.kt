package com.xemmy.mmbank.ui.util

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTickerTest {
    private val utc = ZoneId.of("UTC")

    @Test fun `one hour before midnight`() {
        val now = ZonedDateTime.of(2026, 11, 11, 23, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(3_600_000L, millisUntilNextMidnight(now, utc))
    }

    @Test fun `exactly at midnight waits a full day`() {
        val now = ZonedDateTime.of(2026, 11, 11, 0, 0, 0, 0, utc).toInstant().toEpochMilli()
        assertEquals(86_400_000L, millisUntilNextMidnight(now, utc))
    }

    @Test fun `DST spring-forward day is 23 hours long`() {
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(23 * 3_600_000L, millisUntilNextMidnight(now, ny))
    }

    @Test fun `DST fall-back day is 25 hours long`() {
        val ny = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(25 * 3_600_000L, millisUntilNextMidnight(now, ny))
    }

    @Test fun `never zero or negative`() {
        assertTrue(millisUntilNextMidnight(System.currentTimeMillis(), utc) >= 1L)
    }
}
