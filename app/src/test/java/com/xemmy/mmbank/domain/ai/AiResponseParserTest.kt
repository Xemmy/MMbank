package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiResponseParserTest {

    private fun transaction(raw: String): AiExtraction.Transaction =
        AiResponseParser.parse(raw) as AiExtraction.Transaction

    @Test
    fun `parses clean strict json`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": 46.12, "currency": "AED", "merchant": "CARREFOUR"}"""
        )
        assertEquals(0, BigDecimal("46.12").compareTo(result.amount))
        assertEquals("AED", result.currency)
        assertEquals("CARREFOUR", result.merchant)
    }

    @Test
    fun `parses json wrapped in markdown fences and prose`() {
        val raw = """
            Sure! Here is the extraction:
            ```json
            {"transaction_intent": true, "amount": 1250.5, "currency": "usd", "merchant": "JOHN DOE"}
            ```
            Let me know if you need anything else.
        """.trimIndent()
        val result = transaction(raw)
        assertEquals(0, BigDecimal("1250.5").compareTo(result.amount))
        assertEquals("USD", result.currency)
        assertEquals("JOHN DOE", result.merchant)
    }

    @Test
    fun `accepts amount as numeric string with thousands separators`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": "1,250.00", "currency": "AED", "merchant": "NOON"}"""
        )
        assertEquals(0, BigDecimal("1250.00").compareTo(result.amount))
    }

    @Test
    fun `intent false wins even without a usable amount`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": false, "amount": 0, "currency": "", "merchant": ""}"""
        )
        assertEquals(AiExtraction.NotATransaction, result)
    }

    @Test
    fun `missing amount with intent true is unusable`() {
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "currency": "AED", "merchant": "X Y"}"""))
    }

    @Test
    fun `zero and negative amounts are unusable`() {
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "amount": 0, "currency": "AED", "merchant": "AB"}"""))
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "amount": -5.0, "currency": "AED", "merchant": "AB"}"""))
    }

    @Test
    fun `missing or non-boolean intent is unusable`() {
        assertNull(AiResponseParser.parse("""{"amount": 12.0, "currency": "AED", "merchant": "AB"}"""))
        assertNull(AiResponseParser.parse("""{"transaction_intent": "maybe", "amount": 12.0}"""))
    }

    @Test
    fun `no json object at all is unusable`() {
        assertNull(AiResponseParser.parse("I could not find a transaction in that message."))
        assertNull(AiResponseParser.parse(""))
        assertNull(AiResponseParser.parse("{ never closes"))
    }

    @Test
    fun `skips a non-matching braced fragment and finds the real object later`() {
        val raw = """The format {key: value} maps to {"transaction_intent": true, "amount": 9.99, "currency": "EUR", "merchant": "STEAM"}"""
        val result = transaction(raw)
        assertEquals(0, BigDecimal("9.99").compareTo(result.amount))
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `junk merchant placeholders become null`() {
        for (junk in listOf("", "null", "Unknown", "N/A", "x")) {
            val result = transaction(
                """{"transaction_intent": true, "amount": 5.0, "currency": "AED", "merchant": "$junk"}"""
            )
            assertNull("merchant '$junk' should be dropped", result.merchant)
        }
    }

    @Test
    fun `unrecognizable currency becomes null but junk currency does not sink the parse`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": 5.0, "currency": "??", "merchant": "AB CD"}"""
        )
        assertNull(result.currency)
        assertEquals("AB CD", result.merchant)
    }

    @Test
    fun `currency symbol is normalized to iso code`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": 5.0, "currency": "$", "merchant": "AB CD"}"""
        )
        assertEquals("USD", result.currency)
    }

    @Test
    fun `extra unknown keys are tolerated`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": 7.5, "currency": "AED", "merchant": "IKEA DXB", "confidence": 0.93}"""
        )
        assertEquals(0, BigDecimal("7.5").compareTo(result.amount))
        assertEquals("IKEA DXB", result.merchant)
    }

    @Test
    fun `braces inside json strings do not break balancing`() {
        val result = transaction(
            """{"transaction_intent": true, "amount": 3.0, "currency": "AED", "merchant": "SHOP {24}"}"""
        )
        assertEquals("SHOP {24}", result.merchant)
    }
}
