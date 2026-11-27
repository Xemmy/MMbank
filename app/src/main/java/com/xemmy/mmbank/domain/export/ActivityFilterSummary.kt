package com.xemmy.mmbank.domain.export

import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/** One line saying which Activity filters produced an export ("Past 30 days; Debits; Tag: Holiday"). */
object ActivityFilterSummary {

    const val NONE = "No filters (all transactions)"

    fun describe(
        filters: ActivityFilters,
        accountNames: Map<Long, String>,
        categoryNames: Map<Long, String>,
        labelNames: Map<Long, String>,
    ): String {
        val parts = buildList {
            when (val d = filters.dateRange) {
                DateRangeFilter.All -> Unit
                DateRangeFilter.Today -> add("Today")
                DateRangeFilter.Past7Days -> add("Past 7 days")
                DateRangeFilter.Past30Days -> add("Past 30 days")
                DateRangeFilter.YearToDate -> add("Year to date")
                DateRangeFilter.StatementCycle -> add("Statement cycle")
                is DateRangeFilter.Custom -> add("${d.start} to ${d.end}")
            }
            filters.direction?.let { add(if (it == TransactionDirection.DEBIT) "Money out" else "Money in") }
            when (val a = filters.account) {
                AccountFilter.Any -> Unit
                AccountFilter.Unassigned -> add("Account: unassigned")
                AccountFilter.HistoryOnly -> add("Account: history only")
                is AccountFilter.Specific -> add("Account: ${accountNames[a.id] ?: "#${a.id}"}")
            }
            when (val p = filters.person) {
                PersonFilter.Any -> Unit
                PersonFilter.Unassigned -> add("Person: unassigned")
                is PersonFilter.Specific -> add("Person: ${p.name}")
            }
            filters.kind?.let { add("Type: ${it.label}") }
            filters.categoryIds?.let { ids ->
                add("Category: " + ids.sorted().joinToString(", ") { categoryNames[it] ?: "#$it" })
            }
            filters.labelIds?.let { ids ->
                add("Tag: " + ids.sorted().joinToString(", ") { labelNames[it] ?: "#$it" })
            }
            amountRange(filters.minAmount, filters.maxAmount)?.let { add(it) }
            filters.source?.let { add("Source: ${it.name}") }
            filters.analyticsStatus?.let {
                add(if (it == AnalyticsStatusFilter.INCLUDED_ONLY) "Counted in stats only" else "Excluded from stats only")
            }
            if (filters.triageOnly) add("Needs review only")
            if (filters.query.isNotBlank()) add("Search: \"${filters.query.trim()}\"")
        }
        return if (parts.isEmpty()) NONE else parts.joinToString("; ")
    }

    private fun amountRange(min: BigDecimal?, max: BigDecimal?): String? = when {
        min != null && max != null -> "Amount ${min.toPlainString()} to ${max.toPlainString()}"
        min != null -> "Amount at least ${min.toPlainString()}"
        max != null -> "Amount at most ${max.toPlainString()}"
        else -> null
    }
}
