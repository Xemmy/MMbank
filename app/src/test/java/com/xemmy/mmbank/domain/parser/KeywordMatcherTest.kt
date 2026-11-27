package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordMatcherTest {
    @Test
    fun `matches case-insensitively as a substring`() {
        assertTrue(KeywordMatcher.matches("Mega SALE today", listOf("sale")))
    }

    @Test
    fun `no keywords, blank text and blank keywords never match`() {
        assertFalse(KeywordMatcher.matches("anything", emptyList()))
        assertFalse(KeywordMatcher.matches("  ", listOf("sale")))
        assertFalse(KeywordMatcher.matches("anything", listOf("")))
    }
}
