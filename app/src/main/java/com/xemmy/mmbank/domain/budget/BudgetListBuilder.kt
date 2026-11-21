package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** One line of the Budgets list: a top-level category with this month's spend and its cap, if set. */
data class BudgetListRow(
    val category: Category,
    /** This month so far, sub-categories included, base currency. */
    val spent: BigDecimal,
    /** Average of the three full months before this one, base currency; what "you spend about X a month" says. */
    val monthlyAverage: BigDecimal,
    /** The cap, or null while not set. */
    val cap: BigDecimal?,
    val status: CategoryBudgetCalculator.CategoryBudgetStatus?,
)

/**
 * Builds the Budgets list. Spend follows the same rules as every cap ([CategoryBudgetCalculator]),
 * so a row here always agrees with the Insights budget card.
 */
object BudgetListBuilder {

    /** How many categories show before "All categories (N)". */
    const val TOP_COUNT = 8

    fun build(
        categories: List<Category>,
        transactions: List<Transaction>,
        baseCurrency: String,
        convert: (String, BigDecimal) -> BigDecimal?,
        today: LocalDate,
        zone: ZoneId,
        rollover: Boolean,
    ): List<BudgetListRow> {
        val built = BudgetStatusBuilder.build(categories, transactions, baseCurrency, convert, today, zone, rollover)
        val statusById = built.statuses.associateBy { it.category.id }

        val thisMonthStart = today.withDayOfMonth(1)
        fun spendOf(monthStart: LocalDate): Map<Long, BigDecimal> {
            val start = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val raw = CategoryBudgetCalculator.monthSpend(transactions, baseCurrency, start, end, convert).byCategory
            return CategoryBudgetCalculator.rollUpSpend(categories, raw)
        }

        val current = spendOf(thisMonthStart)
        val previous = (1..3).map { spendOf(thisMonthStart.minusMonths(it.toLong())) }

        return categories
            .filter { it.parentCategoryId == null && it.flow == com.xemmy.mmbank.domain.model.CategoryFlow.EXPENSE }
            .map { c ->
                val avg = previous.fold(BigDecimal.ZERO) { acc, m -> acc + (m[c.id] ?: BigDecimal.ZERO) }
                    .divide(BigDecimal(3), 2, RoundingMode.HALF_EVEN)
                BudgetListRow(
                    category = c,
                    spent = current[c.id] ?: BigDecimal.ZERO,
                    monthlyAverage = avg,
                    cap = c.monthlyCap?.takeIf { it.signum() > 0 },
                    status = statusById[c.id],
                )
            }
            // Anything with a cap, spend or history is worth a row; empty categories are not.
            .filter { it.cap != null || it.spent.signum() > 0 || it.monthlyAverage.signum() > 0 }
            .sortedWith(compareByDescending<BudgetListRow> { it.spent }.thenBy { it.category.name.lowercase() })
    }

    /** "You spend about 540 a month" figure to suggest, rounded to a tidy number; null with no history. */
    fun suggestedCap(row: BudgetListRow): BigDecimal? {
        val basis = if (row.monthlyAverage.signum() > 0) row.monthlyAverage else row.spent
        if (basis.signum() <= 0) return null
        val step = when {
            basis >= BigDecimal(1000) -> BigDecimal(100)
            basis >= BigDecimal(100) -> BigDecimal(50)
            else -> BigDecimal(10)
        }
        val rounded = basis.divide(step, 0, RoundingMode.CEILING).multiply(step)
        return rounded.setScale(0)
    }
}
