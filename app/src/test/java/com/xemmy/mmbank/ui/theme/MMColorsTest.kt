package com.xemmy.mmbank.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MMColorsTest {
    @Test
    fun `dark and light palettes are flagged and differ`() {
        assertTrue(darkMMColors().isDark)
        assertFalse(lightMMColors().isDark)
        assertTrue(darkMMColors().canvas != lightMMColors().canvas)
    }

    @Test
    fun `color scheme is derived from the semantic tokens`() {
        for (c in listOf(darkMMColors(), lightMMColors())) {
            val scheme = c.toColorScheme()
            assertEquals(c.accent, scheme.primary)
            assertEquals(c.onAccent, scheme.onPrimary)
            assertEquals(c.canvas, scheme.background)
            assertEquals(c.surface, scheme.surface)
            assertEquals(c.textPrimary, scheme.onSurface)
            assertEquals(c.danger, scheme.error)
        }
    }

    @Test
    fun `debits use the primary text colour in both themes`() {
        assertEquals(darkMMColors().textPrimary, darkMMColors().moneyOut)
        assertEquals(lightMMColors().textPrimary, lightMMColors().moneyOut)
    }
}
