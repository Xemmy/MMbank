package com.xemmy.mmbank.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTest {
    @Test
    fun `fraction is clamped to 0 to 1`() {
        assertEquals(0.5f, progressFraction(50.0, 100.0), 0.0001f)
        assertEquals(1f, progressFraction(250.0, 100.0), 0f)
        assertEquals(0f, progressFraction(-5.0, 100.0), 0f)
    }

    @Test
    fun `invalid max or value gives 0`() {
        assertEquals(0f, progressFraction(10.0, 0.0), 0f)
        assertEquals(0f, progressFraction(10.0, -1.0), 0f)
        assertEquals(0f, progressFraction(10.0, Double.NaN), 0f)
        assertEquals(0f, progressFraction(Double.NaN, 10.0), 0f)
    }

    @Test
    fun `tone escalates at the warn and danger thresholds`() {
        assertNull(progressTone(0.79f))
        assertEquals(MMTone.WARNING, progressTone(0.8f))
        assertEquals(MMTone.WARNING, progressTone(0.99f))
        assertEquals(MMTone.DANGER, progressTone(1f))
    }
}
