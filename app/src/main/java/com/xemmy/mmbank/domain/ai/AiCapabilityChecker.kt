package com.xemmy.mmbank.domain.ai

/**
 * Pure decision logic for whether the device has enough headroom to load
 * Gemma 4 E2B via LiteRT-LM, kept separate from any Android
 * ActivityManager/hardware calls so it is unit-testable on the plain JVM.
 */
object AiCapabilityChecker {
    /** Below this total RAM, LiteRT-LM's Gemma 4 E2B is not expected to load reliably. */
    const val MIN_TOTAL_MEMORY_BYTES = 4_000_000_000L

    /** Free-memory floor kept clear of the model's working set, avoiding OOM-kill churn. */
    const val MIN_AVAILABLE_MEMORY_BYTES = 1_500_000_000L

    fun canRunInference(
        totalMemoryBytes: Long,
        availableMemoryBytes: Long,
        isLowRamDevice: Boolean,
    ): Boolean {
        if (isLowRamDevice) return false
        if (totalMemoryBytes < MIN_TOTAL_MEMORY_BYTES) return false
        if (availableMemoryBytes < MIN_AVAILABLE_MEMORY_BYTES) return false
        return true
    }
}
