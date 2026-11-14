package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.MerchantNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/**
 * A calendar year at a glance (backlog B.5 #5): what came in and went out,
 * the busiest and quietest month, the biggest single expense, where the money
 * went, and how spending compares with the year before. All figures are in
 * one base currency, converted row by row with the caller's `toBase`; a row it
 * can't convert is left out, as everywhere else.
 *
 * Spent / Received follow [LedgerMath]: refunds lower spending, transfers,
 * card payments and hidden rows count for nothing. Pure and JVM-testable.
 */
data class YearInReview(
    val year: Int,
    val currency: String,
    val spent: Double,
    val received: Double,
    val net: Double,
    /** (received - spent) / received; null when nothing was received. */
    val savingsRate: Double?,
    /** Net spend for January..December, in order. */
    val monthlySpend: List<Double>,
    val busiestMonth: Month?,
    val quietestMonth: Month?,
    val biggestExpense: Expense?,
    val topMerchants: List<Ranked>,
    val topCategories: List<Ranked>,
    val transactionCount: Int,
    /** Days of the year (up to today, for the current year) with no purchase at all. */
    val noSpendDays: Int,
    val previousYearSpent: Double?,
    /** Percent change in spending vs the year before; null when there was none to compare with. */
    val spendChangePercent: Double?,
) {
    data class Expense(val merchant: String, val amount: Double, val date: LocalDate)
    data class Ranked(val name: String, val amount: Double, val count: Int)
}

object YearInReviewBuilder {

    const val TOP_N = 5
    private const val UNCATEGORIZED = "Uncategorized"

    /** Null when [year] has no spending and no income to review. */
    fun build(
        year: Int,
        transactions: List<Transaction>,
        categoryNames: Map<Long, String>,
        currency: String,
        toBase: (Transaction) -> Double?,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): YearInReview? {
        val inYear = transactions.filter { yearOf(it, zone) == year }
        val spent = LedgerMath.netSpend(inYear, value = toBase)
        val received = LedgerMath.income(inYear, value = toBase)
        if (inYear.none { LedgerMath.affectsSpend(it) || LedgerMath.isIncome(it) }) return null

        val monthly = DoubleArray(12)
        for (tx in inYear) {
            val sign = LedgerMath.spendSign(tx)
            if (sign == 0) continue
            val value = toBase(tx) ?: continue
            monthly[monthOf(tx, zone) - 1] += value * sign
        }
        val monthsWithSpend = monthly.withIndex().filter { it.value > 0.0 }
        val busiest = monthsWithSpend.maxByOrNull { it.value }?.let { Month.of(it.index + 1) }
        val quietest = monthsWithSpend.minByOrNull { it.value }?.let { Month.of(it.index + 1) }

        val purchases = inYear.filter { LedgerMath.isSpend(it) }
        val biggest = purchases
            .mapNotNull { tx -> toBase(tx)?.let { tx to it } }
            .maxByOrNull { it.second }
            ?.let { (tx, value) ->
                YearInReview.Expense(
                    merchant = tx.merchant?.takeIf { it.isNotBlank() }?.trim() ?: "(no merchant)",
                    amount = value,
                    date = dateOf(tx, zone),
                )
            }

        val previous = transactions.filter { yearOf(it, zone) == year - 1 }
        val previousSpent = previous.takeIf { rows -> rows.any { LedgerMath.affectsSpend(it) } }
            ?.let { LedgerMath.netSpend(it, value = toBase) }

        return YearInReview(
            year = year,
            currency = currency,
            spent = spent,
            received = received,
            net = received - spent,
            savingsRate = received.takeIf { it > 0.0 }?.let { (it - spent) / it },
            monthlySpend = monthly.toList(),
            busiestMonth = busiest,
            quietestMonth = quietest,
            biggestExpense = biggest,
            topMerchants = rank(inYear, toBase) { tx ->
                MerchantNormalizer.key(tx.merchant).ifEmpty { tx.merchant?.trim()?.lowercase().orEmpty() }
                    .takeIf { it.isNotEmpty() }
                    ?.let { key -> key to (MerchantNormalizer.displayName(tx.merchant) ?: tx.merchant!!.trim()) }
            },
            topCategories = rank(inYear, toBase) { tx ->
                val name = tx.categoryId?.let(categoryNames::get) ?: UNCATEGORIZED
                name.lowercase() to name
            },
            transactionCount = inYear.size,
            noSpendDays = noSpendDays(year, purchases, today, zone),
            previousYearSpent = previousSpent,
            spendChangePercent = previousSpent?.takeIf { it > 0.0 }?.let { (spent - it) / it * 100.0 },
        )
    }

    /** Net spend grouped by [group] (key to display name), biggest first, positive totals only. */
    private fun rank(
        rows: List<Transaction>,
        toBase: (Transaction) -> Double?,
        group: (Transaction) -> Pair<String, String>?,
    ): List<YearInReview.Ranked> {
        data class Acc(var name: String, var amount: Double = 0.0, var count: Int = 0)
        val totals = LinkedHashMap<String, Acc>()
        for (tx in rows) {
            val sign = LedgerMath.spendSign(tx)
            if (sign == 0) continue
            val value = toBase(tx) ?: continue
            val (key, name) = group(tx) ?: continue
            val acc = totals.getOrPut(key) { Acc(name) }
            acc.amount += value * sign
            if (sign > 0) acc.count++
        }
        return totals.values.filter { it.amount > 0.0 }
            .sortedByDescending { it.amount }
            .take(TOP_N)
            .map { YearInReview.Ranked(it.name, it.amount, it.count) }
    }

    private fun noSpendDays(year: Int, purchases: List<Transaction>, today: LocalDate, zone: ZoneId): Int {
        val first = LocalDate.of(year, 1, 1)
        val last = minOf(LocalDate.of(year, 12, 31), today)
        if (last.isBefore(first)) return 0
        val spendDays = purchases.map { dateOf(it, zone) }.toSet()
        val total = (java.time.temporal.ChronoUnit.DAYS.between(first, last) + 1).toInt()
        return total - spendDays.count { !it.isBefore(first) && !it.isAfter(last) }
    }

    private fun dateOf(tx: Transaction, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()

    private fun yearOf(tx: Transaction, zone: ZoneId): Int = dateOf(tx, zone).year

    private fun monthOf(tx: Transaction, zone: ZoneId): Int = dateOf(tx, zone).monthValue
}
