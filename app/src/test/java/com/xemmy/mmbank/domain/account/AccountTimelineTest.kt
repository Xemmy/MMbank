package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTimelineTest {

    private val account = Account(
        id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED",
        openingBalance = BigDecimal("1000"), createdAt = 0,
    )

    private fun tx(
        id: Long,
        amount: String,
        direction: TransactionDirection,
        at: Long,
        accountId: Long? = 1,
        currency: String = "AED",
    ) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal(amount), currencyCode = currency,
        direction = direction, merchant = "m$id", source = TransactionSource.MANUAL, timestampMillis = at,
    )

    private fun change(
        id: Long,
        type: AccountChangeType,
        at: Long,
        delta: String? = null,
        accountId: Long = 1,
        old: String? = null,
        new: String? = null,
    ) = AccountChange(
        id = id, accountId = accountId, accountName = "Current", timestampMillis = at, type = type,
        oldValue = old, newValue = new, balanceDelta = delta?.let(::BigDecimal),
    )

    private val credit = TransactionDirection.CREDIT
    private val debit = TransactionDirection.DEBIT

    @Test
    fun `the running balance replays transactions oldest first from the opening balance`() {
        val r = AccountTimeline.build(
            account,
            listOf(tx(3, "25", debit, 30), tx(1, "200", credit, 10), tx(2, "50", debit, 20)),
            emptyList(),
        )
        val items = r.items.filterIsInstance<AccountTimeline.TransactionItem>()
        // Newest first on screen...
        assertEquals(listOf(3L, 2L, 1L), items.map { it.transaction.id })
        // ...but the balance was built up oldest first.
        assertEquals(listOf("1125", "1150", "1200"), items.map { it.balanceAfter.toPlainString() })
        assertEquals(listOf("-25", "-50", "200"), items.map { it.delta.toPlainString() })
        assertEquals(BigDecimal("1125"), r.currentBalance)
        assertEquals(BigDecimal("1000"), r.openingBalance)
    }

    @Test
    fun `same-instant transactions replay in id order`() {
        val r = AccountTimeline.build(account, listOf(tx(2, "10", debit, 5), tx(1, "10", credit, 5)), emptyList())
        val items = r.items.filterIsInstance<AccountTimeline.TransactionItem>()
        assertEquals(listOf(2L, 1L), items.map { it.transaction.id })
        assertEquals(BigDecimal("1000"), items.first().balanceAfter)
    }

    @Test
    fun `other accounts and rows in no account are left out`() {
        val r = AccountTimeline.build(
            account,
            listOf(tx(1, "10", debit, 1), tx(2, "99", debit, 2, accountId = 2), tx(3, "77", debit, 3, accountId = null)),
            emptyList(),
        )
        assertEquals(1, r.items.size)
        assertEquals(BigDecimal("990"), r.currentBalance)
    }

    @Test
    fun `an empty account is just its opening balance`() {
        val r = AccountTimeline.build(account, emptyList(), emptyList())
        assertTrue(r.items.isEmpty())
        assertEquals(BigDecimal("1000"), r.currentBalance)
        assertEquals(0, r.changeCount)
        assertFalse(r.mixedCurrency)
    }

    @Test
    fun `another currency is flagged`() {
        val r = AccountTimeline.build(account, listOf(tx(1, "10", debit, 1, currency = "USD")), emptyList())
        assertTrue(r.mixedCurrency)
        assertFalse(AccountTimeline.build(account, listOf(tx(1, "10", debit, 1, currency = "aed")), emptyList()).mixedCurrency)
    }

    @Test
    fun `changes and transactions interleave newest first`() {
        val r = AccountTimeline.build(
            account,
            listOf(tx(1, "10", debit, 10), tx(2, "10", debit, 30)),
            listOf(change(5, AccountChangeType.OPENING_BALANCE_CHANGED, 20, "100")),
        )
        assertEquals(listOf("t2", "c5", "t1"), r.items.map { it.key })
    }

    @Test
    fun `a change at the same instant as a row sits above it`() {
        val r = AccountTimeline.build(
            account,
            listOf(tx(1, "10", debit, 10)),
            listOf(change(9, AccountChangeType.TRANSACTION_DELETED, 10, "5")),
        )
        assertEquals(listOf("c9", "t1"), r.items.map { it.key })
    }

    @Test
    fun `changes at the same instant keep the order they were written, newest first`() {
        val r = AccountTimeline.build(
            account,
            emptyList(),
            listOf(
                change(1, AccountChangeType.DETAILS_EDITED, 10),
                change(2, AccountChangeType.ARCHIVED, 10),
                change(3, AccountChangeType.RESTORED, 10),
            ),
        )
        assertEquals(listOf("c3", "c2", "c1"), r.items.map { it.key })
    }

    @Test
    fun `changes of other accounts are ignored and the count is per account`() {
        val r = AccountTimeline.build(
            account,
            emptyList(),
            listOf(change(1, AccountChangeType.ARCHIVED, 1), change(2, AccountChangeType.ARCHIVED, 2, accountId = 9)),
        )
        assertEquals(1, r.items.size)
        assertEquals(1, r.changeCount)
    }

    @Test
    fun `filters pick the right entries and only Everything shows transactions`() {
        val txs = listOf(tx(1, "10", debit, 1))
        val changes = listOf(
            change(1, AccountChangeType.OPENING_BALANCE_CHANGED, 2, "5"),
            change(2, AccountChangeType.DETAILS_EDITED, 3),
            change(3, AccountChangeType.BALANCE_ADJUSTED, 4, "7"),
            change(4, AccountChangeType.RETENTION_FOLDED, 5, "0"),
            change(5, AccountChangeType.ARCHIVED, 6),
        )
        fun keys(f: AccountTimeline.Filter) = AccountTimeline.build(account, txs, changes, f).items.map { it.key }.toSet()

        assertEquals(setOf("t1", "c1", "c2", "c3", "c4", "c5"), keys(AccountTimeline.Filter.ALL))
        assertEquals(setOf("c1", "c3"), keys(AccountTimeline.Filter.BALANCE))
        assertEquals(setOf("c1", "c2", "c5"), keys(AccountTimeline.Filter.EDITS))
        // The count ignores the filter, so the empty state can tell "nothing logged" from "nothing here".
        assertEquals(5, AccountTimeline.build(account, txs, changes, AccountTimeline.Filter.EDITS).changeCount)
    }

    @Test
    fun `a bulk move at one instant collapses into one line with the summed effect`() {
        val moves = (1L..3L).map {
            change(it, AccountChangeType.TRANSACTION_MOVED_IN, 50, "-100", old = "No account", new = "Current")
        }
        val r = AccountTimeline.build(account, emptyList(), moves)
        val item = r.items.single() as AccountTimeline.ChangeItem
        assertEquals(3, item.count)
        assertEquals(BigDecimal("-300"), item.delta)
        assertEquals("3 transactions moved in from No account", item.line.headline)
    }

    @Test
    fun `moves at different instants or from different accounts stay separate`() {
        val r = AccountTimeline.build(
            account,
            emptyList(),
            listOf(
                change(1, AccountChangeType.TRANSACTION_MOVED_IN, 50, "-1", old = "A", new = "Current"),
                change(2, AccountChangeType.TRANSACTION_MOVED_IN, 51, "-1", old = "A", new = "Current"),
                change(3, AccountChangeType.TRANSACTION_MOVED_IN, 51, "-1", old = "B", new = "Current"),
            ),
        )
        assertEquals(3, r.items.size)
        assertTrue(r.items.all { (it as AccountTimeline.ChangeItem).count == 1 })
    }

    @Test
    fun `bulk deletes collapse whatever the individual amounts were`() {
        val r = AccountTimeline.build(
            account,
            emptyList(),
            listOf(
                change(1, AccountChangeType.TRANSACTION_DELETED, 9, "10", old = "-10 AED"),
                change(2, AccountChangeType.TRANSACTION_DELETED, 9, "20", old = "-20 AED"),
            ),
        )
        val item = r.items.single() as AccountTimeline.ChangeItem
        assertEquals(2, item.count)
        assertEquals(BigDecimal("30"), item.delta)
        assertEquals("2 transactions deleted", item.line.headline)
    }

    @Test
    fun `a change with no stated effect has a null delta, and edits are never grouped`() {
        val r = AccountTimeline.build(
            account,
            emptyList(),
            listOf(
                change(1, AccountChangeType.DETAILS_EDITED, 5),
                change(2, AccountChangeType.TRANSACTION_EDITED, 5, "-3"),
                change(3, AccountChangeType.TRANSACTION_EDITED, 5, "-4"),
            ),
        )
        assertEquals(3, r.items.size)
        val byKey = r.items.associateBy { it.key }
        assertNull((byKey.getValue("c1") as AccountTimeline.ChangeItem).delta)
        assertEquals(BigDecimal("-3"), (byKey.getValue("c2") as AccountTimeline.ChangeItem).delta)
    }

    @Test
    fun `filter membership`() {
        val adjusted = change(1, AccountChangeType.BALANCE_ADJUSTED, 1)
        val archived = change(2, AccountChangeType.ARCHIVED, 1)
        assertTrue(AccountTimeline.Filter.ALL.matches(adjusted))
        assertTrue(AccountTimeline.Filter.BALANCE.matches(adjusted))
        assertFalse(AccountTimeline.Filter.EDITS.matches(adjusted))
        assertFalse(AccountTimeline.Filter.BALANCE.matches(archived))
        assertTrue(AccountTimeline.Filter.EDITS.matches(archived))
    }
}
