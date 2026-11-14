package com.xemmy.mmbank.data.digest

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyDigestWorkerTest {

    @Test
    fun `week keys follow ISO weeks across a year end`() {
        assertEquals("2026-W45", WeeklyDigestWorker.weekKey(LocalDate.of(2026, 11, 7)))
        // 1 Jan 2027 is a Friday, still in ISO week 53 of 2026.
        assertEquals("2026-W53", WeeklyDigestWorker.weekKey(LocalDate.of(2027, 1, 1)))
        assertEquals("2027-W01", WeeklyDigestWorker.weekKey(LocalDate.of(2027, 1, 4)))
    }

    @Test
    fun `sends once per week, only when opted in`() {
        assertTrue(WeeklyDigestWorker.shouldSend(enabled = true, lastSentKey = "2026-W44", thisWeekKey = "2026-W45"))
        assertTrue(WeeklyDigestWorker.shouldSend(enabled = true, lastSentKey = "", thisWeekKey = "2026-W45"))
        assertFalse(WeeklyDigestWorker.shouldSend(enabled = true, lastSentKey = "2026-W45", thisWeekKey = "2026-W45"))
        assertFalse(WeeklyDigestWorker.shouldSend(enabled = false, lastSentKey = "", thisWeekKey = "2026-W45"))
    }
}
