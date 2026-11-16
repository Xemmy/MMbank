package com.xemmy.mmbank.domain.widget

import com.xemmy.mmbank.domain.budget.BudgetListRow
import com.xemmy.mmbank.domain.report.CategoryShare

/** What a home-screen widget shows. A locked or hidden widget never carries the figures at all. */
sealed interface WidgetState<out T> {
    /** The app lock is engaged: nothing about the ledger may appear. */
    data object Locked : WidgetState<Nothing>

    /** "Hide balances" is on. */
    data object Hidden : WidgetState<Nothing>

    /** Nothing to show yet (no accounts, no spending, no budgets set). */
    data object Empty : WidgetState<Nothing>

    data class Normal<T>(val data: T) : WidgetState<T>
}

object WidgetGate {
    /** Locked beats hidden beats empty beats normal, so privacy settings always win over content. */
    fun <T> decide(locked: Boolean, hideBalances: Boolean, data: T?, isEmpty: (T) -> Boolean = { false }): WidgetState<T> = when {
        locked -> WidgetState.Locked
        hideBalances -> WidgetState.Hidden
        data == null || isEmpty(data) -> WidgetState.Empty
        else -> WidgetState.Normal(data)
    }
}

data class BalanceWidgetData(val netWorth: Double, val change30d: Double?, val currency: String)

data class NamedAmount(val name: String, val amount: Double)

data class SpendingWidgetData(val spentThisMonth: Double, val top: List<NamedAmount>, val currency: String) {
    val isEmpty: Boolean get() = spentThisMonth <= 0.0 && top.isEmpty()
}

data class BudgetWidgetLine(val name: String, val icon: String, val spent: Double, val cap: Double) {
    /** Spent over cap, 0..1+ (over 1 once the cap is passed). */
    val fraction: Double get() = if (cap > 0.0) spent / cap else 0.0
    val over: Boolean get() = spent > cap
}

data class BudgetsWidgetData(val lines: List<BudgetWidgetLine>, val currency: String)

object WidgetData {
    const val TOP_CATEGORIES = 3
    const val BUDGET_LINES = 4

    /** The categories that took the most this month, biggest first. */
    fun topCategories(shares: List<CategoryShare>, max: Int = TOP_CATEGORIES): List<NamedAmount> =
        shares.sortedByDescending { it.amount }.take(max).map { NamedAmount(it.category.name, it.amount) }

    /** Up to [max] categories that have a cap, the fullest first, so a blown budget is never off the widget. */
    fun budgetLines(rows: List<BudgetListRow>, max: Int = BUDGET_LINES): List<BudgetWidgetLine> =
        rows.filter { it.cap != null }
            .map { BudgetWidgetLine(it.category.name, it.category.icon, it.spent.toDouble(), it.cap!!.toDouble()) }
            .sortedByDescending { it.fraction }
            .take(max)

    /** "−" or "+" in front of a change, nothing for a flat one (sub-cent noise counts as flat). */
    fun changeSign(change: Double): Int = if (change > 0.005) 1 else if (change < -0.005) -1 else 0
}
