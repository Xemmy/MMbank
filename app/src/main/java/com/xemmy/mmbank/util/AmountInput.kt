package com.xemmy.mmbank.util

import java.math.BigDecimal

/**
 * Parses an amount the way people type it, in either locale convention:
 * "1,250.50", "12,50", "1.250,50", "2 000", " -300 ".
 *
 * The old per-screen `replace(",", "")` turned the decimal comma "12,50"
 * into 1250 -- a x100 error saved silently. The rule here: whichever of
 * `,` / `.` appears LAST is the decimal mark when both are present; a lone
 * comma followed by one or two digits is a decimal comma; a comma followed
 * by exactly three digits (or repeated commas) is a thousands separator.
 *
 * Null for blank or unparseable input.
 */
object AmountInput {
    fun parse(raw: String): BigDecimal? {
        var s = raw.trim()
            .replace(" ", "").replace(" ", "").replace(" ", "").replace("'", "")
        if (s.isEmpty()) return null
        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        s = when {
            lastComma >= 0 && lastDot >= 0 ->
                if (lastComma > lastDot) s.replace(".", "").replace(',', '.')
                else s.replace(",", "")
            lastComma >= 0 -> {
                val commas = s.count { it == ',' }
                val fractionDigits = s.length - lastComma - 1
                if (commas == 1 && fractionDigits in 1..2) s.replace(',', '.')
                else s.replace(",", "")
            }
            lastDot >= 0 && s.count { it == '.' } > 1 -> s.replace(".", "")
            else -> s
        }
        return s.toBigDecimalOrNull()
    }
}
