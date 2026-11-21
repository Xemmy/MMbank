package com.xemmy.mmbank.domain.budget

import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.BudgetState
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryBudgetCalculatorTest {

    private val monthStart = 1_700_000_000_000L
    private val monthEnd = monthStart + 30L * 24 * 60 * 60 * 1000

    private fun tx(
        amount: String,
        categoryId: Long? = 1L,
        at: Long = monthStart + 1000,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = "Merchant",
        source = TransactionSource.SMS,
        timestampMillis = at,
        excludeFromStats = excludeFromStats,
        categoryId = categoryId,
    )

    private fun category(id: Long = 1L, cap: String? = "100.00") = Category(
        id = id,
        name = "Groceries",
        icon = "🛒",
        colorHex = "#4CAF50",
        monthlyCap = cap?.let(::BigDecimal),
    )

    private fun spend(vararg transactions: Transaction) =
        CategoryBudgetCalculator.monthToDateByCategory(transactions.toList(), "AED", monthStart, monthEnd)

    // -- monthToDateByCategory -------------------------------------------------

    @Test
    fun `sums debits per category`() {
        val result = spend(
            tx("30.00", categoryId = 1L),
            tx("20.00", categoryId = 1L),
            tx("15.00", categoryId = 2L),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("50.00")))
        assertEquals(0, result.getValue(2L).compareTo(BigDecimal("15.00")))
    }

    @Test
    fun `credits do not count toward a spending budget`() {
        val result = spend(
            tx("30.00"),
            tx("500.00", direction = TransactionDirection.CREDIT),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("30.00")))
    }

    @Test
    fun `analytics-excluded rows are ignored, matching every other spend total`() {
        val result = spend(
            tx("30.00"),
            tx("999.00", excludeFromStats = true),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("30.00")))
    }

    @Test
    fun `uncategorized transactions belong to no budget`() {
        val result = spend(tx("30.00", categoryId = null))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a different currency is never summed into the cap's currency`() {
        val result = spend(
            tx("30.00"),
            tx("900.00", currencyCode = "USD"),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("30.00")))
    }

    @Test
    fun `the month window is half-open, so the next month's first row is excluded`() {
        val result = spend(
            tx("30.00", at = monthStart),
            tx("70.00", at = monthEnd - 1),
            tx("999.00", at = monthEnd),
            tx("999.00", at = monthStart - 1),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("100.00")))
    }

    // -- statusFor -------------------------------------------------------------

    @Test
    fun `a category with no cap has no status`() {
        assertNull(CategoryBudgetCalculator.statusFor(category(cap = null), BigDecimal("50.00")))
    }

    @Test
    fun `a zero or negative cap reads as no budget, not an instantly blown one`() {
        assertNull(CategoryBudgetCalculator.statusFor(category(cap = "0"), BigDecimal.ZERO))
        assertNull(CategoryBudgetCalculator.statusFor(category(cap = "-10"), BigDecimal.ZERO))
    }

    @Test
    fun `just under the near threshold is still UNDER`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("79.99"))!!
        assertEquals(BudgetState.UNDER, status.state)
    }

    @Test
    fun `exactly the near threshold is NEAR`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("80.00"))!!
        assertEquals(BudgetState.NEAR, status.state)
    }

    @Test
    fun `exactly the cap is OVER, not NEAR`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("100.00"))!!
        assertEquals(BudgetState.OVER, status.state)
        assertEquals(0, status.remaining.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `an overspent category reports zero remaining rather than a negative`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("150.00"))!!
        assertEquals(BudgetState.OVER, status.state)
        assertEquals(0, status.remaining.compareTo(BigDecimal.ZERO))
        assertTrue(status.fraction > 1.0)
    }

    @Test
    fun `remaining is what is left under the cap`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("30.00"))!!
        assertEquals(0, status.remaining.compareTo(BigDecimal("70.00")))
    }

    // -- statuses --------------------------------------------------------------

    @Test
    fun `statuses covers only capped categories, most consumed first`() {
        val result = CategoryBudgetCalculator.statuses(
            categories = listOf(
                category(id = 1L, cap = "100.00"),
                category(id = 2L, cap = "100.00"),
                category(id = 3L, cap = null),
            ),
            spendByCategoryId = mapOf(1L to BigDecimal("20.00"), 2L to BigDecimal("90.00")),
        )
        assertEquals(listOf(2L, 1L), result.map { it.category.id })
    }

    @Test
    fun `a capped category with no spend yet still reports, at zero`() {
        val result = CategoryBudgetCalculator.statuses(listOf(category()), emptyMap())
        assertEquals(1, result.size)
        assertEquals(BudgetState.UNDER, result.single().state)
        assertEquals(0, result.single().spent.compareTo(BigDecimal.ZERO))
    }

    // -- pendingAlert ----------------------------------------------------------

    @Test
    fun `an under-budget category never alerts`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("10.00"))!!
        assertNull(CategoryBudgetCalculator.pendingAlert(status, "2026-07", emptySet()))
    }

    @Test
    fun `crossing a threshold alerts once, then stays silent that month`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("85.00"))!!
        val key = CategoryBudgetCalculator.pendingAlert(status, "2026-07", emptySet())
        assertEquals(CategoryBudgetCalculator.alertKey(1L, "2026-07", BudgetState.NEAR), key)
        assertNull(CategoryBudgetCalculator.pendingAlert(status, "2026-07", setOf(key!!)))
    }

    @Test
    fun `going over after a near alert fires a second, distinct alert`() {
        val near = CategoryBudgetCalculator.statusFor(category(), BigDecimal("85.00"))!!
        val nearKey = CategoryBudgetCalculator.pendingAlert(near, "2026-07", emptySet())!!

        val over = CategoryBudgetCalculator.statusFor(category(), BigDecimal("120.00"))!!
        val overKey = CategoryBudgetCalculator.pendingAlert(over, "2026-07", setOf(nearKey))

        assertEquals(CategoryBudgetCalculator.alertKey(1L, "2026-07", BudgetState.OVER), overKey)
    }

    @Test
    fun `a new month alerts again on the same category and threshold`() {
        val status = CategoryBudgetCalculator.statusFor(category(), BigDecimal("85.00"))!!
        val julyKey = CategoryBudgetCalculator.pendingAlert(status, "2026-07", emptySet())!!
        assertEquals(
            CategoryBudgetCalculator.alertKey(1L, "2026-08", BudgetState.NEAR),
            CategoryBudgetCalculator.pendingAlert(status, "2026-08", setOf(julyKey)),
        )
    }

    // -- rollUpSpend -------------------------------------------------------------

    private fun category(id: Long, parentCategoryId: Long?, cap: String? = "100.00") = Category(
        id = id,
        name = "Cat $id",
        icon = "🛒",
        colorHex = "#4CAF50",
        monthlyCap = cap?.let(::BigDecimal),
        parentCategoryId = parentCategoryId,
    )

    @Test
    fun `a parent's total includes its children's direct spend`() {
        val result = CategoryBudgetCalculator.rollUpSpend(
            categories = listOf(category(1L, parentCategoryId = null), category(2L, parentCategoryId = 1L)),
            directSpendByCategoryId = mapOf(1L to BigDecimal("20.00"), 2L to BigDecimal("30.00")),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("50.00")))
    }

    @Test
    fun `a child's own total is unaffected by its siblings or parent`() {
        val result = CategoryBudgetCalculator.rollUpSpend(
            categories = listOf(
                category(1L, parentCategoryId = null),
                category(2L, parentCategoryId = 1L),
                category(3L, parentCategoryId = 1L),
            ),
            directSpendByCategoryId = mapOf(2L to BigDecimal("30.00"), 3L to BigDecimal("40.00")),
        )
        assertEquals(0, result.getValue(2L).compareTo(BigDecimal("30.00")))
        assertEquals(0, result.getValue(3L).compareTo(BigDecimal("40.00")))
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("70.00")))
    }

    @Test
    fun `a top-level category with no children just reports its own spend`() {
        val result = CategoryBudgetCalculator.rollUpSpend(
            categories = listOf(category(1L, parentCategoryId = null)),
            directSpendByCategoryId = mapOf(1L to BigDecimal("20.00")),
        )
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("20.00")))
    }

    @Test
    fun `a parent cycle terminates instead of recursing forever`() {
        // The Edit-category UI already blocks creating a direct 2-cycle, but the
        // pure function itself must never hang or overflow the stack if one
        // somehow exists -- the cycle guard stops each path at its own start.
        val result = CategoryBudgetCalculator.rollUpSpend(
            categories = listOf(category(1L, parentCategoryId = 2L), category(2L, parentCategoryId = 1L)),
            directSpendByCategoryId = mapOf(1L to BigDecimal("10.00"), 2L to BigDecimal("15.00")),
        )
        assertEquals(setOf(1L, 2L), result.keys)
        assertTrue(result.getValue(1L).signum() >= 0)
        assertTrue(result.getValue(2L).signum() >= 0)
    }

    // -- monthSpend: other currencies -----------------------------------------

    private val usdRate = mapOf("USD" to BigDecimal("0.2723")) // quote per 1 AED

    private fun monthSpend(rates: Map<String, BigDecimal>, vararg transactions: Transaction) =
        CategoryBudgetCalculator.monthSpend(
            transactions.toList(), "AED", monthStart, monthEnd,
            CategoryBudgetCalculator.converter("AED", rates),
        )

    @Test
    fun `converter divides by the quote rate and rounds half-even`() {
        val convert = CategoryBudgetCalculator.converter("AED", usdRate)
        assertEquals(0, convert("USD", BigDecimal("27.23"))!!.compareTo(BigDecimal("100.00")))
        assertEquals(0, convert("aed", BigDecimal("5.5"))!!.compareTo(BigDecimal("5.5")))
    }

    @Test
    fun `converter returns null for unknown or non-positive rates`() {
        val convert = CategoryBudgetCalculator.converter("AED", mapOf("EUR" to BigDecimal.ZERO))
        assertNull(convert("USD", BigDecimal.TEN))
        assertNull(convert("EUR", BigDecimal.TEN))
    }

    @Test
    fun `foreign spend with a rate counts toward the category`() {
        val result = monthSpend(usdRate, tx("30.00"), tx("27.23", currencyCode = "USD"))
        assertEquals(0, result.byCategory.getValue(1L).compareTo(BigDecimal("130.00")))
        assertTrue(!result.hasUncounted)
    }

    @Test
    fun `foreign spend with no rate is reported not dropped`() {
        val result = monthSpend(emptyMap(), tx("30.00"), tx("45.00", currencyCode = "usd"))
        assertEquals(0, result.byCategory.getValue(1L).compareTo(BigDecimal("30.00")))
        assertEquals(setOf("USD"), result.uncountedByCurrency.keys)
        assertEquals(0, result.uncountedByCurrency.getValue("USD").compareTo(BigDecimal("45.00")))
        assertTrue(result.hasUncounted)
    }

    @Test
    fun `uncounted foreign spend is net of refunds and never negative`() {
        val refund = tx("50.00", currencyCode = "USD", direction = TransactionDirection.CREDIT)
            .copy(kind = com.xemmy.mmbank.domain.model.TransactionKind.REFUND)
        val result = monthSpend(emptyMap(), tx("45.00", currencyCode = "USD"), refund)
        assertTrue(!result.hasUncounted)
    }

    @Test
    fun `uncategorised and out-of-window foreign spend is not reported`() {
        val result = monthSpend(
            emptyMap(),
            tx("45.00", currencyCode = "USD", categoryId = null),
            tx("45.00", currencyCode = "USD", at = monthEnd),
        )
        assertTrue(!result.hasUncounted)
    }

    @Test
    fun `monthToDateByCategory still ignores other currencies`() {
        val result = spend(tx("30.00"), tx("999.00", currencyCode = "USD"))
        assertEquals(0, result.getValue(1L).compareTo(BigDecimal("30.00")))
    }
}
