package com.xemmy.mmbank.domain.intelligence

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

class ChargeFilterTest {
    private val utc = ZoneOffset.UTC

    private fun tx(
        id: Long = 1, amount: String = "39.99", merchant: String? = "Netflix", raw: String? = null, note: String? = null,
        date: LocalDate = LocalDate.of(2026, 3, 10),
    ) = Transaction(
        id = id, amount = BigDecimal(amount), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, rawMessage = raw, note = note,
        timestampMillis = date.atTime(12, 0).atZone(utc).toInstant().toEpochMilli(),
    )

    private fun m(t: Transaction, q: String = "", from: LocalDate? = null, to: LocalDate? = null) =
        ChargeFilter.matches(t, q, from, to, utc)

    @Test fun `text matches merchant, sms and note ignoring case`() {
        val t = tx(raw = "Purchase of AED 39.99 at NETFLIX.COM card 1234", note = "family plan")
        assertTrue(m(t, "netflix"))
        assertTrue(m(t, "card 1234"))
        assertTrue(m(t, "FAMILY"))
        assertFalse(m(t, "spotify"))
    }

    @Test fun `blank query matches everything`() {
        assertTrue(m(tx(), ""))
        assertTrue(m(tx(), "   "))
    }

    @Test fun `a full amount matches exactly`() {
        assertTrue(m(tx(amount = "39.99"), "39.99"))
        assertFalse(m(tx(amount = "39.98"), "39.99"))
    }

    @Test fun `a short number is a prefix of the amount`() {
        assertTrue(m(tx(amount = "40.00"), "40"))
        assertTrue(m(tx(amount = "40.50"), "40"))
        assertFalse(m(tx(amount = "140.00"), "40"))
        assertTrue(m(tx(amount = "1200.00"), "1,200"))
    }

    @Test fun `a numeric query searches amounts, not sms digits`() {
        assertFalse(m(tx(amount = "10.00", raw = "card 4000"), "4000"))
    }

    @Test fun `the date range is inclusive at both ends`() {
        val t = tx(date = LocalDate.of(2026, 3, 10))
        assertTrue(m(t, from = LocalDate.of(2026, 3, 10), to = LocalDate.of(2026, 3, 10)))
        assertFalse(m(t, from = LocalDate.of(2026, 3, 11)))
        assertFalse(m(t, to = LocalDate.of(2026, 3, 9)))
        assertTrue(m(t, from = LocalDate.of(2026, 1, 1)))
    }

    @Test fun `snippet collapses whitespace and truncates`() {
        assertNull(ChargeFilter.snippet(tx(raw = null)))
        assertEquals("a b", ChargeFilter.snippet(tx(raw = " a \n b ")))
        val long = ChargeFilter.snippet(tx(raw = "x".repeat(200)), max = 20)!!
        assertEquals(20, long.length)
        assertTrue(long.endsWith("…"))
    }

    @Test fun `pickable finds a match older than the newest hundred`() {
        val newer = (1..120L).map { tx(id = it, merchant = "Other $it", date = LocalDate.of(2026, 3, 1).plusDays(it)) }
        val old = tx(id = 500, merchant = "Old Gym", date = LocalDate.of(2025, 1, 1))
        val all = newer + old
        assertTrue(SubscriptionLinks.pickable(all, emptyList(), "", 100, zone = utc).none { it.id == 500L })
        assertEquals(listOf(500L), SubscriptionLinks.pickable(all, emptyList(), "old gym", 100, zone = utc).map { it.id })
    }

    @Test fun `presets give inclusive ranges ending today`() {
        val today = LocalDate.of(2026, 4, 15)
        assertEquals(null to null, ChargeFilter.Preset.ALL.range(today))
        assertEquals(LocalDate.of(2026, 4, 1) to today, ChargeFilter.Preset.THIS_MONTH.range(today))
        assertEquals(LocalDate.of(2026, 1, 15) to today, ChargeFilter.Preset.LAST_3_MONTHS.range(today))
        assertEquals(LocalDate.of(2026, 1, 1) to today, ChargeFilter.Preset.THIS_YEAR.range(today))
    }
}
