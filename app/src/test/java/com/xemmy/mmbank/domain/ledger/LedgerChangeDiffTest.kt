package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerChangeDiffTest {

    private val now = 1_700_000_000_000L
    private val names = mapOf(1L to "Current", 2L to "Savings")
    private val nameOf: (Long) -> String? = names::get

    private fun tx(
        amount: String = "100",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        account: Long? = 1,
        currency: String = "AED",
        timestamp: Long = 1_000,
    ) = Transaction(
        id = 42,
        accountId = account,
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = direction,
        merchant = "Carrefour",
        source = TransactionSource.MANUAL,
        timestampMillis = timestamp,
    )

    @Test
    fun `raising a debit lowers the balance by the difference`() {
        val c = LedgerChangeDiff.edited(tx("100"), tx("150"), nameOf, now).single()
        assertEquals(AccountChangeType.TRANSACTION_EDITED, c.type)
        assertEquals(1L, c.accountId)
        assertEquals("Current", c.accountName)
        assertEquals("amount", c.field)
        assertEquals("-100 AED", c.oldValue)
        assertEquals("-150 AED", c.newValue)
        assertEquals(BigDecimal("-50"), c.balanceDelta)
        assertEquals(42L, c.transactionId)
        assertEquals("Carrefour", c.note)
    }

    @Test
    fun `flipping direction swings the balance by twice the amount`() {
        val c = LedgerChangeDiff.edited(
            tx("100", TransactionDirection.DEBIT),
            tx("100", TransactionDirection.CREDIT),
            nameOf,
            now,
        ).single()
        assertEquals("direction", c.field)
        assertEquals(BigDecimal("200"), c.balanceDelta)
    }

    @Test
    fun `several balance fields share one entry`() {
        val c = LedgerChangeDiff.edited(tx("100", timestamp = 1), tx("90", timestamp = 2), nameOf, now).single()
        assertEquals("amount,date", c.field)
        assertEquals(BigDecimal("10"), c.balanceDelta)
    }

    @Test
    fun `a date-only edit has a zero effect`() {
        val c = LedgerChangeDiff.edited(tx(timestamp = 1), tx(timestamp = 2), nameOf, now).single()
        assertEquals("date", c.field)
        assertEquals(0, c.balanceDelta!!.signum())
    }

    @Test
    fun `a currency change has no stated delta`() {
        val c = LedgerChangeDiff.edited(tx(currency = "AED"), tx(currency = "USD"), nameOf, now).single()
        assertEquals("currency", c.field)
        assertNull(c.balanceDelta)
        assertEquals("USD", c.currencyCode)
    }

    @Test
    fun `edits that cannot move a balance log nothing`() {
        val old = tx()
        val new = old.copy(merchant = "Lulu", person = "Sam", spendingCategory = SpendingCategory.DINING, excludeFromStats = true)
        assertTrue(LedgerChangeDiff.edited(old, new, nameOf, now).isEmpty())
    }

    @Test
    fun `a row in no account logs nothing when edited in place`() {
        assertTrue(LedgerChangeDiff.edited(tx(account = null), tx("200", account = null), nameOf, now).isEmpty())
    }

    @Test
    fun `moving a debit credits the old account and debits the new one`() {
        val changes = LedgerChangeDiff.edited(tx("100", account = 1), tx("100", account = 2), nameOf, now)
        assertEquals(listOf(AccountChangeType.TRANSACTION_MOVED_OUT, AccountChangeType.TRANSACTION_MOVED_IN), changes.map { it.type })
        val out = changes[0]
        val inn = changes[1]
        assertEquals(1L, out.accountId)
        assertEquals(BigDecimal("100"), out.balanceDelta)
        assertEquals("Current", out.oldValue)
        assertEquals("Savings", out.newValue)
        assertEquals(2L, inn.accountId)
        assertEquals(BigDecimal("-100"), inn.balanceDelta)
        assertEquals("Savings", inn.accountName)
    }

    @Test
    fun `moving a credit is the mirror image`() {
        val credit = TransactionDirection.CREDIT
        val changes = LedgerChangeDiff.edited(tx("30", credit, 1), tx("30", credit, 2), nameOf, now)
        assertEquals(BigDecimal("-30"), changes[0].balanceDelta)
        assertEquals(BigDecimal("30"), changes[1].balanceDelta)
    }

    @Test
    fun `an unassigned row assigned to an account only moves in`() {
        val changes = LedgerChangeDiff.edited(tx(account = null), tx(account = 2), nameOf, now)
        assertEquals(1, changes.size)
        assertEquals(AccountChangeType.TRANSACTION_MOVED_IN, changes[0].type)
        assertEquals("No account", changes[0].oldValue)
    }

    @Test
    fun `an account cleared from a row only moves out`() {
        val changes = LedgerChangeDiff.edited(tx(account = 1), tx(account = null), nameOf, now)
        assertEquals(1, changes.size)
        assertEquals(AccountChangeType.TRANSACTION_MOVED_OUT, changes[0].type)
        assertEquals("No account", changes[0].newValue)
    }

    @Test
    fun `a move with an amount edit uses each side's own amount`() {
        val changes = LedgerChangeDiff.edited(tx("100", account = 1), tx("80", account = 2), nameOf, now)
        assertEquals(BigDecimal("100"), changes[0].balanceDelta)
        assertEquals(BigDecimal("-80"), changes[1].balanceDelta)
    }

    @Test
    fun `an unknown account falls back to a readable name`() {
        val c = LedgerChangeDiff.edited(tx(account = 9), tx("5", account = 9), nameOf, now).single()
        assertEquals("Account 9", c.accountName)
    }

    @Test
    fun `deleting a debit gives the money back to the balance`() {
        val c = LedgerChangeDiff.deleted(tx("40"), nameOf, now).single()
        assertEquals(AccountChangeType.TRANSACTION_DELETED, c.type)
        assertEquals(BigDecimal("40"), c.balanceDelta)
        assertEquals("-40 AED", c.oldValue)
    }

    @Test
    fun `deleting a credit lowers the balance`() {
        val c = LedgerChangeDiff.deleted(tx("40", TransactionDirection.CREDIT), nameOf, now).single()
        assertEquals(BigDecimal("-40"), c.balanceDelta)
    }

    @Test
    fun `deleting a row in no account logs nothing`() {
        assertTrue(LedgerChangeDiff.deleted(tx(account = null), nameOf, now).isEmpty())
    }

    @Test
    fun `an adjustment logs its signed amount and reason`() {
        val adj = tx("25", TransactionDirection.CREDIT).copy(id = 0)
        val c = LedgerChangeDiff.adjusted(adj, nameOf, now, reason = "Cash count", note = "Found in jacket").single()
        assertEquals(AccountChangeType.BALANCE_ADJUSTED, c.type)
        assertEquals(BigDecimal("25"), c.balanceDelta)
        assertEquals("Cash count", c.reason)
        assertEquals("Found in jacket", c.note)
        // Not inserted yet, so no transaction id to point at.
        assertNull(c.transactionId)
    }

    @Test
    fun `demo rows log as demo`() {
        val demo = tx().copy(isDemo = true)
        assertTrue(LedgerChangeDiff.deleted(demo, nameOf, now).single().isDemo)
    }
}
