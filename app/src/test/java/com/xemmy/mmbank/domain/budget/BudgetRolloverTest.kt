package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRolloverTest {

    private val utc = ZoneOffset.UTC
    private val now = YearMonth.of(2026, 11)
    private val same: (String, BigDecimal) -> BigDecimal? = CategoryBudgetCalculator.converter("AED", emptyMap())

    private fun category(id: Long = 1, cap: String? = "500", parent: Long? = null) = Category(
        id = id, name = "Cat$id", icon = "", colorHex = "#000000",
        monthlyCap = cap?.let(::BigDecimal), parentCategoryId = parent,
    )

    private fun spend(month: String, amount: String, categoryId: Long? = 1, currency: String = "AED", kind: TransactionKind = TransactionKind.SPEND) =
        Transaction(
            amount = BigDecimal(amount), currencyCode = currency,
            direction = if (kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
            merchant = "Shop", source = TransactionSource.SMS,
            timestampMillis = LocalDate.parse("$month-15").atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
            categoryId = categoryId, kind = kind,
        )

    private fun carry(categories: List<Category>, txs: List<Transaction>, max: Int = BudgetRollover.MAX_MONTHS) =
        BudgetRollover.carryByCategory(categories, txs, "AED", same, now, utc, max)

    @Test
    fun `unspent cap from the last month carries forward`() {
        val result = carry(listOf(category()), listOf(spend("2026-10", "200")))
        assertEquals(0, result.getValue(1).compareTo(BigDecimal("300")))
    }

    @Test
    fun `leftovers accumulate across months`() {
        // Aug: 500 - 100 = 400 carried. Sep: 900 - 700 = 200. Oct: 700 - 0... spend 50 -> 650.
        val txs = listOf(spend("2026-08", "100"), spend("2026-09", "700"), spend("2026-10", "50"))
        assertEquals(0, carry(listOf(category()), txs).getValue(1).compareTo(BigDecimal("650")))
    }

    @Test
    fun `overspending carries nothing and never creates debt`() {
        val txs = listOf(spend("2026-09", "100"), spend("2026-10", "900"))
        val result = carry(listOf(category()), txs)
        // Sep leaves 400; Oct has 900 available and spends 900 -> 0 left -> absent.
        assertNull(result[1])
        val worse = carry(listOf(category()), listOf(spend("2026-09", "100"), spend("2026-10", "2000")))
        assertNull(worse[1])
    }

    @Test
    fun `a category with no spend yet carries nothing, however old the cap is`() {
        val txs = listOf(spend("2026-04", "10", categoryId = 2), spend("2026-10", "100", categoryId = 2))
        val result = carry(listOf(category(1), category(2)), txs)
        // Category 1 never had spend: it must not inherit six months of envelope.
        assertNull(result[1])
        assertTrue(result.containsKey(2L))
    }

    @Test
    fun `accumulation begins in the category's first month of spend`() {
        // First spend in Sep: Sep leaves 500-100=400, Oct leaves 900-100=800.
        val txs = listOf(spend("2026-09", "100"), spend("2026-10", "100"))
        assertEquals(0, carry(listOf(category()), txs).getValue(1).compareTo(BigDecimal("800")))
    }

    @Test
    fun `only the last six months are read`() {
        val txs = listOf(spend("2025-06", "1"), spend("2026-10", "500"))
        // 2025-06 is far outside the window; the window starts at 2026-05 and no month before Oct has spend,
        // so accumulation begins in Oct and leaves 0.
        assertNull(carry(listOf(category()), txs)[1])
        val within = carry(listOf(category()), listOf(spend("2026-05", "100"), spend("2026-10", "500")))
        // May leaves 400; Jun..Sep each add the 500 cap with no spend (900, 1400, 1900, 2400);
        // Oct has 2900 available and spends 500 -> 2400 left.
        assertEquals(0, within.getValue(1).compareTo(BigDecimal("2400")))
    }

    @Test
    fun `a parent category rolls up its children's spend`() {
        val parent = category(1, "1000")
        val child = category(2, null, parent = 1)
        val result = carry(listOf(parent, child), listOf(spend("2026-10", "250", categoryId = 2)))
        assertEquals(0, result.getValue(1).compareTo(BigDecimal("750")))
        assertNull(result[2]) // uncapped child
    }

    @Test
    fun `refunds give money back to the envelope`() {
        val txs = listOf(spend("2026-10", "300"), spend("2026-10", "100", kind = TransactionKind.REFUND))
        assertEquals(0, carry(listOf(category()), txs).getValue(1).compareTo(BigDecimal("300")))
    }

    @Test
    fun `foreign spend with no rate is not counted, with a rate it is converted`() {
        val txs = listOf(spend("2026-10", "100", currency = "USD"))
        // No rate: left out, nothing counted, so no spend history exists for the category.
        assertTrue(carry(listOf(category()), txs).isEmpty())
        val rated = BudgetRollover.carryByCategory(
            listOf(category()), txs, "AED",
            CategoryBudgetCalculator.converter("AED", mapOf("USD" to BigDecimal("0.25"))), now, utc,
        )
        // 100 USD at 0.25 USD per AED = 400 AED spent of 500 -> 100 carried.
        assertEquals(0, rated.getValue(1).compareTo(BigDecimal("100")))
    }

    @Test
    fun `no capped categories or no history is empty`() {
        assertTrue(carry(emptyList(), listOf(spend("2026-10", "1"))).isEmpty())
        assertTrue(carry(listOf(category(cap = null)), listOf(spend("2026-10", "1"))).isEmpty())
        assertTrue(carry(listOf(category()), emptyList()).isEmpty())
    }

    @Test
    fun `this month's own spend is never counted as carry`() {
        assertTrue(carry(listOf(category()), listOf(spend("2026-11", "100"))).isEmpty())
    }

    @Test
    fun `statusFor adds the carry to the cap and keeps it visible`() {
        val c = category(cap = "500")
        val plain = CategoryBudgetCalculator.statusFor(c, BigDecimal("450"))!!
        assertEquals(CategoryBudgetCalculator.BudgetState.NEAR, plain.state)
        val withCarry = CategoryBudgetCalculator.statusFor(c, BigDecimal("450"), BigDecimal("300"))!!
        assertEquals(0, withCarry.cap.compareTo(BigDecimal("800")))
        assertEquals(0, withCarry.carriedOver.compareTo(BigDecimal("300")))
        assertEquals(CategoryBudgetCalculator.BudgetState.UNDER, withCarry.state)
        assertEquals(0, withCarry.remaining.compareTo(BigDecimal("350")))
    }

    @Test
    fun `statuses pass the carry to the right category and ignore a negative carry`() {
        val statuses = CategoryBudgetCalculator.statuses(
            listOf(category(1, "100"), category(2, "100")),
            mapOf(1L to BigDecimal("150"), 2L to BigDecimal("150")),
            mapOf(1L to BigDecimal("100"), 2L to BigDecimal("-50")),
        )
        val byId = statuses.associateBy { it.category.id }
        assertEquals(CategoryBudgetCalculator.BudgetState.UNDER, byId.getValue(1).state)
        assertEquals(CategoryBudgetCalculator.BudgetState.OVER, byId.getValue(2).state)
        assertEquals(0, byId.getValue(2).cap.compareTo(BigDecimal("100")))
    }
}
