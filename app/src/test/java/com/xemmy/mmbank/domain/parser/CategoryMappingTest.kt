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
    fun `exact match requires the whole string, not a substring`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "Carrefour", categoryId = 1, isExactMatch = true),
        )
        assertNull(CategoryMatcher.match("Carrefour Express Mall", mappings))
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
    fun `a malformed regex pattern is skipped instead of throwing`() {
        val mappings = listOf(
            MerchantCategoryMapping(merchantPattern = "(unclosed", categoryId = 1, isExactMatch = false),
            MerchantCategoryMapping(merchantPattern = "carrefour", categoryId = 2, isExactMatch = false),
        )
        assertEquals(2L, CategoryMatcher.match("Carrefour", mappings))
    }
}
