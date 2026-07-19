package com.xemmy.mmbank.data.ai

import android.app.ActivityManager
import android.content.Context
import com.xemmy.mmbank.domain.ai.AiCapabilityChecker
import com.xemmy.mmbank.domain.ai.AiConfigValidator
import com.xemmy.mmbank.domain.ai.AiProviderMode
import com.xemmy.mmbank.domain.ai.LocalAiEngine
import com.xemmy.mmbank.domain.ai.LocalAiEngineCache
import com.xemmy.mmbank.domain.ai.LoggingLocalAiEngine
import okhttp3.OkHttpClient

/** Snapshot of the user-configurable Gate-2 AI settings (from AppPreferences / SecureCredentialStore). */
data class LocalAiConfig(
    val enabled: Boolean,
    val mode: AiProviderMode,
    val endpointUrl: String,
    val model: String,
    /** Only meaningful for [AiProviderMode.ONLINE]; never read for LOCAL. */
    val apiKey: String?,
)

/**
 * Data-layer gateway to the Gate-2 engine, with two provider modes:
 *
 * - [AiProviderMode.LOCAL] (default): a server on this device (e.g. Off Grid),
 *   reached over loopback only -- [LoopbackGuard] enforces this both here and
 *   inside the engine itself.
 * - [AiProviderMode.ONLINE]: an explicit opt-in to a cloud OpenAI-compatible
 *   API, authenticated with an API key. Choosing this means transaction
 *   message text is sent to that third party; the Settings UI states this
 *   plainly before it can be enabled.
 *
 * Either way, [engine] yields [UnavailableLocalAiEngine] (a silent no-op) when
 * misconfigured -- Gate 2 must never throw into the ingestion pipeline. The
 * cached engine rebuilds whenever the config changes, so Settings edits apply
 * without a restart.
 */
class LocalAiManager(
    context: Context,
    private val readConfig: () -> LocalAiConfig,
    private val httpClientFactory: () -> OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val cache = LocalAiEngineCache(::createEngine)

    @Volatile
    private var lastConfig: LocalAiConfig? = null

    /**
     * Retained hardware check for a possible future in-process model path; the
     * server-based paths (local or online) don't depend on this device's RAM.
     */
    fun isDeviceCapable(): Boolean {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return AiCapabilityChecker.canRunInference(
            totalMemoryBytes = info.totalMem,
            availableMemoryBytes = info.availMem,
            isLowRamDevice = am.isLowRamDevice,
        )
    }

    /** True when the feature is on and the config is valid for its selected mode. */
    fun isConfigured(): Boolean = readConfig().let { it.enabled && isValidForMode(it) }

    private fun isValidForMode(cfg: LocalAiConfig): Boolean =
        AiConfigValidator.isValid(cfg.mode, cfg.endpointUrl, cfg.apiKey)

    /** Returns the cached engine (rebuilt on config change), initializing it on first use. */
    suspend fun engine(): LocalAiEngine {
        val current = readConfig()
        if (current != lastConfig) {
            cache.invalidate()
            lastConfig = current
        }
        val engine = cache.get()
        if (!engine.isReady) engine.initialize()
        return engine
    }

    /**
     * Builds a transient engine from the current config and probes it, for the
     * Settings "Test connection" button. Ignores the enabled flag (so the user
     * can verify before switching the feature on) but still validates the
     * config for its selected mode.
     */
    suspend fun testConnection(): Result<Unit> {
        val cfg = readConfig()
        if (!isValidForMode(cfg)) {
            val reason = when (cfg.mode) {
                AiProviderMode.LOCAL -> "Endpoint must be a loopback address (127.0.0.1 or localhost)"
                AiProviderMode.ONLINE -> "Endpoint and API key are both required for an online provider"
            }
            return Result.failure(SecurityException(reason))
        }
        return buildEngine(cfg).initialize()
    }

    private fun createEngine(): LocalAiEngine {
        val cfg = readConfig()
        if (!cfg.enabled) return UnavailableLocalAiEngine
        if (!isValidForMode(cfg)) return UnavailableLocalAiEngine
        return buildEngine(cfg)
    }

    private fun buildEngine(cfg: LocalAiConfig): LocalAiEngine {
        val raw = OpenAiCompatibleEngine(
            chatCompletionsUrl = cfg.endpointUrl,
            model = cfg.model,
            client = httpClientFactory(),
            apiKey = cfg.apiKey.takeIf { cfg.mode == AiProviderMode.ONLINE },
            requireLoopback = cfg.mode == AiProviderMode.LOCAL,
        )
        return LoggingLocalAiEngine(raw) { prompt, result -> AiCallLogStore.recordCall(appContext, prompt, result) }
    }
}
