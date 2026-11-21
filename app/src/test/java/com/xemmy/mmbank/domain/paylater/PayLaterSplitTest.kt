package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterSplitTest {
    private val utc = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 3, 10)
    private val charge = Transaction(
        id = 42, accountId = 5, amount = BigDecimal("100.00"), currencyCode = "AED",
        direction = TransactionDirection.DEBIT, merchant = "Sephora", source = TransactionSource.SMS,
        timestampMillis = day.atStartOfDay(utc).toInstant().toEpochMilli(), categoryId = 3,
    )

    private fun millis(d: LocalDate) = d.atStartOfDay(utc).toInstant().toEpochMilli()

    @Test fun `installment mode multiplies the charge and links installment one to it`() {
        val split = PayLaterSplit.fromCharge(charge, 4, chargeIsInstallment = true, PayLaterCadence.MONTHLY, utc, nowMillis = 1)
        assertEquals(BigDecimal("400.00"), split.plan.totalAmount)
        assertTrue(split.plan.sourceIsInstallment)
        assertEquals(42L, split.plan.sourceTransactionId)
        assertEquals(4, split.installments.size)
        val first = split.installments.first()
        assertEquals(42L, first.paymentTransactionId)
        assertEquals(charge.timestampMillis, first.paidAtMillis)
        assertEquals(millis(LocalDate.of(2026, 4, 10)), split.installments[1].dueDateMillis)
        assertTrue(split.installments.drop(1).none { it.isPaid })
        assertTrue(split.installments.all { it.amount == BigDecimal("100.00") })
    }

    @Test fun `full price mode keeps the total and marks only the first paid`() {
        val split = PayLaterSplit.fromCharge(charge, 4, chargeIsInstallment = false, PayLaterCadence.BIWEEKLY, utc, nowMillis = 1)
        assertEquals(BigDecimal("100.00"), split.plan.totalAmount)
        assertFalse(split.plan.sourceIsInstallment)
        assertEquals(BigDecimal("25.00"), split.installments[1].amount)
        assertNull(split.installments.first().paymentTransactionId)
        assertTrue(split.installments.first().isPaid)
        assertEquals(millis(LocalDate.of(2026, 3, 24)), split.installments[1].dueDateMillis)
    }

    @Test fun `plan copies merchant account and category from the charge`() {
        val plan = PayLaterSplit.fromCharge(charge, 3, true, PayLaterCadence.MONTHLY, utc, nowMillis = 9).plan
        assertEquals("Sephora", plan.merchant)
        assertEquals(5L, plan.payFromAccountId)
        assertEquals(3L, plan.categoryId)
        assertEquals(9L, plan.createdAt)
    }
}
