package com.xemmy.mmbank.data.ai

import com.xemmy.mmbank.domain.ai.LocalAiEngine

/** Fail-closed engine used when the device doesn't meet [com.xemmy.mmbank.domain.ai.AiCapabilityChecker]'s floor. */
object UnavailableLocalAiEngine : LocalAiEngine {
    override val isReady: Boolean = false

    override suspend fun initialize(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Device does not meet local-AI hardware requirements"))

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> =
        Result.failure(IllegalStateException("LocalAiEngine unavailable on this device"))
}
