package com.xemmy.mmbank.ui.util

import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Currency
import java.util.Date
import java.util.Locale

/**
 * Decimal places to show for [currencyCode]: ISO-4217 default for fiat (JPY 0,
 * KWD 3), up to 8 for crypto tickers (trailing zeros trimmed by [asMoney]),
 * 2 for anything unrecognised.
 */
fun fractionDigitsFor(currencyCode: String): Int {
    if (currencyCode in CRYPTO_CODES) return 8
    return try {
        Currency.getInstance(currencyCode).defaultFractionDigits.let { if (it < 0) 2 else it }
    } catch (_: IllegalArgumentException) {
        2
    }
}

private val CRYPTO_CODES = setOf("BTC", "ETH", "USDT", "USDC", "BNB", "SOL", "XRP", "ADA", "DOGE", "MATIC", "BUSD", "DOT", "LTC", "TRX")

fun Double.asMoney(currencyCode: String): String {
    val max = fractionDigitsFor(currencyCode)
    val min = if (max > 2 && currencyCode in CRYPTO_CODES) 2 else max
    val nf = java.text.NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = min
        maximumFractionDigits = max
        roundingMode = java.math.RoundingMode.HALF_UP
    }
    return "${nf.format(this)} $currencyCode"
}

/**
 * Whole-currency-unit rendering (cents rounded off) for compact stat tiles
 * — e.g. the Dashboard's Income/Spent/Net — where a full 2-decimal figure
 * plus the drawn AED glyph doesn't reliably fit a narrow column. Display
 * only: the underlying amount stays full-precision everywhere else.
 */
fun Double.asMoneyWhole(currencyCode: String): String =
    String.format(Locale.US, "%,.0f %s", this, currencyCode)

/** What the hide-balances toggle shows instead of any amount. */
const val AMOUNT_MASK = "••••••"

/** All maskable money rendering goes through this so the eye toggle works everywhere. */
fun Double.asMoneyOrMask(currencyCode: String, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else asMoney(currencyCode)

/** [asMoneyWhole] variant of [asMoneyOrMask], for compact stat tiles. */
fun Double.asMoneyWholeOrMask(currencyCode: String, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else asMoneyWhole(currencyCode)

fun BigDecimal.asMoney(currencyCode: String): String =
    toDouble().asMoney(currencyCode)

/**
 * [asMoneyOrMask] for the exact type. Account balances are computed in
 * BigDecimal (see [com.xemmy.mmbank.domain.account.AccountBalanceCalculator]),
 * so they reach the UI without ever passing through a Double.
 */
fun BigDecimal.asMoneyOrMask(currencyCode: String, hidden: Boolean): String =
    if (hidden) AMOUNT_MASK else asMoney(currencyCode)

/**
 * Accepted currency-code length: 3–5 uppercase letters. Covers ISO-4217 fiat
 * (3, e.g. AED/USD) plus the crypto tickers the app now ingests — notably USDT
 * (4) from Binance, and up to 5 (MATIC). Single source of truth so the
 * transaction editor's validation and the split-typography formatter agree on
 * what a code is; before this, both hard-coded 3 and rejected USDT (an edited
 * USDT row could not be saved, and its code did not get the split styling).
 */
const val CURRENCY_CODE_MIN_LENGTH = 3
const val CURRENCY_CODE_MAX_LENGTH = 5

/** True for a trailing money token that is a 3–5 letter uppercase currency code. */
fun isCurrencyCode(token: String): Boolean =
    token.length in CURRENCY_CODE_MIN_LENGTH..CURRENCY_CODE_MAX_LENGTH && token.all { it in 'A'..'Z' }

/** The numeral and ISO-code halves of a rendered money string, for split styling. */
data class MoneyParts(val numeral: String, val currencyCode: String?)

/**
 * Splits a display string like "+1,234.56 AED" into its numeral half (sign
 * included) and trailing currency code, so the code can render smaller and
 * lighter than the figures. Strings without a trailing code — the ••••••
 * hide-balances mask, quantities, plain text — come back whole with a null code.
 */
fun splitMoneyDisplay(display: String): MoneyParts {
    val spaceAt = display.lastIndexOf(' ')
    if (spaceAt <= 0) return MoneyParts(display, null)
    val tail = display.substring(spaceAt + 1)
    return if (isCurrencyCode(tail)) {
        MoneyParts(display.substring(0, spaceAt), tail)
    } else {
        MoneyParts(display, null)
    }
}

/**
 * Splits a numeral like "+1,234.56" or "≈ 129,289.66" into its leading
 * sign/approximation prefix and the digits, so a drawn currency symbol can
 * slot between them ("-Đ85.00") the way banking apps place it. Strings that
 * start with a digit (or contain none at all) come back with an empty prefix.
 */
fun splitLeadingSign(numeral: String): Pair<String, String> {
    val firstDigit = numeral.indexOfFirst { it.isDigit() }
    return if (firstDigit <= 0) "" to numeral
    else numeral.substring(0, firstDigit) to numeral.substring(firstDigit)
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
