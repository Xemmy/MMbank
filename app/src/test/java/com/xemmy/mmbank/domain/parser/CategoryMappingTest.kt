package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryMappingTest {

    @Test
    fun `exact match hits case-insensitively`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertEquals(1L, CategoryMatcher.match("CARREFOUR", mappings))
    }

    @Test
    fun `a longer store name now resolves through the normalized-key tier`() {
        // This used to assert null: an exact mapping for "Carrefour" did not
        // apply to "Carrefour Express Mall", so the user had to teach the app
        // the same thing once per branch. The normalized-key tier is exactly
        // the feature that changed it.
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertEquals(1L, CategoryMatcher.match("Carrefour Express Mall", mappings))
        assertEquals(1L, CategoryMatcher.match("POS CARREFOUR HYPERMARKET DXB *4543", mappings))
    }

    @Test
    fun `an exact mapping still does not match an unrelated merchant`() {
        // Replaces the null coverage the test above used to carry: generalizing
        // must not turn into matching everything.
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertNull(CategoryMatcher.match("Spinneys Express Mall", mappings))
        assertNull(CategoryMatcher.match("Emirates Airline", mappings))
    }

    @Test
    fun `exact beats a normalized-key candidate pointing elsewhere`() {
        val mappings = listOf(
            // Listed FIRST and would win on the key tier (it keys to "carrefour")...
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
            // ...but this one is the literal spelling the user taught.
            MerchantCategoryMapping(merchantPattern = "Carrefour Market Jumeirah", categoryId = 99, isExactMatch = true),
        )
        assertEquals(99L, CategoryMatcher.match("Carrefour Market Jumeirah", mappings))
    }

    @Test
    fun `the normalized-key tier beats a regex mapping listed first`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = ".*", categoryId = 50, isExactMatch = false),
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertEquals(1L, CategoryMatcher.match("CARREFOUR MARKET JUMEIRAH", mappings))
    }

    @Test
    fun `the key tier ignores regex mappings, so a regex still has to match`() {
        // A regex pattern is a pattern, not a merchant name. "^carrefour$"
        // would NORMALIZE to the key "carrefour" and so would wrongly match
        // this merchant through the key tier -- but the regex itself is
        // anchored and does not match, so the answer must be null.
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "^carrefour$", categoryId = 8, isExactMatch = false),
        )
        assertNull(CategoryMatcher.match("CARREFOUR MARKET JUMEIRAH", mappings))
    }

    @Test
    fun `regex mappings keep their own first-hit-wins order`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "carrefour.*", categoryId = 8, isExactMatch = false),
            MerchantCategoryMapping(merchantPattern = "express.*", categoryId = 7, isExactMatch = false),
        )
        assertEquals(8L, CategoryMatcher.match("CARREFOUR EXPRESS", mappings))
    }

    @Test
    fun `an unkeyable merchant falls through to the regex tier`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "88", categoryId = 4, isExactMatch = true),
            MerchantCategoryMapping(merchantPattern = "7", categoryId = 5, isExactMatch = false),
        )
        // "77" keys to "" (a bare number is not a merchant name), so the key
        // tier must skip it rather than match it against the other unkeyable
        // mapping, which also keys to "".
        assertEquals(5L, CategoryMatcher.match("77", mappings))
    }

    @Test
    fun `two unkeyable merchants never collide on the empty key`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "@@", categoryId = 1, isExactMatch = true),
        )
        assertNull(CategoryMatcher.match("##", mappings))
    }

    @Test
    fun `regex match hits on a substring pattern`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "carrefour.*", categoryId = 2, isExactMatch = false),
        )
        assertEquals(2L, CategoryMatcher.match("CARREFOUR EXPRESS MALL", mappings))
    }

    @Test
    fun `regex match is case-insensitive`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "netflix", categoryId = 3, isExactMatch = false),
        )
        assertEquals(3L, CategoryMatcher.match("NETFLIX.COM", mappings))
    }

    @Test
    fun `first mapping to hit wins`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "shop", categoryId = 10, isExactMatch = false),
            MerchantCategoryMapping(merchantPattern = "coffee shop", categoryId = 20, isExactMatch = false),
        )
        assertEquals(10L, CategoryMatcher.match("Coffee Shop", mappings))
    }

    @Test
    fun `no mapping matches returns null`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertNull(CategoryMatcher.match("Amazon", mappings))
    }

    @Test
    fun `empty mapping list returns null`() {
        assertNull(CategoryMatcher.match("Carrefour", emptyList()))
    }

    @Test
    fun `blank or null merchant returns null without matching anything`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = ".*", categoryId = 1, isExactMatch = false),
        )
        assertNull(CategoryMatcher.match(null, mappings))
        assertNull(CategoryMatcher.match("   ", mappings))
    }

    @Test
    fun `a malformed regex is still skipped after the key tier misses`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "(unclosed", categoryId = 1, isExactMatch = false),
            MerchantCategoryMapping(merchantPattern = "carrefour", categoryId = 2, isExactMatch = false),
        )
        assertEquals(2L, CategoryMatcher.match("Carrefour", mappings))
    }
}
