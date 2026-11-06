package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MerchantCategorizer.BRAND_TOKENS] is derived by filtering the categorizer's
 * own keyword vocabulary against a hand-curated brand set, rather than being a
 * second copy of the list. That keeps the two from drifting -- but it also
 * means a typo in the curated set silently produces a *shorter* brand list
 * instead of an error, and the only symptom would be merchants quietly
 * failing to merge months later.
 *
 * These tests are that error.
 */
class MerchantCategorizerBrandsTest {

    @Test
    fun `every curated brand still exists in the categorizer's vocabulary`() {
        val missing = MerchantCategorizer.curatedBrandTokens
            .filterNot { it in MerchantCategorizer.keywordVocabulary }
        assertEquals(
            "Curated brands that no longer match any keyword (typo, or the " +
                "keyword was renamed/removed): $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `no curated brand is shorter than the minimum collapse length`() {
        val tooShort = MerchantCategorizer.curatedBrandTokens
            .filter { it.length < MerchantCategorizer.MIN_BRAND_LENGTH }
        assertEquals(
            "Brands below MIN_BRAND_LENGTH are silently dropped from " +
                "BRAND_TOKENS; remove them from the curated set instead: $tooShort",
            emptyList<String>(),
            tooShort,
        )
    }

    @Test
    fun `the brand list is non-empty and ordered longest first`() {
        val tokens = MerchantCategorizer.BRAND_TOKENS
        assertTrue("brand list should not be empty", tokens.isNotEmpty())
        // Longest-first is what makes "union coop" beat "coop" and
        // "noon food" beat "noon" when both could match.
        assertEquals(tokens.sortedByDescending { it.length }, tokens)
    }

    @Test
    fun `blocklisted brands are excluded from what the normalizer will collapse`() {
        // They may legitimately appear in BRAND_TOKENS (the categorizer knows
        // them); the normalizer must still refuse to collapse onto them.
        for (blocked in MerchantNormalizer.COLLAPSE_BLOCKLIST) {
            assertNotEquals(
                "'$blocked' is blocklisted and must never be collapsed onto",
                blocked.lowercase(),
                MerchantNormalizer.key("$blocked something else entirely"),
            )
        }
    }

    @Test
    fun `categorization itself is unchanged by the vocabulary split`() {
        // BRAND_TOKENS is a filter over `rules`, not a reordering of it -- the
        // categorizer's own answers must be byte-identical.
        assertEquals(
            com.xemmy.mmbank.domain.model.SpendingCategory.GROCERIES,
            MerchantCategorizer.categorize("Carrefour Hypermarket"),
        )
        assertEquals(
            com.xemmy.mmbank.domain.model.SpendingCategory.FUEL,
            MerchantCategorizer.categorize("ADNOC Station 123"),
        )
        assertEquals(
            com.xemmy.mmbank.domain.model.SpendingCategory.DINING,
            MerchantCategorizer.categorize("KFC Drive Thru"),
        )
    }
}
