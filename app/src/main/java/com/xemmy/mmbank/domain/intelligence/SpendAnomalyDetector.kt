package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId

/**
 * Flags a charge that is far outside what this merchant usually costs you --
 * "3.1× your usual at Careem" -- the single most useful thing a finance app
 * can point out, and the one that destroys trust fastest when it's wrong. So
 * it is deliberately conservative:
 *
 * - it needs at least [MIN_HISTORY] earlier purchases at the same merchant, in
 *   the same currency (a robust baseline, not a guess from two data points);
 * - the baseline is the median, with the spread measured by the median
 *   absolute deviation (MAD) -- one past outlier can't drag either;
 * - a charge must clear BOTH max(median + [MAD_K]·σ̂, [MIN_RATIO]·median) AND
 *   exceed the median by at least [MIN_EXCESS], so a 4.00 coffee becoming
 *   9.00 doesn't raise an alarm.
 *
 * Merchants are matched exactly as [com.xemmy.mmbank.data.local.dao.TransactionDao.findRealDebitsByMerchant]
 * does (trimmed, case-insensitive), so the capture-time check and the screens
 * agree. Only purchases count ([LedgerMath.isSpend]): refunds, transfers and
 * excluded rows never form a baseline or get flagged. Pure and JVM-testable.
 */
object SpendAnomalyDetector {

    const val MIN_HISTORY = 5

    /** Only the most recent purchases form the baseline, so it follows a merchant's prices over time. */
    const val MAX_HISTORY = 50

    const val MAD_K = 4.0

    /** MAD × 1.4826 estimates the standard deviation for normally distributed amounts. */
    private const val MAD_SCALE = 1.4826

    const val MIN_RATIO = 2.0

    /** Minimum excess over the usual amount, in the charge's own currency (currency-naive on purpose). */
    val MIN_EXCESS: BigDecimal = BigDecimal("20")

    data class Anomaly(
        val transaction: Transaction,
        /** The merchant as written on the flagged charge. */
        val merchant: String,
        val amount: BigDecimal,
        /** The median of the earlier purchases. */
        val usual: BigDecimal,
        /** amount / usual. */
        val ratio: Double,
        val currencyCode: String,
    )

    /**
     * Whether [candidate] is unusual against [history] (any rows; only earlier
     * purchases at the same merchant in the same currency are used, and the
     * candidate itself never is). Null when it isn't, or there's too little
     * history to say.
     */
    fun evaluate(candidate: Transaction, history: List<Transaction>): Anomaly? {
        if (!LedgerMath.isSpend(candidate)) return null
        val key = merchantKey(candidate) ?: return null
        val prior = history.filter { tx ->
            tx !== candidate &&
                (candidate.id == 0L || tx.id != candidate.id) &&
                tx.timestampMillis < candidate.timestampMillis &&
                LedgerMath.isSpend(tx) &&
                tx.currencyCode.equals(candidate.currencyCode, ignoreCase = true) &&
                merchantKey(tx) == key
        }
        return judge(candidate, prior)
    }

    /**
     * The most unusual charges inside [window], strongest first, at most [max].
     * Each is judged against everything before it in [transactions].
     */
    fun scan(
        transactions: List<Transaction>,
        window: DateWindow,
        zone: ZoneId = ZoneId.systemDefault(),
        max: Int = 2,
    ): List<Anomaly> {
        val purchases = transactions.filter { LedgerMath.isSpend(it) && merchantKey(it) != null }
        val groups = purchases.groupBy { merchantKey(it)!! to it.currencyCode.uppercase() }
            .mapValues { (_, txs) -> txs.sortedBy { it.timestampMillis } }
        return purchases
            .filter { window.contains(it.timestampMillis, zone) }
            .mapNotNull { candidate ->
                val group = groups.getValue(merchantKey(candidate)!! to candidate.currencyCode.uppercase())
                judge(candidate, group.filter { it !== candidate && it.timestampMillis < candidate.timestampMillis })
            }
            .sortedByDescending { it.ratio }
            .take(max)
    }

    private fun judge(candidate: Transaction, prior: List<Transaction>): Anomaly? {
        if (prior.size < MIN_HISTORY) return null
        val amounts = prior.sortedBy { it.timestampMillis }.takeLast(MAX_HISTORY).map { it.amount.toDouble() }
        val median = median(amounts)
        if (median <= 0.0) return null
        val mad = median(amounts.map { kotlin.math.abs(it - median) })
        val threshold = maxOf(median + MAD_K * MAD_SCALE * mad, MIN_RATIO * median)
        val amount = candidate.amount.toDouble()
        if (amount < threshold) return null
        if (candidate.amount - BigDecimal.valueOf(median) < MIN_EXCESS) return null
        return Anomaly(
            transaction = candidate,
            merchant = candidate.merchant!!.trim(),
            amount = candidate.amount,
            usual = BigDecimal.valueOf(median).setScale(2, RoundingMode.HALF_UP),
            ratio = amount / median,
            currencyCode = candidate.currencyCode.uppercase(),
        )
    }

    private fun merchantKey(tx: Transaction): String? =
        tx.merchant?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    internal fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /**
     * Whether a just-recorded [tx] may raise a phone notification: only when
     * the user opted in, only for a live capture (SMS or push, never a
     * pasted backlog or a hand-entered row), and only while it's fresh -- an
     * alert about a charge from last month is noise.
     */
    fun shouldAlert(tx: Transaction, nowMillis: Long, enabled: Boolean): Boolean =
        enabled &&
            LedgerMath.isSpend(tx) &&
            tx.source in LIVE_SOURCES &&
            nowMillis - tx.timestampMillis in -CLOCK_SKEW_MILLIS..ALERT_FRESHNESS_MILLIS

    private val LIVE_SOURCES = setOf(
        com.xemmy.mmbank.domain.model.TransactionSource.SMS,
        com.xemmy.mmbank.domain.model.TransactionSource.PUSH_NOTIFICATION,
    )

    private const val ALERT_FRESHNESS_MILLIS = 48L * 60 * 60 * 1000

    /** A notification's post time can run slightly ahead of this device's clock. */
    private const val CLOCK_SKEW_MILLIS = 5L * 60 * 1000
}
