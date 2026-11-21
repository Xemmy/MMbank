package com.xemmy.mmbank.domain.split

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebtSimplifierTest {

    @Test
    fun `balances that are already all zero need no settlements`() {
        val result = DebtSimplifier.simplify(mapOf("a" to BigDecimal.ZERO, "b" to BigDecimal.ZERO))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a simple two-person debt settles in one payment`() {
        val result = DebtSimplifier.simplify(mapOf("a" to BigDecimal("20.00"), "b" to BigDecimal("-20.00")))
        assertEquals(1, result.size)
        assertEquals(DebtSimplifier.Settlement("b", "a", BigDecimal("20.00")), result.single())
    }

    @Test
    fun `three people settle in two payments, not three`() {
        // a is owed 30, b is owed 10, c owes 40 -- the naive pairwise approach
        // (c pays a, c pays b) happens to match the simplified one here, but
        // the point is it never needs more than participants-1 payments.
        val result = DebtSimplifier.simplify(
            mapOf("a" to BigDecimal("30.00"), "b" to BigDecimal("10.00"), "c" to BigDecimal("-40.00")),
        )
        assertEquals(2, result.size)
        val totalPaid = result.fold(BigDecimal.ZERO) { acc, s -> acc + s.amount }
        assertEquals(0, totalPaid.compareTo(BigDecimal("40.00")))
    }

    @Test
    fun `a chain of debts collapses below the naive pairwise count`() {
        // a owes b 10, b owes c 10 -- naive settling is two payments, but the
        // net effect is just a owing c 10 directly, one payment.
        val result = DebtSimplifier.simplify(
            mapOf("a" to BigDecimal("-10.00"), "b" to BigDecimal.ZERO, "c" to BigDecimal("10.00")),
        )
        assertEquals(1, result.size)
        assertEquals(DebtSimplifier.Settlement("a", "c", BigDecimal("10.00")), result.single())
    }

    @Test
    fun `a balance within a cent of zero is treated as already settled`() {
        val result = DebtSimplifier.simplify(mapOf("a" to BigDecimal("0.01"), "b" to BigDecimal("-0.01")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `every settlement's total exactly accounts for every debtor's debt`() {
        val balances = mapOf(
            "a" to BigDecimal("15.50"),
            "b" to BigDecimal("22.25"),
            "c" to BigDecimal("-9.00"),
            "d" to BigDecimal("-28.75"),
        )
        val result = DebtSimplifier.simplify(balances)
        val paidBy = result.groupBy { it.from }.mapValues { (_, s) -> s.fold(BigDecimal.ZERO) { acc, x -> acc + x.amount } }
        assertEquals(0, paidBy.getValue("c").compareTo(BigDecimal("9.00")))
        assertEquals(0, paidBy.getValue("d").compareTo(BigDecimal("28.75")))
    }
}
