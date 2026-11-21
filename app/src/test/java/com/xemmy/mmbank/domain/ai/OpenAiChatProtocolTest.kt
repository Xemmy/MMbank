package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiChatProtocolTest {

    @Test
    fun `request json carries model, single user message, and non-streaming flags`() {
        val body = OpenAiChatProtocol.buildRequestJson("gemma", "hello", maxTokens = 512)
        assertTrue(body.contains("\"model\":\"gemma\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"hello\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"max_tokens\":512"))
        assertTrue(body.contains("\"temperature\":0"))
    }

    @Test
    fun `extractContent pulls the first choice message content`() {
        val response = """
            {"choices":[{"message":{"role":"assistant","content":"{\"transaction_intent\":true}"}}]}
        """.trimIndent()
        assertEquals("{\"transaction_intent\":true}", OpenAiChatProtocol.extractContent(response))
    }

    @Test
    fun `extractContent ignores unknown fields`() {
        val response = """
            {"id":"x","object":"chat.completion","usage":{"total_tokens":5},
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"hi"}}]}
        """.trimIndent()
        assertEquals("hi", OpenAiChatProtocol.extractContent(response))
    }

    @Test
    fun `extractContent returns null on empty choices`() {
        assertNull(OpenAiChatProtocol.extractContent("""{"choices":[]}"""))
    }

    @Test
    fun `extractContent returns null on blank content`() {
        assertNull(OpenAiChatProtocol.extractContent("""{"choices":[{"message":{"role":"a","content":"  "}}]}"""))
    }

    @Test
    fun `extractContent fails closed to null on malformed json`() {
        assertNull(OpenAiChatProtocol.extractContent("not json"))
        assertNull(OpenAiChatProtocol.extractContent(""))
    }
}
