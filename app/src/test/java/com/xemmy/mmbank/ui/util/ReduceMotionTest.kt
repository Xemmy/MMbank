package com.xemmy.mmbank.ui.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReduceMotionTest {
    @Test
    fun `zero animator scale means reduce motion`() {
        assertTrue(isReduceMotion(0f))
    }

    @Test
    fun `any running animator scale keeps motion on`() {
        assertFalse(isReduceMotion(1f))
        assertFalse(isReduceMotion(0.5f))
        assertFalse(isReduceMotion(10f))
    }
}
