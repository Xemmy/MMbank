package com.xemmy.mmbank.domain.account

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The windows of the Accounts wealth line: 7D, 1M, 1Y and everything the ledger can speak for. */
enum class WealthRange(val label: String) {
    WEEK("7D"),
    MONTH("1M"),
    YEAR("1Y"),
    MAX("Max"),
}

object WealthDays {
    const val DEFAULT_MAX_POINTS = 90

    /** First day of [range] ending [today]; Max starts at [earliest] (the oldest transaction day), never after today. */
    fun start(range: WealthRange, today: LocalDate, earliest: LocalDate?): LocalDate = when (range) {
        WealthRange.WEEK -> today.minusDays(6)
        WealthRange.MONTH -> today.minusDays(29)
        WealthRange.YEAR -> today.minusDays(364)
        WealthRange.MAX -> (earliest ?: today.minusDays(29)).let { if (it > today) today else it }
    }

    /**
     * The days to plot from [start] to [end], inclusive: every day while that is at most
     * [maxPoints], else evenly spaced days (always including both ends) so a long history stays
     * cheap to replay. Oldest first, no duplicates.
     */
    fun sample(start: LocalDate, end: LocalDate, maxPoints: Int = DEFAULT_MAX_POINTS): List<LocalDate> {
        if (end < start) return listOf(end)
        val span = ChronoUnit.DAYS.between(start, end).toInt()
        if (span + 1 <= maxPoints || maxPoints < 2) return (0..span).map { start.plusDays(it.toLong()) }
        return (0 until maxPoints)
            .map { i -> start.plusDays(Math.round(i.toDouble() * span / (maxPoints - 1))) }
            .distinct()
    }
}
