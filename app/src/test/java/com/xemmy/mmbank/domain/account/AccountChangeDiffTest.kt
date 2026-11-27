package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import com.xemmy.mmbank.domain.model.AccountType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountChangeDiffTest {

    private val now = 1_700_000_000_000L

    private fun account(
        opening: String = "1000",
        name: String = "ADCB Current",
    ) = Account(
        id = 7,
        name = name,
        type = AccountType.CHECKING,
        currencyCode = "AED",
        openingBalance = BigDecimal(opening),
        createdAt = 0,
    )

    @Test
    fun `a new account logs where its balance starts`() {
        val c = AccountChangeDiff.created(account("1000"), now)
        assertEquals(AccountChangeType.CREATED, c.type)
        assertEquals(7L, c.accountId)
        assertEquals("ADCB Current", c.accountName)
        assertEquals(BigDecimal("1000"), c.balanceDelta)
        assertEquals("1000", c.newValue)
        assertEquals("AED", c.currencyCode)
        assertEquals(now, c.timestampMillis)
        assertEquals(AccountChangeSource.USER, c.source)
    }

    @Test
    fun `an opening balance edit carries the exact delta`() {
        val changes = AccountChangeDiff.diff(account("1000"), account("1250.50"), now)
        assertEquals(1, changes.size)
        val c = changes.single()
        assertEquals(AccountChangeType.OPENING_BALANCE_CHANGED, c.type)
        assertEquals("openingBalance", c.field)
        assertEquals("1000", c.oldValue)
        assertEquals("1250.5", c.newValue)
        assertEquals(BigDecimal("250.50"), c.balanceDelta)
    }

    @Test
    fun `a lowered opening balance has a negative delta`() {
        val c = AccountChangeDiff.diff(account("1000"), account("400"), now).single()
        assertEquals(BigDecimal("-600"), c.balanceDelta)
    }

    @Test
    fun `numerically equal amounts are not an edit`() {
        assertTrue(AccountChangeDiff.diff(account("100"), account("100.00"), now).isEmpty())
    }

    @Test
    fun `detail edits are one entry per field in a stable order`() {
        val old = account()
        val new = old.copy(name = "ADCB Salary", iban = "AE070331234567890123456", cardEndings = "1234, 5678")
        val changes = AccountChangeDiff.diff(old, new, now)
        assertEquals(listOf("name", "cardEndings", "iban"), changes.map { it.field })
        assertTrue(changes.all { it.type == AccountChangeType.DETAILS_EDITED })
        val name = changes.first()
        assertEquals("ADCB Current", name.oldValue)
        assertEquals("ADCB Salary", name.newValue)
        // The snapshot carries the name as it is after the edit.
        assertEquals("ADCB Salary", name.accountName)
        assertNull(name.balanceDelta)
    }

    @Test
    fun `blank and null are the same value`() {
        val old = account().copy(notes = null, bankName = "  ")
        val new = account().copy(notes = "", bankName = null)
        assertTrue(AccountChangeDiff.diff(old, new, now).isEmpty())
    }

    @Test
    fun `display-only settings are not logged`() {
        val old = account()
        val new = old.copy(accentColor = 0xFF112233.toInt(), hiddenFromDashboard = true)
        assertTrue(AccountChangeDiff.diff(old, new, now).isEmpty())
    }

    @Test
    fun `a currency change is logged`() {
        val c = AccountChangeDiff.diff(account(), account().copy(currencyCode = "usd"), now).single()
        assertEquals("currency", c.field)
        assertEquals("AED", c.oldValue)
        assertEquals("USD", c.newValue)
    }

    @Test
    fun `archive flips become ARCHIVED and RESTORED`() {
        val a = account()
        assertEquals(AccountChangeType.ARCHIVED, AccountChangeDiff.diff(a, a.copy(archived = true), now).single().type)
        assertEquals(AccountChangeType.RESTORED, AccountChangeDiff.diff(a.copy(archived = true), a, now).single().type)
    }

    @Test
    fun `long free text is truncated`() {
        val c = AccountChangeDiff.diff(account(), account().copy(notes = "x".repeat(500)), now).single()
        assertEquals(AccountChangeDiff.MAX_VALUE_LENGTH, c.newValue!!.length)
    }

    @Test
    fun `opening balance and details edited together give separate entries`() {
        val changes = AccountChangeDiff.diff(account("1000"), account("900").copy(name = "Renamed"), now)
        assertEquals(
            listOf(AccountChangeType.OPENING_BALANCE_CHANGED, AccountChangeType.DETAILS_EDITED),
            changes.map { it.type },
        )
    }

    @Test
    fun `demo accounts log as demo`() {
        val demo = account().copy(isDemo = true)
        assertTrue(AccountChangeDiff.created(demo, now).isDemo)
        assertTrue(AccountChangeDiff.diff(demo, demo.copy(name = "x"), now).single().isDemo)
    }

    @Test
    fun `a retention fold records the opening move but a zero balance effect`() {
        val adj = PrunedBalanceFolder.Adjustment(
            accountId = 7,
            previousOpeningBalance = BigDecimal("1000"),
            newOpeningBalance = BigDecimal("1350.25"),
            foldedNet = BigDecimal("350.25"),
        )
        val c = AccountChangeDiff.retentionFolded(account(), adj, now)
        assertEquals(AccountChangeType.RETENTION_FOLDED, c.type)
        assertEquals(AccountChangeSource.RETENTION, c.source)
        assertEquals("1000", c.oldValue)
        assertEquals("1350.25", c.newValue)
        assertEquals(0, c.balanceDelta!!.signum())
        assertTrue(c.note!!.contains("350.25"))
    }

    @Test
    fun `deleted keeps the name and a backup restore is marked as such`() {
        val d = AccountChangeDiff.deleted(account(), now)
        assertEquals(AccountChangeType.DELETED, d.type)
        assertEquals("ADCB Current", d.oldValue)

        val r = AccountChangeDiff.backupRestored(account("55"), now)
        assertEquals(AccountChangeType.BACKUP_RESTORED, r.type)
        assertEquals(AccountChangeSource.BACKUP_RESTORE, r.source)
        assertEquals("55", r.newValue)
    }

    @Test
    fun `only some types can move a balance`() {
        assertTrue(AccountChangeType.OPENING_BALANCE_CHANGED.canMoveBalance)
        assertTrue(AccountChangeType.TRANSACTION_DELETED.canMoveBalance)
        assertTrue(!AccountChangeType.DETAILS_EDITED.canMoveBalance)
        assertTrue(!AccountChangeType.RETENTION_FOLDED.canMoveBalance)
    }
}
