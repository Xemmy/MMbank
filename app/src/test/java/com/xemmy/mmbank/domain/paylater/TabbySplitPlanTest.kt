package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
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
