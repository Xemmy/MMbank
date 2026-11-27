package com.xemmy.mmbank.domain.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Minimal OpenAI-compatible /v1/chat/completions wire format, enough to drive
 * a local Off Grid / Ollama / LM Studio server. Request building and response
 * extraction are pure so they unit-test on the JVM without a socket; the raw
 * assistant text still flows through the lenient [AiResponseParser].
 */
object OpenAiChatProtocol {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val temperature: Double = 0.0,
        val stream: Boolean = false,
        @SerialName("max_tokens") val maxTokens: Int = LocalAiEngine.DEFAULT_MAX_TOKENS,
    )

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList())

    @Serializable
    private data class Choice(val message: ChatMessage? = null)

    /**
     * Serializes a single-user-turn chat request. An empty [model] is sent as
     * "" -- most local servers ignore it and use their loaded model.
     */
    fun buildRequestJson(model: String, prompt: String, maxTokens: Int = LocalAiEngine.DEFAULT_MAX_TOKENS): String =
        json.encodeToString(
            ChatRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                maxTokens = maxTokens,
            ),
        )

    /**
     * Pulls choices[0].message.content out of a response body, failing closed
     * to null on any shape/type mismatch or empty content -- never throws.
     */
    fun extractContent(responseBody: String): String? = runCatching {
        val parsed = json.decodeFromString<ChatResponse>(responseBody)
        parsed.choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
