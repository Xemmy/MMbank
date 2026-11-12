package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import com.xemmy.mmbank.ui.util.asMoneyWholeOrMask
import com.xemmy.mmbank.ui.util.fractionDigitsFor
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/** Which semantic money colour an amount takes. Mapped to MMTheme.colors in [MoneyText]. */
enum class MoneyTone { IN, OUT, NEUTRAL }

/** Credits are [MoneyTone.IN], spending is [MoneyTone.OUT], everything else is neutral. */
fun moneyToneFor(kind: TransactionKind): MoneyTone = when (kind) {
    TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.CASHBACK -> MoneyTone.IN
    TransactionKind.SPEND, TransactionKind.ATM -> MoneyTone.OUT
    TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT, TransactionKind.ADJUSTMENT -> MoneyTone.NEUTRAL
}

private const val MINUS = "−"

/** "+" for credits, "−" for debits, nothing for internal moves; adjustments follow the amount's own sign. */
fun moneySignFor(kind: TransactionKind, amount: Double): String = when (kind) {
    TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.CASHBACK -> "+"
    TransactionKind.SPEND, TransactionKind.ATM -> MINUS
    TransactionKind.TRANSFER, TransactionKind.CARD_PAYMENT -> ""
    TransactionKind.ADJUSTMENT -> if (amount < 0) MINUS else "+"
}

/** Grouped magnitude with the currency's fraction digits (crypto trims to a 2-digit minimum). */
fun formatMoneyNumber(amount: Double, currencyCode: String): String {
    val max = fractionDigitsFor(currencyCode)
    val min = if (max > 2) 2 else max
    val nf = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = min
        maximumFractionDigits = max
        roundingMode = RoundingMode.HALF_UP
    }
    return nf.format(abs(amount))
}

/**
 * The string [MoneyText] hands to AmountText: "−46.12 AED", or the mask when
 * [hidden]. The trailing code is what AmountText splits off (and replaces
 * with the drawn Dirham symbol for AED).
 */
fun moneyDisplayString(amount: Double, currencyCode: String, kind: TransactionKind, hidden: Boolean): String {
    if (hidden) return AMOUNT_MASK
    return moneySignFor(kind, amount) + formatMoneyNumber(amount, currencyCode) + " " + currencyCode
}

private data class UnitNames(val majorOne: String, val majorMany: String, val minorOne: String, val minorMany: String)

private val UNIT_NAMES = mapOf(
    "AED" to UnitNames("dirham", "dirhams", "fils", "fils"),
    "USD" to UnitNames("dollar", "dollars", "cent", "cents"),
    "EUR" to UnitNames("euro", "euros", "cent", "cents"),
    "GBP" to UnitNames("pound", "pounds", "penny", "pence"),
)

private fun verbFor(kind: TransactionKind): String = when (kind) {
    TransactionKind.SPEND -> "spent"
    TransactionKind.ATM -> "withdrew"
    TransactionKind.INCOME -> "received"
    TransactionKind.REFUND -> "refunded"
    TransactionKind.CASHBACK -> "earned cashback of"
    TransactionKind.TRANSFER -> "transferred"
    TransactionKind.CARD_PAYMENT -> "paid card with"
    TransactionKind.ADJUSTMENT -> "adjusted by"
}

private fun plural(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"

/**
 * Spoken form for TalkBack, e.g. "spent 46 dirhams 12 fils". Known two-decimal
 * currencies read in major and minor units; anything else reads "<number> <CODE>".
 * Hidden amounts read "amount hidden" and never leak the figure.
 */
fun moneyContentDescription(amount: Double, currencyCode: String, kind: TransactionKind, hidden: Boolean): String {
    if (hidden) return "amount hidden"
    val verb = verbFor(kind)
    val names = UNIT_NAMES[currencyCode]
    if (names == null || fractionDigitsFor(currencyCode) != 2) {
        return "$verb ${formatMoneyNumber(amount, currencyCode)} $currencyCode"
    }
    val cents = BigDecimal.valueOf(abs(amount)).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
    val major = cents / 100
    val minor = cents % 100
    val spoken = when {
        major == 0L && minor == 0L -> "0 ${names.majorMany}"
        major == 0L -> plural(minor, names.minorOne, names.minorMany)
        minor == 0L -> plural(major, names.majorOne, names.majorMany)
        else -> plural(major, names.majorOne, names.majorMany) + " " + plural(minor, names.minorOne, names.minorMany)
    }
    return "$verb $spoken"
}

/**
 * Plain money string for running text (sentences, captions, summary lines)
 * where a coloured [MoneyText] cannot be embedded. Keeps the amount's own sign
 * and the app-wide number format; the mask when [hidden].
 */
fun moneyLabel(amount: Double, currencyCode: String, hidden: Boolean = false): String =
    amount.asMoneyOrMask(currencyCode, hidden)

/** [moneyLabel] for exact balances held as BigDecimal. */
fun moneyLabel(amount: BigDecimal, currencyCode: String, hidden: Boolean = false): String =
    amount.asMoneyOrMask(currencyCode, hidden)

/** [moneyLabel] rounded to whole units, for compact stat tiles. */
fun moneyLabelWhole(amount: Double, currencyCode: String, hidden: Boolean = false): String =
    amount.asMoneyWholeOrMask(currencyCode, hidden)
