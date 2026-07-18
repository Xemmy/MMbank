package com.xemmy.mmbank.data.ai

import android.app.ActivityManager
import android.content.Context
import com.xemmy.mmbank.domain.ai.AiDeviceGate
import com.xemmy.mmbank.domain.ai.AiExtraction
import com.xemmy.mmbank.domain.ai.AiPromptBuilder
import com.xemmy.mmbank.domain.ai.AiResponseParser
import com.xemmy.mmbank.domain.model.CapturedMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the on-device LLM: locates the model bundle under files/models/,
 * gates on [AiDeviceGate] (memory/hardware) before any native init, and
 * caches the single engine instance so repeated messages never pay the
 * multi-second model-load again. All failure modes degrade to null — the
 * caller treats that as "AI unavailable", never as an error the user sees.
 */
class LocalAiManager(
    context: Context,
    private val recordError: (String, Throwable) -> Unit,
    private val engineFactory: (File, Int) -> LocalAiEngine? = LiteRtLmEngineFactory::create,
) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var engine: LocalAiEngine? = null

    /**
     * Set when engine creation fails (or the factory has no runtime bound):
     * capability re-checks per message are cheap, native init churn is not,
     * so a failed init stands the whole Gate 2 down until process restart.
     */
    @Volatile
    private var engineUnavailable = false

    /** Fast pre-check the capture gate uses before enqueueing any worker. */
    fun isAvailable(): Boolean = !engineUnavailable && capability() == AiDeviceGate.Verdict.ELIGIBLE

    fun capability(): AiDeviceGate.Verdict {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return AiDeviceGate.evaluate(
            AiDeviceGate.DeviceProfile(
                totalRamBytes = info.totalMem,
                availableRamBytes = info.availMem,
                isLowRamDevice = am.isLowRamDevice,
                modelFilePresent = findModelFile() != null,
            )
        )
    }

    /** One full Gate 2 pass: prompt -> generate -> strict-JSON extraction. */
    suspend fun parseTransaction(message: CapturedMessage): AiExtraction? {
        val currentEngine = obtainEngine() ?: return null
        val raw = try {
            currentEngine.generate(AiPromptBuilder.build(message))
        } catch (t: Throwable) {
            // Inference errors are treated as transient (thermal throttle, OOM
            // kill of the session): drop the session but allow a later re-init.
            recordError("LocalAiManager.generate", t)
            releaseEngine()
            return null
        }
        return AiResponseParser.parse(raw)
    }

    private suspend fun obtainEngine(): LocalAiEngine? = mutex.withLock {
        engine?.let { return@withLock it }
        if (engineUnavailable) return@withLock null
        if (capability() != AiDeviceGate.Verdict.ELIGIBLE) return@withLock null
        val model = findModelFile() ?: return@withLock null
        val created = try {
            engineFactory(model, MAX_TOKENS)
        } catch (t: Throwable) {
            recordError("LocalAiManager.init", t)
            null
        }
        if (created == null) engineUnavailable = true
        engine = created
        created
    }

    private suspend fun releaseEngine(): Unit = mutex.withLock {
        runCatching { engine?.close() }
        engine = null
    }

    private fun findModelFile(): File? = runCatching {
        File(appContext.filesDir, MODEL_DIR).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in MODEL_EXTENSIONS }
            ?.maxByOrNull { it.length() }
    }.getOrNull()

    companion object {
        /** Model bundles are sideloaded to files/models/ (adb push / file picker later). */
        const val MODEL_DIR = "models"
        val MODEL_EXTENSIONS = setOf("litertlm", "task")

        /** Strict context cap — preserves thermals/battery per the workstream spec. */
        const val MAX_TOKENS = 1024
    }
}
