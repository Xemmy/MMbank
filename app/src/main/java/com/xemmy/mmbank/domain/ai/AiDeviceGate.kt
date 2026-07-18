package com.xemmy.mmbank.domain.ai

/**
 * Pure eligibility policy for running the on-device model: LocalAiManager
 * feeds it real ActivityManager numbers, tests feed it synthetic profiles.
 * An E2B-class model needs multiple GB resident during inference, so the
 * bar is deliberately high — an ineligible device simply keeps the
 * deterministic parser and loses nothing.
 */
object AiDeviceGate {

    const val MIN_TOTAL_RAM_BYTES = 6L shl 30 // 6 GiB total
    const val MIN_AVAILABLE_RAM_BYTES = 1536L shl 20 // 1.5 GiB free at init time

    data class DeviceProfile(
        val totalRamBytes: Long,
        val availableRamBytes: Long,
        val isLowRamDevice: Boolean,
        val modelFilePresent: Boolean,
    )

    enum class Verdict {
        ELIGIBLE,
        NO_MODEL_FILE,
        LOW_RAM_DEVICE,
        INSUFFICIENT_TOTAL_RAM,
        INSUFFICIENT_FREE_RAM,
    }

    fun evaluate(profile: DeviceProfile): Verdict = when {
        !profile.modelFilePresent -> Verdict.NO_MODEL_FILE
        profile.isLowRamDevice -> Verdict.LOW_RAM_DEVICE
        profile.totalRamBytes < MIN_TOTAL_RAM_BYTES -> Verdict.INSUFFICIENT_TOTAL_RAM
        profile.availableRamBytes < MIN_AVAILABLE_RAM_BYTES -> Verdict.INSUFFICIENT_FREE_RAM
        else -> Verdict.ELIGIBLE
    }
}
