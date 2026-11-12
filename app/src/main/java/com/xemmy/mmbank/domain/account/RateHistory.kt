package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.ExchangeRateOnDay
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Daily forex-rate history, so a past month can be valued at the rates of
 * that time instead of today's (backlog B2).
 *
 * Pure: building the day's snapshot to store, and looking a rate up for a
 * date. The provider only serves the latest rates, so history can only grow
 * from the day this shipped; [rateOn] therefore falls back gracefully for
 * dates with nothing recorded.
 */
object RateHistory {

    /**
     * The rows to store for [day]: one per held currency that has a positive
     * rate in [ratesFromBase] (quote per 1 [base]). The base itself and
     * currencies the user doesn't hold are left out, which keeps the table to
     * a handful of rows a day instead of the provider's ~160.
     */
    fun snapshot(
        base: String,
        ratesFromBase: Map<String, BigDecimal>,
        heldCodes: Collection<String>,
        day: LocalDate,
    ): List<ExchangeRateOnDay> {
        val baseCode = base.trim().uppercase()
        return heldCodes
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() && it != baseCode }
            .distinct()
            .mapNotNull { code ->
                ratesFromBase[code]?.takeIf { it.signum() > 0 }
                    ?.let { ExchangeRateOnDay(baseCode, code, day, it) }
            }
    }

    /**
     * The rate for [quote] on [day]: the most recent recording on or before
     * it (a rate holds until the next refresh), else the earliest recording
     * after it (the best available for a date before history began), else
     * null. [history] needn't be sorted; only rows for [quote] count.
     */
    fun rateOn(history: List<ExchangeRateOnDay>, quote: String, day: LocalDate): BigDecimal? {
        val rows = history.filter { it.quoteCode.equals(quote, ignoreCase = true) && it.rate.signum() > 0 }
        val onOrBefore = rows.filter { !it.day.isAfter(day) }.maxByOrNull { it.day }
        if (onOrBefore != null) return onOrBefore.rate
        return rows.minByOrNull { it.day }?.rate
    }

    /**
     * The rates to value [day] with: today's [todayRates], overridden per
     * currency by what was recorded for [day]. A null [day] (the current
     * period) or an empty [history] leaves today's rates untouched.
     */
    fun overlay(
        todayRates: Map<String, BigDecimal>,
        history: List<ExchangeRateOnDay>,
        day: LocalDate?,
    ): Map<String, BigDecimal> =
        if (day == null || history.isEmpty()) todayRates else todayRates + ratesOn(history, day)

    /**
     * [rateOn] for every quote currency in [history], as the quote -> rate map
     * the converters take. A quote with no usable rate is simply absent.
     */
    fun ratesOn(history: List<ExchangeRateOnDay>, day: LocalDate): Map<String, BigDecimal> =
        history.map { it.quoteCode.uppercase() }.distinct()
            .mapNotNull { quote -> rateOn(history, quote, day)?.let { quote to it } }
            .toMap()
}
