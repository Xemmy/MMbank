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

    @Test
    fun `obsidian palette is exactly the legacy dark and light sets`() {
        assertEquals(darkMMColors(), mmColors(com.xemmy.mmbank.domain.model.ColorPalette.OBSIDIAN, true))
        assertEquals(lightMMColors(), mmColors(com.xemmy.mmbank.domain.model.ColorPalette.OBSIDIAN, false))
    }

    @Test
    fun `obsidian colour scheme keeps its pre-palette container roles`() {
        val dark = darkMMColors().toColorScheme()
        assertEquals(PrimaryContainerDark, dark.primaryContainer)
        assertEquals(DarkSurfaceContainerHigh, dark.surfaceContainerHigh)
        assertEquals(DarkSurfaceContainerLowest, dark.surfaceContainerLowest)
        val light = lightMMColors().toColorScheme()
        assertEquals(PrimaryContainerLight, light.primaryContainer)
        assertEquals(LightSurfaceContainer, light.surfaceVariant)
    }

    @Test
    fun `graphite differs from obsidian but keeps money and status colours`() {
        for (dark in listOf(true, false)) {
            val o = mmColors(com.xemmy.mmbank.domain.model.ColorPalette.OBSIDIAN, dark)
            val g = mmColors(com.xemmy.mmbank.domain.model.ColorPalette.GRAPHITE, dark)
            assertEquals(dark, g.isDark)
            assertTrue(g.canvas != o.canvas && g.accent != o.accent)
            assertEquals(o.moneyIn, g.moneyIn)
            assertEquals(o.danger, g.danger)
            assertEquals(o.chartPalette, g.chartPalette)
            assertEquals(g.accent, g.toColorScheme().primary)
            assertEquals(g.primaryContainer, g.toColorScheme().primaryContainer)
        }
    }
}
