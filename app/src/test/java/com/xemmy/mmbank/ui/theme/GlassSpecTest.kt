package com.xemmy.mmbank.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glass treatment is gated on API 31 (RenderEffect): below the gate blur
 * must be fully off and the surface must fall back to a more opaque solid,
 * so the overlay never turns unreadable on devices where blur is a no-op.
 */
class GlassSpecTest {

    @Test
    fun `blur is off below API 31 and on from 31 up`() {
        assertFalse(GlassSpec.supportsBlur(26))
        assertFalse(GlassSpec.supportsBlur(30))
        assertTrue(GlassSpec.supportsBlur(31))
        assertTrue(GlassSpec.supportsBlur(35))
    }

    @Test
    fun `pre-31 fallback surface is more opaque than the blurred glass`() {
        val glass = GlassSpec.containerAlpha(31)
        val fallback = GlassSpec.containerAlpha(30)
        assertTrue(fallback > glass)
        // Both remain translucent surfaces per the spec — never fully solid.
        assertTrue(glass in 0f..1f)
        assertTrue(fallback < 1f)
    }

    @Test
    fun `backdrop blur radius is zero exactly when blur is unsupported`() {
        assertEquals(0, GlassSpec.backdropBlurRadiusDp(30))
        assertEquals(GlassSpec.BACKDROP_BLUR_RADIUS_DP, GlassSpec.backdropBlurRadiusDp(31))
        assertTrue(GlassSpec.BACKDROP_BLUR_RADIUS_DP > 0)
    }
}
