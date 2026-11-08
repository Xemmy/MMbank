package com.xemmy.mmbank.ui.screens.dashboard

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.BalanceHistory
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetWorthTrendTest {

    private val zone = ZoneId.of("UTC")
    private val thisMonth = YearMonth.of(2026, 11)

    private val current = Account(id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED", openingBalance = BigDecimal("1000"))
    private val card = Account(id = 2, name = "Card", type = AccountType.CREDIT_CARD, currencyCode = "AED")
    private val usd = Account(id = 3, name = "USD", type = AccountType.SAVINGS, currencyCode = "USD", openingBalance = BigDecimal("100"))

    private fun tx(account: Long, amount: String, date: LocalDate, credit: Boolean = false, currency: String = "AED") = Transaction(
        accountId = account,
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli(),
    )

    private val ledger = listOf(
        tx(1, "5000", LocalDate.of(2026, 9, 1), credit = true),
        tx(2, "400", LocalDate.of(2026, 9, 20)),
        tx(1, "1000", LocalDate.of(2026, 10, 5)),
        tx(1, "3000", LocalDate.of(2026, 11, 1), credit = true),
    )

    private fun trend(
        rows: List<Transaction> = ledger,
        accounts: List<Account> = listOf(current, card),
        rates: List<ExchangeRate> = emptyList(),
        excludeCards: Boolean = false,
    ) = computeNetWorthTrend(accounts, rows, rates, "AED", excludeCards, zone, thisMonth, months = 4)

    @Test
    fun `month-end values replay the ledger, null before history starts`() {
        val t = trend()
        assertEquals(listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 9), YearMonth.of(2026, 10), thisMonth), t.months)
        // Aug: before the first row. Sep: 1000+5000-400. Oct: -1000. Nov: +3000.
        assertEquals(listOf(null, 5600.0, 4600.0, 7600.0), t.values)
        assertEquals(2000.0, t.changeSince(2)!!, 1e-9)
        assertNull(t.changeSince(3))
        assertTrue(t.hasTrend)
    }

    @Test
    fun `the last point is exactly Home's fiat total`() {
        val rows = accountRows(listOf(current, card), ledger)
        val home = computeAccountsPresentation(rows, hideCreditCardBalance = false, excludeCreditCardFromTotal = false)
        assertEquals(home.fiatTotalInBase, trend().current!!, 1e-9)
    }

    @Test
    fun `excluding cards from the total excludes them from history too`() {
        assertEquals(listOf(null, 6000.0, 5000.0, 8000.0), trend(excludeCards = true).values)
    }

    @Test
    fun `accounts with no rate mark the trend approximate`() {
        val t = trend(accounts = listOf(current, card, usd))
        assertTrue(t.approximate)
        assertEquals(7600.0, t.current!!, 1e-9)
        val withRate = trend(
            accounts = listOf(current, card, usd),
            rates = listOf(ExchangeRate("AED", "USD", BigDecimal("0.25"), 0L)),
        )
        assertFalse(withRate.approximate)
        assertEquals(7600.0 + 400.0, withRate.current!!, 1e-9)
    }

    @Test
    fun `an empty ledger still shows today, but no trend`() {
        val t = trend(rows = emptyList())
        assertEquals(listOf(null, null, null, 1000.0), t.values)
        assertFalse(t.hasTrend)
    }

    private fun accountRows(accounts: List<Account>, rows: List<Transaction>): List<AccountRow> {
        val balances = AccountBalanceCalculator.balances(accounts, rows.map(BalanceHistory::toLedgerEntry))
        return accounts.map { AccountRow(it, balances.getValue(it.id).toDouble(), balances.getValue(it.id).toDouble()) }
    }
}
