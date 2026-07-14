package com.xemmy.mmbank.ui.screens.transactions

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure toggle behind bulk-mode row taps: a single-tap check/uncheck while the
 * Transactions screen is in multi-select mode.
 */
class ToggleSelectionTest {

    @Test
    fun `toggling an id not yet selected adds it`() {
        assertEquals(setOf(1L, 2L), toggleSelection(setOf(1L), 2L))
    }

    @Test
    fun `toggling an id already selected removes it`() {
        assertEquals(setOf(1L), toggleSelection(setOf(1L, 2L), 2L))
    }

    @Test
    fun `toggling on an empty set adds the id`() {
        assertEquals(setOf(5L), toggleSelection(emptySet(), 5L))
    }

    @Test
    fun `toggling the last remaining id empties the set`() {
        assertEquals(emptySet<Long>(), toggleSelection(setOf(3L), 3L))
    }
}
