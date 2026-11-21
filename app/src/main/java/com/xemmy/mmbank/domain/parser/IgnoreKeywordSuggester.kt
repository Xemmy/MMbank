package com.xemmy.mmbank.domain.parser

/**
 * Proposes the phrase to pre-fill when the user chooses "Delete & ignore
 * containing…" on a wrongly captured message. The user can always edit it.
 */
object IgnoreKeywordSuggester {

    private const val MIN_TOKEN = 4

    /**
     * The merchant when it appears verbatim in [rawMessage] (it is what the
     * parser keyed on), otherwise the longest purely alphabetic word of at
     * least [MIN_TOKEN] letters. Blank when the message has neither.
     */
    fun suggest(rawMessage: String?, merchant: String?): String {
        val raw = rawMessage?.trim().orEmpty()
        if (raw.isEmpty()) return ""
        val m = merchant?.trim().orEmpty()
        if (m.length >= 3 && raw.contains(m, ignoreCase = true)) return m
        return raw.split(Regex("[^\\p{L}]+"))
            .filter { it.length >= MIN_TOKEN }
            .maxByOrNull { it.length }
            .orEmpty()
    }

    /** True when [keyword] is long enough to store and actually occurs in [rawMessage]. */
    fun isValid(keyword: String, rawMessage: String?): Boolean {
        val k = keyword.trim()
        return k.length >= 3 && rawMessage?.contains(k, ignoreCase = true) == true
    }
}
