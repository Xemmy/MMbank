package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DayTotalsTest {
    private val zone = ZoneOffset.UTC
    private val d1 = LocalDate.of(2026, 3, 10)
    private val d2 = LocalDate.of(2026, 3, 11)

    private fun tx(day: LocalDate, amount: String, kind: TransactionKind, currency: String = "AED", time: LocalTime = LocalTime.NOON) =
        Transaction(
            amount = BigDecimal(amount), currencyCode = currency,
            direction = if (kind == TransactionKind.INCOME || kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL, timestampMillis = day.atTime(time).toInstant(zone).toEpochMilli(), kind = kind,
        )

    private fun conv(t: Transaction): Double? = when (t.currencyCode) { "AED" -> t.amount.toDouble(); "USD" -> t.amount.toDouble() * 3.0; else -> null }

    @Test
    fun `spent and received are summed per day`() {
        val r = DayTotals.byDay(
            listOf(tx(d1, "40", TransactionKind.SPEND), tx(d1, "10", TransactionKind.SPEND), tx(d1, "100", TransactionKind.INCOME), tx(d2, "5", TransactionKind.SPEND)),
            zone, value = ::conv,
        )
        assertEquals(50.0, r.getValue(d1).spent, 1e-9)
        assertEquals(100.0, r.getValue(d1).received, 1e-9)
        assertEquals(50.0, r.getValue(d1).net, 1e-9)
        assertEquals(1, r.getValue(d1).netSign)
        assertEquals(-1, r.getValue(d2).netSign)
    }

    @Test
    fun `a refund lowers the day's spending and transfers count for nothing`() {
        val r = DayTotals.byDay(
            listOf(tx(d1, "40", TransactionKind.SPEND), tx(d1, "15", TransactionKind.REFUND), tx(d1, "900", TransactionKind.TRANSFER), tx(d1, "700", TransactionKind.CARD_PAYMENT)),
            zone, value = ::conv,
        )
        assertEquals(25.0, r.getValue(d1).spent, 1e-9)
        assertEquals(0.0, r.getValue(d1).received, 1e-9)
    }

    @Test
    fun `other currencies are converted and unconvertible rows skipped`() {
        val r = DayTotals.byDay(listOf(tx(d1, "10", TransactionKind.SPEND, "USD"), tx(d1, "99", TransactionKind.SPEND, "XXX")), zone, value = ::conv)
        assertEquals(30.0, r.getValue(d1).spent, 1e-9)
    }

    @Test
    fun `a day with only transfers is zero and has no sign`() {
        val r = DayTotals.byDay(listOf(tx(d1, "900", TransactionKind.TRANSFER)), zone, value = ::conv)
        assertEquals(0, r.getValue(d1).netSign)
    }

    @Test
    fun `days keep first-seen order and group by local day`() {
        val late = tx(d1, "1", TransactionKind.SPEND, time = LocalTime.of(23, 59))
        val early = tx(d2, "1", TransactionKind.SPEND, time = LocalTime.of(0, 1))
        val r = DayTotals.byDay(listOf(late, early), zone, value = ::conv)
        assertEquals(listOf(d1, d2), r.keys.toList())
        assertTrue(r.size == 2)
    }
}
