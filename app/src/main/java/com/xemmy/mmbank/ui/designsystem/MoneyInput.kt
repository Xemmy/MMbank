package com.xemmy.mmbank.ui.designsystem

import java.math.BigDecimal

/** Shown under a money field that holds something that isn't an amount. */
const val MONEY_FIELD_HINT = "Enter an amount like 125.50"

private val PLAIN_AMOUNT = Regex("""^\d*\.?\d+$|^\d+\.$""")
private val GROUPED_AMOUNT = Regex("""^\d{1,3}(,\d{3})+(\.\d+)?$""")

/**
 * Parses what a person typed into an amount. Accepts "125.50", ".5", "1,250.50";
 * returns null for blank, negative, signed, or otherwise unparseable text.
 */
fun parseMoneyInput(text: String): BigDecimal? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val cleaned = when {
        GROUPED_AMOUNT.matches(t) -> t.replace(",", "")
        PLAIN_AMOUNT.matches(t) -> t
        else -> return null
    }
    return cleaned.toBigDecimalOrNull()
}

/**
 * Live input filter for the decimal keypad: keeps digits, thousands commas and a
 * single decimal point, dropping everything else (pasted text, spaces, signs).
 */
fun sanitizeMoneyInput(text: String): String {
    val sb = StringBuilder()
    var seenDot = false
    for (c in text) {
        if (c.isDigit() || c == ',') {
            sb.append(c)
        } else if (c == '.' && !seenDot) {
            sb.append(c)
            seenDot = true
        }
    }
    return sb.toString()
}

/**
 * The error to show under a money field, or null when it's fine. Blank is fine
 * unless [required]; callers pass `required = false` until the field is touched.
 */
fun moneyFieldError(text: String, maxFractionDigits: Int = 2, required: Boolean = false): String? {
    if (text.isBlank()) return if (required) MONEY_FIELD_HINT else null
    val value = parseMoneyInput(text) ?: return MONEY_FIELD_HINT
    if (value.signum() <= 0) return "Amount must be greater than 0"
    val decimals = text.trim().substringAfter('.', "").length
    if (decimals > maxFractionDigits) {
        return if (maxFractionDigits == 0) "Use a whole number" else "Use at most $maxFractionDigits decimal places"
    }
    return null
}
