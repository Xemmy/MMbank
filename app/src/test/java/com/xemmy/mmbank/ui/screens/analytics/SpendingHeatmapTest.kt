package com.xemmy.mmbank.ui.screens.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class SpendingHeatmapTest {

    @Test
    fun `zero spend is always bucket zero`() {
        assertEquals(0, spendPercentileBucket(0.0, listOf(10.0, 20.0, 30.0)))
    }

    @Test
    fun `negative spend is treated as bucket zero`() {
        assertEquals(0, spendPercentileBucket(-5.0, listOf(10.0, 20.0)))
    }

    @Test
    fun `empty context with a positive amount is bucket zero`() {
        assertEquals(0, spendPercentileBucket(15.0, emptyList()))
    }

    @Test
    fun `all-zero context is bucket zero regardless of query`() {
        assertEquals(0, spendPercentileBucket(15.0, listOf(0.0, 0.0, 0.0)))
    }

    @Test
    fun `quartiles map to buckets 1 through 4`() {
        val amounts = listOf(10.0, 20.0, 30.0, 40.0)
        assertEquals(1, spendPercentileBucket(10.0, amounts))
        assertEquals(2, spendPercentileBucket(20.0, amounts))
        assertEquals(3, spendPercentileBucket(30.0, amounts))
        assertEquals(4, spendPercentileBucket(40.0, amounts))
    }

    @Test
    fun `zero-spend days in the context are excluded from the percentile calculation`() {
        val amounts = listOf(0.0, 0.0, 10.0, 20.0)
        // Only [10, 20] are ranked; 20 is the maximum, so it's bucket 4.
        assertEquals(4, spendPercentileBucket(20.0, amounts))
    }

    @Test
    fun `the lowest non-zero day still gets a visible bucket, not zero`() {
        val amounts = listOf(5.0, 50.0, 500.0)
        assertEquals(1, spendPercentileBucket(5.0, amounts))
    }

    @Test
    fun `tied amounts all rank at the top bucket`() {
        val amounts = listOf(10.0, 10.0, 10.0)
        assertEquals(4, spendPercentileBucket(10.0, amounts))
    }

    @Test
    fun `a value not present in the context is still ranked among it`() {
        val amounts = listOf(10.0, 20.0, 30.0, 40.0)
        // 25 ranks above 10 and 20 (2 of 4 <= 25) -> rank 0.5 -> bucket 2.
        assertEquals(2, spendPercentileBucket(25.0, amounts))
    }
}
