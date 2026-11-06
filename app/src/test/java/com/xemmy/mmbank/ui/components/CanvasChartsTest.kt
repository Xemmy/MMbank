package com.xemmy.mmbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasChartsTest {

    @Test
    fun `no allowance configured yields an empty, non-overconsumed gauge`() {
        val state = computeArcGaugeState(spent = 50.0, allowance = 0.0)
        assertEquals(0f, state.consumedFraction, 1e-4f)
        assertFalse(state.overConsumed)
    }

    @Test
    fun `half the allowance spent is a half-filled gauge`() {
        val state = computeArcGaugeState(spent = 25.0, allowance = 50.0)
        assertEquals(0.5f, state.consumedFraction, 1e-4f)
        assertFalse(state.overConsumed)
    }

    @Test
    fun `exactly the allowance spent is a full gauge, not overconsumed`() {
        val state = computeArcGaugeState(spent = 50.0, allowance = 50.0)
        assertEquals(1f, state.consumedFraction, 1e-4f)
        assertFalse(state.overConsumed)
    }

    @Test
    fun `overspending clamps the drawn fraction to 1 but flags overConsumed`() {
        val state = computeArcGaugeState(spent = 75.0, allowance = 50.0)
        assertEquals(1f, state.consumedFraction, 1e-4f)
        assertTrue(state.overConsumed)
    }

    @Test
    fun `zero spend against a real allowance is an empty gauge`() {
        val state = computeArcGaugeState(spent = 0.0, allowance = 50.0)
        assertEquals(0f, state.consumedFraction, 1e-4f)
        assertFalse(state.overConsumed)
    }
}
