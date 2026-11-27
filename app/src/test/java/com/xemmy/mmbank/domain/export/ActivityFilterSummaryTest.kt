package com.xemmy.mmbank.domain.export

import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFilterSummaryTest {

    private fun describe(f: ActivityFilters) = ActivityFilterSummary.describe(
        filters = f,
        accountNames = mapOf(1L to "Main"),
        categoryNames = mapOf(5L to "Dining", 2L to "Food"),
        labelNames = mapOf(3L to "Holiday"),
    )

    @Test fun noFiltersSaysEverything() {
        assertEquals(ActivityFilterSummary.NONE, describe(ActivityFilters()))
    }

    @Test fun combinesActiveFiltersInOrder() {
        val f = ActivityFilters(
            dateRange = DateRangeFilter.Past30Days,
            direction = TransactionDirection.DEBIT,
            account = AccountFilter.Specific(1),
            labelIds = setOf(3),
            query = " coffee ",
        )
        assertEquals("Past 30 days; Money out; Account: Main; Tag: Holiday; Search: \"coffee\"", describe(f))
    }

    @Test fun customRangeAndAmounts() {
        val f = ActivityFilters(
            dateRange = DateRangeFilter.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
            minAmount = BigDecimal("10"),
            maxAmount = BigDecimal("50.5"),
        )
        assertEquals("2026-10-01 to 2026-10-31; Amount 10 to 50.5", describe(f))
        assertEquals("Amount at least 10", describe(ActivityFilters(minAmount = BigDecimal("10"))))
        assertEquals("Amount at most 9", describe(ActivityFilters(maxAmount = BigDecimal("9"))))
    }

    @Test fun categoriesPeopleKindAndAnalytics() {
        val f = ActivityFilters(
            categoryIds = setOf(5, 2),
            person = PersonFilter.Specific("Sara"),
            kind = TransactionKind.REFUND,
            analyticsStatus = AnalyticsStatusFilter.EXCLUDED_ONLY,
            triageOnly = true,
        )
        assertEquals("Person: Sara; Type: Refund; Category: Food, Dining; Excluded from stats only; Needs review only", describe(f))
    }

    @Test fun unknownIdsFallBackToTheNumber() {
        assertEquals("Account: #9", describe(ActivityFilters(account = AccountFilter.Specific(9))))
        assertEquals("Account: unassigned", describe(ActivityFilters(account = AccountFilter.Unassigned)))
    }
}
