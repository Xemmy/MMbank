package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator
import com.xemmy.mmbank.domain.model.FinancialHealthMetrics
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import java.math.RoundingMode

/** One category's share of a month's spend. */
data class ReportCategoryLine(
    val name: String,
    val amount: BigDecimal,
    /** 0.0-1.0 of the month's total spend. */
    val shareOfSpend: Double,
)

/** One merchant's share of a month's spend. */
data class ReportMerchantLine(val name: String, val amount: BigDecimal, val transactionCount: Int)

/**
 * Everything a month's statement shows. Assembled once, then rendered — the
 * formatter never recomputes anything.
 */
data class MonthlyReport(
    val monthLabel: String,
    val currency: String,
    val income: BigDecimal,
    val spent: BigDecimal,
    val net: BigDecimal,
    /** Change vs the previous month; null when there's no previous month to compare against. */
    val incomeDelta: BigDecimal?,
    val spentDelta: BigDecimal?,
    val transactionCount: Int,
    val netWorth: NetWorthAggregator.NetWorthBreakdown?,
    val topCategories: List<ReportCategoryLine>,
    val topMerchants: List<ReportMerchantLine>,
    val subscriptions: List<Subscription>,
    /** Monthly-equivalent cost of every active subscription. */
    val subscriptionMonthlyTotal: BigDecimal,
    val goals: List<SavingsGoal>,
    val health: FinancialHealthMetrics?,
)

/**
 * Assembles a month's statement from data the caller has already loaded.
 *
 * Pure and top-level, so the whole shape of a report is JVM-testable without a
 * database, a clock or a renderer — the same stance as
 * [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator] and
 * [com.xemmy.mmbank.domain.intelligence.SubscriptionReminders].
 *
 * Scoped to one [currency]: rows in other currencies are left out rather than
 * summed in, since the report has no exchange rates and adding dirhams to
 * dollars would silently produce a wrong headline figure.
 */
object MonthlyReportBuilder {

    private const val TOP_N = 8

    fun build(
        monthLabel: String,
        currency: String,
        monthTransactions: List<Transaction>,
        previousMonthTransactions: List<Transaction> = emptyList(),
        categoryNames: Map<Long, String> = emptyMap(),
        accountBalances: List<NetWorthAggregator.AccountBalance> = emptyList(),
        subscriptions: List<Subscription> = emptyList(),
        goals: List<SavingsGoal> = emptyList(),
        health: FinancialHealthMetrics? = null,
    ): MonthlyReport {
        val current = monthTransactions.filter { it.countsToward(currency) }
        val previous = previousMonthTransactions.filter { it.countsToward(currency) }

        val income = current.total(TransactionDirection.CREDIT)
        val spent = current.total(TransactionDirection.DEBIT)
        val hasPrevious = previous.isNotEmpty()

        return MonthlyReport(
            monthLabel = monthLabel,
            currency = currency,
            income = income,
            spent = spent,
            net = income - spent,
            incomeDelta = if (hasPrevious) income - previous.total(TransactionDirection.CREDIT) else null,
            spentDelta = if (hasPrevious) spent - previous.total(TransactionDirection.DEBIT) else null,
            transactionCount = current.size,
            netWorth = accountBalances.takeIf { it.isNotEmpty() }?.let(NetWorthAggregator::aggregate),
            topCategories = topCategories(current, categoryNames, spent),
            topMerchants = topMerchants(current),
            subscriptions = subscriptions.filter { !it.dismissed }.sortedBy { it.nextDueDateMillis },
            subscriptionMonthlyTotal = subscriptionMonthlyTotal(subscriptions, currency),
            goals = goals.filter { !it.archived },
            health = health,
        )
    }

    /**
     * Real outgoing/incoming money in [currency]: analytics-excluded rows
     * (balance adjustments, cashback) are skipped so the statement agrees with
     * every other total in the app.
     */
    private fun Transaction.countsToward(currency: String): Boolean =
        !excludeFromStats && currencyCode.equals(currency, ignoreCase = true)

    private fun List<Transaction>.total(direction: TransactionDirection): BigDecimal =
        filter { it.direction == direction }.fold(BigDecimal.ZERO) { acc, tx -> acc + tx.amount }

    private fun topCategories(
        transactions: List<Transaction>,
        categoryNames: Map<Long, String>,
        totalSpend: BigDecimal,
    ): List<ReportCategoryLine> {
        val sums = mutableMapOf<String, BigDecimal>()
        transactions
            .filter { it.direction == TransactionDirection.DEBIT }
            .forEach { tx ->
                // Uncategorized spend is shown rather than dropped -- a report
                // whose category rows don't add up to the headline is worse
                // than one that admits what hasn't been filed yet.
                val name = tx.categoryId?.let { categoryNames[it] } ?: "Uncategorized"
                sums[name] = (sums[name] ?: BigDecimal.ZERO) + tx.amount
            }
        return sums.entries
            .sortedByDescending { it.value }
            .take(TOP_N)
            .map { (name, amount) ->
                ReportCategoryLine(
                    name = name,
                    amount = amount,
                    shareOfSpend = if (totalSpend.signum() > 0) {
                        amount.toDouble() / totalSpend.toDouble()
                    } else {
                        0.0
                    },
                )
            }
    }

    private fun topMerchants(transactions: List<Transaction>): List<ReportMerchantLine> {
        val sums = mutableMapOf<String, Pair<BigDecimal, Int>>()
        transactions
            .filter { it.direction == TransactionDirection.DEBIT }
            .forEach { tx ->
                val name = tx.merchant?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                val (amount, count) = sums[name] ?: (BigDecimal.ZERO to 0)
                sums[name] = (amount + tx.amount) to (count + 1)
            }
        return sums.entries
            .sortedByDescending { it.value.first }
            .take(TOP_N)
            .map { (name, pair) -> ReportMerchantLine(name, pair.first, pair.second) }
    }

    /**
     * Normalizes each cadence to a monthly figure using the same nominal day
     * counts the detector itself uses ([SubscriptionCadence.nominalDays]), so a
     * weekly charge is counted at 30/7 per month rather than a hand-picked 4.
     */
    private fun subscriptionMonthlyTotal(subscriptions: List<Subscription>, currency: String): BigDecimal =
        subscriptions
            .filter { !it.dismissed && it.currencyCode.equals(currency, ignoreCase = true) }
            .fold(BigDecimal.ZERO) { acc, subscription ->
                val perMonth = subscription.averageAmount
                    .multiply(BigDecimal(SubscriptionCadence.MONTHLY.nominalDays))
                    .divide(BigDecimal(subscription.cadence.nominalDays), 2, RoundingMode.HALF_UP)
                acc + perMonth
            }
}
