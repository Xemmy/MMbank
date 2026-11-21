package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.BudgetState
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetStatusBuilderTest {

    private val utc = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 12)
    private val convert = CategoryBudgetCalculator.converter("AED", mapOf("USD" to BigDecimal("0.25")))

    private val groceries = Category(id = 1, name = "Groceries", icon = "", colorHex = "#000000", monthlyCap = BigDecimal("500"))

    private fun spend(date: String, amount: String, currency: String = "AED") = Transaction(
        amount = BigDecimal(amount), currencyCode = currency, direction = TransactionDirection.DEBIT,
        merchant = "Shop", source = TransactionSource.SMS, categoryId = 1,
        timestampMillis = LocalDate.parse(date).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
    )

    private fun build(txs: List<Transaction>, rollover: Boolean = false) =
        BudgetStatusBuilder.build(listOf(groceries), txs, "AED", convert, today, utc, rollover)

    @Test
    fun `this month's spend decides the state`() {
        val status = build(listOf(spend("2026-11-03", "450"))).statuses.single()
        assertEquals(BudgetState.NEAR, status.state)
        assertEquals(0, status.spent.compareTo(BigDecimal("450")))
    }

    @Test
    fun `last month's spend is not counted`() {
        assertEquals(0, build(listOf(spend("2026-10-30", "999"))).statuses.single().spent.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `foreign spend is converted, and spend with no rate is reported`() {
        val built = build(listOf(spend("2026-11-02", "25", "USD"), spend("2026-11-03", "40", "EUR")))
        assertEquals(0, built.statuses.single().spent.compareTo(BigDecimal("100")))
        assertEquals(setOf("EUR"), built.uncountedByCurrency.keys)
    }

    @Test
    fun `rollover adds last month's leftover to the cap only when asked`() {
        val txs = listOf(spend("2026-10-10", "200"), spend("2026-11-03", "100"))
        assertEquals(0, build(txs, rollover = false).statuses.single().cap.compareTo(BigDecimal("500")))
        val rolled = build(txs, rollover = true).statuses.single()
        assertEquals(0, rolled.cap.compareTo(BigDecimal("800")))
        assertEquals(0, rolled.carriedOver.compareTo(BigDecimal("300")))
    }

    @Test
    fun `categories without a cap produce no status`() {
        val built = BudgetStatusBuilder.build(listOf(groceries.copy(monthlyCap = null)), emptyList(), "AED", convert, today, utc, false)
        assertTrue(built.statuses.isEmpty())
    }
}
