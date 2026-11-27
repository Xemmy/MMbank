package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.Subscription
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** What active subscriptions cost per month and year, per currency, counting parallel `lines`. */
object SubscriptionTotals {

    data class Total(val currency: String, val perMonth: BigDecimal, val perYear: BigDecimal)

    /** What one renewal takes: the price × its parallel [Subscription.lines]. */
    fun perCharge(subscription: Subscription): BigDecimal =
        subscription.averageAmount.multiply(BigDecimal(subscription.lines.coerceAtLeast(1)))

    /**
     * Monthly cost of one row. Month-based cadences are exact (a quarterly 30 is 10 a month);
     * weekly ones spread 365 days of charges over 12 months.
     */
    fun monthly(subscription: Subscription): BigDecimal {
        val charge = perCharge(subscription)
        val months = subscription.cadence.months
        return if (months != null) {
            charge.divide(BigDecimal(months), 2, RoundingMode.HALF_UP)
        } else {
            charge.multiply(BigDecimal(DAYS_PER_YEAR))
                .divide(BigDecimal(subscription.cadence.nominalDays * 12), 2, RoundingMode.HALF_UP)
        }
    }

    /**
     * Yearly cost of one row: a yearly 140 is 140 (it read 138.08 when the year was taken as
     * twelve 30-day months), a monthly 10 is 120, a weekly 10 is 365/7 charges.
     */
    fun yearly(subscription: Subscription): BigDecimal {
        val charge = perCharge(subscription)
        val months = subscription.cadence.months
        return if (months != null) {
            charge.multiply(BigDecimal(12)).divide(BigDecimal(months), 2, RoundingMode.HALF_UP)
        } else {
            charge.multiply(BigDecimal(DAYS_PER_YEAR)).divide(BigDecimal(subscription.cadence.nominalDays), 2, RoundingMode.HALF_UP)
        }
    }

    private const val DAYS_PER_YEAR = 365

    /** One entry per currency, biggest first. Dismissed rows are ignored. */
    fun byCurrency(subscriptions: List<Subscription>): List<Total> =
        subscriptions.filter { !it.dismissed }
            .groupBy { it.currencyCode.uppercase() }
            .map { (currency, rows) ->
                Total(
                    currency,
                    rows.fold(BigDecimal.ZERO) { acc, s -> acc + monthly(s) },
                    rows.fold(BigDecimal.ZERO) { acc, s -> acc + yearly(s) },
                )
            }
            .sortedByDescending { it.perMonth }

    /** "5 active" plus one "AED 140.00/month · AED 1,680.00/year" line per currency. */
    fun summaryLines(subscriptions: List<Subscription>, money: (BigDecimal, String) -> String): List<String> {
        val active = subscriptions.count { !it.dismissed }
        if (active == 0) return emptyList()
        val totals = byCurrency(subscriptions)
        return listOf("$active active") + totals.map { "${money(it.perMonth, it.currency)}/month · ${money(it.perYear, it.currency)}/year" }
    }
}

/** Overdue / due this week / later, each soonest first. */
object SubscriptionGroups {

    data class Groups(val overdue: List<Subscription>, val thisWeek: List<Subscription>, val later: List<Subscription>)

    fun group(subscriptions: List<Subscription>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Groups {
        fun days(s: Subscription) = ChronoUnit.DAYS.between(today, Instant.ofEpochMilli(s.nextDueDateMillis).atZone(zone).toLocalDate())
        val sorted = subscriptions.sortedBy { it.nextDueDateMillis }
        return Groups(
            overdue = sorted.filter { days(it) < 0 },
            thisWeek = sorted.filter { days(it) in 0..7 },
            later = sorted.filter { days(it) > 7 },
        )
    }
}
