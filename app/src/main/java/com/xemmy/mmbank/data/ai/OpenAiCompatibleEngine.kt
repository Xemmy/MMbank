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
 * Gate-2 [LocalAiEngine] backed by any OpenAI-compatible /v1/chat/completions
 * server, covering two distinct use cases selected by [requireLoopback]:
 *
 * - **Local** (default, [requireLoopback] = true): a server on this device
 *   (Off Grid / Ollama / LM Studio). Every network path is fenced by
 *   [LoopbackGuard] -- validated at construction and again immediately before
 *   each request -- so captured bank-message text can only ever be POSTed to
 *   a loopback address on this device.
 * - **Online** ([requireLoopback] = false): an explicit opt-in to a cloud
 *   provider (OpenAI, Groq, OpenRouter, ...). The loopback guard is skipped
 *   entirely -- choosing this mode means the message text IS sent to that
 *   third party, which the Settings UI must say plainly before enabling it.
 *   [apiKey], when non-null, is sent as `Authorization: Bearer <key>`.
 *
 * Fails closed on everything (unreachable server, non-2xx, empty/garbled body)
 * via Result.failure -- Gate 2 must never throw into the ingestion pipeline.
 */
class OpenAiCompatibleEngine(
    private val chatCompletionsUrl: String,
    private val model: String,
    private val client: OkHttpClient,
    private val apiKey: String? = null,
    private val requireLoopback: Boolean = true,
) : LocalAiEngine {

    @Volatile
    private var ready: Boolean = false
    override val isReady: Boolean get() = ready

    private val jsonMedia = "application/json".toMediaType()

    override suspend fun initialize(): Result<Unit> {
        loopbackFailure()?.let { return Result.failure(it) }
        // Reachability probe against the sibling /v1/models on the same origin.
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = authorizedRequestBuilder(modelsProbeUrl()).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw httpError("AI server probe failed", response)
                    }
                }
                ready = true
            }
        }
    }

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> {
        loopbackFailure()?.let { return Result.failure(it) }
        return withContext(Dispatchers.IO) {
            runCatching {
                val body = OpenAiChatProtocol.buildRequestJson(model, prompt, maxTokens).toRequestBody(jsonMedia)
                val request = authorizedRequestBuilder(chatCompletionsUrl).post(body).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw httpError("AI generation failed", response)
                    }
                    val payload = response.body?.string().orEmpty()
                    OpenAiChatProtocol.extractContent(payload)
                        ?: throw IOException("AI response had no usable content")
                }
            }
        }
    }

    /**
     * Non-2xx responses carry the server's own reason ("API key not valid",
     * "API keys with Android application restrictions cannot be used with this
     * API", quota details, ...). Surfacing a compact slice of it -- into the
     * Settings test status and the request log -- turns an opaque "HTTP 401"
     * into a diagnosable one. The body flows through LogRedactor before it
     * ever reaches the on-device log.
     */
    private fun httpError(prefix: String, response: okhttp3.Response): IOException {
        val detail = runCatching { response.body?.string() }.getOrNull()
            ?.replace(Regex("\\s+"), " ")?.trim()?.take(300)
        return IOException(
            if (detail.isNullOrBlank()) "$prefix: HTTP ${response.code}" else "$prefix: HTTP ${response.code} — $detail"
        )
    }

    private fun loopbackFailure(): SecurityException? {
        if (!requireLoopback) return null
        if (LoopbackGuard.isLoopbackUrl(chatCompletionsUrl)) return null
        return SecurityException("AI endpoint is not a loopback address: $chatCompletionsUrl")
    }

    private fun authorizedRequestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        apiKey?.takeIf { it.isNotBlank() }?.let { builder.addHeader("Authorization", "Bearer $it") }
        return builder
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
