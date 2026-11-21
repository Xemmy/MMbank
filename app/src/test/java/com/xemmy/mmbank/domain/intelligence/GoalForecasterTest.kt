package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.SavingsGoal
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalForecasterTest {

    private val zone = ZoneOffset.UTC
    private val now = LocalDate.of(2026, 1, 15).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun goal(target: String, current: String) = SavingsGoal(
        title = "Emergency Fund",
        targetAmount = BigDecimal(target),
        currentAmount = BigDecimal(current),
    )

    @Test
    fun `an already-reached goal is due now, at zero months`() {
        val forecast = GoalForecaster.forecast(goal("1000", "1000"), BigDecimal("100"), now, zone)
        assertEquals(0, forecast.monthsToTarget)
        assertEquals(now, forecast.projectedCompletionMillis)
    }

    @Test
    fun `a goal already past its target is also due now`() {
        val forecast = GoalForecaster.forecast(goal("1000", "1200"), BigDecimal("100"), now, zone)
        assertEquals(0, forecast.monthsToTarget)
    }

    @Test
    fun `an exact multiple of the average needs no rounding up`() {
        val forecast = GoalForecaster.forecast(goal("1000", "400"), BigDecimal("200"), now, zone)
        assertEquals(3, forecast.monthsToTarget)
        assertEquals(
            LocalDate.of(2026, 4, 15).atStartOfDay(zone).toInstant().toEpochMilli(),
            forecast.projectedCompletionMillis,
        )
    }

    @Test
    fun `a remainder rounds up to the next whole month`() {
        val forecast = GoalForecaster.forecast(goal("1000", "0"), BigDecimal("300"), now, zone)
        // 1000 / 300 = 3.33... -> 4 months, not truncated to 3.
        assertEquals(4, forecast.monthsToTarget)
    }

    @Test
    fun `zero average savings can never close a positive gap`() {
        val forecast = GoalForecaster.forecast(goal("1000", "0"), BigDecimal.ZERO, now, zone)
        assertNull(forecast.monthsToTarget)
        assertNull(forecast.projectedCompletionMillis)
    }

    @Test
    fun `negative average savings -- spending more than earning -- also can't project`() {
        val forecast = GoalForecaster.forecast(goal("1000", "0"), BigDecimal("-50"), now, zone)
        assertNull(forecast.monthsToTarget)
        assertNull(forecast.projectedCompletionMillis)
    }

    @Test
    fun `a tiny remaining gap still needs at least one month, not zero`() {
        val forecast = GoalForecaster.forecast(goal("1000", "999"), BigDecimal("500"), now, zone)
        assertEquals(1, forecast.monthsToTarget)
    }
}
