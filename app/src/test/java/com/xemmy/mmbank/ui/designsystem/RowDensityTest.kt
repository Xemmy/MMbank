package com.xemmy.mmbank.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowDensityTest {
    @Test
    fun `heights are 56 64 and 72`() {
        assertEquals(listOf(56, 64, 72), RowDensity.entries.map { it.minHeightDp })
    }

    @Test
    fun `density grows with content`() {
        assertEquals(RowDensity.COMPACT, rowDensityFor(hasSubtitle = false, hasLeading = false))
        assertEquals(RowDensity.COMFORTABLE, rowDensityFor(hasSubtitle = true, hasLeading = false))
        assertEquals(RowDensity.COMFORTABLE, rowDensityFor(hasSubtitle = false, hasLeading = true))
        assertEquals(RowDensity.SPACIOUS, rowDensityFor(hasSubtitle = true, hasLeading = true))
    }

    @Test
    fun `every density meets the 48dp touch target`() {
        assertTrue(RowDensity.entries.all { it.minHeightDp >= 48 })
    }
}
