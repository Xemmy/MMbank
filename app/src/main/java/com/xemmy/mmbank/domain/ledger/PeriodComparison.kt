package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.model.Transaction
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Fair "vs last time" comparisons for a headline number.
 *
 * "Spent 4,210 this month" means little without context, but comparing a
 * month that is a week old with the whole of last month always reads as
 * "down 75%". [monthToDate] compares like with like: this month so far
 * against last month up to the same day.
 */
object PeriodComparison {

    data class Comparison(
        val current: Double,
        val previous: Double,
        /** Positive = more than before; null when there was nothing before. */
        val percentChange: Double?,
    )

    /**
     * The window that corresponds to "this month so far" ([today]'s month,
     * day 1 to [today]) in the previous month: day 1 to the same day,
     * clamped to that month's length (31 March compares with 28/29 Feb).
     */
    fun previousMonthToDate(today: LocalDate): DateWindow {
        val previous = YearMonth.from(today).minusMonths(1)
        return DateWindow(previous.atDay(1), previous.atDay(minOf(today.dayOfMonth, previous.lengthOfMonth())))
    }

    /** True when [window] has started but [today] is before its last day: only part of it has happened. */
    fun inProgress(window: DateWindow, today: LocalDate): Boolean =
        !today.isBefore(window.start) && today.isBefore(window.end)

    /**
     * The window to compare [window] with, like with like:
     *  - a calendar month: the previous calendar month (March vs February, not the 31 days
     *    before), or month-to-date while it is in progress;
     *  - a calendar year: the previous year, or the same days of it while in progress
     *    (1 Jan - 22 Nov vs 1 Jan - 22 Nov last year, not the whole of last year);
     *  - anything else (a week, a custom span): the span of equal length just before it,
     *    cut to the same number of elapsed days while it is in progress (Monday-Wednesday
     *    vs last Monday-Wednesday, not all of last week).
     */
    fun previousWindow(window: DateWindow, today: LocalDate): DateWindow {
        val running = inProgress(window, today)
        val month = YearMonth.from(window.start)
        val wholeMonth = window.start == month.atDay(1) && window.end == month.atEndOfMonth()
        val wholeYear = window.start.dayOfYear == 1 && window.start.year == window.end.year &&
            window.end.dayOfYear == window.end.lengthOfYear()
        return when {
            wholeMonth && running -> previousMonthToDate(today)
            wholeMonth -> month.minusMonths(1).let { DateWindow(it.atDay(1), it.atEndOfMonth()) }
            wholeYear && running -> DateWindow(window.start.minusYears(1), today.minusYears(1))
            wholeYear -> DateWindow(window.start.minusYears(1), window.end.minusYears(1))
            else -> {
                val length = java.time.temporal.ChronoUnit.DAYS.between(window.start, window.end) + 1
                val start = window.start.minusDays(length)
                val elapsed = if (running) java.time.temporal.ChronoUnit.DAYS.between(window.start, today) + 1 else length
                DateWindow(start, start.plusDays(elapsed - 1))
            }
        }
    }

    /** Net spending ([LedgerMath.netSpend]) this month so far vs the same days last month. */
    fun monthToDate(
        transactions: List<Transaction>,
        toBase: (Transaction) -> Double?,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Comparison? {
        val current = DateWindow(today.withDayOfMonth(1), today)
        return compare(transactions, toBase, current, previousMonthToDate(today), zone)
    }

    /**
     * [current] vs the window of equal length just before it -- for a
     * filtered view with a bounded date range.
     */
    fun priorPeriod(
        transactions: List<Transaction>,
        toBase: (Transaction) -> Double?,
        current: DateWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Comparison? {
        val days = java.time.temporal.ChronoUnit.DAYS.between(current.start, current.end) + 1
        val previous = DateWindow(current.start.minusDays(days), current.start.minusDays(1))
        return compare(transactions, toBase, current, previous, zone)
    }

    /** Null when neither window has any spending. */
    fun compare(
        transactions: List<Transaction>,
        toBase: (Transaction) -> Double?,
        current: DateWindow,
        previous: DateWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Comparison? {
        val now = LedgerMath.netSpend(transactions.filter { current.contains(it.timestampMillis, zone) }, value = toBase)
        val before = LedgerMath.netSpend(transactions.filter { previous.contains(it.timestampMillis, zone) }, value = toBase)
        if (now <= 0.0 && before <= 0.0) return null
        return Comparison(now, before, if (before > 0.0) (now - before) / before * 100.0 else null)
    }
}
