package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/**
 * Detects recurring charges (subscriptions) from historical transactions,
 * grouped by merchant brand. Pure and top-level so it's JVM-testable — the
 * caller (see [com.xemmy.mmbank.di.AppContainer]) persists the result as
 * [com.xemmy.mmbank.data.local.entity.SubscriptionEntity] rows.
 *
 * v2 (backlog Part C.1) fixes what the original "every gap must fit" rule
 * threw away:
 *  - a **late or missed cycle** no longer hides the subscription (one missed
 *    cycle per three gaps is tolerated, so it takes four charges to cover one);
 *  - **extra purchases** at the same merchant (Apple, Amazon, Google Play)
 *    are skipped instead of breaking the chain -- but only while the chain
 *    still explains at least [MIN_CHAIN_SHARE] of the merchant's charges, so
 *    a daily same-price coffee never reads as a weekly subscription;
 *  - **more cadences**: bi-weekly, quarterly, semi-annual and yearly;
 *  - **brand grouping** ([MerchantNormalizer.key]): "NETFLIX.COM" and
 *    "Netflix.com Amsterdam" are one merchant;
 *  - **calendar-true renewal dates** for the month-based cadences (the 15th
 *    stays the 15th instead of drifting by 30-day steps);
 *  - **message wording** ("subscription", "renewal", "auto-debit", Arabic
 *    اشتراك / تجديد) lets two charges on a clean cadence count.
 */
object SubscriptionRadarEngine {

    /** Amounts across occurrences must stay within this fraction of their average to count as one subscription. */
    private const val AMOUNT_VARIANCE_THRESHOLD = 0.05

    /** At least this many charges are required before a repeating pattern is trusted... */
    private const val MIN_OCCURRENCES = 3

    /** ...or this many when the message wording itself says "subscription". */
    private const val MIN_OCCURRENCES_WITH_HINT = 2

    /** The matched chain must explain at least this share of the merchant's charges since it began. */
    private const val MIN_CHAIN_SHARE = 0.6

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** How far a gap may sit from a cadence's nominal length, in days. */
    private fun toleranceDays(cadence: SubscriptionCadence): Long = when (cadence) {
        SubscriptionCadence.WEEKLY -> 2L
        SubscriptionCadence.BIWEEKLY -> 3L
        SubscriptionCadence.MONTHLY -> 5L
        SubscriptionCadence.QUARTERLY -> 10L
        SubscriptionCadence.SEMI_ANNUAL -> 15L
        SubscriptionCadence.YEARLY -> 20L
    }

