package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.SavingsGoal
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/**
 * Projects when a [SavingsGoal] will be reached from its current progress and
 * the ledger's recent average monthly net savings (see [DashboardStats.avgMonthlyNet]
 * / [com.xemmy.mmbank.ui.screens.dashboard.computeDashboardStats] -- the same
 * figure the Dashboard already computes, reused here rather than duplicated).
 *
 * Pure and top-level so it's JVM-testable; the caller supplies the average and
 * the current time.
 */
object GoalForecaster {

    /** Null fields mean "can't project" -- no positive average savings to pace against. */
    data class Forecast(
        val monthsToTarget: Int?,
        val projectedCompletionMillis: Long?,
    )

    /**
     * Rounds the remaining amount up to whole months at [averageMonthlyNetSavings]
     * per month. Already-reached goals report zero months, due now. A
     * non-positive average (spending more than earning, on average) can never
     * close a positive gap, so it reports an unknown projection rather than an
     * infinite or negative one.
     */
    fun forecast(
        goal: SavingsGoal,
        averageMonthlyNetSavings: BigDecimal,
        nowMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Forecast {
        val remaining = goal.targetAmount - goal.currentAmount
        if (remaining.signum() <= 0) return Forecast(monthsToTarget = 0, projectedCompletionMillis = nowMillis)
        if (averageMonthlyNetSavings.signum() <= 0) return Forecast(null, null)

        val months = ceil(remaining.toDouble() / averageMonthlyNetSavings.toDouble()).toInt().coerceAtLeast(1)
        val projected = Instant.ofEpochMilli(nowMillis)
            .atZone(zone)
            .toLocalDate()
            .plusMonths(months.toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
        return Forecast(monthsToTarget = months, projectedCompletionMillis = projected)
    }
}
