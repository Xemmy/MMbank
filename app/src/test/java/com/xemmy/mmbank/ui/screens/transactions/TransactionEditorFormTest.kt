package com.xemmy.mmbank.ui.screens.transactions

import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionEditorFormTest {
    private val zone = ZoneOffset.UTC
    private val now = LocalDateTime.of(2026, 3, 18, 14, 35, 42)
    private val aed = Account(id = 1, name = "Main", type = AccountType.CHECKING, currencyCode = "AED")
    private val usd = Account(id = 2, name = "Dollar", type = AccountType.SAVINGS, currencyCode = "USD")
    private val card = Account(id = 3, name = "Card", type = AccountType.CREDIT_CARD, currencyCode = "AED")
    private val accounts = listOf(aed, usd, card)

    private fun blank() = TransactionEditorForm.initial(null, "AED", zone, now)

    @Test
    fun `a blank form starts now without seconds and is not valid`() {
        val f = blank()
        assertEquals(LocalDate.of(2026, 3, 18), f.date)
        assertEquals(LocalTime.of(14, 35), f.time)
        assertEquals("AED", f.currency)
        assertFalse(f.valid(accounts, isEdit = false, zone = zone))
    }

    @Test
    fun `amount and currency decide validity for an ordinary entry`() {
        val f = blank().copy(amount = "12.50")
        assertTrue(f.valid(accounts, false, zone))
        assertFalse(f.copy(amount = "0").valid(accounts, false, zone))
        assertFalse(f.copy(amount = "abc").valid(accounts, false, zone))
        assertFalse(f.copy(currency = "").valid(accounts, false, zone))
        assertFalse(f.copy(currency = "A").valid(accounts, false, zone))
    }

    @Test
    fun `kind decides direction, a two-way kind keeps the saved side`() {
        assertEquals(TransactionDirection.DEBIT, blank().direction(null))
        assertEquals(TransactionDirection.CREDIT, blank().copy(kind = TransactionKind.INCOME).direction(null))
        val credit = existing(kind = TransactionKind.ADJUSTMENT, direction = TransactionDirection.CREDIT)
        assertEquals(TransactionDirection.CREDIT, blank().copy(kind = TransactionKind.ADJUSTMENT).direction(credit))
    }

    @Test
    fun `adding a transfer needs two different accounts or one tracked side`() {
        val f = blank().copy(amount = "100", kind = TransactionKind.TRANSFER)
        assertTrue(f.transferMode(isEdit = false))
        assertFalse(f.transferMode(isEdit = true))
        assertEquals("Pick at least one account", f.transferError(accounts, false, zone))
        assertFalse(f.valid(accounts, false, zone))
        assertNull(f.copy(fromAccountId = 1, toAccountId = 3).transferError(accounts, false, zone))
        assertEquals("From and To must be different accounts", f.copy(fromAccountId = 1, toAccountId = 1).transferError(accounts, false, zone))
        assertTrue(f.copy(fromAccountId = 1).valid(accounts, false, zone))
    }

    @Test
    fun `a cross-currency transfer needs the received amount`() {
        val f = blank().copy(amount = "100", kind = TransactionKind.TRANSFER, fromAccountId = 1, toAccountId = 2)
        assertTrue(f.crossCurrency(accounts, false))
        assertEquals("Enter the amount received", f.transferError(accounts, false, zone))
        assertFalse(f.valid(accounts, false, zone))
        val ok = f.copy(toAmount = "27.2")
        assertNull(ok.transferError(accounts, false, zone))
        assertEquals(BigDecimal("27.2"), ok.transferDraft(accounts, false, zone)!!.toAmount)
        assertFalse(f.copy(toAccountId = 3).crossCurrency(accounts, false))
    }

    @Test
    fun `editing never uses transfer mode, so the currency field still counts`() {
        val f = blank().copy(amount = "5", kind = TransactionKind.TRANSFER, currency = "")
        assertFalse(f.valid(accounts, isEdit = true, zone = zone))
        assertTrue(f.copy(currency = "AED").valid(accounts, isEdit = true, zone = zone))
    }

    @Test
    fun `editing keeps the exact saved time of day and fields`() {
        val saved = existing(timestamp = LocalDateTime.of(2026, 1, 5, 9, 7, 31).toInstant(zone).toEpochMilli(), merchant = "Careem", categoryId = 4L, person = "Sam")
        val f = TransactionEditorForm.initial(saved, "AED", zone, now)
        assertEquals(LocalDate.of(2026, 1, 5), f.date)
        assertEquals(LocalTime.of(9, 7, 31), f.time)
        assertEquals("Careem", f.merchant)
        assertEquals(4L, f.categoryOverride)
        assertEquals("Sam", f.person)
        assertEquals(saved.timestampMillis, f.timestamp(zone))
    }

    @Test
    fun `changing the date or time changes the timestamp`() {
        val f = blank()
        val moved = f.copy(date = LocalDate.of(2026, 3, 1), time = LocalTime.of(8, 0))
        assertEquals(LocalDateTime.of(2026, 3, 1, 8, 0).toInstant(zone).toEpochMilli(), moved.timestamp(zone))
    }

    @Test
    fun `saveable round-trip keeps every field`() {
        val f = TransactionEditorForm(
            amount = "12.5", currency = "USD", kind = TransactionKind.TRANSFER, merchant = "x", accountId = 7, person = "Sam",
            categoryOverride = 9, excludeFromStats = true, date = LocalDate.of(2026, 2, 3), time = LocalTime.of(1, 2, 3),
            fromAccountId = 1, toAccountId = 2, toAmount = "3", outside = TransferLegs.OutsideParty.SOMEONE_ELSE,
        )
        assertEquals(f, TransactionEditorForm.fromSaveable(f.toSaveable()))
        assertEquals(blank(), TransactionEditorForm.fromSaveable(blank().toSaveable()))
    }

    @Test
    fun `a damaged saved state restores to null instead of crashing`() {
        assertNull(TransactionEditorForm.fromSaveable(listOf("only one")))
        assertNotNull(TransactionEditorForm.fromSaveable(blank().toSaveable()))
    }

    private fun existing(
        kind: TransactionKind = TransactionKind.SPEND,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        timestamp: Long = 0L,
        merchant: String? = null,
        categoryId: Long? = null,
        person: String? = null,
    ) = Transaction(
        amount = BigDecimal("10"), currencyCode = "AED", direction = direction, source = TransactionSource.MANUAL,
        timestampMillis = timestamp, kind = kind, merchant = merchant, categoryId = categoryId, person = person,
    )
}
