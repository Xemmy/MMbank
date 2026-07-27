package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.MerchantCategoryMapping

/**
 * Resolves a merchant string to a learned category id via
 * [MerchantCategoryMapping] rules — the dynamic counterpart to
 * [MerchantCategorizer]'s fixed keyword buckets. First mapping to hit wins, so
 * callers should order more specific rules first, same convention as
 * [MerchantCategorizer]. A malformed regex pattern (e.g. hand-edited) is
 * skipped rather than thrown, so one bad rule can never break capture.
 */
object CategoryMatcher {
    fun match(merchant: String?, mappings: List<MerchantCategoryMapping>): Long? {
        if (merchant.isNullOrBlank()) return null

        for (mapping in mappings) {
            val hit = if (mapping.isExactMatch) {
                mapping.merchantPattern.equals(merchant, ignoreCase = true)
            } else {
                runCatching {
                    Regex(mapping.merchantPattern, RegexOption.IGNORE_CASE).containsMatchIn(merchant)
                }.getOrDefault(false)
            }
            if (hit) return mapping.categoryId
        }
        return null
    }
}
