package com.xemmy.mmbank.domain.split

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitAllocatorTest {

    // -- splitEqually ------------------------------------------------------

    @Test
    fun `an evenly divisible amount splits without any remainder`() {
        val result = SplitAllocator.splitEqually(BigDecimal("90.00"), listOf("a", "b", "c"))
        assertEquals(BigDecimal("30.00"), result.getValue("a"))
        assertEquals(BigDecimal("30.00"), result.getValue("b"))
        assertEquals(BigDecimal("30.00"), result.getValue("c"))
    }

    @Test
    fun `a remainder cent goes to the first participants in order, never dropped`() {
        val result = SplitAllocator.splitEqually(BigDecimal("10.00"), listOf("a", "b", "c"))
        // 10.00 / 3 = 3.33 with 0.01 left over.
        assertEquals(BigDecimal("3.34"), result.getValue("a"))
        assertEquals(BigDecimal("3.33"), result.getValue("b"))
        assertEquals(BigDecimal("3.33"), result.getValue("c"))
        val sum = result.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        assertEquals(0, sum.compareTo(BigDecimal("10.00")))
    }

    @Test
    fun `a single participant gets the whole amount`() {
        val result = SplitAllocator.splitEqually(BigDecimal("42.50"), listOf("a"))
        assertEquals(BigDecimal("42.50"), result.getValue("a"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `splitEqually rejects an empty participant list`() {
        SplitAllocator.splitEqually(BigDecimal("10.00"), emptyList<String>())
    }

    // -- splitByExactAmounts -------------------------------------------------

    @Test
    fun `exact amounts that sum to the total are accepted unchanged`() {
        val amounts = mapOf("a" to BigDecimal("20.00"), "b" to BigDecimal("30.00"))
        assertEquals(amounts, SplitAllocator.splitByExactAmounts(BigDecimal("50.00"), amounts))
    }

    @Test
    fun `exact amounts that don't sum to the total are rejected`() {
        val amounts = mapOf("a" to BigDecimal("20.00"), "b" to BigDecimal("25.00"))
        assertNull(SplitAllocator.splitByExactAmounts(BigDecimal("50.00"), amounts))
    }

    @Test
    fun `empty exact amounts are rejected`() {
        assertNull(SplitAllocator.splitByExactAmounts(BigDecimal("50.00"), emptyMap()))
    }

    // -- splitByPercentages --------------------------------------------------

    @Test
    fun `percentages summing to 100 split proportionally`() {
        val result = SplitAllocator.splitByPercentages(
            BigDecimal("100.00"),
            mapOf("a" to 25.0, "b" to 75.0),
        )!!
        assertEquals(BigDecimal("25.00"), result.getValue("a"))
        assertEquals(BigDecimal("75.00"), result.getValue("b"))
    }

    @Test
    fun `the last share absorbs rounding so the total always matches exactly`() {
        val result = SplitAllocator.splitByPercentages(
            BigDecimal("10.00"),
            mapOf("a" to 33.33, "b" to 33.33, "c" to 33.34),
        )!!
        val sum = result.values.fold(BigDecimal.ZERO) { acc, v -> acc + v }
        assertEquals(0, sum.compareTo(BigDecimal("10.00")))
    }

    @Test
    fun `percentages that don't sum to 100 are rejected`() {
        assertNull(SplitAllocator.splitByPercentages(BigDecimal("100.00"), mapOf("a" to 40.0, "b" to 40.0)))
    }

    @Test
    fun `percentages within a hundredth of a percent of 100 are still accepted`() {
        assertTrue(SplitAllocator.splitByPercentages(BigDecimal("100.00"), mapOf("a" to 50.005, "b" to 49.995)) != null)
    }

    // -- splitByAdjustments ---------------------------------------------------

    @Test
    fun `adjustments that net to zero shift shares off the equal split`() {
        val result = SplitAllocator.splitByAdjustments(
            totalAmount = BigDecimal("30.00"),
            participantIds = listOf("a", "b"),
            adjustments = mapOf("a" to BigDecimal("5.00"), "b" to BigDecimal("-5.00")),
        )!!
        assertEquals(BigDecimal("20.00"), result.getValue("a"))
        assertEquals(BigDecimal("10.00"), result.getValue("b"))
    }

    @Test
    fun `a participant missing from adjustments gets no adjustment`() {
        val result = SplitAllocator.splitByAdjustments(
            totalAmount = BigDecimal("30.00"),
            participantIds = listOf("a", "b", "c"),
            adjustments = mapOf("a" to BigDecimal("6.00"), "b" to BigDecimal("-6.00")),
        )!!
        assertEquals(BigDecimal("10.00"), result.getValue("c"))
    }

    @Test
    fun `adjustments that don't net to zero are rejected`() {
        val result = SplitAllocator.splitByAdjustments(
            totalAmount = BigDecimal("30.00"),
            participantIds = listOf("a", "b"),
            adjustments = mapOf("a" to BigDecimal("5.00"), "b" to BigDecimal("-4.00")),
        )
        assertNull(result)
    }
}
