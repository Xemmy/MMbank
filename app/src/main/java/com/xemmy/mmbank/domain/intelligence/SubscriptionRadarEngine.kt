package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.TransactionKind
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
            .filter { it.kind == TransactionKind.SPEND && !it.excludeFromStats }
            .filter { !it.merchant.isNullOrBlank() }
            .groupBy { it.merchant!!.trim().lowercase() to it.currencyCode.uppercase() }
            .values
            .mapNotNull(::detectForMerchant)
            .sortedBy { it.nextDueDateMillis }

    private fun detectForMerchant(occurrences: List<Transaction>): Subscription? {
        if (occurrences.size < MIN_OCCURRENCES) return null
        val sorted = occurrences.sortedBy { it.timestampMillis }

        // The rhythm has to hold either way -- a steady price on an irregular
        // schedule is not a subscription, and neither is a price change on one.
        val gapsDays = sorted.zipWithNext { a, b -> (b.timestampMillis - a.timestampMillis) / DAY_MILLIS }
        val cadence = classifyCadence(gapsDays) ?: return null

        val average = averageOf(sorted) ?: return null
        return if (sorted.all { withinVariance(it.amount, average) }) {
            build(sorted, cadence, price = average, previousPrice = null)
        } else {
            detectWithPriceChange(sorted, cadence)
        }
    }

    /**
     * Rescues the case a flat variance check silently drops: a subscription
     * whose price changed partway through. Without this, a Netflix that went
     * from 39.99 to 56.00 fails the variance test and *disappears* from the
     * radar entirely -- exactly when the user most wants to be told.
     *
     * Splits at the last amount change: charges matching the newest amount are
     * the current price, everything before it must be internally consistent to
     * qualify. One charge at the new price is enough, since a rise is usually
     * noticed at the first renewal that carries it.
     */
    private fun detectWithPriceChange(sorted: List<Transaction>, cadence: SubscriptionCadence): Subscription? {
        val newest = sorted.last().amount

        var splitIndex = sorted.size
        for (i in sorted.indices.reversed()) {
            if (!withinVariance(sorted[i].amount, newest)) break
            splitIndex = i
        }

        val older = sorted.subList(0, splitIndex)
        val recent = sorted.subList(splitIndex, sorted.size)
        if (older.isEmpty() || recent.isEmpty()) return null

        // The older run has to be a steady price too. Three different amounts
        // in a row is erratic spending at one merchant, not a price rise.
        val previousPrice = averageOf(older) ?: return null
        if (older.any { !withinVariance(it.amount, previousPrice) }) return null

        val currentPrice = averageOf(recent) ?: return null
        // Guard the boundary: if the two runs are within variance of each other
        // the flat path should have matched, so this is not a real change.
        if (withinVariance(currentPrice, previousPrice)) return null

        return build(sorted, cadence, price = currentPrice, previousPrice = previousPrice)
    }

    private fun build(
        sorted: List<Transaction>,
        cadence: SubscriptionCadence,
        price: BigDecimal,
        previousPrice: BigDecimal?,
    ): Subscription {
        val last = sorted.last()
        return Subscription(
            merchant = last.merchant!!.trim(),
            averageAmount = price.setScale(2, RoundingMode.HALF_UP),
            currencyCode = last.currencyCode.uppercase(),
            cadence = cadence,
            nextDueDateMillis = last.timestampMillis + cadence.nominalDays * DAY_MILLIS,
            lastSeenMillis = last.timestampMillis,
            occurrenceCount = sorted.size,
            previousAmount = previousPrice?.setScale(2, RoundingMode.HALF_UP),
        )
    }

    /** Mean charge, or null when it is zero/negative and so can't anchor a variance ratio. */
    private fun averageOf(transactions: List<Transaction>): BigDecimal? {
        if (transactions.isEmpty()) return null
        val total = transactions.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
        return total.divide(BigDecimal(transactions.size), 4, RoundingMode.HALF_UP)
            .takeIf { it.signum() > 0 }
    }

    private fun withinVariance(amount: BigDecimal, reference: BigDecimal): Boolean {
        val ref = reference.toDouble()
        if (ref <= 0.0) return false
        return abs(amount.toDouble() - ref) / ref <= AMOUNT_VARIANCE_THRESHOLD
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
