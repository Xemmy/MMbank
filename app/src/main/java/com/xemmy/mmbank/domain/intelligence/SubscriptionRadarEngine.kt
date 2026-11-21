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

    /** Looser price tolerance for FX-billed charges, used only for "possible" matches. */
    private const val LOOSE_VARIANCE_THRESHOLD = 0.12

    /** A group with more charges than this is shopping, not a candidate for 2-charge "possible" matches. */
    private const val POSSIBLE_MAX_GROUP = 12

    /** Parallel (identical twin) charges in one cycle must land within this many days of each other. */
    private const val PARALLEL_SPAN_DAYS = 3L

    /** Active (confident) subscriptions plus lower-confidence "possible" ones, with the charges behind each. */
    data class Found(val subscription: Subscription, val charges: List<Transaction>)

    data class Scan(val active: List<Found>, val possible: List<Found>)

    /**
     * Scans real, analytics-eligible debit transactions for merchants whose
     * charges repeat on a regular rhythm (see the class comment) with amounts
     * within [AMOUNT_VARIANCE_THRESHOLD] of each other. Returns the confident
     * [Subscription]s, soonest renewal first; [scan] also returns the "possible" ones.
     *
     * v3: a merchant can yield several subscriptions (Apple iCloud + Music, Amazon
     * Prime + shopping), one per amount cluster, and identical parallel charges
     * (two 50 AED gym lines a month) come back as one row with `lines = 2`.
     */
    fun detect(transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): List<Subscription> =
        scan(transactions, zone).active.map { it.subscription }

    fun scan(transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): Scan {
        val active = mutableListOf<Found>()
        val possible = mutableListOf<Found>()
        for (group in groups(transactions).values) {
            scanGroup(group, zone, active, possible)
        }
        return Scan(
            active.sortedBy { it.subscription.nextDueDateMillis },
            possible.sortedBy { it.subscription.nextDueDateMillis },
        )
    }

    private fun groups(transactions: List<Transaction>): Map<Pair<String, String>, List<Transaction>> =
        transactions
            .asSequence()
            .filter { it.kind == TransactionKind.SPEND && !it.excludeFromStats }
            .filter { !it.merchant.isNullOrBlank() }
            .groupBy { merchantKey(it.merchant!!) to it.currencyCode.uppercase() }

    private fun scanGroup(
        group: List<Transaction>,
        zone: ZoneId,
        active: MutableList<Found>,
        possible: MutableList<Found>,
    ) {
        val all = group.sortedBy { it.timestampMillis }
        // Identical twins first: their interleaved charges would otherwise read as one
        // subscription plus a stray second one.
        val twins = amountClusters(all, AMOUNT_VARIANCE_THRESHOLD).mapNotNull { detectParallel(it, zone) }
        active += twins
        val sorted = all.filter { tx -> twins.none { t -> t.charges.any { it === tx } } }
        // Pass A: the best chain over everything the merchant charged (as in v2).
        val primary = detectFound(sorted, zone)
        var leftover = sorted
        if (primary != null) {
            active += primary
            leftover = sorted.filter { tx -> primary.charges.none { it === tx } }
        }
        // A regular rhythm whose amounts wander up to 12% (FX-billed): possible, never confident.
        val loose = mutableListOf<Found>()
        if (primary == null) {
            for (cluster in amountClusters(sorted, LOOSE_VARIANCE_THRESHOLD)) {
                if (cluster.size < MIN_OCCURRENCES) continue
                detectFound(cluster, zone, LOOSE_VARIANCE_THRESHOLD)?.let {
                    loose += it.copy(subscription = it.subscription.copy(confidence = 0.65))
                }
            }
        }
        // Pass B: whatever the first chain did not explain, split by amount.
        for (cluster in amountClusters(leftover, AMOUNT_VARIANCE_THRESHOLD)) {
            if (cluster.size < 2) continue
            val found = detectFound(cluster, zone)
            if (found != null) {
                active += found
                continue
            }
            if (loose.any { l -> cluster.any { tx -> l.charges.any { it === tx } } }) continue
            possibleFor(cluster, zone, all.size)?.let { possible += it }
        }
        // Loose matches that no confident row already covers.
        loose.filter { l -> active.none { a -> l.charges.any { tx -> a.charges.any { it === tx } } } }
            .forEach { possible += it }
    }

    /** Charges split into runs whose amounts stay within [threshold] of the run's first amount. */
    private fun amountClusters(charges: List<Transaction>, threshold: Double): List<List<Transaction>> {
        val bySize = charges.sortedBy { it.amount }
        val clusters = mutableListOf<MutableList<Transaction>>()
        for (tx in bySize) {
            val current = clusters.lastOrNull()
            if (current != null && withinVariance(tx.amount, current.first().amount, threshold)) {
                current += tx
            } else {
                clusters += mutableListOf(tx)
            }
        }
        return clusters.map { c -> c.sortedBy { it.timestampMillis } }
    }

    /**
     * Identical twins: k (2..4) equal charges within a few days of each other every
     * month or longer. One row, `lines = k`, the per-line amount, every charge counted.
     */
    private fun detectParallel(cluster: List<Transaction>, zone: ZoneId): Found? {
        if (cluster.size < 4) return null
        for (k in 2..4) {
            if (cluster.size % k != 0) continue
            val chunks = cluster.chunked(k)
            val tight = chunks.all {
                (it.last().timestampMillis - it.first().timestampMillis) <= PARALLEL_SPAN_DAYS * DAY_MILLIS
            }
            if (!tight) continue
            val found = detectFound(chunks.map { it.first() }, zone) ?: continue
            if (found.subscription.cadence.months == null) continue
            val last = cluster.last()
            return Found(
                found.subscription.copy(
                    lines = k,
                    occurrenceCount = cluster.size,
                    lastSeenMillis = last.timestampMillis,
                    averageAmount = (averageOf(cluster) ?: found.subscription.averageAmount).setScale(2, RoundingMode.HALF_UP),
                ),
                cluster,
            )
        }
        return null
    }

    /** A weaker match worth showing as "Possible": two charges one cycle (monthly or longer) apart. */
    private fun possibleFor(cluster: List<Transaction>, zone: ZoneId, groupSize: Int): Found? {
        if (cluster.size != 2 || groupSize > POSSIBLE_MAX_GROUP) return null
        val gapDays = (cluster[1].timestampMillis - cluster[0].timestampMillis) / DAY_MILLIS
        val cadence = SubscriptionCadence.entries
            .filter { it.months != null }
            .firstOrNull { abs(gapDays - it.nominalDays) <= toleranceDays(it) } ?: return null
        val hinted = cluster.any { it.rawMessage?.let(SUBSCRIPTION_HINT::containsMatchIn) == true }
        val price = averageOf(cluster) ?: return null
        val built = build(cluster, cadence, price, null, zone).copy(confidence = if (hinted) 0.7 else 0.55)
        return Found(built, cluster)
    }

    // ---- "Why isn't X detected?" -------------------------------------------------

    enum class Reason { TOO_FEW, IRREGULAR_GAPS, PRICE_VARIES, MIXED_WITH_OTHER_PURCHASES }

    sealed interface Outcome {
        data object Detected : Outcome
        data class Possible(val score: Double) : Outcome
        data class Rejected(val reason: Reason, val detail: String) : Outcome
    }

    /** One merchant + amount cluster with at least two charges, and what the radar made of it. */
    data class Verdict(
        val merchant: String,
        val currencyCode: String,
        val amount: BigDecimal,
        val charges: Int,
        val outcome: Outcome,
    )

    /**
     * Per merchant brand + amount cluster (2+ charges), says whether the radar
     * detected it, would show it as possible, or why it did not -- the user-facing
     * answer to "it picked up only one".
     */
    fun explain(transactions: List<Transaction>, zone: ZoneId = ZoneId.systemDefault()): List<Verdict> {
        val verdicts = mutableListOf<Verdict>()
        for ((_, group) in groups(transactions)) {
            val sorted = group.sortedBy { it.timestampMillis }
            val active = mutableListOf<Found>()
            val possible = mutableListOf<Found>()
            scanGroup(sorted, zone, active, possible)
            val clusters = amountClusters(sorted, AMOUNT_VARIANCE_THRESHOLD)
            val groupRegular = sorted.size >= MIN_OCCURRENCES && gapsFitACadence(sorted)
            val maxDeviation = maxDeviationPercent(sorted)
            if (clusters.none { it.size >= 2 } && groupRegular && maxDeviation > AMOUNT_VARIANCE_THRESHOLD * 100 &&
                active.isEmpty() && possible.isEmpty()
            ) {
                verdicts += Verdict(
                    merchant = sorted.last().merchant!!.trim(),
                    currencyCode = sorted.last().currencyCode.uppercase(),
                    amount = (averageOf(sorted) ?: BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                    charges = sorted.size,
                    outcome = Outcome.Rejected(Reason.PRICE_VARIES, "amounts differ by up to ${maxDeviation.toInt()}%"),
                )
            }
            for (cluster in clusters) {
                if (cluster.size < 2) continue
                val avg = averageOf(cluster) ?: continue
                val outcome = when {
                    active.any { f -> cluster.any { tx -> f.charges.any { it === tx } } } -> Outcome.Detected
                    possible.firstOrNull { f -> cluster.any { tx -> f.charges.any { it === tx } } } != null ->
                        Outcome.Possible(possible.first { f -> cluster.any { tx -> f.charges.any { it === tx } } }.subscription.confidence)
                    groupRegular && clusters.size > 1 && maxDeviation > AMOUNT_VARIANCE_THRESHOLD * 100 ->
                        Outcome.Rejected(Reason.PRICE_VARIES, "amounts differ by up to ${maxDeviation.toInt()}%")
                    cluster.size < neededFor(cluster) ->
                        Outcome.Rejected(Reason.TOO_FEW, "${cluster.size} of ${neededFor(cluster)} charges needed")
                    !gapsFitACadence(cluster) ->
                        Outcome.Rejected(Reason.IRREGULAR_GAPS, "gaps of ${gapDays(cluster).joinToString(", ")} days")
                    sorted.size > cluster.size ->
                        Outcome.Rejected(Reason.MIXED_WITH_OTHER_PURCHASES, "${sorted.size - cluster.size} other purchases at this merchant")
                    else -> Outcome.Rejected(Reason.IRREGULAR_GAPS, "gaps of ${gapDays(cluster).joinToString(", ")} days")
                }
                verdicts += Verdict(
                    merchant = cluster.last().merchant!!.trim(),
                    currencyCode = cluster.last().currencyCode.uppercase(),
                    amount = avg.setScale(2, RoundingMode.HALF_UP),
                    charges = cluster.size,
                    outcome = outcome,
                )
            }
        }
        return verdicts.sortedWith(compareBy({ it.outcome is Outcome.Rejected }, { it.merchant.lowercase() }))
    }

    private fun neededFor(cluster: List<Transaction>): Int =
        if (cluster.any { it.rawMessage?.let(SUBSCRIPTION_HINT::containsMatchIn) == true }) MIN_OCCURRENCES_WITH_HINT else MIN_OCCURRENCES

    private fun gapDays(sorted: List<Transaction>): List<Long> =
        sorted.zipWithNext { a, b -> (b.timestampMillis - a.timestampMillis) / DAY_MILLIS }

    /** True when every gap sits near some cadence (or twice it, a missed cycle). */
    private fun gapsFitACadence(sorted: List<Transaction>): Boolean {
        val gaps = gapDays(sorted)
        if (gaps.isEmpty()) return false
        return SubscriptionCadence.entries.any { c ->
            val tol = toleranceDays(c)
            gaps.all { g -> abs(g - c.nominalDays) <= tol || abs(g - 2 * c.nominalDays) <= tol }
        }
    }

    private fun maxDeviationPercent(charges: List<Transaction>): Double {
        val avg = averageOf(charges)?.toDouble() ?: return 0.0
        return charges.maxOf { abs(it.amount.toDouble() - avg) / avg } * 100
    }

    /** Brand key when the normalizer has one, else the plain lower-cased name (as before). */
    private fun merchantKey(merchant: String): String =
        MerchantNormalizer.key(merchant).ifEmpty { merchant.trim().lowercase() }

    /** A run of charges that fit one cadence: [misses] counts skipped cycles inside it. */
    private data class Chain(val cadence: SubscriptionCadence, val charges: List<Transaction>, val misses: Int)

    private fun detectFound(
        sorted: List<Transaction>,
        zone: ZoneId,
        threshold: Double = AMOUNT_VARIANCE_THRESHOLD,
    ): Found? {
        val chain = bestChain(sorted) ?: return null

        // A steady price on an irregular schedule is not a subscription, and
        // neither is a price change on one: the chain is the schedule check,
        // the variance check below is the price check.
        val charges = chain.charges
        val average = averageOf(charges) ?: return null
        val built = (
            if (charges.all { withinVariance(it.amount, average, threshold) }) {
                build(charges, chain.cadence, price = average, previousPrice = null, zone = zone)
            } else {
                detectWithPriceChange(charges, chain.cadence, zone, threshold)
            }
            ) ?: return null
        return Found(built, charges)
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
    private fun detectWithPriceChange(sorted: List<Transaction>, cadence: SubscriptionCadence, zone: ZoneId, threshold: Double = AMOUNT_VARIANCE_THRESHOLD): Subscription? {
        val newest = sorted.last().amount

        var splitIndex = sorted.size
        for (i in sorted.indices.reversed()) {
            if (!withinVariance(sorted[i].amount, newest, threshold)) break
            splitIndex = i
        }

        val older = sorted.subList(0, splitIndex)
        val recent = sorted.subList(splitIndex, sorted.size)
        if (older.isEmpty() || recent.isEmpty()) return null

        // The older run has to be a steady price too. Three different amounts
        // in a row is erratic spending at one merchant, not a price rise.
        val previousPrice = averageOf(older) ?: return null
        if (older.any { !withinVariance(it.amount, previousPrice, threshold) }) return null

        val currentPrice = averageOf(recent) ?: return null
        // Guard the boundary: if the two runs are within variance of each other
        // the flat path should have matched, so this is not a real change.
        if (withinVariance(currentPrice, previousPrice, threshold)) return null

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

    private fun withinVariance(amount: BigDecimal, reference: BigDecimal, threshold: Double = AMOUNT_VARIANCE_THRESHOLD): Boolean {
        val ref = reference.toDouble()
        if (ref <= 0.0) return false
        return abs(amount.toDouble() - ref) / ref <= threshold
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
