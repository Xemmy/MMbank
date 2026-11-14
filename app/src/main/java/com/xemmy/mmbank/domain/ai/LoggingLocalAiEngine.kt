package com.xemmy.mmbank.domain.ai

/**
 * Decorates any [LocalAiEngine] with an [onCall] hook fired after every
 * [generate] request completes, carrying the exact prompt sent and the
 * result received (success or failure) -- the raw material for a
 * request/response log. Kept Android-free so the logging seam is
 * pure-JVM testable; [com.xemmy.mmbank.data.ai.AiCallLogStore] is the
 * concrete Android sink wired in by AppContainer.
 */
class LoggingLocalAiEngine(
    private val delegate: LocalAiEngine,
    private val onCall: (prompt: String, result: Result<String>) -> Unit,
) : LocalAiEngine {

    override val isReady: Boolean get() = delegate.isReady

    override suspend fun initialize(): Result<Unit> = delegate.initialize()

    override suspend fun generate(prompt: String, maxTokens: Int): Result<String> {
        val result = delegate.generate(prompt, maxTokens)
        onCall(prompt, result)
        return result
    }
}
