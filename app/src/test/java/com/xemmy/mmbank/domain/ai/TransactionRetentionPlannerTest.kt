package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionRetentionPlannerTest {

    private val now = Instant.parse("2026-08-14T00:00:00Z").toEpochMilli()

    private fun monthsAgoMillis(months: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).minusMonths(months).toInstant().toEpochMilli()

    private fun tx(
        id: Long,
        amount: String,
        timestampMillis: Long,
        currencyCode: String = "AED",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        category: SpendingCategory = SpendingCategory.UNCATEGORIZED,
        excludeFromStats: Boolean = false,
    ) = Transaction(
        id = id,
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = timestampMillis,
        spendingCategory = category,
        excludeFromStats = excludeFromStats,
    )

    @Test
    fun `retention disabled prunes nothing`() {
        val transactions = listOf(tx(1, "50", monthsAgoMillis(48)))
        val plan = TransactionRetentionPlanner.plan(transactions, retentionMonths = 0, nowMillis = now)
        assertEquals(emptyList<Long>(), plan.transactionIdsToDelete)
    }

    @Test
    fun `rows inside the retention window are always kept`() {
        val transactions = listOf(tx(1, "50", monthsAgoMillis(3)))
        val plan = TransactionRetentionPlanner.plan(transactions, retentionMonths = 12, nowMillis = now)
        assertEquals(emptyList<Long>(), plan.transactionIdsToDelete)
    }

    @Test
    fun `ordinary old row past the window is pruned`() {
        val transactions = listOf(tx(1, "50", monthsAgoMillis(24)))
        val plan = TransactionRetentionPlanner.plan(transactions, retentionMonths = 12, nowMillis = now)
        assertEquals(listOf(1L), plan.transactionIdsToDelete)
    }

    @Test
    fun `an old anomalously large debit is protected`() {
        // 11 ordinary AED 100 debits (one per month) set the average; one huge
        // old outlier should clear AiRetentionScorer's 3x-average bar and survive.
        val ordinary = (1..11).map { tx(it.toLong(), "100", monthsAgoMillis(it.toLong() + 12)) }
        val outlier = tx(99, "10000", monthsAgoMillis(24))
        val plan = TransactionRetentionPlanner.plan(ordinary + outlier, retentionMonths = 12, nowMillis = now)
        assertTrue(99L !in plan.transactionIdsToDelete)
    }

    @Test
    fun `an old income row is protected regardless of amount`() {
        val transactions = listOf(tx(1, "50", monthsAgoMillis(24), category = SpendingCategory.INCOME))
        val plan = TransactionRetentionPlanner.plan(transactions, retentionMonths = 12, nowMillis = now)
        assertEquals(emptyList<Long>(), plan.transactionIdsToDelete)
    }

    @Test
    fun `an old excludeFromStats correction is not protected and gets pruned`() {
        val transactions = listOf(tx(1, "5000", monthsAgoMillis(24), excludeFromStats = true))
        val plan = TransactionRetentionPlanner.plan(transactions, retentionMonths = 12, nowMillis = now)
        assertEquals(listOf(1L), plan.transactionIdsToDelete)
    }

    @Test
    fun `averages are scoped per currency so a huge AED average can't shield a small USD anomaly`() {
        // A large old AED history inflates any *mixed* average enormously.
        val aed = (1..3).map { tx(it.toLong(), "100000", monthsAgoMillis(it.toLong() + 12), currencyCode = "AED") }
        // USD's own average (including the outlier itself) is 57.50, so 200
        // clears its 3x bar (172.50) and should be protected -- but would look
        // ordinary against a mixed average dragged up by the AED rows above.
        val usdHistory = (4..6).map { tx(it.toLong(), "10", monthsAgoMillis(it.toLong() + 9), currencyCode = "USD") }
        val usdOutlier = tx(50, "200", monthsAgoMillis(24), currencyCode = "USD")
        val plan = TransactionRetentionPlanner.plan(aed + usdHistory + listOf(usdOutlier), retentionMonths = 12, nowMillis = now)
        assertTrue(50L !in plan.transactionIdsToDelete)
    }

    @Test
    fun `credit rows never anchor the debit average and can themselves be pruned`() {
        val credit = tx(1, "9999", monthsAgoMillis(24), direction = TransactionDirection.CREDIT)
        val plan = TransactionRetentionPlanner.plan(listOf(credit), retentionMonths = 12, nowMillis = now)
        assertEquals(listOf(1L), plan.transactionIdsToDelete)
    }

    @Test
    fun `cutoffMillis moves back exactly the configured number of months in UTC`() {
        val cutoff = TransactionRetentionPlanner.cutoffMillis(now, retentionMonths = 6)
        assertEquals(monthsAgoMillis(6), cutoff)
    }

    @Test
    fun `empty transaction list plans nothing`() {
        val plan = TransactionRetentionPlanner.plan(emptyList(), retentionMonths = 12, nowMillis = now)
        assertEquals(emptyList<Long>(), plan.transactionIdsToDelete)
    }
}
