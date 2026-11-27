package com.xemmy.mmbank.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SparklineGeometryTest {

    @Test
    fun `evenly spaced, smallest at the bottom, largest at the top`() {
        val p = sparklinePoints(listOf(10.0, 30.0, 20.0), width = 100f, height = 50f)
        assertEquals(listOf(0f, 50f, 100f), p.map { it!!.x })
        assertEquals(50f, p[0]!!.y, 0.001f)
        assertEquals(0f, p[1]!!.y, 0.001f)
        assertEquals(25f, p[2]!!.y, 0.001f)
    }

    @Test
    fun `inset keeps the line off the edges`() {
        val p = sparklinePoints(listOf(0.0, 1.0), width = 10f, height = 20f, inset = 2f)
        assertEquals(18f, p[0]!!.y, 0.001f)
        assertEquals(2f, p[1]!!.y, 0.001f)
    }

    @Test
    fun `a flat series sits on the middle line`() {
        val p = sparklinePoints(listOf(5.0, 5.0, 5.0), width = 10f, height = 20f)
        assertTrue(p.all { it!!.y == 10f })
    }

    @Test
    fun `negative values scale like any other`() {
        val p = sparklinePoints(listOf(-100.0, 0.0, 100.0), width = 10f, height = 20f)
        assertEquals(20f, p[0]!!.y, 0.001f)
        assertEquals(10f, p[1]!!.y, 0.001f)
        assertEquals(0f, p[2]!!.y, 0.001f)
    }

    @Test
    fun `gaps stay gaps and keep their slot`() {
        val p = sparklinePoints(listOf(null, 1.0, null, 3.0), width = 30f, height = 10f)
        assertNull(p[0])
        assertNull(p[2])
        assertEquals(10f, p[1]!!.x, 0.001f)
        assertEquals(30f, p[3]!!.x, 0.001f)
    }

    @Test
    fun `single value is centred and empty input stays empty`() {
        val single = sparklinePoints(listOf(7.0), width = 40f, height = 10f).single()!!
        assertEquals(20f, single.x, 0.001f)
        assertEquals(5f, single.y, 0.001f)
        assertTrue(sparklinePoints(emptyList(), 10f, 10f).isEmpty())
        assertTrue(sparklinePoints(listOf(null, null), 10f, 10f).all { it == null })
    }
}
