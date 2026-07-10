package com.xemmy.mmbank.domain.parser

import java.math.BigDecimal

/**
 * Generic fallback extraction for signature-routed messages: when a rule is
 * selected by plain-text signature instead of a regex capture pattern, these
 * shared extractors recover the amount/currency and (best-effort) merchant
 * from the raw text.
 *
 * Both functions expect [TextNormalizer]-normalized input (ASCII digits,
 * collapsed whitespace), which is what [TransactionParser] matches against.
 */
object GenericExtractors {

    /** Amount plus the currency token found next to it (null if unrecognizable). */
    data class ExtractedAmount(val amount: BigDecimal, val currencyCode: String?)

    // Same restricted currency vocabulary as BuiltInRules: known ISO codes,
    // symbols and Arabic abbreviations only, so stray 3-letter words are never
    // mistaken for a currency under IGNORE_CASE.
    private const val CUR =
        "USD|EUR|GBP|SAR|AED|EGP|KWD|QAR|OMR|BHD|JOD|LBP|IQD|TRY|INR|PKR|JPY|CNY|CHF|CAD|AUD" +
            "|[$€£₹₺¥]|ر\\.\\s?س|د\\.\\s?إ|ج\\.\\s?م|د\\.\\s?ك|ر\\.\\s?ق|ر\\.\\s?ع|د\\.\\s?ب|د\\.\\s?ا|ل\\.\\s?ل|ريال|درهم|جنيه"

    // "Plausible" number: optional thousands separators, at most 2 decimals.
    // The (?!\.?\d) guard after each use rejects longer decimal tails
    // (46.123) instead of silently truncating them to 46.12.
    private const val NUM = "\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?"

    // One flexible pattern for every currency-adjacent layout seen in the
    // reference notifications: "AED 46.12", "AED229.59" (non-spaced),
    // "46.12 AED" (trailing currency), "1,234.56" thousands. A bare number
    // with no adjacent currency is deliberately NOT plausible — signature
    // routing must never guess.
    private val AMOUNT = Regex(
        "(?<![A-Za-z])(?<cur1>$CUR)\\s?(?<amt1>$NUM)(?!\\.?\\d)" +
            "|(?<amt2>$NUM)(?!\\.?\\d)\\s?(?<cur2>$CUR)(?![A-Za-z])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Returns the first plausible currency-adjacent amount in text order, or
     * null when none exists. Zero and negative values are not plausible
     * (a fabricated zero-amount transaction is worse than an unparsed message),
     * so scanning continues past them.
     */
    fun extractAmount(text: String): ExtractedAmount? {
        for (match in AMOUNT.findAll(text)) {
            val raw = match.groupOrNull("amt1") ?: match.groupOrNull("amt2") ?: continue
            val amount = raw.replace(",", "").toBigDecimalOrNull() ?: continue
            if (amount.signum() <= 0) continue
            val currency = (match.groupOrNull("cur1") ?: match.groupOrNull("cur2"))
                ?.let(CurrencyNormalizer::normalize)
            return ExtractedAmount(amount, currency)
        }
        return null
    }

    // Merchant clause: text trailing " at " / " to " up to the next sentence,
    // clause or line boundary. The lookahead also stops before trailing status
    // verbs ("was successful", "is confirmed"), attached-card phrases ("with
    // your ... Mastercard"), dates ("on 12/06") and reference codes, per the
    // notification shapes this scraper exists for.
    private val MERCHANT_CLAUSE = Regex(
        "(?:^|\\s)(?:at|to)\\s+(?<merchant>[^.,;:!?\\n]{2,40}?)" +
            "(?=\\s+(?:was|is|has|had|will|were|with|using|via|ref|txn)\\b|\\s+on\\s+\\d|[.,;:!?\\n]|$)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Best-effort merchant extraction. Primary: the first " at "/" to " clause.
     * Fallback: a prominent vendor label heading the notification ("Binance
     * Mastercard: …"). Returns null when neither yields anything usable —
     * merchant failure is non-fatal for signature routing.
     */
    fun extractMerchant(text: String): String? {
        MERCHANT_CLAUSE.find(text)
            ?.groupOrNull("merchant")
            ?.let(::clean)
            ?.let { return it }

        // Header fallback: "Vendor Label: rest of the message". Digits in the
        // header usually mean it is an amount/date fragment, not a vendor.
        val header = text.substringBefore(':', missingDelimiterValue = "").trim()
        if (header.length in 2..40 && header.any { it.isLetter() } && header.none { it.isDigit() }) {
            return clean(header)
        }
        return null
    }

    private fun clean(raw: String): String? = raw
        .trim()
        .trimEnd('.', ',', '-', ':', '!', '?')
        .trim()
        .takeIf { it.length >= 2 }
}
