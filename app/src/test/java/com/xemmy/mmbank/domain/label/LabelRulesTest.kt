package com.xemmy.mmbank.domain.label

import com.xemmy.mmbank.domain.model.Label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelRulesTest {
    @Test
    fun `names are trimmed, collapsed and capped, blank gives null`() {
        assertEquals("Holiday trip", LabelRules.normalize("  Holiday   trip "))
        assertNull(LabelRules.normalize("   "))
        assertEquals(LabelRules.MAX_NAME_LENGTH, LabelRules.normalize("x".repeat(80))!!.length)
    }

    @Test
    fun `duplicates ignore case and the label being renamed`() {
        val labels = listOf(Label(1, "Holiday", "#111111"), Label(2, "Tax", "#222222"))
        assertTrue(LabelRules.isDuplicate(labels, "holiday"))
        assertFalse(LabelRules.isDuplicate(labels, "Holiday", ignoreId = 1))
        assertFalse(LabelRules.isDuplicate(labels, "Work"))
        assertFalse(LabelRules.isDuplicate(labels, "  "))
    }

    @Test
    fun `colours cycle through the palette`() {
        assertEquals(LabelRules.PALETTE[0], LabelRules.nextColor(0))
        assertEquals(LabelRules.PALETTE[1], LabelRules.nextColor(LabelRules.PALETTE.size + 1))
    }

    @Test
    fun `merging adds only the missing links, never a duplicate`() {
        val links = listOf(LabelLink(1, 10), LabelLink(2, 10), LabelLink(2, 20), LabelLink(3, 20))
        assertEquals(listOf(LabelLink(1, 20)), LabelRules.mergeLinksToAdd(links, from = 10, to = 20))
        assertTrue(LabelRules.mergeLinksToAdd(links, 10, 10).isEmpty())
    }

    @Test
    fun `grouping helpers`() {
        val links = listOf(LabelLink(1, 10), LabelLink(1, 20), LabelLink(2, 10))
        assertEquals(mapOf(1L to setOf(10L, 20L), 2L to setOf(10L)), LabelRules.byTransaction(links))
        assertEquals(mapOf(10L to 2, 20L to 1), LabelRules.useCounts(links))
    }
}
