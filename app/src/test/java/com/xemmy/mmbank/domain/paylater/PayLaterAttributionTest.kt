package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.AssignmentSource
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterAttributionTest {

    private fun tx(
        id: Long,
        merchant: String? = "Tabby",
        categoryId: Long? = null,
        categorySource: AssignmentSource = AssignmentSource.DEFAULT,
        person: String? = null,
        personSource: AssignmentSource = AssignmentSource.DEFAULT,
        rawMessage: String? = null,
        sender: String? = null,
    ) = Transaction(
        id = id, amount = BigDecimal("231.00"), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = 0L,
        categoryId = categoryId, categorySource = categorySource, person = person, personSource = personSource,
        rawMessage = rawMessage, sender = sender,
    )

    private fun plan(id: Long = 1, source: Long? = 100, categoryId: Long? = 8) = PayLaterPlan(
        id = id, merchant = "Noon", totalAmount = BigDecimal("924.00"), currencyCode = "AED",
        installmentCount = 4, sourceTransactionId = source, categoryId = categoryId, createdAt = 0,
    )

    private fun inst(id: Long, planId: Long, number: Int, payment: Long?) = PayLaterInstallment(
        id = id, planId = planId, number = number, amount = BigDecimal("231.00"), dueDateMillis = 0,
        paidAtMillis = payment?.let { 1L }, paymentTransactionId = payment,
    )

    // --- of ---

    @Test fun `the purchase's own category and person are what its payments follow`() {
        val purchase = tx(100, merchant = "Noon", categoryId = 3, person = " Sara ")
        val a = PayLaterAttribution.of(plan(categoryId = 8), purchase)
        assertEquals(3L, a.categoryId)
        assertEquals("Sara", a.person)
        assertTrue(a.hasPurchase)
    }

    @Test fun `an uncategorized purchase falls back to the plan's category and a blank person to none`() {
        val a = PayLaterAttribution.of(plan(categoryId = 8), tx(100, merchant = "Noon", person = "  "))
        assertEquals(8L, a.categoryId)
        assertNull(a.person)
    }

    @Test fun `a plan that splits no captured row follows only its own category`() {
        val a = PayLaterAttribution.of(plan(source = null, categoryId = 8), source = null)
        assertEquals(8L, a.categoryId)
        assertNull(a.person)
        assertFalse(a.hasPurchase)
    }

    // --- planOf / paymentIds ---

    @Test fun `a row belongs to the plan it is the purchase of, or the payment of`() {
        val plans = listOf(plan(id = 1, source = 100), plan(id = 2, source = 200))
        val installments = listOf(inst(10, 1, 1, payment = 101), inst(20, 2, 1, payment = 201), inst(21, 2, 2, payment = null))
        assertEquals(1L, PayLaterAttribution.planOf(100, plans, installments)?.id)
        assertEquals(1L, PayLaterAttribution.planOf(101, plans, installments)?.id)
        assertEquals(2L, PayLaterAttribution.planOf(201, plans, installments)?.id)
        assertNull(PayLaterAttribution.planOf(999, plans, installments))
        assertNull(PayLaterAttribution.planOf(0, plans, installments))
    }

    @Test fun `payment rows leave out the purchase even when it is installment one`() {
        // A quick split of a one-installment charge: the charge is both source and payment #1.
        val p = plan(id = 1, source = 100)
        val installments = listOf(
            inst(10, 1, 1, payment = 100), inst(11, 1, 2, payment = 102), inst(12, 1, 3, payment = null),
            inst(13, 1, 4, payment = 102), inst(20, 2, 1, payment = 300),
        )
        assertEquals(listOf(102L), PayLaterAttribution.paymentIds(p, installments))
    }

    // --- idsToStampCategory ---

    private val rows = listOf(
        tx(1, categoryId = 9, categorySource = AssignmentSource.LEARNED), // the "Tabby" guess
        tx(2, categoryId = 3, categorySource = AssignmentSource.PLAN), // already follows
        tx(3, categoryId = 7, categorySource = AssignmentSource.USER), // filed by hand
        tx(4, categoryId = 3, categorySource = AssignmentSource.KEYWORD), // right value, wrong source
        tx(5),
    )

    @Test fun `payments take the purchase's category except the ones the user filed by hand`() {
        val a = PayLaterAttribution.Attribution(categoryId = 3, person = null)
        assertEquals(listOf(1L, 4L, 5L), PayLaterAttribution.idsToStampCategory(rows, a))
    }

    @Test fun `re-filing the split overrides payments the user filed by hand`() {
        val a = PayLaterAttribution.Attribution(categoryId = 3, person = null)
        assertEquals(listOf(1L, 3L, 4L, 5L), PayLaterAttribution.idsToStampCategory(rows, a, overrideUser = true))
    }

    @Test fun `an uncategorized purchase clears only what the plan stamped, or everything when re-filed`() {
        val none = PayLaterAttribution.Attribution(categoryId = null, person = null)
        assertEquals(listOf(2L), PayLaterAttribution.idsToStampCategory(rows, none))
        assertEquals(listOf(1L, 2L, 3L, 4L), PayLaterAttribution.idsToStampCategory(rows, none, overrideUser = true))
    }

    // --- idsToStampPerson ---

    private val people = listOf(
        tx(1, person = "Me", personSource = AssignmentSource.DEFAULT),
        tx(2, person = "Sara", personSource = AssignmentSource.PLAN),
        tx(3, person = "Omar", personSource = AssignmentSource.USER),
        tx(4, person = "Sara", personSource = AssignmentSource.LEARNED),
        tx(5),
    )

    @Test fun `payments take the purchase's person except the ones the user set by hand`() {
        val a = PayLaterAttribution.Attribution(categoryId = null, person = "Sara")
        assertEquals(listOf(1L, 4L, 5L), PayLaterAttribution.idsToStampPerson(people, a))
        assertEquals(listOf(1L, 3L, 4L, 5L), PayLaterAttribution.idsToStampPerson(people, a, overrideUser = true))
    }

    @Test fun `a purchase with no person clears only what the plan stamped, or everything when re-filed`() {
        val none = PayLaterAttribution.Attribution(categoryId = null, person = null)
        assertEquals(listOf(2L), PayLaterAttribution.idsToStampPerson(people, none))
        assertEquals(listOf(1L, 2L, 3L, 4L), PayLaterAttribution.idsToStampPerson(people, none, overrideUser = true))
    }

    @Test fun `a plan with no purchase row never touches its payments' persons`() {
        val a = PayLaterAttribution.of(plan(source = null), source = null)
        assertEquals(emptyList<Long>(), PayLaterAttribution.idsToStampPerson(people, a, overrideUser = true))
    }

    // --- refileFromEdit ---

    @Test fun `a category picked on a payment becomes the purchase's and the plan's`() {
        val edited = tx(102, categoryId = 5, categorySource = AssignmentSource.USER, person = "Omar")
        val r = PayLaterAttribution.refileFromEdit(edited, plan(categoryId = 8), sourceId = 100, categoryChanged = true, personChanged = false)
        assertEquals(PayLaterAttribution.Change(5L), r.sourceCategory)
        assertNull(r.sourcePerson) // only the category was touched
        assertEquals(5L, r.planCategoryId)
    }

    @Test fun `a person set on a payment becomes the purchase's and leaves the plan's category`() {
        val edited = tx(102, categoryId = 5, person = " Omar ")
        val r = PayLaterAttribution.refileFromEdit(edited, plan(categoryId = 8), sourceId = 100, categoryChanged = false, personChanged = true)
        assertNull(r.sourceCategory)
        assertEquals(PayLaterAttribution.Change("Omar"), r.sourcePerson)
        assertEquals(8L, r.planCategoryId)
    }

    @Test fun `clearing on a payment clears the purchase too`() {
        val edited = tx(102, categoryId = null, person = null)
        val r = PayLaterAttribution.refileFromEdit(edited, plan(categoryId = 8), sourceId = 100, categoryChanged = true, personChanged = true)
        assertEquals(PayLaterAttribution.Change<Long>(null), r.sourceCategory)
        assertEquals(PayLaterAttribution.Change<String>(null), r.sourcePerson)
        assertNull(r.planCategoryId)
    }

    @Test fun `an edit on the purchase itself only keeps the plan's category in step`() {
        val edited = tx(100, merchant = "Noon", categoryId = 4, person = "Sara")
        val r = PayLaterAttribution.refileFromEdit(edited, plan(categoryId = 8), sourceId = 100, categoryChanged = true, personChanged = true)
        assertNull(r.sourceCategory)
        assertNull(r.sourcePerson)
        assertEquals(4L, r.planCategoryId)
    }

    @Test fun `a plan with no purchase row keeps its own category in step`() {
        val edited = tx(102, categoryId = 6)
        val r = PayLaterAttribution.refileFromEdit(edited, plan(source = null, categoryId = 8), sourceId = null, categoryChanged = true, personChanged = false)
        assertNull(r.sourceCategory)
        assertEquals(6L, r.planCategoryId)
    }

    // --- learnsMerchantRule ---

    @Test fun `a payment to Tabby teaches no merchant rule, however its bank names it`() {
        assertFalse(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "Tabby"), inSplit = false))
        assertFalse(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "TABBY FZ LLC"), inSplit = false))
        val unnamed = tx(1, merchant = null, rawMessage = "Purchase of AED 231.00 at Tabby with card ending 1234", sender = "ADIB")
        assertFalse(PayLaterAttribution.learnsMerchantRule(unnamed, inSplit = false))
    }

    @Test fun `ordinary shops and Tabby's own card and subscription still teach`() {
        assertTrue(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "Carrefour"), inSplit = false))
        assertTrue(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "Tabby Card"), inSplit = false))
        assertTrue(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "Tabby+"), inSplit = false))
    }

    @Test fun `a row of a split teaches no merchant rule because the split is re-filed instead`() {
        assertFalse(PayLaterAttribution.learnsMerchantRule(tx(1, merchant = "Noon"), inSplit = true))
    }
}
