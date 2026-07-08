package com.xemmy.mmbank.ui.util

import java.math.BigDecimal
import java.text.SimpleDateFormat
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

/** Crypto quantities keep full precision instead of 2-decimal money formatting. */
fun BigDecimal.asQuantity(): String = stripTrailingZeros().toPlainString()

private val dateTimeFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)

fun Long.asDateTime(): String = dateTimeFormat.format(Date(this))

private val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)

fun Long.asDate(): String = dateFormat.format(Date(this))

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
