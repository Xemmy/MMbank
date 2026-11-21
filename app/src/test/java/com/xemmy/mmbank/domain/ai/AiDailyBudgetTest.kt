package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDailyBudgetTest {

    @Test
    fun `no prior state is never exhausted`() {
        assertFalse(AiDailyBudget.isExhausted(null, "2026-07-21", limit = 5))
    }

    @Test
    fun `below the limit is not exhausted`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-21", count = 4)
        assertFalse(AiDailyBudget.isExhausted(state, "2026-07-21", limit = 5))
    }

    @Test
    fun `at the limit is exhausted`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-21", count = 5)
        assertTrue(AiDailyBudget.isExhausted(state, "2026-07-21", limit = 5))
    }

    @Test
    fun `over the limit is exhausted`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-21", count = 9)
        assertTrue(AiDailyBudget.isExhausted(state, "2026-07-21", limit = 5))
    }

    @Test
    fun `a stale day's count never blocks today`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-20", count = 99)
        assertFalse(AiDailyBudget.isExhausted(state, "2026-07-21", limit = 5))
    }

    @Test
    fun `next starts a fresh count of 1 when there is no prior state`() {
        assertEquals(AiDailyBudget.State("2026-07-21", 1), AiDailyBudget.next(null, "2026-07-21"))
    }

    @Test
    fun `next increments within the same day`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-21", count = 3)
        assertEquals(AiDailyBudget.State("2026-07-21", 4), AiDailyBudget.next(state, "2026-07-21"))
    }

    @Test
    fun `next rolls a stale day over to a fresh count of 1`() {
        val state = AiDailyBudget.State(dayKey = "2026-07-20", count = 47)
        assertEquals(AiDailyBudget.State("2026-07-21", 1), AiDailyBudget.next(state, "2026-07-21"))
    }
}
