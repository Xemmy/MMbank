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
        assertNull(result?.merchant)
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
