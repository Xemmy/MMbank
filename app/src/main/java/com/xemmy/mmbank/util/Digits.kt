package com.xemmy.mmbank.util

/**
 * One place that turns digits into plain ASCII. Banks and Arabic keyboards
 * write Arabic-Indic (٠-٩) and Extended Arabic-Indic (۰-۹) digits; Kotlin's
 * [Char.isDigit] accepts them but Java regex `\d` does not, so "١٢٣٤" and
 * "1234" would otherwise compare as different numbers.
 */
object Digits {

    private fun isArabicIndic(c: Char) = c in '٠'..'٩' || c in '۰'..'۹'

    /** [text] with every Arabic-Indic digit replaced by its ASCII digit; everything else unchanged. */
    fun fold(text: String): String {
        if (text.none(::isArabicIndic)) return text
        return buildString(text.length) {
            for (c in text) {
                append(
                    when (c) {
                        in '٠'..'٩' -> '0' + (c - '٠')
                        in '۰'..'۹' -> '0' + (c - '۰')
                        else -> c
                    },
                )
            }
        }
    }

    /** Only the digits of [text], as ASCII ("AE07-٣٤" -> "0734"); empty for null. */
    fun only(text: String?): String = fold(text.orEmpty()).filter { it in '0'..'9' }
}
