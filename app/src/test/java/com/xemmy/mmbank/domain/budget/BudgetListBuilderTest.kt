package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetListBuilderTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 3, 18)
    private val convert = CategoryBudgetCalculator.converter("AED", emptyMap())

    private val food = Category(id = 1, name = "Food", icon = "x", colorHex = "#111111", monthlyCap = BigDecimal("500"))
    private val dining = Category(id = 2, name = "Dining", icon = "x", colorHex = "#222222", parentCategoryId = 1)
    private val fuel = Category(id = 3, name = "Fuel", icon = "x", colorHex = "#333333")
    private val unused = Category(id = 4, name = "Unused", icon = "x", colorHex = "#444444")
    private val cats = listOf(food, dining, fuel, unused)

    private fun tx(day: LocalDate, amount: String, categoryId: Long, kind: TransactionKind = TransactionKind.SPEND) = Transaction(
        amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        source = TransactionSource.MANUAL, timestampMillis = day.atTime(12, 0).toInstant(zone).toEpochMilli(),
        kind = kind, categoryId = categoryId,
    )

    private fun rows(txs: List<Transaction>) = BudgetListBuilder.build(cats, txs, "AED", convert, today, zone, rollover = false)

    @Test
    fun `rows are top-level categories ordered by this month's spend, empty ones left out`() {
        val r = rows(listOf(tx(LocalDate.of(2026, 3, 2), "50", 3), tx(LocalDate.of(2026, 3, 3), "200", 1)))
        assertEquals(listOf("Food", "Fuel"), r.map { it.category.name })
        assertTrue(r.none { it.category.id == 4L })
        assertTrue(r.none { it.category.parentCategoryId != null })
    }

    @Test
    fun `sub-category spend rolls into its parent`() {
        val r = rows(listOf(tx(LocalDate.of(2026, 3, 2), "60", 1), tx(LocalDate.of(2026, 3, 3), "40", 2)))
        assertEquals(0, BigDecimal("100").compareTo(r.single().spent))
    }

    @Test
    fun `average is the three full previous months`() {
        val r = rows(
            listOf(
                tx(LocalDate.of(2026, 2, 10), "300", 3), tx(LocalDate.of(2026, 1, 10), "300", 3),
                tx(LocalDate.of(2025, 12, 10), "300", 3), tx(LocalDate.of(2025, 11, 10), "9000", 3), // too old
            ),
        )
        assertEquals(0, BigDecimal("300").compareTo(r.single { it.category.id == 3L }.monthlyAverage))
    }

    @Test
    fun `a capped category carries its cap and status, an uncapped one does not`() {
        val r = rows(listOf(tx(LocalDate.of(2026, 3, 2), "450", 1), tx(LocalDate.of(2026, 3, 2), "10", 3)))
        val f = r.single { it.category.id == 1L }
        assertEquals(0, BigDecimal("500").compareTo(f.cap))
        assertEquals(CategoryBudgetCalculator.BudgetState.NEAR, f.status!!.state)
        val g = r.single { it.category.id == 3L }
        assertNull(g.cap)
        assertNull(g.status)
    }

    @Test
    fun `refunds net against the category and transfers are ignored`() {
        val r = rows(
            listOf(
                tx(LocalDate.of(2026, 3, 2), "100", 3), tx(LocalDate.of(2026, 3, 3), "30", 3, TransactionKind.REFUND),
                tx(LocalDate.of(2026, 3, 4), "999", 3, TransactionKind.TRANSFER),
            ),
        )
        assertEquals(0, BigDecimal("70").compareTo(r.single { it.category.id == 3L }.spent))
    }

    @Test
    fun `suggested cap rounds the average up to a tidy figure`() {
        fun row(avg: String, spent: String = "0") = BudgetListRow(fuel, BigDecimal(spent), BigDecimal(avg), null, null)
        assertEquals(BigDecimal("550"), BudgetListBuilder.suggestedCap(row("512.30")))
        assertEquals(BigDecimal("1300"), BudgetListBuilder.suggestedCap(row("1234")))
        assertEquals(BigDecimal("40"), BudgetListBuilder.suggestedCap(row("31")))
        assertEquals(BigDecimal("60"), BudgetListBuilder.suggestedCap(row("0", "55")))
        assertNull(BudgetListBuilder.suggestedCap(row("0")))
        assertNotNull(BudgetListBuilder.suggestedCap(row("5")))
    }
}
