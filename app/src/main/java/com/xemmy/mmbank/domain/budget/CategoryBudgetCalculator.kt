package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * Per-category monthly budgets: how much of each [Category.monthlyCap] this
 * month's spending has eaten, and which caps are near or past their limit.
 *
 * Deliberately a sibling of [DailyBudgetPacing] rather than part of it -- that
 * object paces *total* discretionary spend across the days left in the month,
 * this one measures *per-category* spend against that category's own cap. The
 * two answer different questions and are configured independently.
 *
 * Amounts are scoped to a single [currencyCode] throughout, mirroring how the
 * existing pacing check reads `sumDebitsInRange(transaction.currencyCode, ...)`:
 * a cap is denominated in one currency, so summing mixed-currency rows into it
 * would silently compare dirhams against dollars.
 *
 * Pure state-in/state-out, decoupled from Room and from the wall clock (the
 * caller supplies the month window), so it's JVM-testable without Robolectric.
 */
object CategoryBudgetCalculator {

    /** Fraction of a cap at which a category is flagged as approaching its limit. */
    const val NEAR_THRESHOLD = 0.80

    /** Where a category sits against its cap. */
    enum class BudgetState { UNDER, NEAR, OVER }

    data class CategoryBudgetStatus(
        val category: Category,
        val spent: BigDecimal,
        val cap: BigDecimal,
        /** [spent] / [cap]; can exceed 1.0 once the cap is blown. */
        val fraction: Double,
        val state: BudgetState,
    ) {
        /** Never negative -- an overspent category has zero left, not "minus 40". */
        val remaining: BigDecimal get() = (cap - spent).max(BigDecimal.ZERO)
    }

    /**
     * Month-to-date spend per `categoryId`, in [currencyCode].
     *
     * Net spending as [LedgerMath] defines it: purchases add, refunds give
     * the money back to the category's envelope, and transfers, card
     * payments, income and hidden rows are skipped -- so this agrees with
     * every other spend total in the app. Transactions with no dynamic
     * category are skipped -- they belong to no budget.
     *
     * The window is half-open, `[startMillis, endMillisExclusive)`, so a
     * transaction landing exactly on the next month's first millisecond counts
     * toward that month and not this one.
     */
    fun monthToDateByCategory(
        transactions: List<Transaction>,
        currencyCode: String,
        startMillis: Long,
        endMillisExclusive: Long,
    ): Map<Long, BigDecimal> {
        val sums = mutableMapOf<Long, BigDecimal>()
        for (tx in transactions) {
            if (!LedgerMath.affectsSpend(tx)) continue
            if (!tx.currencyCode.equals(currencyCode, ignoreCase = true)) continue
            if (tx.timestampMillis < startMillis || tx.timestampMillis >= endMillisExclusive) continue
            val categoryId = tx.categoryId ?: continue
            sums[categoryId] = (sums[categoryId] ?: BigDecimal.ZERO) + LedgerMath.spendDelta(tx)
        }
        return sums.mapValues { (_, v) -> v.max(BigDecimal.ZERO) }
    }

    /**
     * The status of one category, or null when it carries no usable cap --
     * either unset, or set to zero/negative, which reads as "no budget" rather
     * than "a budget of nothing you instantly blow past".
     */
    fun statusFor(category: Category, spent: BigDecimal): CategoryBudgetStatus? {
        val cap = category.monthlyCap ?: return null
        if (cap.signum() <= 0) return null
        val fraction = spent.toDouble() / cap.toDouble()
        val state = when {
            spent >= cap -> BudgetState.OVER
            fraction >= NEAR_THRESHOLD -> BudgetState.NEAR
            else -> BudgetState.UNDER
        }
        return CategoryBudgetStatus(category, spent, cap, fraction, state)
    }

    /** Statuses for every capped category, closest to (or furthest past) its cap first. */
    fun statuses(
        categories: List<Category>,
        spendByCategoryId: Map<Long, BigDecimal>,
    ): List<CategoryBudgetStatus> =
        categories
            .mapNotNull { statusFor(it, spendByCategoryId[it.id] ?: BigDecimal.ZERO) }
            .sortedByDescending { it.fraction }

    /**
     * [directSpendByCategoryId] with every parent's total widened to include
     * its children's spend, so a parent category's cap reads as a combined
     * budget for itself and everything filed under it (a child keeps its own
     * unrolled total for its own status).
     *
     * Guards against a cycle (a category set as its own ancestor) by never
     * revisiting a category already on the current path -- that category's
     * contribution through the cycle is simply zero rather than an infinite
     * recursion.
     */
    fun rollUpSpend(
        categories: List<Category>,
        directSpendByCategoryId: Map<Long, BigDecimal>,
    ): Map<Long, BigDecimal> {
        val childrenOf = categories.filter { it.parentCategoryId != null }.groupBy { it.parentCategoryId }
        val memo = mutableMapOf<Long, BigDecimal>()

        fun total(category: Category, path: Set<Long>): BigDecimal {
            memo[category.id]?.let { return it }
            if (category.id in path) return BigDecimal.ZERO
            val own = directSpendByCategoryId[category.id] ?: BigDecimal.ZERO
            val childrenTotal = childrenOf[category.id].orEmpty()
                .fold(BigDecimal.ZERO) { acc, child -> acc + total(child, path + category.id) }
            val result = own + childrenTotal
            memo[category.id] = result
            return result
        }

        return categories.associate { it.id to total(it, emptySet()) }
    }

    /**
     * Identity of one alert, so each threshold notifies once per category per
     * month instead of on every transaction that keeps it over the line.
     * Scoped by month so a new month starts clean without any reset job.
     */
    fun alertKey(categoryId: Long, monthKey: String, state: BudgetState): String =
        "$categoryId:$monthKey:$state"

    /**
     * The key to record and notify on, or null when nothing should fire --
     * either the category is still [BudgetState.UNDER], or this threshold has
     * already been announced this month.
     *
     * NEAR and OVER are separate keys, so a category that creeps to 80% and
     * later blows past 100% alerts twice, once for each milestone. Spend only
     * accumulates within a month, so a category that goes straight to OVER
     * never retroactively fires its NEAR alert.
     */
    fun pendingAlert(
        status: CategoryBudgetStatus,
        monthKey: String,
        alreadyAlerted: Set<String>,
    ): String? {
        if (status.state == BudgetState.UNDER) return null
        val key = alertKey(status.category.id, monthKey, status.state)
        return if (key in alreadyAlerted) null else key
    }
}
