package com.xemmy.mmbank.domain.ai

/**
 * Pure validity check for a Gate-2 provider config, kept out of
 * [com.xemmy.mmbank.data.ai.LocalAiManager] (which also carries Android
 * Context dependencies) so this decision is plain-JVM testable.
 */
object AiConfigValidator {
    fun isValid(mode: AiProviderMode, endpointUrl: String, apiKey: String?): Boolean = when (mode) {
        AiProviderMode.LOCAL -> LoopbackGuard.isLoopbackUrl(endpointUrl)
        AiProviderMode.ONLINE -> endpointUrl.isNotBlank() && !apiKey.isNullOrBlank()
    }
}
