package com.xemmy.mmbank.ui.screens.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The collapsing filter header must consume exactly the scroll it absorbs
 * (or the list underneath jumps), clamp at both ends, and stay inert until
 * it learns its measured height.
 */
class FilterBarCollapseTest {

    @Test
    fun `upward scroll collapses and consumes its delta`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        val consumed = collapse.onPreScroll(-30f)
        assertEquals(-30f, consumed, 0.001f)
        assertEquals(30f, collapse.offsetPx, 0.001f)
        assertEquals(0.3f, collapse.fraction, 0.001f)
    }

    @Test
    fun `collapse clamps at the full range and consumes only the used part`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        collapse.onPreScroll(-90f)
        val consumed = collapse.onPreScroll(-30f)
        assertEquals(-10f, consumed, 0.001f)
        assertEquals(100f, collapse.offsetPx, 0.001f)
        assertEquals(1f, collapse.fraction, 0.001f)
        // Fully collapsed: further upward scroll passes through untouched.
        assertEquals(0f, collapse.onPreScroll(-50f), 0.001f)
    }

    @Test
    fun `downward pre-scroll goes to the list, expansion happens post-scroll`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        collapse.onPreScroll(-100f)
        // Pre-scroll never expands — the list scrolls back down first.
        assertEquals(0f, collapse.onPreScroll(40f), 0.001f)
        assertEquals(100f, collapse.offsetPx, 0.001f)
        // Leftover downward delta (list at top) expands and is consumed.
        val consumed = collapse.onPostScroll(40f)
        assertEquals(40f, consumed, 0.001f)
        assertEquals(60f, collapse.offsetPx, 0.001f)
    }

    @Test
    fun `expansion clamps fully open`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        collapse.onPreScroll(-20f)
        val consumed = collapse.onPostScroll(50f)
        assertEquals(20f, consumed, 0.001f)
        assertEquals(0f, collapse.offsetPx, 0.001f)
        assertEquals(0f, collapse.fraction, 0.001f)
        // Fully open: downward leftovers pass through (overscroll untouched).
        assertEquals(0f, collapse.onPostScroll(10f), 0.001f)
    }

    @Test
    fun `zero range stays inert with fraction zero`() {
        val collapse = FilterBarCollapse()
        assertEquals(0f, collapse.onPreScroll(-50f), 0.001f)
        assertEquals(0f, collapse.fraction, 0.001f)
    }

    @Test
    fun `shrinking the measured range re-clamps the offset`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        collapse.onPreScroll(-80f)
        collapse.updateRange(50f)
        assertEquals(50f, collapse.offsetPx, 0.001f)
        assertEquals(1f, collapse.fraction, 0.001f)
    }

    @Test
    fun `expand snaps fully open`() {
        val collapse = FilterBarCollapse(initialRangePx = 100f)
        collapse.onPreScroll(-100f)
        collapse.expand()
        assertEquals(0f, collapse.offsetPx, 0.001f)
    }
}
