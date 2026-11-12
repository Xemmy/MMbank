package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colour tokens (Improvement Plan §1.1). Screens read these through
 * [MMTheme.colors]; the Material [ColorScheme] is derived from the same tokens
 * by [toColorScheme] so M3 buttons, fields and switches always match.
 * Contrast of every text/surface pair is pinned in ColorContrastTest.
 */
@Immutable
data class MMColors(
    val isDark: Boolean,
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val borderSubtle: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val moneyIn: Color,
    /** Spending is the normal state of a ledger, so debits use the primary text colour. */
    val moneyOut: Color,
    val moneyNeutral: Color,
    val danger: Color,
    val warning: Color,
    val info: Color,
    val brandGold: Color,
    val brandTeal: Color,
)

fun darkMMColors() = MMColors(
    isDark = true,
    canvas = Ink950,
    surface = Ink900,
    surfaceRaised = Ink800,
    surfaceOverlay = Ink750,
    borderSubtle = Ink700,
    borderStrong = Ink600,
    textPrimary = Ink50,
    textSecondary = Ink300,
    textTertiary = Ink400,
    accent = Indigo400,
    onAccent = Ink950,
    moneyIn = Mint300,
    moneyOut = Ink50,
    moneyNeutral = Ink300,
    danger = Red400,
    warning = Gold400,
    info = Blue400,
    brandGold = Gold300,
    brandTeal = Teal400,
)

fun lightMMColors() = MMColors(
    isDark = false,
    canvas = Paper100,
    surface = Color.White,
    surfaceRaised = Color.White,
    surfaceOverlay = Color.White,
    borderSubtle = Paper300,
    borderStrong = Paper400,
    textPrimary = Ink1000,
    textSecondary = Slate600,
    textTertiary = Slate500,
    accent = Indigo600,
    onAccent = Color.White,
    moneyIn = Mint700,
    moneyOut = Ink1000,
    moneyNeutral = Slate600,
    danger = Red700,
    warning = Amber700,
    info = Blue700,
    brandGold = Gold700,
    brandTeal = Teal700,
)

/** Builds the Material scheme from the semantic tokens so both always agree. */
fun MMColors.toColorScheme(): ColorScheme = if (isDark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = PrimaryContainerDark, onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark, onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark, onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark, onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark, onTertiaryContainer = OnTertiaryContainerDark,
        background = canvas, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceRaised, onSurfaceVariant = textSecondary,
        surfaceContainerLowest = DarkSurfaceContainerLowest, surfaceContainerLow = DarkSurfaceContainerLow,
        surfaceContainer = DarkSurfaceContainer, surfaceContainerHigh = DarkSurfaceContainerHigh,
        surfaceContainerHighest = DarkSurfaceContainerHighest,
        outline = borderStrong, outlineVariant = borderSubtle,
        error = danger, onError = OnErrorDark,
        errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
        inverseSurface = textPrimary, inverseOnSurface = canvas,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = PrimaryContainerLight, onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight, onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight, onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight, onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight, onTertiaryContainer = OnTertiaryContainerLight,
        background = canvas, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = LightSurfaceContainer, onSurfaceVariant = textSecondary,
        surfaceContainerLowest = LightSurfaceContainerLowest, surfaceContainerLow = LightSurfaceContainerLow,
        surfaceContainer = LightSurfaceContainer, surfaceContainerHigh = LightSurfaceContainerHigh,
        surfaceContainerHighest = LightSurfaceContainerHighest,
        outline = borderStrong, outlineVariant = borderSubtle,
        error = danger, onError = OnErrorLight,
        errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
        inverseSurface = textPrimary, inverseOnSurface = canvas,
        scrim = Color.Black,
    )
}

val LocalMMColors = staticCompositionLocalOf { darkMMColors() }

object MMTheme {
    val colors: MMColors
        @Composable @ReadOnlyComposable get() = LocalMMColors.current
}
