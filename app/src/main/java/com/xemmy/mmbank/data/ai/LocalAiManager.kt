package com.xemmy.mmbank.data.ai

import android.app.ActivityManager
import android.content.Context
import com.xemmy.mmbank.domain.ai.AiCapabilityChecker
import com.xemmy.mmbank.domain.ai.LocalAiEngine
import com.xemmy.mmbank.domain.ai.LocalAiEngineCache

/**
 * Data-layer gateway to the on-device Gemma 4 E2B model via LiteRT-LM.
 * Resolves to [UnavailableLocalAiEngine] on underpowered hardware, and to a
 * cached [LiteRtLmEngine] otherwise -- which itself fails closed until a real
 * model asset is bundled (see LiteRtLmEngine).
 */
class LocalAiManager(context: Context) {
    private val appContext = context.applicationContext
    private val cache = LocalAiEngineCache(::createEngine)

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

    /** Returns the cached engine, initializing it on first use. */
    suspend fun engine(): LocalAiEngine {
        val engine = cache.get()
        if (!engine.isReady) engine.initialize()
        return engine
    }

    private fun createEngine(): LocalAiEngine =
        if (isDeviceCapable()) LiteRtLmEngine(appContext) else UnavailableLocalAiEngine
}
