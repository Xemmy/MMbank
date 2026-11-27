package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabbySplitPlanTest {

    private val zone = ZoneId.of("Asia/Dubai")

    private fun at(y: Int, m: Int, d: Int, h: Int = 13, min: Int = 26): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

    private fun tx(id: Long, amount: String, direction: TransactionDirection, at: Long, sender: String, raw: String, accountId: Long? = null) =
        Transaction(
            id = id, accountId = accountId, amount = BigDecimal(amount), currencyCode = "AED", direction = direction,
            source = TransactionSource.SMS, sender = sender, rawMessage = raw, timestampMillis = at, categoryId = if (id == 1L) 8 else null,
        )

    private fun checkout(at: Long) = tx(1, "924.00", TransactionDirection.DEBIT, at, "Tabby",
        "Your AED 924.00 purchase at Shory is confirmed. To pay as low as 77.00/month, extend your payment plan in the app")

    private fun receipt(at: Long) = tx(2, "231.00", TransactionDirection.CREDIT, at, "Tabby",
        "We received your first payment of AED 231.00. To pay ahead, open the Tabby app.")

    private fun charge(at: Long) = tx(3, "231.00", TransactionDirection.DEBIT, at, "ADIB",
        "Trx. of AED231.00 on your card ending *876 at Tabby, 8001234 UAE is Approved.", accountId = 5)

    private val today = at(2026, 11, 26)
    private val now = at(2026, 11, 26, 14, 0)

    private fun candidate(vararg rows: Transaction) =
        TabbySplitDetector.find(rows.toList(), emptyList(), emptyList(), emptySet(), zone).single()

    @Test fun `each payment when paid hides the purchase and links the first card charge`() {
        val result = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today), charge(today)),
            mode = TabbySplitPlan.Mode.PER_PAYMENT, zone = zone, nowMillis = now,
        )
        val plan = result.plan
        assertEquals("Shory", plan.merchant)
        assertEquals("Tabby", plan.provider)
        assertEquals(0, BigDecimal("924.00").compareTo(plan.totalAmount))
        assertEquals(4, plan.installmentCount)
        assertEquals(PayLaterCadence.MONTHLY, plan.cadence)
        assertEquals(1L, plan.sourceTransactionId)
        assertEquals(5L, plan.payFromAccountId)
        assertEquals(8L, plan.categoryId)
        assertTrue(plan.sourceIsInstallment)

        assertEquals(
            listOf(LocalDate.of(2026, 11, 26), LocalDate.of(2026, 12, 26), LocalDate.of(2027, 1, 26), LocalDate.of(2027, 2, 26)),
            result.installments.map { day(it.dueDateMillis) },
        )
        assertTrue(result.installments.all { BigDecimal("231.00").compareTo(it.amount) == 0 })
        assertEquals(3L, result.installments[0].paymentTransactionId)
        assertEquals(today, result.installments[0].paidAtMillis)
        assertTrue(result.installments.drop(1).none { it.isPaid })

        assertEquals(listOf(1L), result.excludeIds)
        assertEquals(listOf(2L), result.deleteIds)
        assertTrue(result.convertReceiptIds.isEmpty())
        assertEquals(mapOf(1L to "Shory", 3L to "Tabby"), result.nameIfBlank)
    }

    @Test fun `full price keeps the purchase and hides the card payments`() {
        val result = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today), charge(today)),
            mode = TabbySplitPlan.Mode.FULL_PRICE, zone = zone, nowMillis = now,
        )
        assertFalse(result.plan.sourceIsInstallment)
        assertEquals(listOf(3L), result.excludeIds)
        assertEquals(PayLaterPayment.Shape.EXCLUDED_DEBIT, PayLaterPayment.shapeOf(result.plan, hasProviderAccount = false))
    }

    @Test fun `with the card charge not in yet the first payment stays open for auto-match`() {
        val result = TabbySplitPlan.build(candidate(checkout(today)), zone = zone, nowMillis = now)
        assertNull(result.plan.payFromAccountId)
        assertTrue(result.installments.none { it.isPaid })
        assertEquals(LocalDate.of(2026, 11, 26), day(result.installments[0].dueDateMillis))
        assertTrue(result.excludeIds == listOf(1L))
    }

    @Test fun `an old split's missing past payments are paid without a link, or left open`() {
        val august = at(2026, 8, 26)
        val old = candidate(checkout(august), charge(august))
        val marked = TabbySplitPlan.build(old, markMissingPaid = true, zone = zone, nowMillis = now)
        assertEquals(3L, marked.installments[0].paymentTransactionId)
        // 26 Sep, 26 Oct are long past; 26 Nov is today, so its SMS may still come.
        assertTrue(marked.installments[1].isPaid)
        assertNull(marked.installments[1].paymentTransactionId)
        assertEquals(marked.installments[1].dueDateMillis, marked.installments[1].paidAtMillis)
        assertTrue(marked.installments[2].isPaid)
        assertFalse(marked.installments[3].isPaid)

        val open = TabbySplitPlan.build(old, markMissingPaid = false, zone = zone, nowMillis = now)
        assertTrue(open.installments.drop(1).none { it.isPaid })
    }

    @Test fun `an old receipt that is the only record becomes the payment`() {
        val result = TabbySplitPlan.build(candidate(checkout(today), receipt(today)), zone = zone, nowMillis = now)
        assertEquals(2L, result.installments[0].paymentTransactionId)
        assertEquals(listOf(2L), result.convertReceiptIds)
        assertTrue(result.deleteIds.isEmpty())
        assertEquals("Tabby", result.nameIfBlank[2L])
    }

    // --- Which account everything lands on: nothing from a reviewed split may sit in "no account" ---

    @Test fun `the picked card goes on a payment that names none and never replaces one that does`() {
        val receiptOnly = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today)), paidFromAccountId = 9, zone = zone, nowMillis = now,
        )
        assertEquals(mapOf(2L to 9L), receiptOnly.assignAccountIds)
        assertEquals(9L, receiptOnly.plan.payFromAccountId)

        // The ADIB charge already carries its card (5): nothing is reassigned, the plan follows the picker.
        val withCharge = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today), charge(today)), paidFromAccountId = 9, zone = zone, nowMillis = now,
        )
        assertTrue(withCharge.assignAccountIds.isEmpty())
        assertEquals(9L, withCharge.plan.payFromAccountId)
    }

    @Test fun `with no card picked the plan follows the first card charge and a receipt-only payment stays unfiled`() {
        val withCharge = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today), charge(today)), zone = zone, nowMillis = now,
        )
        assertEquals(5L, withCharge.plan.payFromAccountId)
        assertTrue(withCharge.assignAccountIds.isEmpty())

        val receiptOnly = TabbySplitPlan.build(candidate(checkout(today), receipt(today)), zone = zone, nowMillis = now)
        assertNull(receiptOnly.plan.payFromAccountId)
        assertTrue(receiptOnly.assignAccountIds.isEmpty())
    }

    @Test fun `the purchase row is set aside on purpose in both modes, unless it already has a card`() {
        listOf(TabbySplitPlan.Mode.PER_PAYMENT, TabbySplitPlan.Mode.FULL_PRICE).forEach { mode ->
            val result = TabbySplitPlan.build(
                candidate(checkout(today), receipt(today), charge(today)), mode = mode, zone = zone, nowMillis = now,
            )
            assertEquals(listOf(1L), result.historyOnlyIds)
        }
        val filed = TabbySplitPlan.build(
            candidate(checkout(today).copy(accountId = 4), charge(today)), zone = zone, nowMillis = now,
        )
        assertTrue(filed.historyOnlyIds.isEmpty())
    }

    private fun plan(id: Long, provider: String, from: Long?, createdAt: Long) = PayLaterPlan(
        id = id, merchant = "x", provider = provider, totalAmount = BigDecimal("100"), currencyCode = "AED",
        installmentCount = 4, payFromAccountId = from, createdAt = createdAt,
    )

    @Test fun `the default card is the first charge's, else the last Tabby plan's, else none`() {
        val plans = listOf(
            plan(1, "Tabby", from = 7, createdAt = 10),
            plan(2, "Tabby", from = 8, createdAt = 20),
            plan(3, "Tamara", from = 6, createdAt = 30),
            plan(4, "Tabby", from = null, createdAt = 40),
        )
        val withCharge = candidate(checkout(today), charge(today))
        assertEquals(5L, TabbySplitPlan.defaultPaidFrom(withCharge, plans))
        val noCharge = candidate(checkout(today))
        assertEquals(8L, TabbySplitPlan.defaultPaidFrom(noCharge, plans))
        assertNull(TabbySplitPlan.defaultPaidFrom(noCharge, emptyList()))
    }

    @Test fun `another count builds that many payments and drops charges that no longer fit`() {
        val result = TabbySplitPlan.build(
            candidate(checkout(today), receipt(today), charge(today)), count = 2, zone = zone, nowMillis = now,
        )
        assertEquals(2, result.plan.installmentCount)
        assertTrue(result.installments.all { BigDecimal("462.00").compareTo(it.amount) == 0 })
        assertTrue(result.installments.none { it.isPaid })
        assertTrue(result.deleteIds.isEmpty())
    }
}
