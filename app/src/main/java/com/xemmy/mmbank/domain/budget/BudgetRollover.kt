package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId

/**
 * Envelope budgets (backlog B.5 #4): money a category did not spend last month
 * stays in its envelope and raises this month's cap, instead of resetting.
 *
 * For each earlier month, `available = cap + carry`, `left = max(0, available
 * - spent)`, and `left` becomes the next month's carry. Overspending never
 * creates debt: a blown month simply carries nothing, so one bad month can't
 * leave a category permanently capped at zero.
 *
 * A category starts accumulating in the first month it has spend (its own, or
 * a sub-category's, since a parent's budget covers its children) -- otherwise a
 * cap set today on a category with no history would "carry" months of money
 * that was never allotted. At most [MAX_MONTHS] earlier months are read.
 * Pure and JVM-testable; spend is converted with the caller's converter, as in
 * [CategoryBudgetCalculator.monthSpend].
 */
object BudgetRollover {

    const val MAX_MONTHS = 6

    /** Carry into [thisMonth], per capped category that has any; absent means nothing carried. */
    fun carryByCategory(
        categories: List<Category>,
        transactions: List<Transaction>,
        baseCurrency: String,
        convert: (String, BigDecimal) -> BigDecimal?,
        thisMonth: YearMonth,
        zone: ZoneId,
        maxMonths: Int = MAX_MONTHS,
    ): Map<Long, BigDecimal> {
        val capped = categories.filter { (it.monthlyCap?.signum() ?: 0) > 0 }
        if (capped.isEmpty()) return emptyMap()

        val firstSpend = transactions
            .filter { it.categoryId != null && LedgerMath.affectsSpend(it) }
            .minOfOrNull { it.timestampMillis }
            ?.let { YearMonth.from(java.time.Instant.ofEpochMilli(it).atZone(zone)) }
            ?: return emptyMap()
        val start = maxOf(firstSpend, thisMonth.minusMonths(maxMonths.toLong()))
        if (!start.isBefore(thisMonth)) return emptyMap()

        val carry = HashMap<Long, BigDecimal>()
        val started = HashSet<Long>()
        var month = start
        while (month.isBefore(thisMonth)) {
            val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val spent = CategoryBudgetCalculator.rollUpSpend(
                categories,
                CategoryBudgetCalculator.monthSpend(transactions, baseCurrency, from, to, convert).byCategory,
            )
            for (category in capped) {
                val monthSpent = spent[category.id] ?: BigDecimal.ZERO
                if (category.id !in started) {
                    if (monthSpent.signum() <= 0) continue
                    started += category.id
                }
                val available = category.monthlyCap!! + (carry[category.id] ?: BigDecimal.ZERO)
                carry[category.id] = (available - monthSpent).max(BigDecimal.ZERO)
            }
            month = month.plusMonths(1)
        }
        return carry.filterValues { it.signum() > 0 }
    }
}
