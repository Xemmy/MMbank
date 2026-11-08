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
}
