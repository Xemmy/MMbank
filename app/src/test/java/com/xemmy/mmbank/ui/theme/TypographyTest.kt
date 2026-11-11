package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.text.TextStyle
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographyTest {
    private val styles: Map<String, TextStyle> = mapOf(
        "headlineSmall" to ObsidianTypography.headlineSmall,
        "titleLarge" to ObsidianTypography.titleLarge,
        "titleMedium" to ObsidianTypography.titleMedium,
        "titleSmall" to ObsidianTypography.titleSmall,
        "bodyLarge" to ObsidianTypography.bodyLarge,
        "bodyMedium" to ObsidianTypography.bodyMedium,
        "bodySmall" to ObsidianTypography.bodySmall,
        "labelLarge" to ObsidianTypography.labelLarge,
        "labelMedium" to ObsidianTypography.labelMedium,
        "labelSmall" to ObsidianTypography.labelSmall,
    )

    @Test
    fun `no custom style is below the 11sp floor`() {
        styles.forEach { (n, s) -> assertTrue("$n ${s.fontSize}", s.fontSize.value >= 11f) }
        assertTrue(ObsidianTokens.metaFontSize.value >= 11f)
        assertTrue(ObsidianTokens.currencyFontSize.value >= 11f)
    }

    @Test
    fun `every custom style defines a line height`() {
        styles.forEach { (n, s) -> assertTrue("$n lineHeight", s.lineHeight.value > 0f) }
    }
}
