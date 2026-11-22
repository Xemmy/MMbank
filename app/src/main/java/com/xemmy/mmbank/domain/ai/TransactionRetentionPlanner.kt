package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure planner for the opt-in transaction-retention job: decides which
 * transactions past the configured age are safe to prune, deferring the
 * "is this one worth keeping regardless of age" call to [AiRetentionScorer]
 * -- the object phase-ai-c shipped for exactly this purpose but never wired
 * into a real job (`BackupManager` only exported/imported until now).
 *
 * Pure state-in/state-out, decoupled from Room and the wall clock (the
 * caller supplies [nowMillis]), so it's JVM-testable without Robolectric --
 * same shape as [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator].
 */
object TransactionRetentionPlanner {

    data class Plan(
        val transactionIdsToDelete: List<Long>,
        val cutoffMillis: Long,
    )

    /** `retentionMonths` months back from `nowMillis`, in UTC so the result doesn't depend on the device's zone. */
    fun cutoffMillis(nowMillis: Long, retentionMonths: Int): Long =
        Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneOffset.UTC)
            .minusMonths(retentionMonths.toLong())
            .toInstant()
            .toEpochMilli()

    /**
     * [retentionMonths] <= 0 means retention is off -- returns an empty plan
     * without scanning, so a disabled setting can never delete anything.
     * [transactions] should already be the real (non-demo) partition; demo
     * rows have no business being pruned by a background job.
     *
     * The average that feeds [AiRetentionScorer]'s anomaly check is computed
     * from every real debit in [transactions], not just the rows old enough
     * to be candidates -- otherwise the bar a transaction must clear to be
     * "significant" would drift as old rows get pruned in earlier runs.
     */
    fun plan(
        transactions: List<Transaction>,
        retentionMonths: Int,
        nowMillis: Long,
        anomalyMultiplier: BigDecimal = BigDecimal("3"),
        protectedIds: Set<Long> = emptySet(),
    ): Plan {
        if (retentionMonths <= 0) return Plan(emptyList(), nowMillis)
        val cutoff = cutoffMillis(nowMillis, retentionMonths)
        val averageByCurrency = averageMonthlyDebitByCurrency(transactions)

        val toDelete = transactions.filter { tx ->
            tx.timestampMillis < cutoff &&
                tx.id !in protectedIds &&
                !isAnnotated(tx) &&
                !AiRetentionScorer.isSignificant(
                    tx,
                    averageByCurrency[tx.currencyCode.uppercase()] ?: BigDecimal.ZERO,
                    anomalyMultiplier,
                )
        }.map(Transaction::id)

        return Plan(toDelete, cutoff)
    }

    /** The user wrote something on it (a note) or it is a bill line's payment: kept whatever its age. */
    private fun isAnnotated(tx: Transaction): Boolean = !tx.note.isNullOrBlank() || tx.billRef != null

    /**
     * Ids the prune must keep because something else points at them: a subscription's linked
     * charges, a Pay Later plan's purchase and its paid installments, and labelled rows. Deleting
     * them would shrink a subscription's history, un-pay an installment's record or drop a label.
     */
    fun protectedIds(
        subscriptionCharges: Collection<Long>,
        payLaterRows: Collection<Long>,
        labelledRows: Collection<Long>,
    ): Set<Long> = buildSet {
        addAll(subscriptionCharges)
        addAll(payLaterRows)
        addAll(labelledRows)
    }

    /** Real, analytics-eligible debit spend averaged per calendar month present in the data, keyed by currency. */
    private fun averageMonthlyDebitByCurrency(transactions: List<Transaction>): Map<String, BigDecimal> {
        val debits = transactions.filter { LedgerMath.isSpend(it) }
        return debits.groupBy { it.currencyCode.uppercase() }.mapValues { (_, rows) ->
            val total = rows.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }
            val months = rows.map { monthKey(it.timestampMillis) }.distinct().size.coerceAtLeast(1)
            total.divide(BigDecimal(months), 2, RoundingMode.HALF_UP)
        }
    }

    private fun monthKey(millis: Long): String {
        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
        return "${date.year}-${date.monthValue}"
    }
}
