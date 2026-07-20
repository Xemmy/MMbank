package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class AiEndpointNormalizerTest {

    private val googleCompat = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"

    @Test
    fun `google native generateContent path is rewritten to the openai compat endpoint`() {
        assertEquals(
            googleCompat,
            AiEndpointNormalizer.normalize(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
            ),
        )
    }

    @Test
    fun `google native path with a key query param is rewritten and the key dropped`() {
        assertEquals(
            googleCompat,
            AiEndpointNormalizer.normalize(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=AIzaXXX"
            ),
        )
    }

    @Test
    fun `bare google host is completed to the compat endpoint`() {
        assertEquals(googleCompat, AiEndpointNormalizer.normalize("https://generativelanguage.googleapis.com"))
    }

    @Test
    fun `google compat endpoint is left unchanged`() {
        assertEquals(googleCompat, AiEndpointNormalizer.normalize(googleCompat))
    }

    @Test
    fun `bare origin gets v1 chat completions appended`() {
        assertEquals(
            "http://127.0.0.1:11434/v1/chat/completions",
            AiEndpointNormalizer.normalize("http://127.0.0.1:11434"),
        )
    }

    @Test
    fun `version-suffixed base url gets chat completions appended`() {
        assertEquals(
            "https://api.llm.kiwi/v1/chat/completions",
            AiEndpointNormalizer.normalize("https://api.llm.kiwi/v1"),
        )
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            AiEndpointNormalizer.normalize("https://api.groq.com/openai/v1/"),
        )
    }

    @Test
    fun `already-complete url only loses the trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            AiEndpointNormalizer.normalize("https://api.openai.com/v1/chat/completions/"),
        )
    }

    @Test
    fun `unrecognized custom path is left unchanged`() {
        assertEquals(
            "https://gateway.example.com/llm/proxy",
            AiEndpointNormalizer.normalize("https://gateway.example.com/llm/proxy"),
        )
    }

    @Test
    fun `unparseable or blank input is returned as-is`() {
        assertEquals("not a url", AiEndpointNormalizer.normalize("not a url"))
        assertEquals("", AiEndpointNormalizer.normalize("  "))
    }
}
