package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class AiTransactionPromptTest {

    @Test
    fun `prompt embeds the exact required keyset and the raw message`() {
        val prompt = AiTransactionPrompt.build("AED229.59 with Binance Mastercard **2957")

        assertTrue(prompt.contains("\"transaction_intent\""))
        assertTrue(prompt.contains("\"amount\""))
        assertTrue(prompt.contains("\"currency\""))
        assertTrue(prompt.contains("\"merchant\""))
        assertTrue(prompt.contains("AED229.59 with Binance Mastercard **2957"))
    }
}
