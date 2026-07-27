package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator.AccountBalance
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetWorthAggregatorTest {

    @Test
    fun `net worth is debit total minus credit total across multiple accounts`() {
        val balances = listOf(
            AccountBalance(1, AccountType.CHECKING, BigDecimal("5000.00")),
            AccountBalance(2, AccountType.SAVINGS, BigDecimal("10000.00")),
            AccountBalance(3, AccountType.CREDIT_CARD, BigDecimal("1200.00")),
            AccountBalance(4, AccountType.LOAN, BigDecimal("3000.00")),
        )
        val result = NetWorthAggregator.aggregate(balances)

        assertEquals(BigDecimal("15000.00"), result.debitTotal)
        assertEquals(BigDecimal("4200.00"), result.creditTotal)
        assertEquals(BigDecimal("10800.00"), result.netWorth)
    }

    @Test
    fun `credit account balance sign is normalized to a magnitude owed`() {
        // Some callers track a credit card's balance as negative (debt), others as a positive amount owed.
        val negativeConvention = NetWorthAggregator.aggregate(
            listOf(AccountBalance(1, AccountType.CREDIT_CARD, BigDecimal("-500.00")))
        )
        val positiveConvention = NetWorthAggregator.aggregate(
            listOf(AccountBalance(1, AccountType.CREDIT_CARD, BigDecimal("500.00")))
        )
        assertEquals(BigDecimal("500.00"), negativeConvention.creditTotal)
        assertEquals(BigDecimal("500.00"), positiveConvention.creditTotal)
        assertEquals(negativeConvention.netWorth, positiveConvention.netWorth)
    }

    @Test
    fun `cash crypto and investment accounts count toward the debit total`() {
        val balances = listOf(
            AccountBalance(1, AccountType.CASH, BigDecimal("200.00")),
            AccountBalance(2, AccountType.CRYPTO_SPOT, BigDecimal("800.00")),
            AccountBalance(3, AccountType.INVESTMENT, BigDecimal("1000.00")),
        )
        val result = NetWorthAggregator.aggregate(balances)
        assertEquals(BigDecimal("2000.00"), result.debitTotal)
        assertEquals(BigDecimal.ZERO, result.creditTotal)
    }

    @Test
    fun `net worth can go negative when liabilities exceed assets`() {
        val balances = listOf(
            AccountBalance(1, AccountType.CHECKING, BigDecimal("100.00")),
            AccountBalance(2, AccountType.LOAN, BigDecimal("5000.00")),
        )
        val result = NetWorthAggregator.aggregate(balances)
        assertTrue(result.netWorth < BigDecimal.ZERO)
        assertEquals(BigDecimal("-4900.00"), result.netWorth)
    }

    @Test
    fun `empty account list yields zero everywhere`() {
        val result = NetWorthAggregator.aggregate(emptyList())
        assertEquals(BigDecimal.ZERO, result.debitTotal)
        assertEquals(BigDecimal.ZERO, result.creditTotal)
        assertEquals(BigDecimal.ZERO, result.netWorth)
        assertTrue(result.perAccount.isEmpty())
    }

    @Test
    fun `per-account contribution is signed negative for a credit account`() {
        val balances = listOf(
            AccountBalance(1, AccountType.CHECKING, BigDecimal("1000.00")),
            AccountBalance(2, AccountType.CREDIT_CARD, BigDecimal("300.00")),
        )
        val result = NetWorthAggregator.aggregate(balances)
        assertEquals(BigDecimal("1000.00"), result.perAccount[1])
        assertEquals(BigDecimal("-300.00"), result.perAccount[2])
    }

    private fun tx(accountId: Long?) = Transaction(
        accountId = accountId,
        amount = BigDecimal("10.00"),
        currencyCode = "USD",
        direction = TransactionDirection.DEBIT,
        source = TransactionSource.MANUAL,
        timestampMillis = 0L,
    )

    @Test
    fun `filterByAccount with null returns every transaction unfiltered`() {
        val transactions = listOf(tx(1), tx(2), tx(null))
        assertEquals(transactions, NetWorthAggregator.filterByAccount(transactions, null))
    }

    @Test
    fun `filterByAccount narrows the feed to the selected account`() {
        val transactions = listOf(tx(1), tx(2), tx(1), tx(null))
        val filtered = NetWorthAggregator.filterByAccount(transactions, accountId = 1)
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.accountId == 1L })
    }
}
