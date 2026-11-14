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
    /**
     * Categorical chart series, an Okabe-Ito-derived set that stays distinguishable under the
     * common colour-vision deficiencies. Each clears 3:1 against canvas, surface and surfaceRaised
     * (ColorContrastTest). Charts still never rely on colour alone.
     */
    val chart1: Color,
    val chart2: Color,
    val chart3: Color,
    val chart4: Color,
    val chart5: Color,
    val chart6: Color,
    val chart7: Color,
    val chart8: Color,
) {
    /** [chart1]..[chart8] in order. */
    val chartPalette: List<Color>
        get() = listOf(chart1, chart2, chart3, chart4, chart5, chart6, chart7, chart8)
}

private val ChartDark1 = Color(0xFFE69F00)
private val ChartDark2 = Color(0xFF56B4E9)
private val ChartDark3 = Color(0xFF2FC49A)
private val ChartDark4 = Color(0xFFF0E442)
private val ChartDark5 = Color(0xFF6F9CF5)
private val ChartDark6 = Color(0xFFF07C3A)
private val ChartDark7 = Color(0xFFD98CB8)
private val ChartDark8 = Color(0xFFA9B0BD)

private val ChartLight1 = Color(0xFFB36B00)
private val ChartLight2 = Color(0xFF0072B2)
private val ChartLight3 = Color(0xFF007A5A)
private val ChartLight4 = Color(0xFF8A7A00)
private val ChartLight5 = Color(0xFF3B4FC4)
private val ChartLight6 = Color(0xFFC24E00)
private val ChartLight7 = Color(0xFFA3487A)
private val ChartLight8 = Color(0xFF5F6B7A)

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
    chart1 = ChartDark1, chart2 = ChartDark2, chart3 = ChartDark3, chart4 = ChartDark4,
    chart5 = ChartDark5, chart6 = ChartDark6, chart7 = ChartDark7, chart8 = ChartDark8,
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
    chart1 = ChartLight1, chart2 = ChartLight2, chart3 = ChartLight3, chart4 = ChartLight4,
    chart5 = ChartLight5, chart6 = ChartLight6, chart7 = ChartLight7, chart8 = ChartLight8,
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
