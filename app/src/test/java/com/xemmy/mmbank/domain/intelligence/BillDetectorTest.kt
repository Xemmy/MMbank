package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.ui.screens.bills.ordinal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillDetectorTest {
    private val utc = ZoneOffset.UTC
    private fun millis(d: LocalDate) = d.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()

    private fun tx(id: Long, merchant: String, date: LocalDate, amount: String, raw: String? = null) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, rawMessage = raw, timestampMillis = millis(date),
    )

    private val today = LocalDate.of(2026, 11, 21)

    @Test fun `providers are recognised and unified, carrier billing is not a bill`() {
        assertEquals("etisalat", BillDetector.providerKey("E& TELECOM CORP"))
        assertEquals("etisalat", BillDetector.providerKey("ETISALAT"))
        assertEquals("du", BillDetector.providerKey("DU"))
        assertEquals("du", BillDetector.providerKey("du Telecom"))
        assertEquals("dewa", BillDetector.providerKey("DEWA DUBAI"))
        assertEquals("addc", BillDetector.providerKey("TAQA ADDC"))
        assertNull(BillDetector.providerKey("DU Google Payment"))
        assertNull(BillDetector.providerKey("DUBAI MALL"))
        assertNull(BillDetector.providerKey("Netflix"))
        assertNull(BillDetector.providerKey(null))
    }

    @Test fun `the line number comes from a mobile or service number, never a card mask`() {
        assertEquals("4567", BillDetector.accountRef("Payment of AED 168.41 for du number 0551234567 received"))
        assertEquals("4567", BillDetector.accountRef("Bill paid for +971 55 123 4567"))
        assertEquals("8899", BillDetector.accountRef("Thank you. Contract no: 1234568899 paid"))
        assertNull(BillDetector.accountRef("Your card XXXX1234 was used for AED 168.41 at E& TELECOM CORP"))
        assertNull(BillDetector.accountRef(null))
    }

    @Test fun `varying amounts on varying days are one monthly bill`() {
        val ledger = listOf(
            tx(1, "E& TELECOM CORP", LocalDate.of(2026, 8, 3), "168.41"),
            tx(2, "E& TELECOM CORP", LocalDate.of(2026, 9, 17), "442.58"),
            tx(3, "E& TELECOM CORP", LocalDate.of(2026, 10, 9), "599.73"),
            tx(4, "E& TELECOM CORP", LocalDate.of(2026, 11, 12), "210.00"),
        )
        val bill = BillDetector.detect(ledger, today, utc).single()
        assertEquals("etisalat", bill.providerKey)
        assertEquals(BigDecimal("168.41"), bill.minAmount)
        assertEquals(BigDecimal("599.73"), bill.maxAmount)
        assertEquals(BigDecimal("326.29"), bill.typicalAmount)
        assertEquals(9, bill.typicalDay)
        assertEquals(1, bill.paymentsPerMonth)
        assertNull(bill.accountRef)
    }

    @Test fun `two lines with numbers in the SMS are two bills, without numbers one bill with two payments`() {
        val months = listOf(9, 10, 11)
        val withRefs = months.flatMap { m ->
            listOf(
                tx(m * 10L, "DU", LocalDate.of(2026, m, 5), "150.00", "Payment received for 0551234567"),
                tx(m * 10L + 1, "DU", LocalDate.of(2026, m, 8), "320.00", "Payment received for 0509998910"),
            )
        }
        val two = BillDetector.detect(withRefs, today, utc)
        assertEquals(listOf("4567", "8910"), two.map { it.accountRef })

        val noRefs = withRefs.map { it.copy(rawMessage = null) }
        val one = BillDetector.detect(noRefs, today, utc).single()
        assertEquals(2, one.paymentsPerMonth)
        assertEquals(BigDecimal("470.00"), one.typicalAmount)
    }

    @Test fun `two of the last four months is not enough unless the SMS says bill`() {
        val sparse = listOf(
            tx(1, "DEWA", LocalDate.of(2026, 10, 3), "300.00"),
            tx(2, "DEWA", LocalDate.of(2026, 11, 3), "320.00"),
        )
        assertTrue(BillDetector.detect(sparse, today, utc).isEmpty())
        val hinted = sparse.map { it.copy(rawMessage = "Your DEWA bill has been paid") }
        assertEquals(1, BillDetector.detect(hinted, today, utc).size)
    }

    @Test fun `toBill is monthly on the usual day and not in the past`() {
        val detected = BillDetector.DetectedBill(
            providerKey = "etisalat", displayName = "E& TELECOM CORP", accountRef = "4567", currencyCode = "AED",
            typicalAmount = BigDecimal("300.00"), minAmount = BigDecimal("168.41"), maxAmount = BigDecimal("599.73"),
            paymentsPerMonth = 1, typicalDay = 9, lastPaidMillis = millis(LocalDate.of(2026, 11, 12)),
        )
        val bill = BillDetector.toBill(detected, millis(today), utc)
        assertEquals(BillRecurrence.MONTHLY, bill.recurrence)
        assertEquals(LocalDate.of(2026, 12, 9).atStartOfDay(utc).toInstant().toEpochMilli(), bill.nextDueDateMillis)
        assertEquals("etisalat", bill.providerKey)
        assertEquals("4567", bill.accountRef)
        assertEquals(9, bill.dueDayAnchor)
        assertTrue(BillDetector.tracks(bill, detected))
        assertFalse(BillDetector.tracks(bill.copy(accountRef = "8910"), detected))
    }

    private fun bill(due: LocalDate, ref: String? = null, lastPaid: LocalDate? = null, id: Long = 1) = Bill(
        id = id, name = "e&", amount = BigDecimal("300.00"), currencyCode = "AED", recurrence = BillRecurrence.MONTHLY,
        nextDueDateMillis = due.atStartOfDay(utc).toInstant().toEpochMilli(), createdAt = 0, dueDayAnchor = due.dayOfMonth,
        providerKey = "etisalat", accountRef = ref, lastPaidMillis = lastPaid?.let(::millis),
    )

    @Test fun `a late payment of any amount pays the cycle and advances the bill`() {
        val paid = tx(9, "E& TELECOM CORP", LocalDate.of(2026, 11, 19), "442.58")
        val result = BillAutoMatch.match(listOf(bill(LocalDate.of(2026, 11, 9))), paid, utc)!!
        assertEquals(LocalDate.of(2026, 12, 9).atStartOfDay(utc).toInstant().toEpochMilli(), result.nextDueDateMillis)
        assertEquals(BigDecimal("442.58"), result.amount)
        assertEquals(paid.timestampMillis, result.lastPaidMillis)
    }

    @Test fun `a second payment in the same cycle is ignored`() {
        val after = bill(LocalDate.of(2026, 12, 9), lastPaid = LocalDate.of(2026, 11, 12))
        val second = tx(10, "E& TELECOM CORP", LocalDate.of(2026, 11, 20), "100.00")
        assertNull(BillAutoMatch.match(listOf(after), second, utc))
    }

    @Test fun `a payment for another line or another provider pays nothing`() {
        val line = bill(LocalDate.of(2026, 11, 9), ref = "4567")
        val other = tx(11, "E& TELECOM CORP", LocalDate.of(2026, 11, 10), "100.00", "paid for 0509998910")
        assertNull(BillAutoMatch.match(listOf(line), other, utc))
        assertNull(BillAutoMatch.match(listOf(line), tx(12, "DEWA", LocalDate.of(2026, 11, 10), "100.00"), utc))
        val same = tx(13, "E& TELECOM CORP", LocalDate.of(2026, 11, 10), "100.00", "paid for 0551234567")
        assertEquals(1L, BillAutoMatch.match(listOf(line, bill(LocalDate.of(2026, 11, 9), ref = "8910", id = 2)), same, utc)!!.id)
    }

    @Test fun `a payment months after a forgotten due date pays the current cycle`() {
        val stale = bill(LocalDate.of(2026, 6, 9))
        val paid = tx(14, "E& TELECOM CORP", LocalDate.of(2026, 11, 10), "200.00")
        val result = BillAutoMatch.match(listOf(stale), paid, utc)!!
        assertEquals(LocalDate.of(2026, 12, 9).atStartOfDay(utc).toInstant().toEpochMilli(), result.nextDueDateMillis)
    }

    @Test fun `a payment far before the due date pays nothing`() {
        val early = tx(15, "E& TELECOM CORP", LocalDate.of(2026, 10, 1), "200.00")
        assertNull(BillAutoMatch.match(listOf(bill(LocalDate.of(2026, 11, 9))), early, utc))
    }

    @Test fun `ordinal suffixes`() {
        assertEquals(listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "31st"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 31).map(::ordinal))
    }
}
