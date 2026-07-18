package com.xemmy.mmbank.data.ai

import com.xemmy.mmbank.domain.ai.LocalAiEngine
import com.xemmy.mmbank.domain.ai.LoopbackGuard
import com.xemmy.mmbank.domain.ai.OpenAiChatProtocol
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Gate-2 [LocalAiEngine] backed by a local OpenAI-compatible server (Off Grid
 * / Ollama / LM Studio) reachable at [chatCompletionsUrl]. Every network path
 * is fenced by [LoopbackGuard]: the URL is validated at construction and again
 * immediately before each request, so captured bank-message text can only ever
 * be POSTed to a loopback address on this device.
 *
 * Fails closed on everything (unreachable server, non-2xx, empty/garbled body)
 * via Result.failure -- Gate 2 must never throw into the ingestion pipeline.
 */
class OpenAiCompatibleEngine(
    private val chatCompletionsUrl: String,
    private val model: String,
    private val client: OkHttpClient,
) : LocalAiEngine {

    @Volatile
    private var ready: Boolean = false
    override val isReady: Boolean get() = ready

    private val jsonMedia = "application/json".toMediaType()

    override suspend fun initialize(): Result<Unit> {
        if (!LoopbackGuard.isLoopbackUrl(chatCompletionsUrl)) {
            return Result.failure(SecurityException("AI endpoint is not a loopback address: $chatCompletionsUrl"))
        }
        // Reachability probe against the sibling /v1/models on the same origin.
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(modelsProbeUrl()).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("AI server probe failed: HTTP ${response.code}")
                    }
                }
                ready = true
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> {
        if (!LoopbackGuard.isLoopbackUrl(chatCompletionsUrl)) {
            return Result.failure(SecurityException("AI endpoint is not a loopback address: $chatCompletionsUrl"))
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = OpenAiChatProtocol.buildRequestJson(model, prompt, maxTokens).toRequestBody(jsonMedia)
                val request = Request.Builder().url(chatCompletionsUrl).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("AI generation failed: HTTP ${response.code}")
                    }
                    val payload = response.body?.string().orEmpty()
                    OpenAiChatProtocol.extractContent(payload)
                        ?: throw IOException("AI response had no usable content")
                }
            }
        }
    }

    /** Derives ".../v1/models" from ".../v1/chat/completions" for the health probe. */
    private fun modelsProbeUrl(): String {
        val marker = "/chat/completions"
        return if (chatCompletionsUrl.endsWith(marker)) {
            chatCompletionsUrl.removeSuffix(marker) + "/models"
        } else {
            chatCompletionsUrl
        }
    }
}
