package com.xemmy.mmbank.domain.filter

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The unit a [PeriodWindow] steps in. */
enum class PeriodUnit {
    WEEK, MONTH, YEAR, ALL, CUSTOM;

    companion object {
        /** Parses a stored pref; ALL and CUSTOM are never remembered, so they fall back to MONTH. */
        fun fromStored(value: String?): PeriodUnit =
            listOf(WEEK, MONTH, YEAR).firstOrNull { it.name == value } ?: MONTH
    }
}

/**
 * One period the whole app can step through: a week, month or year around [anchor], all time,
 * or an arbitrary custom span. Pure: "today" and the first day of the week are parameters.
 */
data class PeriodWindow(
    val unit: PeriodUnit,
    /** Any day inside the window (WEEK / MONTH / YEAR). Unused for ALL and CUSTOM. */
    val anchor: LocalDate,
    val customStart: LocalDate? = null,
    val customEnd: LocalDate? = null,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
) {
    /** Inclusive day range, or null for ALL (unbounded). */
    fun range(): DateWindow? = when (unit) {
        PeriodUnit.WEEK -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(weekStart))
            DateWindow(start, start.plusDays(6))
        }
        PeriodUnit.MONTH -> DateWindow(anchor.withDayOfMonth(1), anchor.withDayOfMonth(anchor.lengthOfMonth()))
        PeriodUnit.YEAR -> DateWindow(LocalDate.of(anchor.year, 1, 1), LocalDate.of(anchor.year, 12, 31))
        PeriodUnit.ALL -> null
        PeriodUnit.CUSTOM -> {
            val a = customStart ?: anchor
            val b = customEnd ?: anchor
            if (a <= b) DateWindow(a, b) else DateWindow(b, a)
        }
    }

    fun previous(): PeriodWindow = step(-1)

    fun next(): PeriodWindow = step(1)

    private fun step(direction: Int): PeriodWindow = when (unit) {
        PeriodUnit.WEEK -> copy(anchor = anchor.plusWeeks(direction.toLong()))
        // plusMonths clamps the day (31 Jan + 1 month = 28/29 Feb).
        PeriodUnit.MONTH -> copy(anchor = anchor.plusMonths(direction.toLong()))
        PeriodUnit.YEAR -> copy(anchor = anchor.plusYears(direction.toLong()))
        PeriodUnit.ALL -> this
        PeriodUnit.CUSTOM -> {
            val r = range()!!
            val days = ChronoUnit.DAYS.between(r.start, r.end) + 1
            copy(
                customStart = r.start.plusDays(days * direction),
                customEnd = r.end.plusDays(days * direction),
            )
        }
    }

    /** Stepping forward is allowed only while the next window starts on or before [today]. */
    fun canGoNext(today: LocalDate): Boolean {
        if (unit == PeriodUnit.ALL) return false
        val start = next().range()?.start ?: return false
        return start <= today
    }

    fun isCurrent(today: LocalDate): Boolean = when (unit) {
        PeriodUnit.ALL -> true
        PeriodUnit.CUSTOM -> false
        else -> range()!!.let { today >= it.start && today <= it.end }
    }

    /** "This week", "Last month", "Mar 2026", "2025", "All time", or the custom dates. */
    fun label(today: LocalDate): String = when (unit) {
        PeriodUnit.ALL -> "All time"
        PeriodUnit.CUSTOM -> range()!!.let { "${it.start.format(DAY_MONTH_YEAR)} – ${it.end.format(DAY_MONTH_YEAR)}" }
        PeriodUnit.WEEK -> when {
            isCurrent(today) -> "This week"
            next().isCurrent(today) -> "Last week"
            else -> range()!!.let { "${it.start.format(DAY_MONTH)} – ${it.end.format(DAY_MONTH)}" }
        }
        PeriodUnit.MONTH -> when {
            isCurrent(today) -> "This month"
            next().isCurrent(today) -> "Last month"
            else -> anchor.format(MONTH_YEAR)
        }
        PeriodUnit.YEAR -> when {
            isCurrent(today) -> "This year"
            next().isCurrent(today) -> "Last year"
            else -> anchor.year.toString()
        }
    }

    /**
     * Sub-windows for a chart: days for a week, month or short custom span; months for a year,
     * a long custom span and all time. ALL needs [earliest] (the oldest transaction day) and is
     * empty without it.
     */
    fun buckets(today: LocalDate, earliest: LocalDate?): List<DateWindow> {
        val range = range()
            ?: earliest?.let { DateWindow(it, today) }
            ?: return emptyList()
        val days = ChronoUnit.DAYS.between(range.start, range.end) + 1
        val byDay = when (unit) {
            PeriodUnit.WEEK, PeriodUnit.MONTH -> true
            PeriodUnit.CUSTOM -> days <= 62
            else -> false
        }
        if (byDay) {
            return (0 until days).map { range.start.plusDays(it).let { d -> DateWindow(d, d) } }
        }
        val out = mutableListOf<DateWindow>()
        var cursor = range.start.withDayOfMonth(1)
        while (cursor <= range.end) {
            out += DateWindow(maxOf(cursor, range.start), minOf(cursor.withDayOfMonth(cursor.lengthOfMonth()), range.end))
            cursor = cursor.plusMonths(1)
        }
        return out
    }

    companion object {
        private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.US)
        private val DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)

        fun of(unit: PeriodUnit, today: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): PeriodWindow =
            PeriodWindow(unit, today, weekStart = weekStart)

        /** The trailing [months] whole months ending with the current one, as a custom span. */
        fun lastMonths(months: Int, today: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): PeriodWindow {
            val start = today.withDayOfMonth(1).minusMonths((months - 1).toLong())
            return PeriodWindow(
                PeriodUnit.CUSTOM, today, customStart = start,
                customEnd = today.withDayOfMonth(today.lengthOfMonth()), weekStart = weekStart,
            )
        }

        fun custom(start: LocalDate, end: LocalDate, weekStart: DayOfWeek = DayOfWeek.MONDAY): PeriodWindow =
            PeriodWindow(PeriodUnit.CUSTOM, start, customStart = start, customEnd = end, weekStart = weekStart)
    }
}
