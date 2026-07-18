package com.xemmy.mmbank.data.ai

import android.app.ActivityManager
import android.content.Context
import com.xemmy.mmbank.domain.ai.AiCapabilityChecker
import com.xemmy.mmbank.domain.ai.LocalAiEngine
import com.xemmy.mmbank.domain.ai.LocalAiEngineCache
import com.xemmy.mmbank.domain.ai.LoopbackGuard
import okhttp3.OkHttpClient

/** Snapshot of the user-configurable Gate-2 AI settings (from AppPreferences). */
data class LocalAiConfig(
    val enabled: Boolean,
    val endpointUrl: String,
    val model: String,
)

/**
 * Data-layer gateway to the Gate-2 engine. The active path is a local
 * OpenAI-compatible server (e.g. the Off Grid app) reached over loopback: when
 * the feature is enabled and the configured endpoint is a valid loopback URL,
 * [engine] yields an [OpenAiCompatibleEngine]; otherwise it yields
 * [UnavailableLocalAiEngine] and Gate 2 is a silent no-op.
 *
 * The cached engine is rebuilt whenever the config changes (endpoint/model
 * edited in Settings, feature toggled), so edits take effect without a restart.
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
     * localhost server path does not depend on this device's RAM.
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

    /** True when the feature is on and the endpoint is a valid loopback URL. */
    fun isConfigured(): Boolean = readConfig().let { it.enabled && LoopbackGuard.isLoopbackUrl(it.endpointUrl) }

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

    private fun createEngine(): LocalAiEngine {
        val cfg = readConfig()
        if (!cfg.enabled) return UnavailableLocalAiEngine
        if (!LoopbackGuard.isLoopbackUrl(cfg.endpointUrl)) return UnavailableLocalAiEngine
        return OpenAiCompatibleEngine(cfg.endpointUrl, cfg.model, httpClientFactory())
    }
}
