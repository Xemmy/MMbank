package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.xemmy.mmbank.domain.model.BackgroundStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-data checks for the ambient background presets: every glow stays in
 * the ultra-subtle alpha band, OLED Obsidian anchors top-right on a pure
 * black surface, and Minimal Slate is genuinely flat (spec 5.2/4).
 */
class AmbientBackgroundTest {

    @Test
    fun `every glow alpha is ultra-subtle, 0_08f to 0_15f`() {
        BackgroundStyle.entries.forEach { style ->
            specFor(style).glows.forEach { glow ->
                assertTrue(
                    "${style.name} glow alpha ${glow.alpha} out of band",
                    glow.alpha in 0.08f..0.15f,
                )
            }
        }
    }

    @Test
    fun `OLED Obsidian is a deep violet glow top-right on pure black`() {
        val spec = specFor(BackgroundStyle.OLED_OBSIDIAN)
        assertEquals(Color(0xFF000000), spec.surface)
        assertEquals(1, spec.glows.size)
        assertEquals(Offset(0.8f, -0.2f), spec.glows.single().centerFraction)
    }

    @Test
    fun `Minimal Slate is flat with zero color bleed`() {
        assertTrue(specFor(BackgroundStyle.MINIMAL_SLATE).glows.isEmpty())
    }

    @Test
    fun `Aurora Muted combines an indigo and a teal glow`() {
        assertEquals(2, specFor(BackgroundStyle.AURORA_MUTED).glows.size)
    }

    @Test
    fun `every style has a distinct, non-blank display name and description`() {
        val names = BackgroundStyle.entries.map { it.displayName }
        val descriptions = BackgroundStyle.entries.map { it.description }
        assertTrue(names.all { it.isNotBlank() })
        assertTrue(descriptions.all { it.isNotBlank() })
        assertEquals(names.distinct().size, names.size)
        assertEquals(descriptions.distinct().size, descriptions.size)
    }
}
