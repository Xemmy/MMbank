package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeriodComparisonTest {

    private val zone = ZoneId.of("UTC")
    private val toBase: (Transaction) -> Double? = { it.amount.toDouble() }

    private fun spend(amount: String, day: LocalDate, kind: TransactionKind = TransactionKind.SPEND) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = if (kind == TransactionKind.REFUND) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = day.atStartOfDay(zone).plusHours(10).toInstant().toEpochMilli(),
        kind = kind,
    )

    @Test
    fun `month to date compares with the same days of last month`() {
        val today = LocalDate.of(2026, 11, 7)
        val rows = listOf(
            spend("120", LocalDate.of(2026, 11, 3)),
            spend("100", LocalDate.of(2026, 10, 5)),
            spend("900", LocalDate.of(2026, 10, 20)), // after the same point last month
        )
        val c = PeriodComparison.monthToDate(rows, toBase, today, zone)!!
        assertEquals(120.0, c.current, 1e-9)
        assertEquals(100.0, c.previous, 1e-9)
        assertEquals(20.0, c.percentChange!!, 1e-9)
    }

    @Test
    fun `the 31st compares with the end of a shorter month`() {
        assertEquals(
            DateWindow(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)),
            PeriodComparison.previousMonthToDate(LocalDate.of(2026, 3, 31)),
        )
        assertEquals(
            DateWindow(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29)),
            PeriodComparison.previousMonthToDate(LocalDate.of(2028, 3, 30)),
        )
    }

    @Test
    fun `january compares with december of the year before`() {
        assertEquals(
            DateWindow(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 10)),
            PeriodComparison.previousMonthToDate(LocalDate.of(2026, 1, 10)),
        )
    }

    @Test
    fun `refunds net off and nothing before means no percentage`() {
        val today = LocalDate.of(2026, 11, 7)
        val rows = listOf(spend("100", LocalDate.of(2026, 11, 2)), spend("30", LocalDate.of(2026, 11, 3), TransactionKind.REFUND))
        val c = PeriodComparison.monthToDate(rows, toBase, today, zone)!!
        assertEquals(70.0, c.current, 1e-9)
        assertNull(c.percentChange)
    }

    @Test
    fun `nothing in either window is no comparison`() {
        assertNull(PeriodComparison.monthToDate(emptyList(), toBase, LocalDate.of(2026, 11, 7), zone))
    }

    @Test
    fun `prior period is the equal-length window just before`() {
        val rows = listOf(spend("50", LocalDate.of(2026, 11, 5)), spend("100", LocalDate.of(2026, 10, 31)))
        val c = PeriodComparison.priorPeriod(rows, toBase, DateWindow(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 7)), zone)!!
        assertEquals(50.0, c.current, 1e-9)
        assertEquals(100.0, c.previous, 1e-9)
        assertEquals(-50.0, c.percentChange!!, 1e-9)
    }

    // --- 0.140.4: like-with-like windows for "vs last period" ---

    private fun d(text: String) = LocalDate.parse(text)

    @Test
    fun `a week in progress compares the same weekdays of last week`() {
        // Wednesday 2026-11-18 inside Mon 16 - Sun 22.
        val prev = PeriodComparison.previousWindow(DateWindow(d("2026-11-16"), d("2026-11-22")), d("2026-11-18"))
        assertEquals(DateWindow(d("2026-11-09"), d("2026-11-11")), prev)
    }

    @Test
    fun `a finished week compares the whole week before`() {
        val prev = PeriodComparison.previousWindow(DateWindow(d("2026-11-09"), d("2026-11-15")), d("2026-11-18"))
        assertEquals(DateWindow(d("2026-11-02"), d("2026-11-08")), prev)
    }

    @Test
    fun `a year in progress compares the same days of last year`() {
        val prev = PeriodComparison.previousWindow(DateWindow(d("2026-01-01"), d("2026-12-31")), d("2026-11-22"))
        assertEquals(DateWindow(d("2025-01-01"), d("2025-11-22")), prev)
    }

    @Test
    fun `a past calendar month compares the previous calendar month`() {
        val prev = PeriodComparison.previousWindow(DateWindow(d("2026-03-01"), d("2026-03-31")), d("2026-11-22"))
        assertEquals(DateWindow(d("2026-02-01"), d("2026-02-28")), prev)
    }

    @Test
    fun `the current month compares month to date`() {
        val prev = PeriodComparison.previousWindow(DateWindow(d("2026-03-01"), d("2026-03-31")), d("2026-03-31").minusDays(1))
        assertEquals(DateWindow(d("2026-02-01"), d("2026-02-28")), prev)
        val early = PeriodComparison.previousWindow(DateWindow(d("2026-03-01"), d("2026-03-31")), d("2026-03-10"))
        assertEquals(DateWindow(d("2026-02-01"), d("2026-02-10")), early)
    }

    @Test
    fun `a past year and a custom span keep their own rules`() {
        assertEquals(
            DateWindow(d("2024-01-01"), d("2024-12-31")),
            PeriodComparison.previousWindow(DateWindow(d("2025-01-01"), d("2025-12-31")), d("2026-11-22")),
        )
        // 1 Oct - 30 Nov is 61 days, so the span before it is 1 Aug - 30 Sep.
        assertEquals(
            DateWindow(d("2026-08-01"), d("2026-09-30")),
            PeriodComparison.previousWindow(DateWindow(d("2026-10-01"), d("2026-11-30")), d("2026-12-05")),
        )
    }
}
