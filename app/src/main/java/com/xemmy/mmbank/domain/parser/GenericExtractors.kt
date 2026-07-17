package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionDirection
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
        "USD|USDT|EUR|GBP|SAR|AED|EGP|KWD|QAR|OMR|BHD|JOD|LBP|IQD|TRY|INR|PKR|JPY|CNY|CHF|CAD|AUD" +
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
        "(?<![A-Za-z])(?<cur1>$CUR)(?![A-Za-z])\\s?(?<amt1>$NUM)(?!\\.?\\d)" +
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

    private val CREDIT_DIRECTION_KEYWORDS = Regex("\\b(?:received|incoming|deposited)\\b", RegexOption.IGNORE_CASE)
    private val DEBIT_DIRECTION_KEYWORDS = Regex("\\b(?:paid|purchased|sent|spent)\\b", RegexOption.IGNORE_CASE)

    /**
     * Direction implied by explicit wording in [text], for signature-routed
     * rules whose direction is otherwise a single static default regardless
     * of content (e.g. "Binance Mastercard" defaults to DEBIT, but a message
     * that actually says "received" must still credit the account). Returns
     * null when neither vocabulary is present, so the caller keeps the rule's
     * own default; when both appear, the earlier keyword in the text wins.
     */
    fun detectDirectionOverride(text: String): TransactionDirection? {
        val creditAt = CREDIT_DIRECTION_KEYWORDS.find(text)?.range?.first
        val debitAt = DEBIT_DIRECTION_KEYWORDS.find(text)?.range?.first
        return when {
            creditAt == null && debitAt == null -> null
            debitAt == null || (creditAt != null && creditAt < debitAt) -> TransactionDirection.CREDIT
            else -> TransactionDirection.DEBIT
        }
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
     * Fallbacks: the prominent app header / vendor label — the notification
     * [title] when the merchant IS the title (the 99123.jpg Binance Mastercard
     * shape: title "CARREFOUR-MAF MKT YAS", body "AED229.59 with Binance
     * Mastercard ••2957"), else a leading "Vendor Label: …" segment. Returns
     * null when nothing usable is found — merchant failure is non-fatal for
     * signature routing.
     */
    fun extractMerchant(text: String, title: String? = null): String? {
        MERCHANT_CLAUSE.find(text)
            ?.groupOrNull("merchant")
            ?.let(::clean)
            ?.let { return it }

        // Digits in a header candidate usually mean it is an amount/date
        // fragment ("Transaction of AED 46.12"), not a vendor label.
        title?.trim()
            ?.takeIf { it.length in 2..40 && it.any { c -> c.isLetter() } && it.none { c -> c.isDigit() } }
            ?.let { return clean(it) }

        val header = text.substringBefore(':', missingDelimiterValue = "").trim()
        if (header.length in 2..40 && header.any { it.isLetter() } && header.none { it.isDigit() }) {
            return clean(header)
        }
        return null
    }

    // Card/account suffix markers: "*617", "••2957", "•• 2957", "XXX810001"
    // after a marker. Kept separate from the regex rules' ACCTAIL because
    // signature-routed messages never run a regex at all.
    private val ACCOUNT_SUFFIX = Regex("[*•]+\\s?(?<suffix>[Xx]*\\d{3,9})")

    /** Account/card number suffix ("2957" from "••2957"), or null. */
    fun extractAccountSuffix(text: String): String? =
        ACCOUNT_SUFFIX.find(text)?.groupOrNull("suffix")?.takeIf { it.isNotBlank() }

    private fun clean(raw: String): String? = raw
        .trim()
        .trimEnd('.', ',', '-', ':', '!', '?')
        .trim()
        .takeIf { it.length >= 2 }
}
