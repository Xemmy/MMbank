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

    @Test
    fun `chart palette is ordered chart1 to chart8 and differs between themes`() {
        for (c in listOf(darkMMColors(), lightMMColors())) {
            assertEquals(listOf(c.chart1, c.chart2, c.chart3, c.chart4, c.chart5, c.chart6, c.chart7, c.chart8), c.chartPalette)
        }
        assertTrue(darkMMColors().chartPalette != lightMMColors().chartPalette)
    }
}
