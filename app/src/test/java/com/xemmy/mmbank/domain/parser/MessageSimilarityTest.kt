package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSimilarityTest {

    // --- fingerprint: the template shape, with the varying parts erased ---

    @Test
    fun `digit runs collapse so one template has one fingerprint`() {
        val a = MessageSimilarity.fingerprint("Your OTP is 483920. Valid for 5 minutes.")
        val b = MessageSimilarity.fingerprint("Your OTP is 112233. Valid for 9 minutes.")
        assertEquals(a, b)
    }

    @Test
    fun `case and punctuation do not change the fingerprint`() {
        assertEquals(
            MessageSimilarity.fingerprint("SALE! 50% OFF, today only."),
            MessageSimilarity.fingerprint("sale 50 off today only"),
        )
    }

    @Test
    fun `arabic-indic digits collapse like ascii ones`() {
        // TextNormalizer maps ٠-٩ to ASCII first, so both are the same template.
        assertEquals(
            MessageSimilarity.fingerprint("رصيدك ١٢٣٤"),
            MessageSimilarity.fingerprint("رصيدك 5678"),
        )
    }

    @Test
    fun `different templates keep different fingerprints`() {
        val otp = MessageSimilarity.fingerprint("Your OTP is 4839")
        val purchase = MessageSimilarity.fingerprint("AED 25.00 spent at Carrefour")
        assertTrue(otp != purchase)
    }

    // --- similarity: overlap between two templates ---

    @Test
    fun `identical text scores one`() {
        assertEquals(1.0, MessageSimilarity.similarity("spent AED 20 at Costa", "spent AED 20 at Costa"), 0.0001)
    }

    @Test
    fun `unrelated text scores zero`() {
        assertEquals(0.0, MessageSimilarity.similarity("alpha beta gamma", "delta epsilon zeta"), 0.0001)
    }

    @Test
    fun `empty input scores zero rather than dividing by zero`() {
        assertEquals(0.0, MessageSimilarity.similarity("", "anything at all here"), 0.0001)
        assertEquals(0.0, MessageSimilarity.similarity("!!!", "???"), 0.0001)
    }

    // --- isSimilar: the actual ignore decision ---

    @Test
    fun `same promo template with different numbers is similar`() {
        assertTrue(
            MessageSimilarity.isSimilar(
                "Enjoy 30% off at Noon this weekend. Use code SAVE30. T&C apply.",
                "Enjoy 50% off at Noon this weekend. Use code SAVE50. T&C apply.",
            )
        )
    }

    @Test
    fun `one differing word still counts as the same template`() {
        assertTrue(
            MessageSimilarity.isSimilar(
                "Dear customer, your statement for account 1234 is ready to view online now",
                "Dear customer, your statement for account 9876 is ready to download online now",
            )
        )
    }

    @Test
    fun `a real purchase is not similar to a promo`() {
        assertFalse(
            MessageSimilarity.isSimilar(
                "AED 42.50 was spent on your card ending 1234 at CARREFOUR DUBAI",
                "Enjoy 50% off at Noon this weekend. Use code SAVE50. T&C apply.",
            )
        )
    }

    @Test
    fun `short messages must match exactly, never fuzzily`() {
        // Three tokens each and two shared -- 0.5 overlap, but below
        // MIN_FUZZY_TOKENS only an exact fingerprint match may ignore.
        assertFalse(MessageSimilarity.isSimilar("AED 50 spent", "AED 50 received"))
        assertTrue(MessageSimilarity.isSimilar("AED 50 spent", "AED 70 spent"))
    }

    @Test
    fun `blank text is never similar to anything`() {
        assertFalse(MessageSimilarity.isSimilar("", "Enjoy 50% off at Noon this weekend"))
        assertFalse(MessageSimilarity.isSimilar("Enjoy 50% off at Noon this weekend", "   "))
    }

    @Test
    fun `a stricter threshold rejects a loose match`() {
        val a = "Dear customer, your statement for account 1234 is ready to view online now"
        val b = "Dear customer, your statement for account 9876 is ready to download online now"
        assertTrue(MessageSimilarity.isSimilar(a, b, threshold = 0.75))
        assertFalse(MessageSimilarity.isSimilar(a, b, threshold = 0.99))
    }

    // --- matchesAny: the list form the capture pipeline calls ---

    @Test
    fun `matchesAny is true when any sample matches and false for an empty list`() {
        val samples = listOf("Your OTP is 4839", "Enjoy 50% off at Noon this weekend, code SAVE50")
        assertTrue(MessageSimilarity.matchesAny("Your OTP is 1122", samples))
        assertFalse(MessageSimilarity.matchesAny("AED 42.50 spent at CARREFOUR DUBAI", samples))
        assertFalse(MessageSimilarity.matchesAny("Your OTP is 1122", emptyList()))
    }
}
