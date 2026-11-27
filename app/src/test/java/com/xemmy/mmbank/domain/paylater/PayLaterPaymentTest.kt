package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.AssignmentSource
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.paylater.PayLaterPayment.Shape
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterPaymentTest {

    private val from = TransferLegs.Endpoint(1, "Current", AccountType.CHECKING, "AED")
    private val tabby = TransferLegs.Endpoint(2, "Tabby", AccountType.CREDIT_CARD, "AED")

    private fun plan(source: Long? = null) = PayLaterPlan(
        id = 1, merchant = "Carrefour", totalAmount = BigDecimal("327.32"), currencyCode = "AED",
        installmentCount = 4, sourceTransactionId = source, categoryId = 6, createdAt = 0,
    )

    private val installment = PayLaterInstallment(id = 10, planId = 1, number = 2, amount = BigDecimal("81.83"), dueDateMillis = 0)

    @Test
    fun `a never captured purchase is recorded as ordinary spending on the merchant`() {
        val legs = PayLaterPayment.build(plan(source = null), installment, from, tabby, paidAtMillis = 5)
        assertEquals(Shape.SPEND, legs.shape)
        assertNull(legs.counterpart)
        val tx = legs.paying
        assertEquals(1L, tx.accountId)
        assertEquals(TransactionDirection.DEBIT, tx.direction)
        assertEquals(TransactionKind.SPEND, tx.kind)
        assertEquals("Carrefour", tx.merchant)
        assertEquals(6L, tx.categoryId)
        assertFalse(tx.excludeFromStats)
        assertEquals(BigDecimal("81.83"), tx.amount)
        assertEquals(5L, tx.timestampMillis)
        assertEquals("Carrefour · Payment 2 of 4", tx.note)
    }

    @Test
    fun `a captured purchase with a provider account is a transfer pair`() {
        val legs = PayLaterPayment.build(plan(source = 99), installment, from, tabby, paidAtMillis = 5)
        assertEquals(Shape.TRANSFER_PAIR, legs.shape)
        val out = legs.paying
        val inn = legs.counterpart
        assertNotNull(inn)
        assertEquals(1L, out.accountId)
        assertEquals(TransactionDirection.DEBIT, out.direction)
        assertEquals(2L, inn!!.accountId)
        assertEquals(TransactionDirection.CREDIT, inn.direction)
        assertTrue(out.kind.isInternalMove)
        assertEquals(TransactionKind.CARD_PAYMENT, out.kind)
        assertEquals(BigDecimal("81.83"), inn.amount)
    }

    @Test
    fun `a captured purchase without a provider account is a debit hidden from analytics`() {
        val legs = PayLaterPayment.build(plan(source = 99), installment, from, null, paidAtMillis = 5)
        assertEquals(Shape.EXCLUDED_DEBIT, legs.shape)
        assertNull(legs.counterpart)
        assertTrue(legs.paying.excludeFromStats)
        assertEquals(TransactionDirection.DEBIT, legs.paying.direction)
    }

    @Test
    fun `shape follows whether the purchase was captured and a provider account is tracked`() {
        assertEquals(Shape.TRANSFER_PAIR, PayLaterPayment.shapeOf(plan(source = 1), hasProviderAccount = true))
        assertEquals(Shape.EXCLUDED_DEBIT, PayLaterPayment.shapeOf(plan(source = 1), hasProviderAccount = false))
        assertEquals(Shape.SPEND, PayLaterPayment.shapeOf(plan(source = null), hasProviderAccount = true))
    }

    @Test
    fun `marking paid stamps the payment and undoing clears both fields`() {
        val paid = PayLaterPayment.markPaid(installment, paymentTransactionId = 77, paidAtMillis = 123)
        assertTrue(paid.isPaid)
        assertEquals(77L, paid.paymentTransactionId)
        assertEquals(123L, paid.paidAtMillis)
        val undone = PayLaterPayment.markUnpaid(paid)
        assertFalse(undone.isPaid)
        assertNull(undone.paymentTransactionId)
        assertEquals(installment, undone)
    }

    @Test
    fun `later payments of a plan whose charge was one installment are ordinary spending`() {
        val p = plan(source = 40).copy(sourceIsInstallment = true)
        assertEquals(Shape.SPEND, PayLaterPayment.shapeOf(p, hasProviderAccount = true))
        val legs = PayLaterPayment.build(p, installment, from, tabby, paidAtMillis = 5)
        assertEquals(Shape.SPEND, legs.shape)
        assertEquals(false, legs.paying.excludeFromStats)
        assertNull(legs.counterpart)
    }

    // --- 0.140.4: what an auto-matched installment SMS becomes ---

    @Test
    fun `an installment SMS of a full-price plan is not counted as spending again`() {
        // Captured purchase, no provider account: the SMS is hidden from spending.
        assertEquals(Shape.EXCLUDED_DEBIT, PayLaterPayment.matchedShape(plan(source = 7), matchedAccountId = 1))
        // Captured purchase and a tracked provider account: the SMS becomes a transfer to it.
        assertEquals(Shape.TRANSFER_PAIR, PayLaterPayment.matchedShape(plan(source = 7).copy(payToAccountId = 3), matchedAccountId = 1))
    }

    @Test
    fun `an installment SMS stays spending when only one installment was counted`() {
        assertEquals(Shape.SPEND, PayLaterPayment.matchedShape(plan(source = 7).copy(sourceIsInstallment = true), matchedAccountId = 1))
        assertEquals(Shape.SPEND, PayLaterPayment.matchedShape(plan(source = null), matchedAccountId = 1))
    }

    // --- 0.143.0: a recorded payment is filed like the rest of its split ---

    @Test
    fun `a recorded payment takes the purchase's category and person as the plan's`() {
        val attribution = PayLaterAttribution.Attribution(categoryId = 3, person = "Sara")
        val spend = PayLaterPayment.build(plan(source = null), installment, from, null, 5, attribution, defaultPerson = "Me").paying
        assertEquals(3L, spend.categoryId)
        assertEquals(AssignmentSource.PLAN, spend.categorySource)
        assertEquals("Sara", spend.person)
        assertEquals(AssignmentSource.PLAN, spend.personSource)
        // Hidden from spending, it is still filed the same way.
        val hidden = PayLaterPayment.build(plan(source = 99), installment, from, null, 5, attribution).paying
        assertTrue(hidden.excludeFromStats)
        assertEquals(3L, hidden.categoryId)
        assertEquals("Sara", hidden.person)
    }

    @Test
    fun `with no person to follow a recorded payment takes the default person, as a default`() {
        val attribution = PayLaterAttribution.Attribution(categoryId = null, person = null)
        val tx = PayLaterPayment.build(plan(source = null), installment, from, null, 5, attribution, defaultPerson = " Me ").paying
        assertEquals("Me", tx.person)
        assertEquals(AssignmentSource.DEFAULT, tx.personSource)
        assertNull(tx.categoryId)
        assertEquals(AssignmentSource.DEFAULT, tx.categorySource)
    }

    @Test
    fun `without an attribution a recorded payment still follows the plan's own category`() {
        val tx = PayLaterPayment.build(plan(source = null), installment, from, null, paidAtMillis = 5).paying
        assertEquals(6L, tx.categoryId)
        assertEquals(AssignmentSource.PLAN, tx.categorySource)
        assertNull(tx.person)
    }

    @Test
    fun `an unassigned SMS or one on the provider account is hidden rather than made a transfer`() {
        val withProvider = plan(source = 7).copy(payToAccountId = 3)
        assertEquals(Shape.EXCLUDED_DEBIT, PayLaterPayment.matchedShape(withProvider, matchedAccountId = null))
        assertEquals(Shape.EXCLUDED_DEBIT, PayLaterPayment.matchedShape(withProvider, matchedAccountId = 3))
    }
}
