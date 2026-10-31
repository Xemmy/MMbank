package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.TransferLegs.Draft
import com.xemmy.mmbank.domain.ledger.TransferLegs.Endpoint
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLegsTest {

    private val current = Endpoint(1, "Current", AccountType.CHECKING, "AED")
    private val savings = Endpoint(2, "Savings", AccountType.SAVINGS, "AED")
    private val card = Endpoint(3, "ADCB Card", AccountType.CREDIT_CARD, "AED")
    private val usd = Endpoint(4, "USD Wallet", AccountType.CHECKING, "USD")

    private fun draft(from: Endpoint?, to: Endpoint?, amount: String = "2000", toAmount: String? = null) =
        Draft(from, to, BigDecimal(amount), toAmount?.let(::BigDecimal), timestampMillis = 42)

    @Test
    fun `a transfer between two accounts is a debit out and a credit in`() {
        val (out, inn) = TransferLegs.build(draft(current, savings), label = "Manual transfer")
        out!!; inn!!
        assertEquals(TransactionDirection.DEBIT, out.direction)
        assertEquals(1L, out.accountId)
        assertEquals(TransactionDirection.CREDIT, inn.direction)
        assertEquals(2L, inn.accountId)
        assertEquals(TransactionKind.TRANSFER, out.kind)
        assertEquals(TransactionKind.TRANSFER, inn.kind)
        assertTrue(out.kindUserSet && inn.kindUserSet)
        assertEquals(SpendingCategory.TRANSFERS, out.spendingCategory)
        assertEquals("Transfer to Savings", out.merchant)
        assertEquals("Transfer from Current", inn.merchant)
        assertEquals(42L, out.timestampMillis)
        // Neither leg is spending or income.
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.netSpendExact(listOf(out, inn))))
        assertEquals(0, BigDecimal.ZERO.compareTo(LedgerMath.incomeExact(listOf(out, inn))))
    }

    @Test
    fun `paying onto a credit card is a card payment`() {
        val d = draft(current, card, amount = "3600")
        assertEquals(TransactionKind.CARD_PAYMENT, TransferLegs.kindFor(d))
        val (out, inn) = TransferLegs.build(d, label = "x")
        assertEquals(TransactionKind.CARD_PAYMENT, out!!.kind)
        assertEquals("Card payment to ADCB Card", out.merchant)
        assertEquals(TransactionKind.CARD_PAYMENT, inn!!.kind)
    }

    @Test
    fun `a different currency on the receiving side uses the received amount`() {
        val (out, inn) = TransferLegs.build(draft(current, usd, amount = "367.30", toAmount = "100"), label = "x")
        assertEquals(BigDecimal("367.30"), out!!.amount)
        assertEquals("AED", out.currencyCode)
        assertEquals(BigDecimal("100"), inn!!.amount)
        assertEquals("USD", inn.currencyCode)
    }

    @Test
    fun `an outside endpoint yields a single leg`() {
        val (out, inn) = TransferLegs.build(draft(null, savings), label = "x")
        assertNull(out)
        assertNotNull(inn)
        assertEquals("Transfer from outside", inn!!.merchant)
    }

    @Test
    fun `a note replaces the generated description`() {
        val (out, _) = TransferLegs.build(draft(current, savings).copy(note = "  Rent pot  "), label = "x")
        assertEquals("Rent pot", out!!.merchant)
    }

    @Test
    fun `invalid drafts are rejected with a reason`() {
        assertEquals("Pick at least one account", TransferLegs.validate(draft(null, null)))
        assertEquals("From and To must be different accounts", TransferLegs.validate(draft(current, current)))
        assertEquals("Amount must be positive", TransferLegs.validate(draft(current, savings, amount = "0")))
        assertEquals("Received amount must be positive", TransferLegs.validate(draft(current, usd, toAmount = "-1")))
        assertNull(TransferLegs.validate(draft(current, savings)))
    }
}
