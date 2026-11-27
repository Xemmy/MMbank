package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The one place "where does every capped category stand this month?" is
 * worked out: month-to-date spend converted into the base currency, rolled up
 * into parents, with last months' leftovers added when envelope budgets are
 * on. The Budgets screen and the Plan card both read it, so they can never
 * disagree about the same number. Pure and JVM-testable.
 */
object BudgetStatusBuilder {

    data class Built(
        /** Capped categories, closest to (or furthest past) their cap first. */
        val statuses: List<CategoryBudgetCalculator.CategoryBudgetStatus>,
        /** Spend in currencies with no rate -- left out of every cap; see [CategoryBudgetCalculator.MonthSpend]. */
        val uncountedByCurrency: Map<String, BigDecimal>,
    )

    fun build(
        categories: List<Category>,
        transactions: List<Transaction>,
        baseCurrency: String,
        convert: (String, BigDecimal) -> BigDecimal?,
        today: LocalDate,
        zone: ZoneId,
        rollover: Boolean,
    ): Built {
        val monthStart = today.withDayOfMonth(1)
        val startMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val monthSpend = CategoryBudgetCalculator.monthSpend(transactions, baseCurrency, startMillis, endMillis, convert)
        val rolledUp = CategoryBudgetCalculator.rollUpSpend(categories, monthSpend.byCategory)
        val carry = if (rollover) {
            BudgetRollover.carryByCategory(categories, transactions, baseCurrency, convert, YearMonth.from(monthStart), zone)
        } else {
            emptyMap()
        }
        return Built(CategoryBudgetCalculator.statuses(categories, rolledUp, carry), monthSpend.uncountedByCurrency)
    }
}
