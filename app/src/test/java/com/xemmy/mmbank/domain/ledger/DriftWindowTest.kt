package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.account.AccountTimeline
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriftWindowTest {

    private val day = 24L * 60 * 60 * 1000
    private val account = Account(id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED", openingBalance = BigDecimal("1000"))
    private var nextId = 1L

    private fun tx(
        amount: String,
        at: Long,
        dir: TransactionDirection = TransactionDirection.DEBIT,
        printed: String? = null,
        kind: ReportedBalanceKind = ReportedBalanceKind.ACCOUNT_BALANCE,
        accountId: Long? = 1,
        currency: String = "AED",
    ) = Transaction(
        id = nextId++, accountId = accountId, amount = BigDecimal(amount), currencyCode = currency, direction = dir,
        source = TransactionSource.SMS, timestampMillis = at,
        reportedBalance = printed?.let(::BigDecimal), reportedBalanceKind = if (printed != null) kind else null,
    )

    private fun eq(expected: String, actual: BigDecimal) =
        assertEquals("expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))

    private fun change(at: Long, type: AccountChangeType = AccountChangeType.TRANSACTION_EDITED, accountId: Long = 1) =
        AccountChange(id = nextId++, accountId = accountId, accountName = "Current", timestampMillis = at, type = type)

    /** Day 1 and 2 match the bank; the day 3 row does not (the bank says 700, the books 800). */
    private val drifting = listOf(
        tx("100", 1 * day, printed = "900"),
        tx("100", 2 * day, printed = "800"),
        tx("100", 3 * day, printed = "700"),
        tx("100", 4 * day, printed = "500"),
    ).let { it.map { t -> if (t.timestampMillis == 3 * day) t.copy(amount = BigDecimal("0")) else t } }
        // Row 3 is recorded as 0 instead of 100, so books are 800 at day 3 while the bank says 700.

    @Test
    fun `finds the last match and the first mismatch`() {
        val w = DriftWindow.find(account, drifting)!!
        assertEquals(2 * day, w.fromMillis)
        assertEquals(3 * day, w.toMillis)
        // Bank 700 minus books 800.
        eq("-100", w.drift)
        assertEquals("AED", w.currencyCode)
    }

    @Test
    fun `a clean account has no window`() {
        val clean = listOf(tx("100", day, printed = "900"), tx("100", 2 * day, printed = "800"))
        assertNull(DriftWindow.find(account, clean))
    }

    @Test
    fun `a difference that was there from the start has no lower bound`() {
        val rows = listOf(tx("100", day, printed = "850"), tx("100", 2 * day, printed = "750"))
        val w = DriftWindow.find(account, rows)!!
        assertNull(w.fromMillis)
        assertEquals(day, w.toMillis)
        eq("-50", w.drift)
        assertTrue(w.containsEvent(0))
    }

    @Test
    fun `a difference that corrected itself and came back is measured from the latest run`() {
        // Mismatch at day 1, match at day 2, mismatch again at day 3 and 4.
        val rows = listOf(
            tx("100", day, printed = "950"),
            tx("0", 2 * day, printed = "900"),
            tx("100", 3 * day, printed = "750"),
            tx("100", 4 * day, printed = "650"),
        )
        val w = DriftWindow.find(account, rows)!!
        assertEquals(2 * day, w.fromMillis)
        assertEquals(3 * day, w.toMillis)
    }

    @Test
    fun `agrees with the integrity checker about the current difference`() {
        // One printed balance, so the checker's latest anchor and the window's first mismatch are the same row.
        val rows = listOf(tx("100", day, printed = "850"))
        val recon = IntegrityChecker.check(listOf(account), rows, nowMillis = 10 * day, retentionCutoffMillis = null)
            .reconciliations.getValue(1)
        assertEquals(IntegrityChecker.Status.DIFFERS, recon.status)
        eq(recon.drift!!.toPlainString(), DriftWindow.find(account, rows)!!.drift)

        val card = account.copy(type = AccountType.CREDIT_CARD, openingBalance = BigDecimal.ZERO, creditLimit = BigDecimal("5000"))
        val cardRows = listOf(tx("300", day, printed = "4300", kind = ReportedBalanceKind.AVAILABLE_CREDIT))
        val cardRecon = IntegrityChecker.check(listOf(card), cardRows, nowMillis = 10 * day, retentionCutoffMillis = null)
            .reconciliations.getValue(1)
        eq(cardRecon.drift!!.toPlainString(), DriftWindow.find(card, cardRows)!!.drift)
    }

    @Test
    fun `nothing to compare, mixed currency and cards without a limit give no window`() {
        assertNull(DriftWindow.find(account, listOf(tx("100", day))))
        assertNull(DriftWindow.find(account, listOf(tx("100", day, printed = "1"), tx("5", day, currency = "USD"))))
        val card = account.copy(type = AccountType.CREDIT_CARD, openingBalance = BigDecimal.ZERO)
        assertNull(DriftWindow.find(card, listOf(tx("100", day, printed = "4000", kind = ReportedBalanceKind.AVAILABLE_CREDIT))))
    }

    @Test
    fun `a card compares what is owed`() {
        val card = account.copy(type = AccountType.CREDIT_CARD, openingBalance = BigDecimal.ZERO, creditLimit = BigDecimal("5000"))
        val rows = listOf(
            tx("300", day, printed = "4700", kind = ReportedBalanceKind.AVAILABLE_CREDIT),
            tx("200", 2 * day, printed = "4300", kind = ReportedBalanceKind.AVAILABLE_CREDIT),
        )
        // Owed 300 then 500 by the books; the bank says 300 then 700: matches day 1, differs by 200 on day 2.
        val w = DriftWindow.find(card, rows)!!
        assertEquals(day, w.fromMillis)
        assertEquals(2 * day, w.toMillis)
        eq("-200", w.drift)
    }

    @Test
    fun `anchors older than retention are not used`() {
        val w = DriftWindow.find(account, drifting, retentionCutoffMillis = 3 * day)
        // Day 1 and 2 are gone, so day 3 is the earliest comparable point and nothing is known before it.
        assertNull(w!!.fromMillis)
        assertEquals(3 * day, w.toMillis)
    }

    @Test
    fun `summary counts the transactions in the window and the changes made since the last match`() {
        val w = DriftWindow.find(account, drifting)!!
        val others = drifting + tx("5", 3 * day - 1, accountId = 1) + tx("5", 3 * day, accountId = 2)
        val changes = listOf(
            change(1 * day),
            change(2 * day + 1),
            change(5 * day, AccountChangeType.OPENING_BALANCE_CHANGED),
            change(5 * day, AccountChangeType.ARCHIVED),
            change(5 * day, accountId = 2),
        )
        val s = DriftWindow.summarize(w, others, changes)
        // Day 3 row and the day 3 - 1ms row; not day 2 (the match), not another account's.
        assertEquals(2, s.transactionCount)
        // The edit at 2d+1 and the opening-balance change; the edit at day 1 is before the last match.
        assertEquals(2, s.changeCount)
        assertFalse(w.containsEvent(2 * day))
        assertTrue(w.containsEvent(3 * day))
    }

    @Test
    fun `describe says when and how much, and hides the sign with balances`() {
        val w = DriftWindow.find(account, drifting)!!
        val s = DriftWindow.Summary(1, 2)
        val shown = DriftWindow.describe(w, s, hidden = false, money = { v, c -> "$c ${v.toPlainString()}" }, date = { "d$it" })
        assertEquals(
            "A difference of −AED 100 appeared between d${2 * day} and d${3 * day}: 1 transaction in that time, 2 changes made since.",
            shown,
        )
        val hidden = DriftWindow.describe(w, s, hidden = true, money = { _, _ -> "••••" }, date = { "x" })
        assertTrue(hidden.startsWith("A difference of •••• appeared"))
        val fromStart = DriftWindow.describe(w.copy(fromMillis = null), DriftWindow.Summary(2, 1), false, { v, c -> "$c $v" }, { "x" })
        assertTrue(fromStart.contains("was there from the first bank balance (x)"))
        assertTrue(fromStart.contains("2 transactions") && fromStart.contains("1 change made"))
    }

    @Test
    fun `the timeline narrows to the window`() {
        val w = DriftWindow.find(account, drifting)!!
        val changes = listOf(change(day), change(5 * day))
        val full = AccountTimeline.build(account, drifting, changes)
        val narrowed = AccountTimeline.restrictTo(full, w)
        val txDays = narrowed.items.filterIsInstance<AccountTimeline.TransactionItem>().map { it.timestampMillis }
        assertEquals(listOf(3 * day), txDays)
        // The day-1 edit predates the last match; only the later one stays.
        assertEquals(1, narrowed.items.count { it is AccountTimeline.ChangeItem })
        // The running balance on the kept row is still the statement view.
        assertEquals(0, BigDecimal("800").compareTo(narrowed.items.filterIsInstance<AccountTimeline.TransactionItem>().single().balanceAfter))
    }
}
