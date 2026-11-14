package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Balances are derived, so this object is the single place the derivation
 * lives. It replaced three copies that disagreed: two summed a lossy
 * `CAST(amount AS REAL)` Double, and the third filtered `excludeFromStats`
 * rows out of a *balance*. Both of those behaviours are pinned here so they
 * cannot come back.
 */
class AccountBalanceCalculatorTest {

    private fun account(
        id: Long = 1,
        opening: String = "0",
        currency: String = "AED",
        type: AccountType = AccountType.CHECKING,
    ) = Account(
        id = id,
        name = "Account $id",
        type = type,
        currencyCode = currency,
        openingBalance = BigDecimal(opening),
    )

    private fun credit(amount: String, accountId: Long? = 1, currency: String = "AED") =
        LedgerEntry(accountId, BigDecimal(amount), currency, TransactionDirection.CREDIT)

    private fun debit(amount: String, accountId: Long? = 1, currency: String = "AED") =
        LedgerEntry(accountId, BigDecimal(amount), currency, TransactionDirection.DEBIT)

    @Test
    fun `balance is opening balance plus credits minus debits`() {
        val balance = AccountBalanceCalculator.balanceFor(
            account(opening = "500"),
            listOf(credit("250.50"), debit("100.25")),
        )
        assertEquals(0, BigDecimal("650.25").compareTo(balance))
    }

    @Test
    fun `an account with no transactions returns its opening balance`() {
        val balance = AccountBalanceCalculator.balanceFor(account(opening = "1200.40"), emptyList())
        assertEquals(0, BigDecimal("1200.40").compareTo(balance))
    }

    @Test
    fun `entries for other accounts never leak into an account's balance`() {
        val balance = AccountBalanceCalculator.balanceFor(
            account(id = 1, opening = "100"),
            listOf(credit("50", accountId = 1), credit("9999", accountId = 2)),
        )
        assertEquals(0, BigDecimal("150").compareTo(balance))
    }

    @Test
    fun `a fraction Double cannot represent survives exactly`() {
        // 0.1 + 0.2 is 0.30000000000000004 in Double. The old SQL path did
        // CAST(amount AS REAL) and would have carried that error into a balance
        // the user then tried to reconcile by hand.
        val balance = AccountBalanceCalculator.balanceFor(
            account(opening = "0"),
            listOf(credit("0.1"), credit("0.2")),
        )
        assertEquals(BigDecimal("0.3"), balance.stripTrailingZeros())
        assertEquals(0, BigDecimal("0.3").compareTo(balance))
    }

    @Test
    fun `a same-currency ledger returns an exact BigDecimal with no rounding`() {
        // Three thirds of a fils must add back up to exactly what went in --
        // no scale is imposed, so nothing is silently rounded.
        val balance = AccountBalanceCalculator.balanceFor(
            account(opening = "0"),
            listOf(credit("0.005"), credit("0.005"), credit("0.005")),
        )
        assertEquals(0, BigDecimal("0.015").compareTo(balance))
    }

    @Test
    fun `balance adjustments and both transfer legs are counted in the balance`() {
        // These rows carry excludeFromStats = true, which keeps them out of
        // income/spend analytics. They are still real movements, and the
        // monthly report used to filter them out of its NET WORTH figure --
        // which is why it disagreed with the Accounts screen. The calculator
        // takes no such flag: every entry handed to it counts.
        val transferOut = debit("400", accountId = 1)
        val transferIn = credit("400", accountId = 2)
        val correction = credit("25", accountId = 1)

        val balances = AccountBalanceCalculator.balances(
            listOf(account(id = 1, opening = "1000"), account(id = 2, opening = "0")),
            listOf(transferOut, transferIn, correction),
        )
        assertEquals(0, BigDecimal("625").compareTo(balances.getValue(1)))
        assertEquals(0, BigDecimal("400").compareTo(balances.getValue(2)))
    }

    @Test
    fun `mixed currencies are summed one to one - deliberate, see plan`() {
        // A USD 100 charge on an AED account reduces it by 100, not by its
        // converted value. This is the app's long-standing behaviour and is
        // kept ON PURPOSE rather than changed underneath the user: converting
        // would need the rates table and a decision about what to show when no
        // rate is cached. This test exists so the choice stays visible.
        val balance = AccountBalanceCalculator.balanceFor(
            account(opening = "1000", currency = "AED"),
            listOf(debit("100", currency = "USD")),
        )
        assertEquals(0, BigDecimal("900").compareTo(balance))
    }

