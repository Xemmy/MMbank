package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.TransactionSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

    private fun message(
        body: String,
        title: String? = "Alert",
        sender: String? = "ADCB",
    ) = CapturedMessage(
        source = TransactionSource.SMS,
        sender = sender,
        title = title,
        body = body,
        timestampMillis = 0L,
    )

    @Test
    fun `prompt carries the strict keyset and the message parts`() {
        val prompt = AiPromptBuilder.build(message("You spent AED 46.12 at CARREFOUR"))
        for (key in listOf(
            AiResponseParser.KEY_INTENT,
            AiResponseParser.KEY_AMOUNT,
            AiResponseParser.KEY_CURRENCY,
            AiResponseParser.KEY_MERCHANT,
        )) {
            assertTrue("prompt must name key $key", prompt.contains("\"$key\""))
        }
        assertTrue(prompt.contains("You spent AED 46.12 at CARREFOUR"))
        assertTrue(prompt.contains("ADCB"))
        assertTrue(prompt.contains("Alert"))
    }

    @Test
    fun `oversized body is truncated to the context budget`() {
        val huge = "x".repeat(50_000)
        val prompt = AiPromptBuilder.build(message(huge))
        assertTrue(prompt.length < AiPromptBuilder.MAX_BODY_CHARS + 2_000)
        assertFalse(prompt.contains("x".repeat(AiPromptBuilder.MAX_BODY_CHARS + 1)))
    }

    @Test
    fun `null title and sender render as empty not literal null`() {
        val prompt = AiPromptBuilder.build(message("AED 5.00 spent", title = null, sender = null))
        assertFalse(prompt.contains("null"))
    }
}
