package com.xemmy.mmbank.domain.parser

/** Maps currency symbols and localized abbreviations to ISO 4217 codes. */
object CurrencyNormalizer {

    private val SYMBOLS = mapOf(
        "$" to "USD",
        "€" to "EUR",
        "£" to "GBP",
        "₹" to "INR",
        "₺" to "TRY",
        "¥" to "JPY",
        // Common Arabic currency abbreviations
        "ر.س" to "SAR",
        "ريال" to "SAR",
        "د.إ" to "AED",
        "درهم" to "AED",
        "ج.م" to "EGP",
        "جنيه" to "EGP",
        "د.ك" to "KWD",
        "د.ب" to "BHD",
        "ر.ق" to "QAR",
        "ر.ع" to "OMR",
        "د.ا" to "JOD",
        "ل.ل" to "LBP",
    )

    /** Returns an ISO-ish uppercase code, or null if the token is unrecognizable. */
    fun normalize(raw: String): String? {
        val token = raw.trim()
        if (token.isEmpty()) return null
        SYMBOLS[token]?.let { return it }
        // Tolerate spacing variants of dotted Arabic abbreviations, e.g. "ر . س"
        SYMBOLS[token.replace(" ", "")]?.let { return it }
        // USDT (Tether) is a real currency for Binance transactions, not a
        // typo of USD — preserve it rather than truncating to the 3-letter case below.
        if (token.equals("USDT", ignoreCase = true)) return "USDT"
        // "Dhs"/"Dh" are common Latin spellings of the dirham.
        if (token.equals("DHS", ignoreCase = true) || token.equals("DH", ignoreCase = true)) return "AED"
        if (token.length == 3 && token.all { it.isLetter() }) return token.uppercase()
        return null
    }
}
