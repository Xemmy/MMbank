package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.MerchantCategoryMapping

/**
 * Resolves a merchant string to a learned category id via
 * [MerchantCategoryMapping] rules — the dynamic counterpart to
 * [MerchantCategorizer]'s fixed keyword buckets. A malformed regex pattern
 * (e.g. hand-edited) is skipped rather than thrown, so one bad rule can never
 * break capture.
 *
 * Three tiers, in order:
 *
 *  1. **Exact**, case-insensitive. Unchanged, and still the strongest signal:
 *     if the user taught this precise merchant, that answer wins outright.
 *  2. **Normalized key** ([MerchantNormalizer]). This is what makes a learned
 *     rule generalize: a category taught for `POS CARREFOUR HYPERMARKET DXB`
 *     now also applies to `CARREFOUR MALL OF EMIRATES`, which previously were
 *     unrelated strings and so needed teaching twice.
 *  3. **Regex**, first hit wins. Unchanged.
 *
 * Tier 2 considers only `isExactMatch` mappings on purpose. A regex mapping is
 * a *pattern*, not a merchant name — normalizing `carrefour.*` is meaningless,
 * and running it through tier 2 would quietly reorder regex precedence.
 *
 * The key is recomputed at match time rather than stored alongside the mapping.
 * A stored key would go stale the moment [MerchantNormalizer]'s rules changed,
 * silently orphaning every learned mapping with nothing to detect the drift;
 * computing it here means a normalizer change re-keys existing data instead.
 */
object CategoryMatcher {
    fun match(merchant: String?, mappings: List<MerchantCategoryMapping>): Long? {
        if (merchant.isNullOrBlank()) return null

        mappings.firstOrNull { it.isExactMatch && it.merchantPattern.equals(merchant, ignoreCase = true) }
            ?.let { return it.categoryId }

        val key = MerchantNormalizer.key(merchant)
        if (key.isNotEmpty()) {
            // An unkeyable mapping yields "" too — requiring a non-empty key on
            // both sides is what stops two unrelated unkeyable merchants from
            // colliding on the empty string.
            mappings.firstOrNull { it.isExactMatch && MerchantNormalizer.key(it.merchantPattern) == key }
                ?.let { return it.categoryId }
        }

        for (mapping in mappings) {
            if (mapping.isExactMatch) continue
            val hit = runCatching {
                Regex(mapping.merchantPattern, RegexOption.IGNORE_CASE).containsMatchIn(merchant)
            }.getOrDefault(false)
            if (hit) return mapping.categoryId
        }
        return null
    }
}
