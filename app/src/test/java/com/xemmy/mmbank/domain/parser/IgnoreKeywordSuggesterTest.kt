package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoreKeywordSuggesterTest {

    @Test
    fun `suggests the merchant when it appears in the message`() {
        assertEquals("CARREFOUR", IgnoreKeywordSuggester.suggest("Paid AED 5 at CARREFOUR card 1234", "CARREFOUR"))
    }

    @Test
    fun `falls back to the longest alphabetic word when the merchant is absent`() {
        assertEquals("Congratulations", IgnoreKeywordSuggester.suggest("Congratulations! You won 500 now", "Noon"))
    }

    @Test
    fun `blank for a missing or word-less message`() {
        assertEquals("", IgnoreKeywordSuggester.suggest(null, "Noon"))
        assertEquals("", IgnoreKeywordSuggester.suggest("12 34 !!", null))
    }

    @Test
    fun `a keyword is valid only when long enough and present in the message`() {
        assertTrue(IgnoreKeywordSuggester.isValid(" promo ", "Big PROMO today"))
        assertFalse(IgnoreKeywordSuggester.isValid("pr", "Big PROMO today"))
        assertFalse(IgnoreKeywordSuggester.isValid("sale", "Big PROMO today"))
        assertFalse(IgnoreKeywordSuggester.isValid("promo", null))
    }
}
