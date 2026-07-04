package com.xemmy.mmbank.domain.parser

/**
 * Normalizes captured message text before regex matching so a single set of
 * patterns can handle mixed-language bank messages:
 *  - Arabic-Indic (٠١٢٣٤٥٦٧٨٩) and Eastern Arabic-Indic (۰۱۲۳۴۵۶۷۸۹) digits -> ASCII
 *  - Arabic decimal (٫) -> '.' and Arabic thousands separator (٬) -> ','
 *  - Strips bidi control characters that banks embed in RTL messages
 *  - Collapses runs of whitespace
 */
object TextNormalizer {

    private val BIDI_CONTROLS = Regex("[\\u200e\\u200f\\u061c\\u202a-\\u202e\\u2066-\\u2069]")
    private val WHITESPACE = Regex("\\s+")

    fun normalize(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                in '٠'..'٩' -> sb.append(('0' + (ch - '٠')))   // ٠-٩
                in '۰'..'۹' -> sb.append(('0' + (ch - '۰')))   // ۰-۹
                '٫' -> sb.append('.')                                     // ٫ arabic decimal
                '٬' -> sb.append(',')                                     // ٬ arabic thousands
                else -> sb.append(ch)
            }
        }
        return sb.toString()
            .replace(BIDI_CONTROLS, "")
            .replace(WHITESPACE, " ")
            .trim()
    }
}
