package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Per-merchant spend profile: totals, extremes and a monthly series for a
 * trend sparkline. Pure and top-level, mirroring [SubscriptionRadarEngine]'s
 * shape so it's JVM-testable without Robolectric.
 */
object MerchantProfiler {

    data class MonthlySpend(val yearMonth: YearMonth, val total: BigDecimal)

    data class MerchantProfile(
        val merchant: String,
        val currencyCode: String,
        val totalSpent: BigDecimal,
        val transactionCount: Int,
        val averageAmount: BigDecimal,
        val minAmount: BigDecimal,
        val maxAmount: BigDecimal,
        val firstSeenMillis: Long,
        val lastSeenMillis: Long,
        val monthlySeries: List<MonthlySpend>,
    )

    /**
     * Builds a profile from real DEBIT charges matching [merchant]
     * (case-insensitive, trimmed — same key [SubscriptionRadarEngine] groups
     * on). Scoped to the merchant's most common currency, mirroring the
     * single-currency stance of [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator]
     * and [com.xemmy.mmbank.domain.report.MonthlyReportBuilder]: a merchant
     * charged in two currencies would otherwise be averaged across both.
     * Returns null when there is no matching history.
     */
    fun profile(
        transactions: List<Transaction>,
        merchant: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): MerchantProfile? {
        val key = merchant.trim().lowercase()
        val matches = transactions.filter {
            LedgerMath.isSpend(it) &&
                !it.isDemo &&
                it.merchant?.trim()?.lowercase() == key
        }
        if (matches.isEmpty()) return null

        val byCurrency = matches.groupBy { it.currencyCode }
        val currencyCode = byCurrency.entries.maxBy { it.value.size }.key
        val sameCurrency = byCurrency.getValue(currencyCode)

        val amounts = sameCurrency.map { it.amount }
        val total = amounts.fold(BigDecimal.ZERO) { acc, amount -> acc + amount }
        val count = sameCurrency.size
        val average = total.divide(BigDecimal(count), 2, RoundingMode.HALF_UP)

        val monthlySeries = sameCurrency
            .groupBy { YearMonth.from(Instant.ofEpochMilli(it.timestampMillis).atZone(zoneId)) }
            .map { (month, txs) -> MonthlySpend(month, txs.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }) }
            .sortedBy { it.yearMonth }

        return MerchantProfile(
            merchant = merchant,
            currencyCode = currencyCode,
            totalSpent = total,
            transactionCount = count,
            averageAmount = average,
            minAmount = amounts.min(),
            maxAmount = amounts.max(),
            firstSeenMillis = sameCurrency.minOf { it.timestampMillis },
            lastSeenMillis = sameCurrency.maxOf { it.timestampMillis },
            monthlySeries = monthlySeries,
        )
    }
}
