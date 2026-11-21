package com.xemmy.mmbank.ui.screens.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class DayElapsedFractionTest {
    @Test
    fun `midnight is zero and noon is half`() {
        assertEquals(0f, dayElapsedFraction(0), 0f)
        assertEquals(0.5f, dayElapsedFraction(43_200), 0f)
    }

    @Test
    fun `last second of the day is just under one`() {
        assertEquals(86_399f / 86_400f, dayElapsedFraction(86_399), 1e-6f)
    }

    @Test
    fun `out of range input is clamped`() {
        assertEquals(0f, dayElapsedFraction(-5), 0f)
        assertEquals(1f, dayElapsedFraction(200_000), 0f)
    }
}
