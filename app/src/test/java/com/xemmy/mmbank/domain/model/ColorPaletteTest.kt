package com.xemmy.mmbank.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorPaletteTest {
    @Test
    fun `stored names round-trip`() {
        ColorPalette.entries.forEach { assertEquals(it, ColorPalette.fromStored(it.name)) }
    }

    @Test
    fun `missing or unknown stored value falls back to Obsidian`() {
        assertEquals(ColorPalette.OBSIDIAN, ColorPalette.fromStored(null))
        assertEquals(ColorPalette.OBSIDIAN, ColorPalette.fromStored(""))
        assertEquals(ColorPalette.OBSIDIAN, ColorPalette.fromStored("NEON"))
    }

    @Test
    fun `names and descriptions are distinct and non-blank`() {
        val names = ColorPalette.entries.map { it.displayName }
        assertTrue(names.all { it.isNotBlank() } && names.distinct().size == names.size)
        assertTrue(ColorPalette.entries.all { it.description.isNotBlank() })
    }
}
