package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConfigValidatorTest {

    @Test
    fun `local mode requires a loopback url and ignores api key`() {
        assertTrue(
            AiConfigValidator.isValid(AiProviderMode.LOCAL, "http://127.0.0.1:11434/v1/chat/completions", null)
        )
        assertFalse(
            AiConfigValidator.isValid(AiProviderMode.LOCAL, "https://api.openai.com/v1/chat/completions", "sk-x")
        )
    }

    @Test
    fun `online mode requires both a non-blank endpoint and api key`() {
        assertTrue(
            AiConfigValidator.isValid(AiProviderMode.ONLINE, "https://api.openai.com/v1/chat/completions", "sk-x")
        )
        assertFalse(AiConfigValidator.isValid(AiProviderMode.ONLINE, "https://api.openai.com/v1/chat/completions", null))
        assertFalse(AiConfigValidator.isValid(AiProviderMode.ONLINE, "https://api.openai.com/v1/chat/completions", "  "))
        assertFalse(AiConfigValidator.isValid(AiProviderMode.ONLINE, "", "sk-x"))
    }

    @Test
    fun `online mode does not require a loopback address`() {
        assertTrue(
            AiConfigValidator.isValid(AiProviderMode.ONLINE, "http://192.168.1.10:8080/v1/chat/completions", "sk-x")
        )
    }
}
