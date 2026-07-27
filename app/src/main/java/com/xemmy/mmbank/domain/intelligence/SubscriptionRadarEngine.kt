package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

/**
 * Detects recurring charges (subscriptions) from historical transactions,
 * grouped by merchant name. Pure and top-level so it's JVM-testable — the
 * caller (see [com.xemmy.mmbank.di.AppContainer]) persists the result as
 * [com.xemmy.mmbank.data.local.entity.SubscriptionEntity] rows.
 */
object SubscriptionRadarEngine {

    /** Amounts across occurrences must stay within this fraction of their average to count as one subscription. */
    private const val AMOUNT_VARIANCE_THRESHOLD = 0.05

    /** At least this many charges are required before a repeating pattern is trusted. */
    private const val MIN_OCCURRENCES = 3

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Gap between charges must land within this many days of the cadence's nominal length. */
    private const val WEEKLY_TOLERANCE_DAYS = 2L
    private const val MONTHLY_TOLERANCE_DAYS = 5L

    /**
     * Scans real, analytics-eligible debit transactions for merchants whose
     * charges repeat on a weekly (~7 day) or monthly (~30 day) rhythm with
     * amounts within [AMOUNT_VARIANCE_THRESHOLD] of each other. Returns one
     * [Subscription] per qualifying merchant, soonest renewal first.
     */
    fun detect(transactions: List<Transaction>): List<Subscription> =
        transactions
            .asSequence()
            .filter { it.direction == TransactionDirection.DEBIT && !it.excludeFromStats }
            .filter { !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!!.trim().lowercase() to it.currencyCode.uppercase() }
            .values
            .mapNotNull(::detectForMerchant)
            .sortedBy { it.nextDueDateMillis }

    private fun detectForMerchant(occurrences: List<Transaction>): Subscription? {
        if (occurrences.size < MIN_OCCURRENCES) return null
        val sorted = occurrences.sortedBy { it.timestampMillis }

        val total = sorted.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
        val average = total.divide(BigDecimal(sorted.size), 4, RoundingMode.HALF_UP)
        if (average.signum() <= 0) return null

        val avgDouble = average.toDouble()
        val withinVariance = sorted.all { tx ->
            abs(tx.amount.toDouble() - avgDouble) / avgDouble <= AMOUNT_VARIANCE_THRESHOLD
        }
        if (!withinVariance) return null

        val gapsDays = sorted.zipWithNext { a, b -> (b.timestampMillis - a.timestampMillis) / DAY_MILLIS }
        val cadence = classifyCadence(gapsDays) ?: return null

        val last = sorted.last()
        return Subscription(
            merchant = last.merchant!!.trim(),
            averageAmount = average.setScale(2, RoundingMode.HALF_UP),
            currencyCode = last.currencyCode.uppercase(),
            cadence = cadence,
            nextDueDateMillis = last.timestampMillis + cadence.nominalDays * DAY_MILLIS,
            lastSeenMillis = last.timestampMillis,
            occurrenceCount = sorted.size,
        )
    }

    /** Every consecutive gap must land within tolerance of the same cadence for it to count. */
    private fun classifyCadence(gapsDays: List<Long>): SubscriptionCadence? {
        if (gapsDays.isEmpty()) return null
        val isWeekly = gapsDays.all { abs(it - SubscriptionCadence.WEEKLY.nominalDays) <= WEEKLY_TOLERANCE_DAYS }
        val isMonthly = gapsDays.all { abs(it - SubscriptionCadence.MONTHLY.nominalDays) <= MONTHLY_TOLERANCE_DAYS }
        return when {
            isWeekly -> SubscriptionCadence.WEEKLY
            isMonthly -> SubscriptionCadence.MONTHLY
            else -> null
        }
    }
}