    @Test
    fun `an empty ledger yields every account at its opening balance`() {
        val balances = AccountBalanceCalculator.balances(
            listOf(account(id = 1, opening = "10"), account(id = 2, opening = "-250")),
            emptyList(),
        )
        assertEquals(2, balances.size)
        assertEquals(0, BigDecimal("10").compareTo(balances.getValue(1)))
        assertEquals(0, BigDecimal("-250").compareTo(balances.getValue(2)))
    }

    @Test
    fun `a liability account trends negative rather than being special-cased`() {
        val balance = AccountBalanceCalculator.balanceFor(
            account(opening = "0", type = AccountType.CREDIT_CARD),
            listOf(debit("750.25")),
        )
        assertTrue(balance.signum() < 0)
        assertEquals(0, BigDecimal("-750.25").compareTo(balance))
    }

    @Test
    fun `unassignedTotals groups entries with no account by currency`() {
        val totals = AccountBalanceCalculator.unassignedTotals(
            listOf(
                debit("100", accountId = null, currency = "AED"),
                debit("50", accountId = null, currency = "AED"),
                credit("20", accountId = null, currency = "USD"),
                debit("999", accountId = 1),
            ),
        )
        assertEquals(setOf("AED", "USD"), totals.keys)
        assertEquals(0, BigDecimal("-150").compareTo(totals.getValue("AED")))
        assertEquals(0, BigDecimal("20").compareTo(totals.getValue("USD")))
    }

    @Test
    fun `unassignedTotals normalizes currency case so one currency is one bucket`() {
        val totals = AccountBalanceCalculator.unassignedTotals(
            listOf(
                debit("10", accountId = null, currency = "aed"),
                debit("10", accountId = null, currency = "AED"),
            ),
        )
        assertEquals(setOf("AED"), totals.keys)
        assertEquals(0, BigDecimal("-20").compareTo(totals.getValue("AED")))
    }

    @Test
    fun `unassignedCount counts only entries with no account`() {
        val entries = listOf(
            debit("1", accountId = null),
            credit("2", accountId = null),
            debit("3", accountId = 7),
        )
        assertEquals(2, AccountBalanceCalculator.unassignedCount(entries))
        assertTrue(AccountBalanceCalculator.unassignedTotals(emptyList()).isEmpty())
        assertEquals(0, AccountBalanceCalculator.unassignedCount(emptyList()))
    }

    @Test
    fun `netByAccount omits accounts with no entries rather than reporting zero`() {
        val net = AccountBalanceCalculator.netByAccount(listOf(credit("5", accountId = 3)))
        assertEquals(setOf(3L), net.keys)
    }

    @Test
    fun `approximateAccounts lists only accounts with an approximate entry`() {
        fun entry(account: Long?, approximate: Boolean) = AccountBalanceCalculator.LedgerEntry(
            accountId = account,
            amount = BigDecimal.TEN,
            currencyCode = "AED",
            direction = com.xemmy.mmbank.domain.model.TransactionDirection.DEBIT,
            approximate = approximate,
        )
        val result = AccountBalanceCalculator.approximateAccounts(
            listOf(entry(1, false), entry(2, true), entry(2, false), entry(null, true), entry(3, true)),
        )
        assertEquals(setOf(2L, 3L), result)
        assertEquals(emptySet<Long>(), AccountBalanceCalculator.approximateAccounts(emptyList()))
    }

    @Test
    fun `history only entries are left out of the unassigned totals and count`() {
        val entries = listOf(
            debit("100", accountId = null),
            debit("40", accountId = null).copy(historyOnly = true),
        )
        assertEquals(1, AccountBalanceCalculator.unassignedCount(entries))
        assertEquals(0, BigDecimal("-100").compareTo(AccountBalanceCalculator.unassignedTotals(entries).getValue("AED")))
    }

    @Test
    fun `when every unassigned entry is history only there is nothing to report`() {
        val entries = listOf(debit("40", accountId = null).copy(historyOnly = true))
        assertEquals(0, AccountBalanceCalculator.unassignedCount(entries))
        assertTrue(AccountBalanceCalculator.unassignedTotals(entries).isEmpty())
    }
}
