package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.ai.AiDeviceGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class AiDeviceGateTest {

    private val flagship = AiDeviceGate.DeviceProfile(
        totalRamBytes = 12L shl 30,
        availableRamBytes = 4L shl 30,
        isLowRamDevice = false,
        modelFilePresent = true,
    )

    @Test
    fun `flagship profile with model present is eligible`() {
        assertEquals(Verdict.ELIGIBLE, AiDeviceGate.evaluate(flagship))
    }

    @Test
    fun `missing model file stands the whole feature down first`() {
        assertEquals(
            Verdict.NO_MODEL_FILE,
            AiDeviceGate.evaluate(flagship.copy(modelFilePresent = false, isLowRamDevice = true)),
        )
    }

    @Test
    fun `low ram device flag is disqualifying`() {
        assertEquals(Verdict.LOW_RAM_DEVICE, AiDeviceGate.evaluate(flagship.copy(isLowRamDevice = true)))
    }

    @Test
    fun `total ram below six gib is disqualifying`() {
        assertEquals(
            Verdict.INSUFFICIENT_TOTAL_RAM,
            AiDeviceGate.evaluate(flagship.copy(totalRamBytes = AiDeviceGate.MIN_TOTAL_RAM_BYTES - 1)),
        )
    }

    @Test
    fun `momentary memory pressure is disqualifying at init time`() {
        assertEquals(
            Verdict.INSUFFICIENT_FREE_RAM,
            AiDeviceGate.evaluate(flagship.copy(availableRamBytes = AiDeviceGate.MIN_AVAILABLE_RAM_BYTES - 1)),
        )
    }

    @Test
    fun `thresholds are inclusive at the exact minimum`() {
        assertEquals(
            Verdict.ELIGIBLE,
            AiDeviceGate.evaluate(
                flagship.copy(
                    totalRamBytes = AiDeviceGate.MIN_TOTAL_RAM_BYTES,
                    availableRamBytes = AiDeviceGate.MIN_AVAILABLE_RAM_BYTES,
                )
            ),
        )
    }
}
