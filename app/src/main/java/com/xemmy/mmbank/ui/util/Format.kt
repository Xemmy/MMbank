package com.xemmy.mmbank.ui.util

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

fun Double.asMoney(currencyCode: String): String =
    String.format(Locale.US, "%,.2f %s", this, currencyCode)

/** What the hide-balances toggle shows instead of any amount. */
const val AMOUNT_MASK = "••••••"

/** All maskable money rendering goes through this so the eye toggle works everywhere. */
fun Double.asMoneyOrMask(currencyCode: String, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else asMoney(currencyCode)

fun BigDecimal.asMoney(currencyCode: String): String =
    toDouble().asMoney(currencyCode)

/** The numeral and ISO-code halves of a rendered money string, for split styling. */
data class MoneyParts(val numeral: String, val currencyCode: String?)

/**
 * Splits a display string like "+1,234.56 AED" into its numeral half (sign
 * included) and trailing 3-letter ISO code, so the code can render smaller and
 * lighter than the figures. Strings without a trailing code — the ••••••
 * hide-balances mask, quantities, plain text — come back whole with a null code.
 */
fun splitMoneyDisplay(display: String): MoneyParts {
    val spaceAt = display.lastIndexOf(' ')
    if (spaceAt <= 0) return MoneyParts(display, null)
    val tail = display.substring(spaceAt + 1)
    return if (tail.length == 3 && tail.all { it in 'A'..'Z' }) {
        MoneyParts(display.substring(0, spaceAt), tail)
    } else {
        MoneyParts(display, null)
    }
}

/** Crypto quantities keep full precision instead of 2-decimal money formatting. */
fun BigDecimal.asQuantity(): String = stripTrailingZeros().toPlainString()

private val dateTimeFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)

fun Long.asDateTime(): String = dateTimeFormat.format(Date(this))

private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

fun Long.asDate(): String = dateFormat.format(Date(this))

/** Calendar day a timestamp falls on, in [zone] — the Activity feed's day-pill grouping key. */
fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/**
 * Floating day-pill label for the Activity feed (Phase 5 spec 5.3), e.g.
 * "TODAY · 10 JUL" or "3 JUL" for older days.
 */
fun dayPillLabel(day: LocalDate, today: LocalDate = LocalDate.now()): String {
    val monthAbbrev = day.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(Locale.US)
    val datePart = "${day.dayOfMonth} $monthAbbrev"
    return when (day) {
        today -> "TODAY · $datePart"
        today.minusDays(1) -> "YESTERDAY · $datePart"
        else -> datePart
    }
}

/**
 * Converts amounts into the app's base currency using Forex rates fetched with
 * that base ("how many quote units per 1 base unit").
 */
object CurrencyMath {

    /** @param ratesFromBase quoteCode -> rate, where the map's base is [baseCurrency]. */
    fun toBase(
        amount: Double,
        fromCurrency: String,
        baseCurrency: String,
        ratesFromBase: Map<String, Double>,
    ): Double? = when {
        fromCurrency.equals(baseCurrency, ignoreCase = true) -> amount
        else -> ratesFromBase[fromCurrency.uppercase()]
            ?.takeIf { it != 0.0 }
            ?.let { amount / it }
    }
}
