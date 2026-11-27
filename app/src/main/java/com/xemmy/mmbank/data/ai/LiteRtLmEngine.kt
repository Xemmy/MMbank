package com.xemmy.mmbank.data.ai

import android.content.Context
import com.xemmy.mmbank.domain.ai.LocalAiEngine

/**
 * Placeholder for the real LiteRT-LM-backed Gemma 4 E2B engine. No .litertlm
 * asset or LiteRT-LM Gradle dependency is bundled yet, so [initialize] always
 * fails closed -- Gate 2 must never block the Gate-1 regex pipeline (see
 * IngestCapturedMessageUseCase). Swap this body in once the model asset and
 * the LiteRT-LM dependency are added to the project.
 */
class LiteRtLmEngine(private val context: Context) : LocalAiEngine {
    override val isReady: Boolean = false

    override suspend fun initialize(): Result<Unit> =
        Result.failure(UnsupportedOperationException("LiteRT-LM model asset not bundled yet"))

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> =
        Result.failure(IllegalStateException("LocalAiEngine not initialized"))
}
