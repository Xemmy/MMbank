package com.xemmy.mmbank.domain.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCapabilityCheckerTest {

    private val ampleTotal = AiCapabilityChecker.MIN_TOTAL_MEMORY_BYTES + 1
    private val ampleAvailable = AiCapabilityChecker.MIN_AVAILABLE_MEMORY_BYTES + 1

    @Test
    fun `capable device with ample memory passes`() {
        assertTrue(
            AiCapabilityChecker.canRunInference(
                totalMemoryBytes = ampleTotal,
                availableMemoryBytes = ampleAvailable,
                isLowRamDevice = false,
            )
        )
    }

    @Test
    fun `low ram device flag always fails regardless of reported memory`() {
        assertFalse(
            AiCapabilityChecker.canRunInference(
                totalMemoryBytes = Long.MAX_VALUE,
                availableMemoryBytes = Long.MAX_VALUE,
                isLowRamDevice = true,
            )
        )
    }

    @Test
    fun `total memory below floor fails`() {
        assertFalse(
            AiCapabilityChecker.canRunInference(
                totalMemoryBytes = AiCapabilityChecker.MIN_TOTAL_MEMORY_BYTES - 1,
                availableMemoryBytes = ampleAvailable,
                isLowRamDevice = false,
            )
        )
    }

    @Test
    fun `available memory below floor fails`() {
        assertFalse(
            AiCapabilityChecker.canRunInference(
                totalMemoryBytes = ampleTotal,
                availableMemoryBytes = AiCapabilityChecker.MIN_AVAILABLE_MEMORY_BYTES - 1,
                isLowRamDevice = false,
            )
        )
    }

    @Test
    fun `exact boundary values pass`() {
        assertTrue(
            AiCapabilityChecker.canRunInference(
                totalMemoryBytes = AiCapabilityChecker.MIN_TOTAL_MEMORY_BYTES,
                availableMemoryBytes = AiCapabilityChecker.MIN_AVAILABLE_MEMORY_BYTES,
                isLowRamDevice = false,
            )
        )
    }
}
