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
 * A cap is denominated in the base currency. Spend in another currency is
 * converted at the stored rates ([converter]); with no rate it is reported as
 * uncounted ([MonthSpend]) instead of being summed in at face value, which
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
     * Month-to-date budget spend, in the base currency, plus what could not be
     * counted. [uncountedByCurrency] holds spend (net of refunds, never
     * negative) in currencies with no usable rate -- shown to the user as
     * "not counted" rather than silently ignored, so a cap that looks safe
     * while foreign spend is missing doesn't mislead.
     */
    data class MonthSpend(
        val byCategory: Map<Long, BigDecimal>,
        val uncountedByCurrency: Map<String, BigDecimal> = emptyMap(),
    ) {
        val hasUncounted: Boolean get() = uncountedByCurrency.isNotEmpty()
    }

    /**
     * A converter into [baseCurrency] for [monthSpend]: an amount in `quote`
     * currency divided by its rate (quote per 1 base), or null when there is
     * no positive rate for it. Rounded to 2 places, half-even.
     */
    fun converter(
        baseCurrency: String,
        ratesFromBase: Map<String, BigDecimal>,
    ): (String, BigDecimal) -> BigDecimal? = { code, amount ->
        if (code.equals(baseCurrency, ignoreCase = true)) {
            amount
        } else {
            ratesFromBase[code.uppercase()]
                ?.takeIf { it.signum() > 0 }
                ?.let { amount.divide(it, 2, java.math.RoundingMode.HALF_EVEN) }
        }
    }

    /**
     * Month-to-date spend per `categoryId`, in [baseCurrency].
     *
     * Net spending as [LedgerMath] defines it: purchases add, refunds give
     * the money back to the category's envelope, and transfers, card
     * payments, income and hidden rows are skipped -- so this agrees with
     * every other spend total in the app. Transactions with no dynamic
     * category are skipped -- they belong to no budget.
     *
     * A row in another currency is converted with [convert] (see
     * [converter]); when that returns null the row goes into
     * [MonthSpend.uncountedByCurrency] instead of being dropped. The default
     * [convert] converts nothing, which is the original same-currency-only
     * behaviour, with the skipped spend now reported.
     *
     * The window is half-open, `[startMillis, endMillisExclusive)`, so a
     * transaction landing exactly on the next month's first millisecond counts
     * toward that month and not this one.
     */
    fun monthSpend(
        transactions: List<Transaction>,
        baseCurrency: String,
        startMillis: Long,
        endMillisExclusive: Long,
        convert: (String, BigDecimal) -> BigDecimal? = converter(baseCurrency, emptyMap()),
    ): MonthSpend {
        val sums = mutableMapOf<Long, BigDecimal>()
        val uncounted = mutableMapOf<String, BigDecimal>()
        for (tx in transactions) {
            if (!LedgerMath.affectsSpend(tx)) continue
            if (tx.timestampMillis < startMillis || tx.timestampMillis >= endMillisExclusive) continue
            val categoryId = tx.categoryId ?: continue
            val delta = LedgerMath.spendDelta(tx)
            val inBase = convert(tx.currencyCode, delta)
            if (inBase == null) {
                val code = tx.currencyCode.uppercase()
                uncounted[code] = (uncounted[code] ?: BigDecimal.ZERO) + delta
            } else {
                sums[categoryId] = (sums[categoryId] ?: BigDecimal.ZERO) + inBase
            }
        }
        return MonthSpend(
            byCategory = sums.mapValues { (_, v) -> v.max(BigDecimal.ZERO) },
            uncountedByCurrency = uncounted.filterValues { it.signum() > 0 },
        )
    }

    /**
     * "Not counted: USD 45.00, EUR 12.00 (no exchange rate)" -- or null when
     * nothing was left out. Currencies are listed alphabetically so the line
     * doesn't reorder between refreshes.
     */
    fun uncountedNotice(uncountedByCurrency: Map<String, BigDecimal>): String? {
        if (uncountedByCurrency.isEmpty()) return null
        val parts = uncountedByCurrency.toSortedMap().entries
            .joinToString(", ") { (code, amount) -> "$code ${amount.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString()}" }
        return "Not counted toward caps: $parts (no exchange rate)"
    }

    /** Same-currency month-to-date spend per `categoryId`; see [monthSpend]. */
    fun monthToDateByCategory(
        transactions: List<Transaction>,
        currencyCode: String,
        startMillis: Long,
        endMillisExclusive: Long,
    ): Map<Long, BigDecimal> =
        monthSpend(transactions, currencyCode, startMillis, endMillisExclusive).byCategory

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
