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
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = ObsidianTokens.titleFontSize,
        letterSpacing = ObsidianTokens.displayTracking,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = ObsidianTokens.displayTracking,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = AppFonts.spaceGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Medium,
        fontSize = ObsidianTokens.bodyFontSize,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = ObsidianTokens.metaWeight,
        fontSize = 12.sp,
        letterSpacing = ObsidianTokens.metaTracking,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = AppFonts.inter,
        fontWeight = ObsidianTokens.metaWeight,
        fontSize = 11.sp,
        letterSpacing = ObsidianTokens.metaTracking,
        lineHeight = 16.sp,
    ),
)
