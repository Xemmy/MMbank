package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Obsidian Ledger type scale (spec 5.1): Space Grotesk for display/titles,
 * Inter for body copy. Amounts and uppercase meta labels bypass this scale
 * entirely — see [com.xemmy.mmbank.ui.util.AmountText] and [MetaLabel].
 */
val ObsidianTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = ObsidianTokens.titleFontSize,
        letterSpacing = ObsidianTokens.displayTracking,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = ObsidianTokens.titleFontSize,
        letterSpacing = ObsidianTokens.displayTracking,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = ObsidianTokens.displayTracking,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Medium,
        fontSize = ObsidianTokens.bodyFontSize,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = ObsidianTokens.metaWeight,
        fontSize = ObsidianTokens.metaFontSize,
        letterSpacing = ObsidianTokens.metaTracking,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = ObsidianTokens.metaWeight,
        fontSize = 8.sp,
        letterSpacing = ObsidianTokens.metaTracking,
    ),
)
