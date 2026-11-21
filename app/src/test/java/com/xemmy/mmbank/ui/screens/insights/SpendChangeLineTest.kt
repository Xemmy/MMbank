package com.xemmy.mmbank.ui.screens.insights

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.ui.designsystem.MoneyTone
import com.xemmy.mmbank.ui.screens.dashboard.previousPeriodLabel
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpendChangeLineTest {

    @Test
    fun `spending down is good, up is bad, flat is neutral`() {
        assertEquals("↓ 16% less spending than last week" to MoneyTone.IN, spendChangeLine(-16.2, "last week"))
        assertEquals("↑ 18% more spending than this point last month" to MoneyTone.OUT, spendChangeLine(18.2, "this point last month"))
        assertEquals("≈ About the same spending as last month" to MoneyTone.NEUTRAL, spendChangeLine(0.3, "last month"))
    }

    @Test
    fun `nothing to compare gives no line`() {
        assertNull(spendChangeLine(null, "last week"))
        assertNull(spendChangeLine(10.0, null))
    }

    @Test
    fun `previous period label follows the window shape`() {
        fun w(a: String, b: String) = DateWindow(LocalDate.parse(a), LocalDate.parse(b))
        assertNull(previousPeriodLabel(null, false, false))
        assertEquals("this point last month", previousPeriodLabel(w("2026-11-01", "2026-11-30"), true, true))
        assertEquals("last week", previousPeriodLabel(w("2026-11-09", "2026-11-15"), true, false))
        assertEquals("last month", previousPeriodLabel(w("2026-10-01", "2026-10-31"), true, false))
        assertEquals("last year", previousPeriodLabel(w("2025-01-01", "2025-12-31"), true, false))
        assertEquals("the previous period", previousPeriodLabel(w("2026-08-01", "2026-10-31"), true, false))
        assertEquals("the previous period", previousPeriodLabel(null, true, false))
    }
}