    /** "subscription", "renewal", "auto-debit", "recurring", Arabic "اشتراك" / "تجديد". */
    private val SUBSCRIPTION_HINT = Regex(
        "subscri(?:ption|bed)|renew(?:al|ed|s)?|auto[- ]?(?:debit|pay|renew)|recurring|standing instruction|اشتراك|تجديد",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Scans real, analytics-eligible debit transactions for merchants whose
     * charges repeat on a regular rhythm (see the class comment) with amounts
     * within [AMOUNT_VARIANCE_THRESHOLD] of each other. Returns one
     * [Subscription] per qualifying merchant, soonest renewal first.
     */
    fun detect(transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): List<Subscription> =
        transactions
            .asSequence()
            .filter { it.kind == TransactionKind.SPEND && !it.excludeFromStats }
            .filter { !it.merchant.isNullOrBlank() }
            .groupBy { merchantKey(it.merchant!!) to it.currencyCode.uppercase() }
            .values
            .mapNotNull { detectForMerchant(it, zone) }
            .sortedBy { it.nextDueDateMillis }

    /** Brand key when the normalizer has one, else the plain lower-cased name (as before). */
    private fun merchantKey(merchant: String): String =
        MerchantNormalizer.key(merchant).ifEmpty { merchant.trim().lowercase() }

    /** A run of charges that fit one cadence: [misses] counts skipped cycles inside it. */
    private data class Chain(val cadence: SubscriptionCadence, val charges: List<Transaction>, val misses: Int)

    private fun detectForMerchant(occurrences: List<Transaction>, zone: ZoneId): Subscription? {
        val sorted = occurrences.sortedBy { it.timestampMillis }
        val chain = bestChain(sorted) ?: return null

        // A steady price on an irregular schedule is not a subscription, and
        // neither is a price change on one: the chain is the schedule check,
        // the variance check below is the price check.
        val charges = chain.charges
        val average = averageOf(charges) ?: return null
        return if (charges.all { withinVariance(it.amount, average) }) {
            build(charges, chain.cadence, price = average, previousPrice = null, zone = zone)
        } else {
            detectWithPriceChange(charges, chain.cadence, zone)
        }
    }

    /**
     * The best chain over every cadence and every starting charge, or null.
     * Fewest missed cycles wins (so a 14-day rhythm is bi-weekly, and a monthly
     * one with an extra purchase in it is not re-read as bi-weekly with
     * "missed" weeks), then the longer chain, then the later start.
     */
    private fun bestChain(sorted: List<Transaction>): Chain? {
        var best: Chain? = null
        for (cadence in SubscriptionCadence.entries) {
            for (start in sorted.indices) {
                val chain = chainFrom(sorted, start, cadence) ?: continue
                if (best == null || isBetter(chain, best)) best = chain
            }
        }
        return best
    }

    private fun isBetter(a: Chain, b: Chain): Boolean = when {
        a.misses != b.misses -> a.misses < b.misses
        a.charges.size != b.charges.size -> a.charges.size > b.charges.size
        else -> a.charges.first().timestampMillis > b.charges.first().timestampMillis
    }

    private fun chainFrom(sorted: List<Transaction>, start: Int, cadence: SubscriptionCadence): Chain? {
        val nominal = cadence.nominalDays
        val tolerance = toleranceDays(cadence)
        val chain = mutableListOf(sorted[start])
        var misses = 0
        for (i in start + 1 until sorted.size) {
            val gapDays = (sorted[i].timestampMillis - chain.last().timestampMillis) / DAY_MILLIS
            when {
                abs(gapDays - nominal) <= tolerance -> chain += sorted[i]
                abs(gapDays - 2 * nominal) <= tolerance -> { chain += sorted[i]; misses++ }
                // Too soon for the next cycle: an extra purchase, not a renewal.
                gapDays < nominal - tolerance -> Unit
                // Too late to be this cadence any more: the run ends here.
                else -> break
            }
        }
        val hinted = chain.any { it.rawMessage?.let(SUBSCRIPTION_HINT::containsMatchIn) == true }
        val required = if (hinted) MIN_OCCURRENCES_WITH_HINT else MIN_OCCURRENCES
        if (chain.size < required) return null
        // At most one skipped cycle per three gaps: more than that is a different cadence.
        if (misses * 3 > chain.size - 1) return null
        // The chain must account for most of what this merchant charged since
        // it began, or this is frequent shopping that merely contains a rhythm.
        val chargesSinceStart = sorted.size - start
        if (chain.size.toDouble() / chargesSinceStart < MIN_CHAIN_SHARE) return null
        return Chain(cadence, chain, misses)
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
    private fun detectWithPriceChange(sorted: List<Transaction>, cadence: SubscriptionCadence, zone: ZoneId): Subscription? {
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

        return build(sorted, cadence, price = currentPrice, previousPrice = previousPrice, zone = zone)
    }

    private fun build(
        sorted: List<Transaction>,
        cadence: SubscriptionCadence,
        price: BigDecimal,
        previousPrice: BigDecimal?,
        zone: ZoneId,
    ): Subscription {
        val last = sorted.last()
        return Subscription(
            merchant = last.merchant!!.trim(),
            averageAmount = price.setScale(2, RoundingMode.HALF_UP),
            currencyCode = last.currencyCode.uppercase(),
            cadence = cadence,
            nextDueDateMillis = nextDue(sorted, cadence, zone),
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

    /**
     * When the next charge is due. Day-based cadences step by their nominal
     * length from the last charge. Month-based ones keep the billing day of
     * the month -- the 15th stays the 15th -- clamped to short months; a
     * biller on the 29th-31st (any such charge in the chain) stays at
     * "month end" instead of sliding down to the 28th after February.
     */
    internal fun nextDue(chain: List<Transaction>, cadence: SubscriptionCadence, zone: ZoneId): Long {
        val last = chain.last()
        val months = cadence.months ?: return last.timestampMillis + cadence.nominalDays * DAY_MILLIS
        val lastAt = Instant.ofEpochMilli(last.timestampMillis).atZone(zone)
        val days = chain.map { Instant.ofEpochMilli(it.timestampMillis).atZone(zone).dayOfMonth }
        val anchorDay = if (days.any { it >= 29 }) days.max() else lastAt.dayOfMonth
        val target = YearMonth.from(lastAt).plusMonths(months.toLong())
        val day = minOf(anchorDay, target.lengthOfMonth())
        return target.atDay(day).atTime(lastAt.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    }
}
