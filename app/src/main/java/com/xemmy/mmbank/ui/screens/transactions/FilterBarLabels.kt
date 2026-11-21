package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.model.TransactionDirection

/**
 * Pure label text for the single-row filter bar's Time and Type chips, kept
 * free of Compose so it's JVM-testable. [chipDateFormat] (TransactionsScreen.kt)
 * formats the custom-range endpoints.
 */
internal fun dateRangeChipLabel(range: DateRangeFilter, statementCycleDay: Int?): String = when (range) {
    DateRangeFilter.All -> "All time"
    DateRangeFilter.Today -> "Today"
    DateRangeFilter.Past7Days -> "Past 7 days"
    DateRangeFilter.Past30Days -> "Past 30 days"
    DateRangeFilter.YearToDate -> "Year-to-date"
    DateRangeFilter.StatementCycle ->
        if (statementCycleDay != null) "Statement (day $statementCycleDay)" else "Statement"
    is DateRangeFilter.Custom -> "${range.start.format(chipDateFormat)} – ${range.end.format(chipDateFormat)}"
}

internal fun directionChipLabel(direction: TransactionDirection?): String = when (direction) {
    null -> "All types"
    TransactionDirection.DEBIT -> "Spent"
    TransactionDirection.CREDIT -> "Received"
}
