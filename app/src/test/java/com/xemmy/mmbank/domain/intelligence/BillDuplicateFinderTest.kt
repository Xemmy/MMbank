package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.140.4: "Merge" only pairs a bank SMS with a real provider receipt. */
class BillDuplicateFinderTest {

    private fun millis(d: LocalDate, hour: Int) = d.atTime(hour, 0).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun tx(id: Long, raw: String?, hour: Int, sender: String? = null, billRef: String? = null, accountId: Long? = 3) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal("150.00"), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = "DU", source = TransactionSource.SMS, sender = sender, rawMessage = raw,
        timestampMillis = millis(LocalDate.of(2026, 11, 5), hour), billRef = billRef,
    )

    @Test fun `two same-day payments of two lines are not a duplicate after one was attached to a line`() {
        // Both bank SMS name no line; the user attached the first to line ...4567 from the payment history.
        val lineA = tx(1, "AED 150.00 debited from card XXXX1111 at DU", 9, sender = "ADCB", billRef = "4567")
        val lineB = tx(2, "AED 150.00 debited from card XXXX1111 at DU ref 2", 10, sender = "ADCB")
        assertTrue(BillDuplicateFinder.pairs(listOf(lineA, lineB)).isEmpty())
    }

    @Test fun `a provider receipt and the bank SMS are still one payment`() {
        val bank = tx(1, "AED 150.00 debited from card XXXX1111 at DU", 9, sender = "ADCB")
        val receipt = tx(2, "Payment received for 0551234567", 10, sender = "du", accountId = null)
        val pairs = BillDuplicateFinder.pairs(listOf(bank, receipt))
        assertEquals(1, pairs.size)
        assertEquals(1L, pairs.single().keep.id)
        assertEquals(2L, pairs.single().remove.id)
        assertEquals("4567", pairs.single().ref)
    }

    @Test fun `two messages from the same sender are two payments`() {
        val first = tx(1, "AED 150.00 paid to du for 0551234567", 9, sender = "ADCB")
        val second = tx(2, "AED 150.00 debited from card XXXX1111 at DU", 10, sender = "ADCB")
        assertTrue(BillDuplicateFinder.pairs(listOf(first, second)).isEmpty())
    }
}
