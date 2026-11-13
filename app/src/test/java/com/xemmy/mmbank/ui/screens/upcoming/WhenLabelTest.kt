package com.xemmy.mmbank.ui.screens.upcoming

import com.xemmy.mmbank.domain.intelligence.UpcomingPayments
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WhenLabelTest {
    private fun item(days: Long) = UpcomingPayments.Item(
        UpcomingPayments.Kind.BILL, "Rent", null, "AED", LocalDate.of(2026, 11, 12).plusDays(days), days,
    )

    @Test fun `relative wording reads naturally in both directions`() {
        assertEquals("today (2026-11-12)", whenLabel(item(0)))
        assertEquals("tomorrow (2026-11-13)", whenLabel(item(1)))
        assertEquals("in 5 days (2026-11-17)", whenLabel(item(5)))
        assertEquals("1 day overdue (2026-11-11)", whenLabel(item(-1)))
        assertEquals("4 days overdue (2026-11-08)", whenLabel(item(-4)))
    }
}
