package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountChangeWordingTest {

    private fun change(
        type: AccountChangeType,
        field: String? = null,
        old: String? = null,
        new: String? = null,
        note: String? = null,
        reason: String? = null,
        source: AccountChangeSource = AccountChangeSource.USER,
    ) = AccountChange(
        id = 1, accountId = 1, accountName = "Current", timestampMillis = 0, type = type, source = source,
        field = field, oldValue = old, newValue = new, balanceDelta = BigDecimal("5"), note = note, reason = reason,
    )

    @Test
    fun `an account creation names the opening balance`() {
        val l = AccountChangeWording.line(change(AccountChangeType.CREATED, new = "1000"))
        assertEquals("Account created", l.headline)
        assertEquals("1000", l.to)
        assertEquals("Opening balance", l.extra)
        assertTrue(l.sensitive)
    }

    @Test
    fun `a name edit is not sensitive but a credit limit edit is`() {
        val name = AccountChangeWording.line(change(AccountChangeType.DETAILS_EDITED, "name", "A", "B"))
        assertEquals("Name changed", name.headline)
        assertEquals("A", name.from)
        assertEquals("B", name.to)
        assertFalse(name.sensitive)

        val limit = AccountChangeWording.line(change(AccountChangeType.DETAILS_EDITED, "creditLimit", "5000", "8000"))
        assertEquals("Credit limit changed", limit.headline)
        assertTrue(limit.sensitive)
    }

    @Test
    fun `an unknown field and a raw epoch date are handled`() {
        assertEquals("Details changed", AccountChangeWording.line(change(AccountChangeType.DETAILS_EDITED, "zzz", "a", "b")).headline)
        val maturity = AccountChangeWording.line(change(AccountChangeType.DETAILS_EDITED, "maturity", "1700000000000", "1800000000000"))
        assertEquals("Maturity date changed", maturity.headline)
        assertNull(maturity.from)
        assertNull(maturity.to)
    }

    @Test
    fun `every known field has a label`() {
        listOf(
            "name", "type", "currency", "accountNumber", "cardEndings", "bankName", "accountTitle", "iban", "swift",
            "creditLimit", "statementDay", "dueDay", "outstandingBalance", "nextInstallment", "profitRate",
            "maturity", "notes", "loanNotes", "openingBalance",
        ).forEach { assertFalse(it, AccountChangeWording.fieldLabel(it) == "Details") }
        assertEquals("Details", AccountChangeWording.fieldLabel(null))
    }

    @Test
    fun `an opening balance change shows old and new`() {
        val l = AccountChangeWording.line(change(AccountChangeType.OPENING_BALANCE_CHANGED, "openingBalance", "1000", "1250"))
        assertEquals("Opening balance changed", l.headline)
        assertEquals("1000", l.from)
        assertEquals("1250", l.to)
        assertTrue(l.sensitive)
    }

    @Test
    fun `an adjustment reads its reason and note`() {
        val both = AccountChangeWording.line(change(AccountChangeType.BALANCE_ADJUSTED, reason = "CASH_COUNT", note = "Found in jacket"))
        assertEquals("Balance adjusted", both.headline)
        assertEquals("Cash count — Found in jacket", both.extra)
        assertFalse(both.sensitive)

        assertNull(AccountChangeWording.line(change(AccountChangeType.BALANCE_ADJUSTED)).extra)
        assertEquals("Cash count", AccountChangeWording.line(change(AccountChangeType.BALANCE_ADJUSTED, reason = "CASH_COUNT")).extra)
        // A reason from somewhere else (or a future version) still shows, as typed.
        assertEquals("Paid rent", AccountChangeWording.line(change(AccountChangeType.BALANCE_ADJUSTED, reason = "Paid rent")).extra)
    }

    @Test
    fun `archive restore and delete`() {
        assertEquals("Account archived", AccountChangeWording.line(change(AccountChangeType.ARCHIVED)).headline)
        assertEquals("Account restored", AccountChangeWording.line(change(AccountChangeType.RESTORED)).headline)
        val d = AccountChangeWording.line(change(AccountChangeType.DELETED, old = "Cash"))
        assertEquals("Account deleted", d.headline)
        assertEquals("Cash", d.from)
    }

    @Test
    fun `a transaction edit lists what changed`() {
        fun headline(field: String) = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_EDITED, field, "-1 AED", "-2 AED")).headline
        assertEquals("Transaction edited: amount", headline("amount"))
        assertEquals("Transaction edited: amount and date", headline("amount,date"))
        assertEquals("Transaction edited: amount, direction and date", headline("amount,direction,date"))
        assertEquals("Transaction edited", headline("mystery"))

        val l = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_EDITED, "amount", "-1 AED", "-2 AED", note = "Carrefour"))
        assertEquals("-1 AED", l.from)
        assertEquals("-2 AED", l.to)
        assertEquals("Carrefour", l.extra)
        assertTrue(l.sensitive)
    }

    @Test
    fun `a single move names the other account and carries the amount`() {
        val inn = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_MOVED_IN, old = "Current", new = "Savings", note = "-100 AED"))
        assertEquals("Transaction moved in from Current", inn.headline)
        assertEquals("-100 AED", inn.extra)
        assertTrue(inn.sensitive)

        val out = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_MOVED_OUT, old = "Current", new = null, note = "-100 AED"))
        assertEquals("Transaction moved out to another account", out.headline)
    }

    @Test
    fun `a bulk move collapses to a count without amounts`() {
        val inn = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_MOVED_IN, old = "No account", new = "Savings", note = "-100 AED"), count = 12)
        assertEquals("12 transactions moved in from No account", inn.headline)
        assertNull(inn.extra)
        assertFalse(inn.sensitive)

        val out = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_MOVED_OUT, old = "Savings", new = "Current"), count = 3)
        assertEquals("3 transactions moved out to Current", out.headline)
    }

    @Test
    fun `deletes read singly or as a count`() {
        val one = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_DELETED, old = "-40 AED", note = "Lulu"))
        assertEquals("Transaction deleted", one.headline)
        assertEquals("-40 AED", one.from)
        assertEquals("Lulu", one.extra)
        assertTrue(one.sensitive)

        val many = AccountChangeWording.line(change(AccountChangeType.TRANSACTION_DELETED, old = "-40 AED"), count = 5)
        assertEquals("5 transactions deleted", many.headline)
        assertNull(many.from)
        assertFalse(many.sensitive)
    }

    @Test
    fun `retention and restore lines`() {
        val fold = AccountChangeWording.line(
            change(AccountChangeType.RETENTION_FOLDED, "openingBalance", "1000", "1350", source = AccountChangeSource.RETENTION),
        )
        assertEquals("Old transactions folded into the opening balance", fold.headline)
        assertEquals("retention clean-up", fold.by)
        assertTrue(fold.sensitive)

        val restored = AccountChangeWording.line(
            change(AccountChangeType.BACKUP_RESTORED, new = "55", source = AccountChangeSource.BACKUP_RESTORE),
        )
        assertEquals("Restored from a backup", restored.headline)
        assertEquals("a backup restore", restored.by)
    }

    @Test
    fun `who made the change`() {
        assertEquals("you", AccountChangeWording.by(AccountChangeSource.USER))
        assertEquals("a suggestion from your messages", AccountChangeWording.by(AccountChangeSource.CAPTURE_SUGGESTION))
    }

    @Test
    fun `a headline never carries an amount`() {
        AccountChangeType.entries.forEach { type ->
            val l = AccountChangeWording.line(
                // Moves carry account names in old/new; the amounts live in the note and in edits' values.
                change(type, field = "amount", old = "Current", new = "Savings", note = "-555 AED"),
            )
            assertTrue("$type: ${l.headline}", l.headline.none { it.isDigit() })
        }
    }

    @Test
    fun `word lists join naturally`() {
        assertEquals("", AccountChangeWording.joinWords(emptyList()))
        assertEquals("a", AccountChangeWording.joinWords(listOf("a")))
        assertEquals("a and b", AccountChangeWording.joinWords(listOf("a", "b")))
        assertEquals("a, b and c", AccountChangeWording.joinWords(listOf("a", "b", "c")))
    }
}
