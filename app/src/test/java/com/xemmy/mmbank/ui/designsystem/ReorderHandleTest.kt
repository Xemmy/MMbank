package com.xemmy.mmbank.ui.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderHandleTest {
    @Test
    fun `a short drag moves nothing and keeps the distance`() {
        assertEquals(0 to 30f, reorderSteps(30f, 56f))
        assertEquals(0 to -30f, reorderSteps(-30f, 56f))
    }

    @Test
    fun `passing the threshold moves one place and keeps the remainder`() {
        val (steps, rest) = reorderSteps(70f, 56f)
        assertEquals(1, steps)
        assertEquals(14f, rest, 1e-4f)
        val (up, upRest) = reorderSteps(-70f, 56f)
        assertEquals(-1, up)
        assertEquals(-14f, upRest, 1e-4f)
    }

    @Test
    fun `a long drag moves several places`() {
        assertEquals(3, reorderSteps(180f, 56f).first)
        assertEquals(-2, reorderSteps(-120f, 56f).first)
    }

    @Test
    fun `a non-positive threshold never loops`() {
        assertEquals(0 to 99f, reorderSteps(99f, 0f))
    }
}
