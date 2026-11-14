package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.filter.DateWindow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroComparisonTest {

    @Test
    fun `prior window is the same length, just before`() {
        val w = DateWindow(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7))
        assertEquals(DateWindow(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 31)), priorWindow(w))
        val day = DateWindow(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 7))
        assertEquals(DateWindow(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 6)), priorWindow(day))
    }

    @Test
    fun `caption`() {
        assertEquals("↑20% vs the 7 days before", heroComparisonCaption(120.0, 100.0, 7))
        assertEquals("↓50% vs the day before", heroComparisonCaption(50.0, 100.0, 1))
        assertEquals("≈ same as the 30 days before", heroComparisonCaption(100.2, 100.0, 30))
        assertNull(heroComparisonCaption(100.0, null, 7))
        assertNull(heroComparisonCaption(100.0, 0.0, 7))
        assertNull(heroComparisonCaption(100.0, 50.0, null))
    }
}
