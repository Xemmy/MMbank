package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Predicts the next payday from recurring salary-shaped credits and computes
 * how much of the current liquid balance is safe to spend before it arrives,
 * after reserving mandatory bills due in that window. Pure and top-level so
 * it's JVM-testable; the caller (a ViewModel, via [com.xemmy.mmbank.di.AppContainer])
 * supplies the transaction history, liquid balance and mandatory-bill total.
 */
object CashFlowForecaster {

    /** Minimum single-credit amount to be considered a salary/income candidate. */
    const val MIN_INCOME_AMOUNT = 500.0

    /** Fewer qualifying credits than this and no rhythm is trusted. */
    private const val MIN_OCCURRENCES = 2

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** A detected recurring-income rhythm — see [detectIncomePattern]. */
    data class IncomePattern(
        val averageAmount: BigDecimal,
        val currencyCode: String,
        val averageIntervalDays: Long,
        val lastOccurrenceMillis: Long,
        val occurrenceCount: Int,
    )

    /** Result of [computeSafeToSpend]. */
    data class SafeToSpendResult(
        val nextPaydayMillis: Long,
        val daysUntilPayday: Int,
        val safeToSpend: BigDecimal,
        val dailyAllowance: BigDecimal,
    )

    /**
     * Detects a recurring income rhythm from real, non-excluded credits >=
     * [MIN_INCOME_AMOUNT] in [currencyCode]. Any qualifying credit counts
     * toward the pattern -- payroll messages rarely repeat an exact merchant
     * name the way subscription charges do (see [SubscriptionRadarEngine]),
     * so this groups by amount threshold and timing only. Returns null when
     * fewer than [MIN_OCCURRENCES] qualifying credits exist.
     */
    fun detectIncomePattern(transactions: List<Transaction>, currencyCode: String): IncomePattern? {
        val credits = transactions
            .filter {
                it.direction == TransactionDirection.CREDIT &&
                    !it.excludeFromStats &&
                    it.currencyCode.equals(currencyCode, ignoreCase = true) &&
                    it.amount.toDouble() >= MIN_INCOME_AMOUNT
            }
            .sortedBy { it.timestampMillis }
        if (credits.size < MIN_OCCURRENCES) return null

        val gaps = credits.zipWithNext { a, b -> (b.timestampMillis - a.timestampMillis) / DAY_MILLIS }
        val averageIntervalDays = (gaps.sum() / gaps.size).coerceAtLeast(1)

        val total = credits.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
        val average = total.divide(BigDecimal(credits.size), 2, RoundingMode.HALF_UP)

        return IncomePattern(
            averageAmount = average,
            currencyCode = currencyCode.uppercase(),
            averageIntervalDays = averageIntervalDays,
            lastOccurrenceMillis = credits.last().timestampMillis,
            occurrenceCount = credits.size,
        )
    }

    /**
     * Next expected payday. [manualPaydayOfMonth] (1-31, the Settings
     * override -- see [com.xemmy.mmbank.domain.model.PaycheckSchedule]) wins
     * over [pattern] when set: the next occurrence of that day of the month
     * on or after [today]. Otherwise projects forward from [pattern]'s last
     * occurrence by its average interval. Null when neither is available.
     */
    fun projectNextPayday(
        pattern: IncomePattern?,
        manualPaydayOfMonth: Int?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): LocalDate? {
        if (manualPaydayOfMonth != null && manualPaydayOfMonth in 1..31) {
            val thisMonthDay = today.withDayOfMonth(minOf(manualPaydayOfMonth, today.lengthOfMonth()))
            return if (!thisMonthDay.isBefore(today)) {
                thisMonthDay
            } else {
                val nextMonth = today.plusMonths(1)
                nextMonth.withDayOfMonth(minOf(manualPaydayOfMonth, nextMonth.lengthOfMonth()))
            }
        }
        if (pattern == null) return null
        val lastDate = Instant.ofEpochMilli(pattern.lastOccurrenceMillis).atZone(zone).toLocalDate()
        var next = lastDate.plusDays(pattern.averageIntervalDays)
        while (next.isBefore(today)) next = next.plusDays(pattern.averageIntervalDays)
        return next
    }

    /**
     * `SafeToSpend = CurrentLiquidBalance - Sum(MandatoryBillsDueBeforePayday)`,
     * floored at zero, spread evenly across the days remaining until
     * [nextPayday] (today inclusive) for a daily allowance.
     */
    fun computeSafeToSpend(
        currentLiquidBalance: BigDecimal,
        mandatoryBillsDueBeforePayday: BigDecimal,
        nextPayday: LocalDate,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): SafeToSpendResult {
        val daysUntilPayday = ChronoUnit.DAYS.between(today, nextPayday).toInt().coerceAtLeast(1)
        val rawSafeToSpend = currentLiquidBalance - mandatoryBillsDueBeforePayday
        val safeToSpend = if (rawSafeToSpend.signum() < 0) BigDecimal.ZERO else rawSafeToSpend
        val dailyAllowance = safeToSpend.divide(BigDecimal(daysUntilPayday), 2, RoundingMode.HALF_UP)
        return SafeToSpendResult(
            nextPaydayMillis = nextPayday.atStartOfDay(zone).toInstant().toEpochMilli(),
            daysUntilPayday = daysUntilPayday,
            safeToSpend = safeToSpend,
            dailyAllowance = dailyAllowance,
        )
    }
}
