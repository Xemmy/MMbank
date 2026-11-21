package com.xemmy.mmbank.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorderListTest {
    @Test
    fun `move swaps neighbours and stops at the ends`() {
        val ids = listOf(1L, 2L, 3L)
        assertEquals(listOf(2L, 1L, 3L), ReorderList.move(ids, 2, -1))
        assertEquals(listOf(1L, 3L, 2L), ReorderList.move(ids, 2, 1))
        assertEquals(ids, ReorderList.move(ids, 1, -1))
        assertEquals(ids, ReorderList.move(ids, 3, 1))
        assertEquals(ids, ReorderList.move(ids, 9, 1))
    }

    @Test
    fun `moving among siblings leaves every other row in place`() {
        val all = listOf(10L, 11L, 12L, 20L, 21L, 22L)
        // children of 10 are 11 and 12; children of 20 are 21 and 22
        assertEquals(listOf(10L, 12L, 11L, 20L, 21L, 22L), ReorderList.moveAmongSiblings(all, listOf(11L, 12L), 12, -1))
        assertEquals(listOf(10L, 11L, 12L, 20L, 22L, 21L), ReorderList.moveAmongSiblings(all, listOf(21L, 22L), 21, 1))
    }

    @Test
    fun `sibling slots are kept when siblings are not adjacent`() {
        val all = listOf(1L, 99L, 2L, 98L, 3L)
        assertEquals(listOf(1L, 99L, 3L, 98L, 2L), ReorderList.moveAmongSiblings(all, listOf(1L, 2L, 3L), 3, -1))
    }

    @Test
    fun `sibling move is a no-op at the ends or for unknown ids`() {
        val all = listOf(1L, 2L, 3L)
        assertEquals(all, ReorderList.moveAmongSiblings(all, listOf(2L, 3L), 2, -1))
        assertEquals(all, ReorderList.moveAmongSiblings(all, listOf(2L, 3L), 7, 1))
    }
}
