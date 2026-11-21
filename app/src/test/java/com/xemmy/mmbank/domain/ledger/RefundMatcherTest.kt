package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefundMatcherTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    private fun tx(
        id: Long,
        amount: String,
        merchant: String? = "Amazon",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        kind: TransactionKind = TransactionKind.SPEND,
        at: Long = now,
        currency: String = "AED",
    ) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = currency, direction = direction,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = at, kind = kind,
    )

    private fun credit(amount: String, merchant: String? = "Amazon", at: Long = now, currency: String = "AED") =
        tx(99, amount, merchant, TransactionDirection.CREDIT, TransactionKind.INCOME, at, currency)

    @Test fun `exact-amount purchase by the same merchant is the original`() {
        val purchase = tx(1, "120.00", at = now - 10 * day)
        assertEquals(purchase, RefundMatcher.findOriginal(credit("120"), listOf(purchase)))
    }

    @Test fun `case and punctuation in the merchant are ignored`() {
        val purchase = tx(1, "50", merchant = "AMAZON.AE", at = now - day)
        assertEquals(purchase, RefundMatcher.findOriginal(credit("50", merchant = "Amazon ae"), listOf(purchase)))
    }

    @Test fun `partial refund matches but a larger credit does not`() {
        val purchase = tx(1, "100", at = now - day)
        assertEquals(purchase, RefundMatcher.findOriginal(credit("40"), listOf(purchase)))
        assertNull(RefundMatcher.findOriginal(credit("100.01"), listOf(purchase)))
    }

    @Test fun `purchase outside the window or after the credit never matches`() {
        assertNull(RefundMatcher.findOriginal(credit("10"), listOf(tx(1, "10", at = now - 91 * day))))
        assertNull(RefundMatcher.findOriginal(credit("10"), listOf(tx(1, "10", at = now + day))))
        assertEquals(1L, RefundMatcher.findOriginal(credit("10"), listOf(tx(1, "10", at = now - 90 * day)))?.id)
    }

    @Test fun `different currency or different merchant never matches`() {
        assertNull(RefundMatcher.findOriginal(credit("10", currency = "USD"), listOf(tx(1, "10", at = now - day))))
        assertNull(RefundMatcher.findOriginal(credit("10", merchant = "Noon"), listOf(tx(1, "10", at = now - day))))
    }

    @Test fun `too-short or missing merchant never matches`() {
        assertNull(RefundMatcher.findOriginal(credit("10", merchant = "A"), listOf(tx(2, "10", merchant = "A", at = now - day))))
        assertNull(RefundMatcher.findOriginal(credit("10", merchant = null), listOf(tx(2, "10", merchant = null, at = now - day))))
    }

    @Test fun `only plain spend rows count as originals`() {
        val atm = tx(1, "10", kind = TransactionKind.ATM, at = now - day)
        val refund = tx(2, "10", kind = TransactionKind.REFUND, direction = TransactionDirection.CREDIT, at = now - day)
        assertNull(RefundMatcher.findOriginal(credit("10"), listOf(atm, refund)))
    }

    @Test fun `a debit is never treated as a refund`() {
        assertNull(RefundMatcher.findOriginal(tx(9, "10", at = now), listOf(tx(1, "10", at = now - day))))
    }

    @Test fun `exact amount beats a more recent larger purchase`() {
        val exact = tx(1, "60", at = now - 20 * day)
        val recent = tx(2, "200", at = now - day)
        assertEquals(exact, RefundMatcher.findOriginal(credit("60"), listOf(exact, recent)))
    }

    @Test fun `with no exact match the most recent candidate wins`() {
        val older = tx(1, "200", at = now - 20 * day)
        val recent = tx(2, "150", at = now - day)
        assertEquals(recent, RefundMatcher.findOriginal(credit("60"), listOf(older, recent)))
    }

    @Test fun `refine turns a matched default income into a refund`() {
        val spends = listOf(tx(1, "120", at = now - day))
        assertEquals(TransactionKind.REFUND, RefundMatcher.refine(TransactionKind.INCOME, credit("120"), spends))
    }

    @Test fun `refine leaves unmatched credits and other kinds alone`() {
        val spends = listOf(tx(1, "120", at = now - day))
        assertEquals(TransactionKind.INCOME, RefundMatcher.refine(TransactionKind.INCOME, credit("500"), spends))
        assertEquals(TransactionKind.CASHBACK, RefundMatcher.refine(TransactionKind.CASHBACK, credit("120"), spends))
        assertEquals(TransactionKind.INCOME, RefundMatcher.refine(TransactionKind.INCOME, credit("120"), spends, overridable = false))
    }
}
