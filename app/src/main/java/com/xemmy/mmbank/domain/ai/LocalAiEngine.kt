package com.xemmy.mmbank.domain.ai

/**
 * Contract for a local, on-device LLM inference engine (Gemma 4 E2B via
 * LiteRT-LM). Kept Android-free so Gate 2 of the parser (see
 * IngestCapturedMessageUseCase) can depend on this instead of a concrete
 * engine, and so fakes are trivial in pure-JVM tests.
 */
interface LocalAiEngine {
    val isReady: Boolean

    suspend fun initialize(): Result<Unit>

    suspend fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): Result<String>

    companion object {
        /** Hard ceiling on generation length, to protect thermals/battery on-device. */
        const val DEFAULT_MAX_TOKENS = 1024
    }
}
