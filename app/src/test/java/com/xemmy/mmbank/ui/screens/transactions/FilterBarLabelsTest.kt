package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FilterBarLabelsTest {

    @Test
    fun `date range chip label - each preset gets a fixed label`() {
        assertEquals("All time", dateRangeChipLabel(DateRangeFilter.All, statementCycleDay = null))
        assertEquals("Today", dateRangeChipLabel(DateRangeFilter.Today, statementCycleDay = null))
        assertEquals("Past 7 days", dateRangeChipLabel(DateRangeFilter.Past7Days, statementCycleDay = null))
        assertEquals("Past 30 days", dateRangeChipLabel(DateRangeFilter.Past30Days, statementCycleDay = null))
        assertEquals("Year-to-date", dateRangeChipLabel(DateRangeFilter.YearToDate, statementCycleDay = null))
    }

    @Test
    fun `date range chip label - statement cycle shows the day when known, else a bare label`() {
        assertEquals(
            "Statement (day 10)",
            dateRangeChipLabel(DateRangeFilter.StatementCycle, statementCycleDay = 10),
        )
        assertEquals("Statement", dateRangeChipLabel(DateRangeFilter.StatementCycle, statementCycleDay = null))
    }

    @Test
    fun `date range chip label - custom range formats both endpoints`() {
        val range = DateRangeFilter.Custom(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 21))
        assertEquals("1 Jul – 21 Jul", dateRangeChipLabel(range, statementCycleDay = null))
    }

    @Test
    fun `direction chip label - null is All types, else Spent or Received`() {
        assertEquals("All types", directionChipLabel(null))
        assertEquals("Spent", directionChipLabel(TransactionDirection.DEBIT))
        assertEquals("Received", directionChipLabel(TransactionDirection.CREDIT))
    }
}
