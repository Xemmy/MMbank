package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PayLaterAutoMatchTest {
    private val dayMs = 24L * 60 * 60 * 1000
    private val due = 100 * dayMs
    private val plan = PayLaterPlan(
        id = 1, merchant = "Sephora", provider = "Tabby", totalAmount = BigDecimal("400"), currencyCode = "AED",
        installmentCount = 4, sourceTransactionId = 7, createdAt = 0,
    )
    private val installments = listOf(
        PayLaterInstallment(id = 10, planId = 1, number = 1, amount = BigDecimal("100.00"), dueDateMillis = due - 30 * dayMs, paidAtMillis = 1, paymentTransactionId = 7),
        PayLaterInstallment(id = 11, planId = 1, number = 2, amount = BigDecimal("100.00"), dueDateMillis = due),
        PayLaterInstallment(id = 12, planId = 1, number = 3, amount = BigDecimal("100.00"), dueDateMillis = due + 30 * dayMs),
    )

    private fun tx(amount: String = "100.00", at: Long = due + 2 * dayMs, merchant: String? = "Sephora", id: Long = 50) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = at,
    )

    @Test fun `a debit near the next installment's amount and date matches it`() {
        assertEquals(11L, PayLaterAutoMatch.match(listOf(plan), installments, tx())?.installment?.id)
    }

    @Test fun `an amount more than one percent off does not match`() {
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(amount = "103.00")))
        assertNotNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(amount = "100.90")))
    }

    @Test fun `a date more than a week from the due date does not match`() {
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(at = due + 9 * dayMs)))
        assertNotNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(at = due - 7 * dayMs)))
    }

    @Test fun `a different merchant does not match but the provider name does`() {
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(merchant = "Carrefour")))
        assertNotNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(merchant = "Tabby")))
    }

    // The paying card can be any bank's: its charge names Tabby in its own words, often with no
    // merchant the parser could read.
    private fun bankCharge(merchant: String?, raw: String, sender: String = "ADIB") =
        tx(merchant = merchant).copy(rawMessage = raw, sender = sender)

    @Test fun `a bank charge that only names Tabby in its text matches the next installment`() {
        val adib = bankCharge(null, "Trx. of AED100.00 on your card ending *876 at Tabby, 8001234 UAE is Approved.")
        assertEquals(11L, PayLaterAutoMatch.match(listOf(plan), installments, adib)?.installment?.id)
        val enbd = bankCharge("TABBY FZ LLC", "Purchase of AED 100.00 at TABBY FZ LLC", sender = "ENBD")
        assertEquals(11L, PayLaterAutoMatch.match(listOf(plan), installments, enbd)?.installment?.id)
    }

    @Test fun `a nameless charge that does not mention Tabby does not match`() {
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, bankCharge(null, "Trx. of AED100.00 on your card ending *876 is Approved.")))
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, bankCharge(null, "AED 100.00 with Tabby Card ••4543")))
    }

    @Test fun `the plan's own source charge and an already linked charge never match`() {
        assertNull(PayLaterAutoMatch.match(listOf(plan), installments, tx(id = 7)))
        val linked = installments.map { if (it.id == 11L) it.copy(paymentTransactionId = 50) else it }
        assertNull(PayLaterAutoMatch.match(listOf(plan), linked, tx(id = 50)))
    }

    @Test fun `a fully paid plan has nothing to match`() {
        val paid = installments.map { it.copy(paidAtMillis = 1) }
        assertNull(PayLaterAutoMatch.match(listOf(plan), paid, tx()))
    }
}
