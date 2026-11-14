package com.xemmy.mmbank.ui.screens.plan

import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.intelligence.BillReminders
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Subscription
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The one-line live status under each Plan card ("2 near their cap", "1 due
 * this week") so the tab says what needs attention without opening every tool
 * (Phase 3). Pure: each function returns null when there is nothing to say,
 * and the screen simply shows no line. Money formatting is passed in, so this
 * stays free of the UI layer's formatters.
 */
object PlanStatus {

    private const val DUE_SOON_DAYS = 7L

    fun budgets(statuses: List<CategoryBudgetCalculator.CategoryBudgetStatus>): String? {
        if (statuses.isEmpty()) return null
        val over = statuses.count { it.state == CategoryBudgetCalculator.BudgetState.OVER }
        val near = statuses.count { it.state == CategoryBudgetCalculator.BudgetState.NEAR }
        return when {
            over > 0 && near > 0 -> "$over over budget · $near near their cap"
            over > 0 -> "$over over budget"
            near > 0 -> "$near near their cap"
            else -> "All ${statuses.size} on track"
        }
    }

    fun bills(bills: List<Bill>, nowMillis: Long): String? {
        if (bills.isEmpty()) return null
        val days = bills.map { it to BillReminders.daysUntil(it, nowMillis) }
        val overdue = days.count { it.second < 0 }
        val soon = days.count { it.second in 0..DUE_SOON_DAYS }
        return when {
            overdue > 0 && soon > 0 -> "$overdue overdue · $soon due this week"
            overdue > 0 -> "$overdue overdue"
            soon > 0 -> "$soon due this week"
            else -> {
                // Nothing is overdue or within a week, so the soonest one is at least 8 days away.
                val (next, d) = days.minBy { it.second }
                "Next: ${next.name} in $d days"
            }
        }
    }

    /** "5 active · AED 140.00/month" when they share one currency, else just the count. */
    fun subscriptions(subscriptions: List<Subscription>, money: (BigDecimal, String) -> String): String? {
        val active = subscriptions.filter { !it.dismissed }
        if (active.isEmpty()) return null
        val perMonth = active.groupBy { it.currencyCode.uppercase() }.mapValues { (_, subs) ->
            subs.fold(BigDecimal.ZERO) { acc, s ->
                acc + s.averageAmount.multiply(BigDecimal(30)).divide(BigDecimal(s.cadence.nominalDays), 2, RoundingMode.HALF_UP)
            }
        }
        val count = "${active.size} active"
        return perMonth.entries.singleOrNull()?.let { (currency, total) -> "$count · ${money(total, currency)}/month" } ?: count
    }

    fun goals(goals: List<SavingsGoal>): String? {
        val active = goals.filter { !it.archived }
        if (active.isEmpty()) return null
        val unfinished = active.filter { it.progressFraction < 1.0 }
        if (unfinished.isEmpty()) return "All ${active.size} reached"
        val closest = unfinished.maxBy { it.progressFraction }
        // Exact and rounded down: 99.6% must not read as a finished 100%.
        val pct = closest.currentAmount.multiply(BigDecimal(100)).divide(closest.targetAmount, 0, RoundingMode.DOWN).toInt()
        val noun = if (active.size == 1) "goal" else "goals"
        return "${active.size} $noun · closest: ${closest.title} $pct%"
    }

    fun forEntries(
        budgets: List<CategoryBudgetCalculator.CategoryBudgetStatus>,
        bills: List<Bill>,
        subscriptions: List<Subscription>,
        goals: List<SavingsGoal>,
        nowMillis: Long,
        money: (BigDecimal, String) -> String,
    ): Map<PlanEntry, String> = buildMap {
        budgets(budgets)?.let { put(PlanEntry.BUDGETS, it) }
        bills(bills, nowMillis)?.let { put(PlanEntry.BILLS, it) }
        subscriptions(subscriptions, money)?.let { put(PlanEntry.SUBSCRIPTIONS, it) }
        goals(goals)?.let { put(PlanEntry.GOALS, it) }
    }
}
