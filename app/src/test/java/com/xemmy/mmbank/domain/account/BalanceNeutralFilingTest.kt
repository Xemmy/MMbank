package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee under test: **filing a transaction dated before an account's
 * last balance update must not move that account's balance**, and one dated
 * after it must. Most cases assert the invariant end to end -- compute the
 * balance, file, apply the offsets, compute again -- rather than a magic number.
 */
class BalanceNeutralFilingTest {

    private val credit = TransactionDirection.CREDIT
    private val debit = TransactionDirection.DEBIT

    private fun account(id: Long = 1, opening: String = "0", currency: String = "AED", createdAt: Long = 100) = Account(
        id = id, name = "Account $id", type = AccountType.CHECKING, currencyCode = currency,
        openingBalance = BigDecimal(opening), createdAt = createdAt,
    )

    private fun tx(
        id: Long,
        amount: String,
        direction: TransactionDirection,
        at: Long,
        accountId: Long? = null,
        currency: String = "AED",
    ) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal(amount), currencyCode = currency,
        direction = direction, merchant = "m$id", source = TransactionSource.MANUAL, timestampMillis = at,
    )

    private fun move(tx: Transaction, to: Long?) = tx to tx.copy(accountId = to)

    private fun balance(account: Account, rows: List<Transaction>): BigDecimal =
        AccountBalanceCalculator.balanceFor(
            account,
            rows.map { LedgerEntry(it.accountId, it.amount, it.currencyCode, it.direction) },
        )

    /** [accounts] with the offsets applied, as the repository does. */
    private fun applied(accounts: List<Account>, kept: List<BalanceNeutralFiling.Kept>): Map<Long, Account> =
        accounts.associate { a ->
            a.id to (kept.firstOrNull { it.accountId == a.id }?.let { a.copy(openingBalance = it.newOpeningBalance) } ?: a)
        }

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected but was $actual", 0, BigDecimal(expected).compareTo(actual))

    // --- cutoff ---

    @Test
    fun `the cutoff is the newest balance adjustment`() {
        assertEquals(500L, BalanceNeutralFiling.cutoffFor(account(createdAt = 100), 500L))
    }

    @Test
    fun `an account never adjusted falls back to when it was created`() {
        assertEquals(100L, BalanceNeutralFiling.cutoffFor(account(createdAt = 100), null))
    }

    @Test
    fun `an adjustment older than the account cannot pull the cutoff back`() {
        assertEquals(100L, BalanceNeutralFiling.cutoffFor(account(createdAt = 100), 50L))
    }

    // --- the invariant ---

    @Test
    fun `adjust then file old rows - the balance does not move`() {
        // Opening 1000, one filed purchase, then the user corrects the balance to 4200 at t=500.
        val acc = account(opening = "1000")
        val filed = tx(1, "300", debit, at = 200, accountId = 1)
        val adjustment = tx(2, "3500", credit, at = 500, accountId = 1)
        assertMoney("4200", balance(acc, listOf(filed, adjustment)))

        // Three older rows were never filed; they are already inside the 4200.
        val old = listOf(tx(10, "120", debit, at = 150), tx(11, "80.50", debit, at = 300), tx(12, "1000", credit, at = 400))
        val edits = old.map { move(it, 1) }
        val kept = BalanceNeutralFiling.offsets(edits, mapOf(1L to acc), mapOf(1L to 500L))

        val after = applied(listOf(acc), kept).getValue(1)
        assertMoney("4200", balance(after, listOf(filed, adjustment) + edits.map { it.second }))
        assertEquals(3, kept.single().rowCount)
        // Net of the three rows is +799.50, so the opening balance gives exactly that back.
        assertMoney("200.50", kept.single().newOpeningBalance)
    }

    @Test
    fun `a row dated after the last balance update moves the balance by its amount`() {
        val acc = account(opening = "1000")
        val adjustment = tx(2, "500", credit, at = 500, accountId = 1)
        val late = tx(10, "40", debit, at = 600)
        val kept = BalanceNeutralFiling.offsets(listOf(move(late, 1)), mapOf(1L to acc), mapOf(1L to 500L))

        assertTrue(kept.isEmpty())
        assertMoney("1460", balance(acc, listOf(adjustment, late.copy(accountId = 1))))
    }

    @Test
    fun `a row dated exactly at the balance update still counts`() {
        val kept = BalanceNeutralFiling.offsets(
            listOf(move(tx(10, "40", debit, at = 500), 1)), mapOf(1L to account()), mapOf(1L to 500L),
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `a mixed batch keeps only the old rows neutral`() {
        val acc = account(opening = "100")
        val old = tx(10, "30", debit, at = 200)
        val recent = tx(11, "20", debit, at = 700)
        val edits = listOf(move(old, 1), move(recent, 1))
        val kept = BalanceNeutralFiling.offsets(edits, mapOf(1L to acc), mapOf(1L to 500L))

        val after = applied(listOf(acc), kept).getValue(1)
        // Only the recent 20 lands on the balance.
        assertMoney("80", balance(after, edits.map { it.second }))
        assertEquals(1, kept.single().rowCount)
    }

    @Test
    fun `with no adjustment, rows older than the account are neutral and newer ones count`() {
        val acc = account(opening = "500", createdAt = 1_000)
        val edits = listOf(move(tx(10, "75", debit, at = 900), 1), move(tx(11, "25", debit, at = 1_100), 1))
        val kept = BalanceNeutralFiling.offsets(edits, mapOf(1L to acc), emptyMap())

        assertMoney("475", balance(applied(listOf(acc), kept).getValue(1), edits.map { it.second }))
    }

    @Test
    fun `moving an old row out of an account leaves that account's balance alone`() {
        val acc = account(opening = "1000")
        val wrong = tx(10, "250", debit, at = 200, accountId = 1)
        val adjustment = tx(2, "50", credit, at = 500, accountId = 1)
        val before = balance(acc, listOf(wrong, adjustment))

        val kept = BalanceNeutralFiling.offsets(listOf(move(wrong, null)), mapOf(1L to acc), mapOf(1L to 500L))

        assertMoney(before.toPlainString(), balance(applied(listOf(acc), kept).getValue(1), listOf(adjustment)))
    }

    @Test
    fun `an old row moved between two accounts keeps both balances`() {
        val a = account(id = 1, opening = "1000")
        val b = account(id = 2, opening = "300")
        val row = tx(10, "90", debit, at = 200, accountId = 1)
        val accounts = mapOf(1L to a, 2L to b)
        val kept = BalanceNeutralFiling.offsets(listOf(move(row, 2)), accounts, mapOf(1L to 500L, 2L to 500L))

        val after = applied(listOf(a, b), kept)
        val moved = listOf(row.copy(accountId = 2))
        assertMoney(balance(a, listOf(row)).toPlainString(), balance(after.getValue(1), moved))
        assertMoney("300", balance(after.getValue(2), moved))
    }

    @Test
    fun `filing then unfiling returns the opening balance to where it started`() {
        val acc = account(opening = "1000")
        val row = tx(10, "64.25", debit, at = 200)
        val filed = applied(
            listOf(acc),
            BalanceNeutralFiling.offsets(listOf(move(row, 1)), mapOf(1L to acc), mapOf(1L to 500L)),
        ).getValue(1)
        val unfiled = applied(
            listOf(filed),
            BalanceNeutralFiling.offsets(listOf(move(row.copy(accountId = 1), null)), mapOf(1L to filed), mapOf(1L to 500L)),
        ).getValue(1)

        assertMoney("1000", unfiled.openingBalance)
    }

    @Test
    fun `an amount edited in the same step uses each side's own value`() {
        val a = account(id = 1, opening = "0")
        val b = account(id = 2, opening = "0")
        val old = tx(10, "100", debit, at = 200, accountId = 1)
        val new = old.copy(accountId = 2, amount = BigDecimal("120"))
        val kept = BalanceNeutralFiling.offsets(listOf(old to new), mapOf(1L to a, 2L to b), mapOf(1L to 500L, 2L to 500L))

        assertMoney("-100", kept.first { it.accountId == 1L }.newOpeningBalance)
        assertMoney("120", kept.first { it.accountId == 2L }.newOpeningBalance)
    }

    // --- what is left alone ---

    @Test
    fun `an edit that keeps the account produces nothing`() {
        val row = tx(10, "100", debit, at = 200, accountId = 1)
        val kept = BalanceNeutralFiling.offsets(
            listOf(row to row.copy(amount = BigDecimal("90"))), mapOf(1L to account()), mapOf(1L to 500L),
        )
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `an unknown account is ignored`() {
        assertTrue(BalanceNeutralFiling.offsets(listOf(move(tx(10, "5", debit, at = 1), 9)), emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun `a foreign-currency row is not kept neutral and is reported`() {
        val acc = account(opening = "1000")
        val kept = BalanceNeutralFiling.offsets(
            listOf(move(tx(10, "50", debit, at = 200, currency = "USD"), 1), move(tx(11, "10", debit, at = 200), 1)),
            mapOf(1L to acc),
            mapOf(1L to 500L),
        ).single()

        assertEquals(1, kept.rowCount)
        assertEquals(1, kept.skippedForeign)
        // Only the AED 10 is given back.
        assertMoney("1010", kept.newOpeningBalance)
    }

    @Test
    fun `currency codes compare without regard to case`() {
        val kept = BalanceNeutralFiling.offsets(
            listOf(move(tx(10, "10", debit, at = 200, currency = "aed"), 1)), mapOf(1L to account()), mapOf(1L to 500L),
        )
        assertEquals(1, kept.single().rowCount)
    }

    // --- wording ---

    @Test
    fun `the summary counts rows in plain words`() {
        assertEquals("1 older transaction filed, balance kept", BalanceNeutralFiling.summary(1))
        assertEquals("12 older transactions filed, balance kept", BalanceNeutralFiling.summary(12))
    }

    @Test
    fun `the notice names the one account whose balance was kept`() {
        val kept = listOf(BalanceNeutralFiling.Kept(1, BigDecimal.ZERO, BigDecimal.TEN, rowCount = 12))
        assertEquals(
            "Filed 12 older transactions. ADCB Current's balance was kept.",
            BalanceNeutralFiling.notice(kept) { "ADCB Current" },
        )
    }

    @Test
    fun `the notice covers several accounts without naming one`() {
        val kept = listOf(
            BalanceNeutralFiling.Kept(1, BigDecimal.ZERO, BigDecimal.TEN, rowCount = 1),
            BalanceNeutralFiling.Kept(2, BigDecimal.ZERO, BigDecimal.TEN, rowCount = 2),
        )
        assertEquals("Filed 3 older transactions. Balances were kept.", BalanceNeutralFiling.notice(kept) { "x" })
    }

    @Test
    fun `no notice when only foreign rows were involved`() {
        val kept = listOf(BalanceNeutralFiling.Kept(1, BigDecimal.ZERO, BigDecimal.ZERO, rowCount = 0, skippedForeign = 2))
        assertNull(BalanceNeutralFiling.notice(kept) { "x" })
    }

    // --- the log row ---

    @Test
    fun `the log entry records the opening balance shift and cancels the moved-in deltas`() {
        val acc = account(opening = "1000")
        val kept = BalanceNeutralFiling.Kept(1, BigDecimal("1000"), BigDecimal("200.50"), rowCount = 3)
        val change = AccountChangeDiff.historyFiled(acc, kept, nowMillis = 900)

        assertEquals(AccountChangeType.OPENING_BALANCE_CHANGED, change.type)
        assertEquals(AccountChangeSource.HISTORY_FILING, change.source)
        assertEquals("1000", change.oldValue)
        assertEquals("200.5", change.newValue)
        assertMoney("-799.50", change.balanceDelta!!)

        val line = AccountChangeWording.line(change)
        assertEquals("3 older transactions filed, balance kept", line.headline)
        assertEquals("MMbank, to keep your balance", line.by)
        assertTrue(line.sensitive)
    }

    @Test
    fun `an ordinary opening balance edit keeps its own wording`() {
        val change = AccountChangeDiff.historyFiled(
            account(), BalanceNeutralFiling.Kept(1, BigDecimal.ZERO, BigDecimal.ONE, 1), 1,
        ).copy(source = AccountChangeSource.USER)
        assertEquals("Opening balance changed", AccountChangeWording.line(change).headline)
    }
}
