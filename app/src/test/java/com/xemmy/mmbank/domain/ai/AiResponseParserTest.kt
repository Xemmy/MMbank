package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseParserTest {

    @Test
    fun `valid JSON is parsed into a transaction result`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": true, "amount": 46.12, "currency": "aed", "merchant": "Tesla Motors"}"""
        )
        assertEquals(
            AiParseResult(true, BigDecimal("46.12"), "AED", "Tesla Motors"),
            result,
        )
    }

    @Test
    fun `code-fenced JSON is unwrapped before parsing`() {
        val raw = """
            Here you go:
            ```json
            {"transaction_intent": true, "amount": 10, "currency": "USD", "merchant": null}
            ```
        """.trimIndent()
        val result = AiResponseParser.parse(raw)
        assertEquals(true, result?.transactionIntent)
        assertEquals(BigDecimal("10"), result?.amount)
        assertEquals("USD", result?.currency)
        assertEquals(AiResponseParser.FALLBACK_MERCHANT, result?.merchant)
    }

    @Test
    fun `conversational preamble with no code fence still recovers the JSON object`() {
        val raw = """Sure! Here's the transaction: {"transaction_intent": true, "amount": 10, "currency": "USD", "merchant": "Shop"}"""
        val result = AiResponseParser.parse(raw)
        assertEquals(true, result?.transactionIntent)
        assertEquals(BigDecimal("10"), result?.amount)
        assertEquals("USD", result?.currency)
        assertEquals("Shop", result?.merchant)
    }

    @Test
    fun `conversational preamble and postamble around the JSON both get stripped`() {
        val raw = """
            Here's what I found:
            {"transaction_intent": true, "amount": 25.5, "currency": "AED", "merchant": "Cafe"}
            Let me know if you need anything else!
        """.trimIndent()
        val result = AiResponseParser.parse(raw)
        assertEquals(true, result?.transactionIntent)
        assertEquals(BigDecimal("25.5"), result?.amount)
        assertEquals("AED", result?.currency)
        assertEquals("Cafe", result?.merchant)
    }

    @Test
    fun `missing currency falls back to the caller-supplied default instead of failing`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": true, "amount": 5, "merchant": "Shop"}""",
            fallbackCurrency = "USD",
        )
        assertEquals("USD", result?.currency)
    }

    @Test
    fun `currency in the response wins over the fallback`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": true, "amount": 5, "currency": "aed", "merchant": "Shop"}""",
            fallbackCurrency = "USD",
        )
        assertEquals("AED", result?.currency)
    }

    @Test
    fun `missing merchant defaults to the fallback merchant label`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": true, "amount": 5, "currency": "USD"}""",
            fallbackCurrency = "USD",
        )
        assertEquals(AiResponseParser.FALLBACK_MERCHANT, result?.merchant)
    }

    @Test
    fun `non-transaction intent short-circuits to a false result with null fields`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": false, "amount": null, "currency": null, "merchant": null}"""
        )
        assertEquals(AiParseResult(false, null, null, null), result)
    }

    @Test
    fun `malformed JSON fails closed to null`() {
        assertNull(AiResponseParser.parse("this is not json at all"))
    }

    @Test
    fun `missing transaction_intent key fails closed to null`() {
        assertNull(AiResponseParser.parse("""{"amount": 10, "currency": "USD"}"""))
    }

    @Test
    fun `missing amount on a positive intent fails closed to null`() {
        assertNull(
            AiResponseParser.parse("""{"transaction_intent": true, "currency": "USD", "merchant": "Shop"}""")
        )
    }

    @Test
    fun `missing currency on a positive intent fails closed to null`() {
        assertNull(
            AiResponseParser.parse("""{"transaction_intent": true, "amount": 5, "merchant": "Shop"}""")
        )
    }

    @Test
    fun `zero or negative amount fails closed to null`() {
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "amount": 0, "currency": "USD"}"""))
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "amount": -5, "currency": "USD"}"""))
    }

    @Test
    fun `empty string fails closed to null`() {
        assertNull(AiResponseParser.parse(""))
        assertNull(AiResponseParser.parse("   "))
    }

    @Test
    fun `blank currency fails closed to null`() {
        assertNull(AiResponseParser.parse("""{"transaction_intent": true, "amount": 5, "currency": "  "}"""))
    }

    @Test
    fun `garbage transaction_intent value fails closed to null`() {
        assertNull(AiResponseParser.parse("""{"transaction_intent": "maybe", "amount": 5, "currency": "USD"}"""))
    }

    @Test
    fun `currency is normalized to uppercase`() {
        val result = AiResponseParser.parse(
            """{"transaction_intent": true, "amount": 5, "currency": "usd", "merchant": "x"}"""
        )
        assertTrue(result?.currency == "USD")
    }
}
