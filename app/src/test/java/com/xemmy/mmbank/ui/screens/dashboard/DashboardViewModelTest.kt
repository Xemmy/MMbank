package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeDashboardStats] is the analytics core behind the Dashboard tab.
 * Rows flagged [Transaction.excludeFromStats] (balance corrections, refund
 * adjustments) must vanish from every aggregate by default, and count again
 * when the "raw ledger" toggle asks for unfiltered figures.
 */
class DashboardViewModelTest {

    private val zone = ZoneId.of("UTC")
    private val thisMonth = YearMonth.of(2026, 6)
    private val noRates = emptyList<ExchangeRate>()

    private fun tx(
        amount: String,
        direction: TransactionDirection,
        excluded: Boolean = false,
        category: SpendingCategory = SpendingCategory.UNCATEGORIZED,
        person: String? = null,
        month: YearMonth = thisMonth,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        source = TransactionSource.MANUAL,
        timestampMillis = month.atDay(15).atStartOfDay(zone).toInstant().toEpochMilli(),
        spendingCategory = category,
        person = person,
        excludeFromStats = excluded,
    )

    private fun stats(transactions: List<Transaction>, includeExcluded: Boolean) =
        computeDashboardStats(
            transactions = transactions,
            rates = noRates,
            baseCurrency = "AED",
            includeExcluded = includeExcluded,
            zone = zone,
            thisMonth = thisMonth,
        )

    // A mixed month: real income + spend, plus a flagged balance-correction
    // credit and a flagged refund-adjustment debit that must not skew stats.
    private val mixed = listOf(
        tx("1000.00", TransactionDirection.CREDIT),
        tx("200.00", TransactionDirection.DEBIT, category = SpendingCategory.GROCERIES),
        tx("50.00", TransactionDirection.DEBIT, category = SpendingCategory.DINING),
        tx("500.00", TransactionDirection.CREDIT, excluded = true),
        tx("75.00", TransactionDirection.DEBIT, excluded = true, category = SpendingCategory.TRANSFERS),
    )

    @Test
    fun `toggle off - flagged rows are omitted from income, spend and net`() {
        val month = stats(mixed, includeExcluded = false).monthly.last()
        assertEquals(1000.0, month.income, 1e-9)
        assertEquals(250.0, month.spend, 1e-9)
        assertEquals(750.0, month.income - month.spend, 1e-9)
    }

    @Test
    fun `toggle on - flagged rows are counted in income, spend and net`() {
        val month = stats(mixed, includeExcluded = true).monthly.last()
        assertEquals(1500.0, month.income, 1e-9)
        assertEquals(325.0, month.spend, 1e-9)
        assertEquals(1175.0, month.income - month.spend, 1e-9)
    }

    @Test
    fun `toggle off - a flagged transaction's category contributes nothing`() {
        val spends = stats(mixed, includeExcluded = false).categorySpends
        assertNull(spends.firstOrNull { it.category == SpendingCategory.TRANSFERS })
        assertEquals(
            200.0,
            spends.first { it.category == SpendingCategory.GROCERIES }.amount,
            1e-9,
        )
    }

    @Test
    fun `toggle on - a flagged transaction's category counts`() {
        val spends = stats(mixed, includeExcluded = true).categorySpends
        assertEquals(
            75.0,
            spends.first { it.category == SpendingCategory.TRANSFERS }.amount,
            1e-9,
        )
    }

    @Test
    fun `toggle off - flagged rows drop out of person spend too`() {
        val transactions = listOf(
            tx("100.00", TransactionDirection.DEBIT, person = "Amir"),
            tx("40.00", TransactionDirection.DEBIT, person = "Amir", excluded = true),
        )
        val people = stats(transactions, includeExcluded = false).personSpends
        assertEquals(100.0, people.first { it.person == "Amir" }.amount, 1e-9)
    }

    @Test
    fun `excluded rows in prior months do not skew the average monthly net`() {
        val transactions = listOf(
            tx("300.00", TransactionDirection.CREDIT, month = thisMonth.minusMonths(1)),
            tx("9999.00", TransactionDirection.CREDIT, excluded = true, month = thisMonth.minusMonths(1)),
        )
        assertEquals(300.0, stats(transactions, includeExcluded = false).avgMonthlyNet, 1e-9)
    }

    @Test
    fun `all-excluded month reads as zero activity by default`() {
        val transactions = listOf(
            tx("500.00", TransactionDirection.CREDIT, excluded = true),
            tx("75.00", TransactionDirection.DEBIT, excluded = true),
        )
        val result = stats(transactions, includeExcluded = false)
        val month = result.monthly.last()
        assertEquals(0.0, month.income, 1e-9)
        assertEquals(0.0, month.spend, 1e-9)
        assertTrue(result.categorySpends.isEmpty())
        assertTrue(result.personSpends.isEmpty())
    }

    @Test
    fun `categorySlicesFrom caps at top 4 and rolls the rest into Other`() {
        val spends = listOf(
            CategorySpend(SpendingCategory.GROCERIES, 600.0),
            CategorySpend(SpendingCategory.DINING, 400.0),
            CategorySpend(SpendingCategory.TRANSPORT, 300.0),
            CategorySpend(SpendingCategory.SHOPPING, 200.0),
            CategorySpend(SpendingCategory.FUEL, 80.0),
            CategorySpend(SpendingCategory.ENTERTAINMENT, 20.0),
        )
        val slices = categorySlicesFrom(spends)

        assertEquals(5, slices.size)
        assertEquals(listOf("Groceries", "Dining", "Transport", "Shopping", "Other"), slices.map { it.name })
        assertEquals(100.0, slices.last().amount, 1e-9)
        assertEquals(
            listOf(0xFF4ECDC4L, 0xFFE07A5FL, 0xFFF2CC8FL, 0xFF81B29AL, 0xFF6C757DL),
            slices.map { it.color },
        )
    }

    @Test
    fun `categorySlicesFrom leaves no Other slice when there are 4 or fewer categories`() {
        val spends = listOf(
            CategorySpend(SpendingCategory.GROCERIES, 600.0),
            CategorySpend(SpendingCategory.DINING, 400.0),
            CategorySpend(SpendingCategory.TRANSPORT, 300.0),
        )
        val slices = categorySlicesFrom(spends)

        assertEquals(3, slices.size)
        assertEquals(listOf("Groceries", "Dining", "Transport"), slices.map { it.name })
        assertTrue(slices.none { it.name == "Other" })
    }

    @Test
    fun `toggle off - categorySlices mirrors categorySpends for the mixed fixture`() {
        val result = stats(mixed, includeExcluded = false)
        assertEquals(2, result.categorySlices.size)
        assertEquals(
            result.categorySpends.map { it.category.label to it.amount },
            result.categorySlices.map { it.name to it.amount },
        )
        assertTrue(result.categorySlices.none { it.name == "Other" })
    }
}
