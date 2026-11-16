package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.filter.PeriodUnit
import com.xemmy.mmbank.domain.filter.PeriodWindow
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeriodBreakdownTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 3, 18)
    private val food = Category(id = 1, name = "Food", icon = "x", colorHex = "#FF0000")
    private val dining = Category(id = 2, name = "Dining", icon = "x", colorHex = "#00FF00", parentCategoryId = 1)
    private val fuel = Category(id = 3, name = "Fuel", icon = "x", colorHex = "#0000FF")
    private val cats = listOf(food, dining, fuel)

    private fun tx(
        day: LocalDate, amount: String, kind: TransactionKind = TransactionKind.SPEND,
        categoryId: Long? = null, currency: String = "AED", exclude: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = if (kind == TransactionKind.INCOME || kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        source = TransactionSource.MANUAL,
        timestampMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        kind = kind,
        categoryId = categoryId,
        excludeFromStats = exclude,
    )

    private val month = PeriodWindow.of(PeriodUnit.MONTH, today)
    private fun conv(t: Transaction): Double? = if (t.currencyCode == "AED") t.amount.toDouble() else if (t.currencyCode == "USD") t.amount.toDouble() * 3.0 else null

    private fun build(txs: List<Transaction>, raw: Boolean = false) =
        PeriodBreakdownBuilder.build(txs, cats, month, today, zone, raw, ::conv)

    @Test
    fun `totals equal LedgerMath`() {
        val txs = listOf(
            tx(LocalDate.of(2026, 3, 2), "100", categoryId = 3),
            tx(LocalDate.of(2026, 3, 3), "40", categoryId = 1),
            tx(LocalDate.of(2026, 3, 4), "10", TransactionKind.REFUND, categoryId = 1),
            tx(LocalDate.of(2026, 3, 5), "500", TransactionKind.INCOME),
            tx(LocalDate.of(2026, 3, 6), "20", currency = "USD", categoryId = 3),
            tx(LocalDate.of(2026, 2, 28), "999"), // outside the window
        )
        val b = build(txs)
        val inMonth = txs.dropLast(1)
        assertEquals(LedgerMath.netSpend(inMonth, false, ::conv), b.spendTotal, 1e-9)
        assertEquals(LedgerMath.income(inMonth, false, ::conv), b.incomeTotal, 1e-9)
        assertEquals(190.0, b.spendTotal, 1e-9)
        assertEquals(310.0, b.net, 1e-9)
    }

    @Test
    fun `category shares sum to 100 and roll sub-categories into the parent`() {
        val b = build(
            listOf(
                tx(LocalDate.of(2026, 3, 2), "60", categoryId = 1),
                tx(LocalDate.of(2026, 3, 3), "40", categoryId = 2),
                tx(LocalDate.of(2026, 3, 4), "100", categoryId = 3),
            ),
        )
        assertEquals(2, b.spending.size)
        assertEquals(100.0, b.spending.sumOf { it.share }, 1e-9)
        val foodShare = b.spending.first { it.category.id == 1L }
        assertEquals(100.0, foodShare.amount, 1e-9)
        assertEquals(2, foodShare.count)
        assertEquals(setOf(1L, 2L), foodShare.categoryIds)
    }

    @Test
    fun `excluded and internal kinds stay out and are counted separately`() {
        val b = build(
            listOf(
                tx(LocalDate.of(2026, 3, 2), "50"),
                tx(LocalDate.of(2026, 3, 3), "900", TransactionKind.TRANSFER),
                tx(LocalDate.of(2026, 3, 3), "800", TransactionKind.CARD_PAYMENT),
                tx(LocalDate.of(2026, 3, 4), "70", TransactionKind.ADJUSTMENT),
                tx(LocalDate.of(2026, 3, 5), "5", exclude = true),
            ),
        )
        assertEquals(50.0, b.spendTotal, 1e-9)
        assertEquals(1, b.notCounted.transfers)
        assertEquals(1, b.notCounted.cardPayments)
        assertEquals(1, b.notCounted.adjustments)
    }

    @Test
    fun `a category refunded to zero drops out and unconvertible rows are skipped`() {
        val b = build(
            listOf(
                tx(LocalDate.of(2026, 3, 2), "30", categoryId = 3),
                tx(LocalDate.of(2026, 3, 3), "30", TransactionKind.REFUND, categoryId = 3),
                tx(LocalDate.of(2026, 3, 4), "99", currency = "XXX", categoryId = 1),
            ),
        )
        assertTrue(b.spending.isEmpty())
        assertEquals(0.0, b.spendTotal, 1e-9)
    }

    @Test
    fun `buckets carry per-day flow and add up to the total`() {
        val b = build(
            listOf(
                tx(LocalDate.of(2026, 3, 2), "10"),
                tx(LocalDate.of(2026, 3, 2), "5"),
                tx(LocalDate.of(2026, 3, 9), "25", TransactionKind.INCOME),
            ),
        )
        assertEquals(31, b.buckets.size)
        assertEquals(15.0, b.buckets[1].spend, 1e-9)
        assertEquals(b.spendTotal, b.buckets.sumOf { it.spend }, 1e-9)
        assertEquals(b.incomeTotal, b.buckets.sumOf { it.income }, 1e-9)
    }

    @Test
    fun `raw view counts every debit and credit`() {
        val b = build(listOf(tx(LocalDate.of(2026, 3, 2), "900", TransactionKind.TRANSFER)), raw = true)
        assertEquals(900.0, b.spendTotal, 1e-9)
        assertEquals(0, b.notCounted.total)
    }

    @Test
    fun `income is grouped by source category`() {
        val b = build(listOf(tx(LocalDate.of(2026, 3, 5), "500", TransactionKind.INCOME, categoryId = 3)))
        assertEquals(1, b.income.size)
        assertEquals(500.0, b.income.single().amount, 1e-9)
    }
}
