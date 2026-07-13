package com.xemmy.mmbank.ui.components

import com.xemmy.mmbank.ui.screens.dashboard.CategorySlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DonutChartTest {

    @Test
    fun `sweep angles are proportional to amount and sum to 360`() {
        val slices = listOf(
            CategorySlice("Groceries", 600.0, 0xFF4ECDC4L),
            CategorySlice("Dining", 300.0, 0xFFE07A5FL),
            CategorySlice("Other", 100.0, 0xFF6C757DL),
        )
        val angles = computeSliceAngles(slices)

        assertEquals(216f, angles[0].sweepAngle, 1e-3f)
        assertEquals(108f, angles[1].sweepAngle, 1e-3f)
        assertEquals(36f, angles[2].sweepAngle, 1e-3f)
        assertEquals(360f, angles.sumOf { it.sweepAngle.toDouble() }.toFloat(), 1e-3f)
    }

    @Test
    fun `angles start at -90 degrees and accumulate in registration order`() {
        val slices = listOf(
            CategorySlice("A", 100.0, 0xFF4ECDC4L),
            CategorySlice("B", 100.0, 0xFFE07A5FL),
        )
        val angles = computeSliceAngles(slices)

        assertEquals(-90f, angles[0].startAngle, 1e-3f)
        assertEquals(90f, angles[1].startAngle, 1e-3f)
    }

    @Test
    fun `empty slice list produces no angles`() {
        assertTrue(computeSliceAngles(emptyList()).isEmpty())
    }

    @Test
    fun `zero total amount produces no angles`() {
        val slices = listOf(CategorySlice("A", 0.0, 0xFF4ECDC4L), CategorySlice("B", 0.0, 0xFFE07A5FL))
        assertTrue(computeSliceAngles(slices).isEmpty())
    }
}
