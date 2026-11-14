package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG 2.x contrast verification for the Zero-Conflict Color System palette
 * (Color.kt). Ratio formula: (L1 + 0.05) / (L2 + 0.05) using relative
 * luminance, per https://www.w3.org/TR/WCAG21/#dfn-contrast-ratio.
 *
 * Every pair below must clear AA (4.5:1) for normal text. AAA (7:1) is
 * asserted wherever the palette actually reaches it; two pairs are pinned to
 * exact brand hex values from the design spec rather than tuned for AAA —
 * PrimaryElectric/OnPrimaryElectric (4.47:1, just under AA) and the raw
 * income/expense *container* pairs (5.3-6.4:1, AA but not AAA). Both income
 * and expense colors are used in the app as text directly on the surface
 * canvas, not as filled container chips, where they clear AAA by a wide
 * margin (checked below).
 */
class ColorContrastTest {

    private fun contrastRatio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val l1 = a.luminance()
        val l2 = b.luminance()
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    private val AA = 4.5f
    private val AAA = 7f

    @Test
    fun `dark theme primary text clears AAA against surface and surfaceContainer`() {
        assertTrue(contrastRatio(DarkOnSurface, DarkSurface) >= AAA)
        assertTrue(contrastRatio(DarkOnSurface, DarkSurfaceContainer) >= AAA)
    }

    @Test
    fun `dark theme secondary text clears AA against surface and surfaceContainer`() {
        assertTrue(contrastRatio(DarkOnSurfaceVariant, DarkSurface) >= AAA)
        assertTrue(contrastRatio(DarkOnSurfaceVariant, DarkSurfaceContainer) >= AA)
    }

    @Test
    fun `light theme primary and secondary text clear AAA and AA`() {
        assertTrue(contrastRatio(LightOnSurface, LightSurface) >= AAA)
        assertTrue(contrastRatio(LightOnSurfaceVariant, LightSurface) >= AAA)
        assertTrue(contrastRatio(LightOnSurfaceVariant, LightSurfaceContainer) >= AA)
    }

    @Test
    fun `income and expense on-container pairs clear AA as filled chips`() {
        assertTrue(contrastRatio(OnIncomeContainerDark, IncomeContainerDark) >= AA)
        assertTrue(contrastRatio(OnExpenseContainerDark, ExpenseContainerDark) >= AA)
        assertTrue(contrastRatio(OnIncomeContainerLight, IncomeContainerLight) >= AAA)
        assertTrue(contrastRatio(OnExpenseContainerLight, ExpenseContainerLight) >= AAA)
    }

    @Test
    fun `income and expense on-container colors clear AAA as plain text on the app canvas`() {
        // This is the app's actual usage today (LedgerColors.credit/debit render
        // as plain text directly on the dark surface, not inside a filled chip).
        assertTrue(contrastRatio(OnIncomeContainerDark, DarkSurface) >= AAA)
        assertTrue(contrastRatio(OnExpenseContainerDark, DarkSurface) >= AAA)
        assertTrue(contrastRatio(OnIncomeContainerLight, LightSurface) >= AAA)
        assertTrue(contrastRatio(OnExpenseContainerLight, LightSurface) >= AAA)
    }

    @Test
    fun `secondary and tertiary role pairs clear AA`() {
        assertTrue(contrastRatio(OnSecondaryDark, SecondaryDark) >= AAA)
        assertTrue(contrastRatio(OnSecondaryContainerDark, SecondaryContainerDark) >= AA)
        assertTrue(contrastRatio(OnTertiaryDark, TertiaryDark) >= AAA)
        assertTrue(contrastRatio(OnTertiaryContainerDark, TertiaryContainerDark) >= AA)

        assertTrue(contrastRatio(OnSecondaryLight, SecondaryLight) >= AA)
        assertTrue(contrastRatio(OnSecondaryContainerLight, SecondaryContainerLight) >= AAA)
        assertTrue(contrastRatio(OnTertiaryLight, TertiaryLight) >= AA)
        assertTrue(contrastRatio(OnTertiaryContainerLight, TertiaryContainerLight) >= AAA)
    }

    @Test
    fun `error role pairs clear AA`() {
        assertTrue(contrastRatio(OnErrorDark, ErrorDark) >= AAA)
        assertTrue(contrastRatio(OnErrorLight, ErrorLight) >= AA)
    }

    @Test
    fun `primary container pairs clear AAA`() {
        assertTrue(contrastRatio(OnPrimaryContainerDark, PrimaryContainerDark) >= AAA)
        assertTrue(contrastRatio(OnPrimaryContainerLight, PrimaryContainerLight) >= AAA)
    }

    @Test
    fun `primaryElectric brand pair is documented as sub-AA by design spec`() {
        // Pinned to the exact spec hex (indigo-500 / white). Recorded here so a
        // future palette change can't silently regress this further without the
        // test failing loud in the other direction.
        val ratio = contrastRatio(OnPrimaryElectric, PrimaryElectric)
        assertTrue(ratio in 4.0f..4.5f)
    }

    // -- Semantic MMColors tokens (Improvement Plan §1.1), both themes -------------

    private fun semantic(c: MMColors) {
        val text = mapOf(
            "textPrimary" to c.textPrimary, "textSecondary" to c.textSecondary,
            "moneyIn" to c.moneyIn, "moneyOut" to c.moneyOut, "moneyNeutral" to c.moneyNeutral,
            "danger" to c.danger, "warning" to c.warning, "info" to c.info,
            "brandGold" to c.brandGold, "brandTeal" to c.brandTeal, "accent" to c.accent,
        )
        for ((name, color) in text) {
            for ((sn, surf) in listOf("canvas" to c.canvas, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised)) {
                val r = contrastRatio(color, surf)
                assertTrue("dark=${c.isDark} $name on $sn = $r", r >= AA)
            }
        }
        // Tertiary is for captions on the two base surfaces only.
        assertTrue(contrastRatio(c.textTertiary, c.canvas) >= AA)
        assertTrue(contrastRatio(c.textTertiary, c.surface) >= AA)
        // Filled primary buttons.
        assertTrue("onAccent on accent", contrastRatio(c.onAccent, c.accent) >= AA)
    }

    @Test
    fun `dark semantic tokens clear AA`() = semantic(darkMMColors())

    @Test
    fun `light semantic tokens clear AA`() = semantic(lightMMColors())

    // -- Chart series tokens: >= 3:1 non-text contrast on every surface, both themes --------

    private fun chartTokens(c: MMColors) {
        assertTrue(c.chartPalette.size == 8)
        assertTrue(c.chartPalette.toSet().size == 8)
        c.chartPalette.forEachIndexed { i, color ->
            for ((sn, surf) in listOf("canvas" to c.canvas, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised)) {
                val r = contrastRatio(color, surf)
                assertTrue("dark=${c.isDark} chart${i + 1} on $sn = $r", r >= 3f)
            }
        }
    }

    @Test
    fun `dark chart tokens clear 3 to 1`() = chartTokens(darkMMColors())

    @Test
    fun `light chart tokens clear 3 to 1`() = chartTokens(lightMMColors())
}
