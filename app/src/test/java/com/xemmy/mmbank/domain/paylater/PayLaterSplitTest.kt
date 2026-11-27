package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterPlan
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

    // --- 0.143.0: a charge with no card is paid from the provider's last card ---

    private fun planOf(provider: String, payFrom: Long?, createdAt: Long) = PayLaterPlan(
        merchant = "Shop", provider = provider, totalAmount = BigDecimal("400.00"), currencyCode = "AED",
        installmentCount = 4, payFromAccountId = payFrom, createdAt = createdAt,
    )

    private val earlier = listOf(
        planOf("Tabby", payFrom = 11, createdAt = 1),
        planOf(" tabby ", payFrom = 12, createdAt = 5),
        planOf("Tabby", payFrom = null, createdAt = 9),
        planOf("Tamara", payFrom = 13, createdAt = 8),
    )

    @Test fun `a charge with no card is paid from the card the provider's latest plan used`() {
        val cardless = charge.copy(accountId = null)
        val plan = PayLaterSplit.fromCharge(cardless, 4, true, PayLaterCadence.MONTHLY, utc, provider = "Tabby", nowMillis = 1, plans = earlier).plan
        assertEquals(12L, plan.payFromAccountId)
    }

    @Test fun `the charge's own card wins over any earlier plan`() {
        val plan = PayLaterSplit.fromCharge(charge, 4, true, PayLaterCadence.MONTHLY, utc, provider = "Tabby", nowMillis = 1, plans = earlier).plan
        assertEquals(5L, plan.payFromAccountId)
    }

    @Test fun `no earlier plan with that provider leaves the card unset`() {
        assertNull(PayLaterSplit.lastPayFrom("Postpay", earlier))
        assertEquals(13L, PayLaterSplit.lastPayFrom("Tamara", earlier))
    }
}
