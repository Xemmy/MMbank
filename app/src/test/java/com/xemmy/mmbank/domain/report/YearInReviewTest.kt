package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YearInReviewTest {

    private val utc = ZoneOffset.UTC
    private val inAed: (Transaction) -> Double? = { if (it.currencyCode == "AED") it.amount.toDouble() else null }

    private fun spend(date: String, amount: String, merchant: String? = "Shop", categoryId: Long? = null) = tx(date, amount, merchant, TransactionKind.SPEND, categoryId)

    private fun tx(
        date: String, amount: String, merchant: String? = "Shop", kind: TransactionKind = TransactionKind.SPEND,
        categoryId: Long? = null, currency: String = "AED",
    ) = Transaction(
        amount = BigDecimal(amount), currencyCode = currency,
        direction = if (kind.fixedDirection == TransactionDirection.CREDIT) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS,
        timestampMillis = LocalDate.parse(date).atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
        kind = kind, categoryId = categoryId,
    )

    private fun build(
        txs: List<Transaction>, year: Int = 2025, today: String = "2026-03-01", names: Map<Long, String> = emptyMap(),
    ) = YearInReviewBuilder.build(year, txs, names, "AED", inAed, LocalDate.parse(today), utc)

    @Test
    fun `a year with nothing to review is null`() {
        assertNull(build(emptyList()))
        assertNull(build(listOf(spend("2024-05-01", "10"))))
    }

    @Test
    fun `totals follow the Spent and Received rules`() {
        val r = build(
            listOf(
                spend("2025-01-10", "300"),
                tx("2025-02-10", "50", kind = TransactionKind.REFUND),
                tx("2025-03-01", "5000", "Employer", TransactionKind.INCOME),
                tx("2025-03-02", "700", "Savings", TransactionKind.TRANSFER),
            ),
        )!!
        assertEquals(250.0, r.spent, 1e-9)
        assertEquals(5000.0, r.received, 1e-9)
        assertEquals(4750.0, r.net, 1e-9)
        assertEquals(0.95, r.savingsRate!!, 1e-9)
        assertEquals(4, r.transactionCount)
    }

    @Test
    fun `no savings rate when nothing was received`() {
        assertNull(build(listOf(spend("2025-01-10", "100")))!!.savingsRate)
    }

    @Test
    fun `monthly spend, busiest and quietest months`() {
        val r = build(listOf(spend("2025-01-05", "100"), spend("2025-03-05", "400"), spend("2025-03-20", "100"), spend("2025-07-01", "50")))!!
        assertEquals(12, r.monthlySpend.size)
        assertEquals(100.0, r.monthlySpend[0], 1e-9)
        assertEquals(500.0, r.monthlySpend[2], 1e-9)
        assertEquals(0.0, r.monthlySpend[1], 1e-9)
        assertEquals(Month.MARCH, r.busiestMonth)
        assertEquals(Month.JULY, r.quietestMonth)
    }

    @Test
    fun `the biggest expense is the largest single purchase`() {
        val r = build(listOf(spend("2025-02-02", "80", "Noon"), spend("2025-06-15", "1200", "TV Store"), spend("2025-06-16", "300", "Lulu")))!!
        assertEquals("TV Store", r.biggestExpense!!.merchant)
        assertEquals(1200.0, r.biggestExpense!!.amount, 1e-9)
        assertEquals(LocalDate.of(2025, 6, 15), r.biggestExpense!!.date)
    }

    @Test
    fun `top merchants merge spellings of one brand, net refunds, and rank biggest first`() {
        val r = build(
            listOf(
                spend("2025-01-01", "100", "NETFLIX DUBAI"), spend("2025-02-01", "100", "Netflix"),
                spend("2025-03-01", "150", "Lulu"),
                tx("2025-03-05", "30", "Lulu", TransactionKind.REFUND),
            ),
        )!!
        assertEquals(listOf("Netflix", "Lulu"), r.topMerchants.map { it.name }.map { if (it.startsWith("Netflix", true)) "Netflix" else it })
        assertEquals(200.0, r.topMerchants[0].amount, 1e-9)
        assertEquals(2, r.topMerchants[0].count)
        assertEquals(120.0, r.topMerchants[1].amount, 1e-9)
    }

    @Test
    fun `top categories use names and fall back to Uncategorized`() {
        val r = build(
            listOf(spend("2025-01-01", "300", categoryId = 1), spend("2025-01-02", "100"), spend("2025-01-03", "50", categoryId = 1)),
            names = mapOf(1L to "Groceries"),
        )!!
        assertEquals(listOf("Groceries", "Uncategorized"), r.topCategories.map { it.name })
        assertEquals(350.0, r.topCategories[0].amount, 1e-9)
    }

    @Test
    fun `only the top five are kept`() {
        val txs = (1..8).map { spend("2025-01-0$it", "${it * 10}", merchant = "Merchant${'A' + it}") }
        assertEquals(YearInReviewBuilder.TOP_N, build(txs)!!.topMerchants.size)
        assertEquals(80.0, build(txs)!!.topMerchants.first().amount, 1e-9)
    }

    @Test
    fun `a past year counts every day, the current year only up to today`() {
        val past = build(listOf(spend("2025-01-01", "10"), spend("2025-01-02", "10")))!!
        assertEquals(365 - 2, past.noSpendDays)
        val now = YearInReviewBuilder.build(
            2026, listOf(spend("2026-01-01", "10")), emptyMap(), "AED", inAed, LocalDate.parse("2026-01-10"), utc,
        )!!
        assertEquals(10 - 1, now.noSpendDays)
    }

    @Test
    fun `spending is compared with the year before`() {
        val r = build(listOf(spend("2024-05-01", "1000"), spend("2025-05-01", "1500")))!!
        assertEquals(1000.0, r.previousYearSpent!!, 1e-9)
        assertEquals(50.0, r.spendChangePercent!!, 1e-9)
    }

    @Test
    fun `no comparison when the year before had no spending`() {
        val r = build(listOf(spend("2025-05-01", "1500")))!!
        assertNull(r.previousYearSpent)
        assertNull(r.spendChangePercent)
    }

    @Test
    fun `rows that cannot be converted are left out`() {
        val r = build(listOf(spend("2025-01-01", "100"), tx("2025-01-02", "999", "Abroad", currency = "USD")))!!
        assertEquals(100.0, r.spent, 1e-9)
        assertEquals(100.0, r.monthlySpend[0], 1e-9)
        assertTrue(r.topMerchants.none { it.name == "Abroad" })
    }
}
