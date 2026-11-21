package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodMappingTest {
    private val today = LocalDate.of(2026, 3, 18)

    @Test
    fun `current month maps to ThisMonth and all time to AllTime`() {
        assertEquals(DashboardDateRange.ThisMonth, PeriodWindow.of(PeriodUnit.MONTH, today).toDashboardRange(today))
        assertEquals(DashboardDateRange.AllTime, PeriodWindow.of(PeriodUnit.ALL, today).toDashboardRange(today))
    }

    @Test
    fun `other windows map to a concrete custom span`() {
        assertEquals(
            DashboardDateRange.Custom(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            PeriodWindow.of(PeriodUnit.MONTH, today).previous().toDashboardRange(today),
        )
        assertEquals(
            DashboardDateRange.Custom(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 22)),
            PeriodWindow.of(PeriodUnit.WEEK, today).toDashboardRange(today),
        )
        assertEquals(
            DashboardDateRange.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            PeriodWindow.of(PeriodUnit.YEAR, today).toDashboardRange(today),
        )
        assertEquals(
            DashboardDateRange.Custom(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)),
            PeriodWindow.lastMonths(3, today).toDashboardRange(today),
        )
    }
}
