package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeSpendSummary] backs the Activity tab's Premium hero card: the
 * already-filtered feed's total spend and total received, converted into the
 * base currency, the resulting net, and how much of the monthly budget
 * threshold the spend consumes.
 */
class SpendSummaryTest {

    private fun tx(amount: String, direction: TransactionDirection, currency: String = "AED") = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = direction,
        source = TransactionSource.MANUAL,
        timestampMillis = 0L,
    )

    @Test
    fun `sums only DEBIT rows into totalSpent, ignoring CREDIT`() {
        val result = computeSpendSummary(
            transactions = listOf(
                tx("100.00", TransactionDirection.DEBIT),
                tx("50.00", TransactionDirection.DEBIT),
                tx("1000.00", TransactionDirection.CREDIT),
            ),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(150.0, result.totalSpent, 1e-9)
    }

    @Test
    fun `sums only CREDIT rows into totalReceived, ignoring DEBIT`() {
        val result = computeSpendSummary(
            transactions = listOf(
                tx("100.00", TransactionDirection.DEBIT),
                tx("1000.00", TransactionDirection.CREDIT),
                tx("200.00", TransactionDirection.CREDIT),
            ),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(1200.0, result.totalReceived, 1e-9)
    }

    @Test
    fun `netAmount is received minus spent, and can go negative`() {
        val positive = computeSpendSummary(
            transactions = listOf(
                tx("100.00", TransactionDirection.DEBIT),
                tx("1000.00", TransactionDirection.CREDIT),
            ),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(900.0, positive.netAmount, 1e-9)

        val negative = computeSpendSummary(
            transactions = listOf(
                tx("1000.00", TransactionDirection.DEBIT),
                tx("100.00", TransactionDirection.CREDIT),
            ),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(-900.0, negative.netAmount, 1e-9)
    }

    @Test
    fun `no budget set leaves consumption at zero and hasBudget false`() {
        val result = computeSpendSummary(
            transactions = listOf(tx("200.00", TransactionDirection.DEBIT)),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertFalse(result.hasBudget)
        assertEquals(0.0, result.budgetConsumedPercent, 1e-9)
    }

    @Test
    fun `budget consumption percent is spent over budget times 100`() {
        val result = computeSpendSummary(
            transactions = listOf(tx("300.00", TransactionDirection.DEBIT)),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 1000.0,
        )
        assertTrue(result.hasBudget)
        assertEquals(30.0, result.budgetConsumedPercent, 1e-9)
    }

    @Test
    fun `spend past the budget reports over 100 percent`() {
        val result = computeSpendSummary(
            transactions = listOf(tx("1500.00", TransactionDirection.DEBIT)),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 1000.0,
        )
        assertEquals(150.0, result.budgetConsumedPercent, 1e-9)
    }

    @Test
    fun `foreign-currency rows convert into the base currency using the supplied rates`() {
        val result = computeSpendSummary(
            transactions = listOf(
                tx("100.00", TransactionDirection.DEBIT, currency = "AED"),
                tx("10.00", TransactionDirection.DEBIT, currency = "USD"),
            ),
            // 1 AED -> 0.27 USD, so 10 USD -> ~37.04 AED.
            rates = listOf(ExchangeRate(baseCode = "AED", quoteCode = "USD", rate = BigDecimal("0.27"), fetchedAt = 0L)),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(100.0 + 10.0 / 0.27, result.totalSpent, 1e-6)
    }

    @Test
    fun `a currency with no rate to base is skipped, not guessed`() {
        val result = computeSpendSummary(
            transactions = listOf(
                tx("100.00", TransactionDirection.DEBIT, currency = "AED"),
                tx("50.00", TransactionDirection.DEBIT, currency = "GBP"),
            ),
            rates = emptyList(),
            baseCurrency = "AED",
            monthlyBudget = 0.0,
        )
        assertEquals(100.0, result.totalSpent, 1e-9)
    }
}
