package com.xemmy.mmbank.domain.paylater

import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionDirection.CREDIT
import com.xemmy.mmbank.domain.model.TransactionDirection.DEBIT
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BuiltInRules
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures mirror the user's screenshot of one Tabby split-in-4 checkout (26 Nov 2026). */
class TabbySplitDetectorTest {

    private val zone = ZoneId.of("Asia/Dubai")

    private fun at(y: Int, m: Int, d: Int, h: Int = 13, min: Int = 26): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun tx(
        id: Long,
        amount: String,
        direction: TransactionDirection,
        at: Long,
        merchant: String? = null,
        sender: String? = null,
        raw: String? = null,
        accountId: Long? = null,
        rule: String? = null,
    ) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal(amount), currencyCode = "AED", direction = direction,
        merchant = merchant, source = TransactionSource.SMS, sender = sender, rawMessage = raw, matchedRuleName = rule,
        timestampMillis = at,
    )

    private fun checkout(id: Long, amount: String, shop: String, at: Long, rule: String = "builtin.en.debit.af.cur-amt") =
        tx(id, amount, DEBIT, at, sender = "Tabby", rule = rule,
            raw = "Your AED $amount purchase at $shop is confirmed. To pay as low as 77.00/month, extend your payment plan in the app")

    private fun oldReceipt(id: Long, amount: String, at: Long, which: String = "first") =
        tx(id, amount, CREDIT, at, sender = "Tabby", rule = "builtin.en.debit.cur-amt",
            raw = "We received your $which payment of AED $amount. To pay ahead, open the Tabby app.")

    private fun adibCharge(id: Long, amount: String, at: Long) =
        tx(id, amount, DEBIT, at, sender = "ADIB", accountId = 5, rule = "builtin.en.debit.transaction",
            raw = "Trx. of AED$amount on your card ending *876 at Tabby, 8001234 UAE is Approved.")

    // The three rows of the screenshot, as captured before 0.142.
    private val purchase = checkout(1, "924.00", "Shory", at(2026, 11, 26))
    private val receipt = oldReceipt(2, "231.00", at(2026, 11, 26))
    private val charge = adibCharge(3, "231.00", at(2026, 11, 26))

    private fun find(
        rows: List<Transaction>,
        plans: List<PayLaterPlan> = emptyList(),
        installments: List<PayLaterInstallment> = emptyList(),
        dismissed: Set<Long> = emptySet(),
    ) = TabbySplitDetector.find(rows, plans, installments, dismissed, zone)

    @Test fun `the screenshot's three rows are one split of 4 paid from the card`() {
        val found = find(listOf(purchase, receipt, charge)).single()
        assertEquals(1L, found.purchase.id)
        assertEquals("Shory", found.merchant)
        assertEquals("Tabby", found.provider)
        assertEquals(4, found.count)
        assertEquals(listOf(3L, null, null, null), found.match.payments.map { it?.id })
        assertEquals(listOf(2L), found.match.duplicateReceipts.map { it.id })
        assertTrue(found.match.legacyReceiptPayments.isEmpty())
    }

    @Test fun `an earlier split paid from two banks gets every payment, and its receipts`() {
        val p = checkout(10, "600.00", "Noon", at(2026, 8, 10, 10, 0), rule = BuiltInRules.PAY_LATER_PURCHASE_RULE)
            .copy(merchant = "Noon")
        val rows = listOf(
            p,
            tx(11, "150.00", DEBIT, at(2026, 8, 10, 10, 1), merchant = "TABBY FZ LLC", sender = "ENBD", accountId = 7),
            oldReceipt(12, "150.00", at(2026, 8, 10, 10, 1)),
            adibCharge(13, "150.00", at(2026, 9, 10, 9, 0)),
            oldReceipt(14, "150.00", at(2026, 9, 10, 9, 0), which = "second"),
            tx(15, "150.00", DEBIT, at(2026, 10, 11, 8, 0), merchant = "Tabby", sender = "ENBD", accountId = 7),
            oldReceipt(16, "150.00", at(2026, 10, 11, 8, 0), which = "third"),
            // November: only Tabby's receipt was captured, as a +credit.
            oldReceipt(17, "150.00", at(2026, 11, 10, 9, 0), which = "fourth"),
        )
        val found = find(rows).single()
        assertEquals("Noon", found.merchant)
        assertEquals(4, found.count)
        assertEquals(listOf(11L, 13L, 15L, 17L), found.match.payments.map { it?.id })
        assertEquals(listOf(12L, 14L, 16L), found.match.duplicateReceipts.map { it.id })
        assertEquals(listOf(17L), found.match.legacyReceiptPayments.map { it.id })
        assertEquals(4, found.match.foundCount)
    }

    @Test fun `two equal splits a week apart never share a charge`() {
        val a = checkout(20, "400.00", "Amazon", at(2026, 10, 1))
        val b = checkout(30, "400.00", "Namshi", at(2026, 10, 8))
        val rows = listOf(
            a, b,
            adibCharge(21, "100.00", at(2026, 10, 1)),
            adibCharge(31, "100.00", at(2026, 10, 8)),
            adibCharge(22, "100.00", at(2026, 11, 1)),
            adibCharge(32, "100.00", at(2026, 11, 8)),
        )
        val found = find(rows).associateBy { it.purchase.id }
        assertEquals(listOf(21L, 22L, null, null), found.getValue(20).match.payments.map { it?.id })
        assertEquals(listOf(31L, 32L, null, null), found.getValue(30).match.payments.map { it?.id })
        // Newest first.
        assertEquals(listOf(30L, 20L), find(rows).map { it.purchase.id })
    }

    @Test fun `a purchase that already has a plan is not offered again`() {
        val bySource = PayLaterPlan(id = 1, merchant = "Shory", totalAmount = BigDecimal("924.00"), currencyCode = "AED",
            installmentCount = 4, sourceTransactionId = 1, createdAt = 0)
        assertTrue(find(listOf(purchase, receipt, charge), plans = listOf(bySource)).isEmpty())

        // Split by hand from the card's 231 (one payment of 4): same total, first installment that day.
        val handMade = bySource.copy(id = 2, sourceTransactionId = 3, sourceIsInstallment = true)
        val first = PayLaterInstallment(id = 9, planId = 2, number = 1, amount = BigDecimal("231.00"),
            dueDateMillis = LocalDate.of(2026, 11, 26).atStartOfDay(zone).toInstant().toEpochMilli(),
            paidAtMillis = at(2026, 11, 26), paymentTransactionId = 3)
        assertTrue(find(listOf(purchase, receipt, charge), plans = listOf(handMade), installments = listOf(first)).isEmpty())
    }

    @Test fun `not a split is never offered again`() {
        assertTrue(find(listOf(purchase, receipt, charge), dismissed = setOf(1L)).isEmpty())
    }

    @Test fun `with no payment seen yet the split defaults to 4 open payments`() {
        val found = find(listOf(purchase)).single()
        assertEquals(4, found.count)
        assertTrue(found.match.payments.all { it == null })
    }

    @Test fun `a Tabby charge that is not a share of the total is not its payment`() {
        val other = adibCharge(40, "250.00", at(2026, 11, 26, 14, 0))
        val found = find(listOf(purchase, other)).single()
        assertEquals(4, found.count)
        assertNull(found.match.payments.first())
    }

    @Test fun `the count follows the first payment's share`() {
        assertEquals(4, TabbySplitDetector.countImplied(BigDecimal("924.00"), BigDecimal("231.00")))
        assertEquals(3, TabbySplitDetector.countImplied(BigDecimal("693.00"), BigDecimal("231.00")))
        assertEquals(4, TabbySplitDetector.countImplied(BigDecimal("2575.63"), BigDecimal("643.91")))
        assertNull(TabbySplitDetector.countImplied(BigDecimal("924.00"), BigDecimal("250.00")))
        assertNull(TabbySplitDetector.countImplied(BigDecimal("924.00"), BigDecimal("924.00")))
    }

    @Test fun `changing the count re-matches the payments`() {
        val found = find(listOf(purchase, receipt, charge)).single()
        // 924 in 2 is 462 a payment: the 231 charge is not one of them.
        assertTrue(TabbySplitDetector.matchFor(found, 2, zone).payments.all { it == null })
        assertEquals(3L, TabbySplitDetector.matchFor(found, 4, zone).payments.first()?.id)
    }
}
