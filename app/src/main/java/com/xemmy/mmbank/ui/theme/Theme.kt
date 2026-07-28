package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xemmy.mmbank.domain.model.DesignMode

private val DarkColors = darkColorScheme(
    primary = PrimaryElectric,
    onPrimary = OnPrimaryElectric,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = PrimaryContainerLight,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = ErrorDark,
    onError = OnErrorDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    scrim = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = PrimaryElectric,
    onPrimary = OnPrimaryElectric,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = PrimaryContainerDark,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = LightSurface,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorLight,
    onError = OnErrorLight,
    errorContainer = ErrorContainerLight,
    onErrorContainer = OnErrorContainerLight,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightSurface,
    scrim = Color.Black,
)

/**
 * Financial-status semantic tokens (Zero-Conflict Color System spec). Resolved
 * off [ColorScheme.surface] luminance rather than a CompositionLocal so any
 * composable already holding `MaterialTheme.colorScheme` can reach them
 * without extra plumbing; ColorContrastTest pins the light/dark selection
 * logic and the underlying hex pairs.
 */
private fun ColorScheme.isDarkScheme(): Boolean = surface.luminance() < 0.5f

val ColorScheme.incomeContainer: Color
    get() = if (isDarkScheme()) IncomeContainerDark else IncomeContainerLight

val ColorScheme.onIncomeContainer: Color
    get() = if (isDarkScheme()) OnIncomeContainerDark else OnIncomeContainerLight

val ColorScheme.expenseContainer: Color
    get() = if (isDarkScheme()) ExpenseContainerDark else ExpenseContainerLight

val ColorScheme.onExpenseContainer: Color
    get() = if (isDarkScheme()) OnExpenseContainerDark else OnExpenseContainerLight

/**
 * Colors used for transaction direction across all screens. Backed by the
 * accessible income/expense "on container" tokens so every credit/debit
 * indicator gets AAA contrast against the app's dark canvas for free.
 */
object LedgerColors {
    val credit: Color
        @Composable get() = MaterialTheme.colorScheme.onIncomeContainer
    val debit: Color
        @Composable get() = MaterialTheme.colorScheme.onExpenseContainer
}

/**
 * Contextual glow color tokens (Phase 5 "Obsidian Ledger", spec 5.1). Every
 * faint background glow in the app resolves through here — screens reference
 * a [GlowTone], never inline hex. Values mirror [ObsidianTokens].
 */
object GlowPalette {
    val creditMint = ObsidianTokens.credit
    val debitCopper = ObsidianTokens.debit
    val binanceGold = ObsidianTokens.glowBinance
    val tabbyTeal = ObsidianTokens.glowTabby
    val systemSlate = ObsidianTokens.glowSystem

    /** Alpha for the direction glow behind amounts. */
    const val DIRECTION_ALPHA = GlowAlphas.DIRECTION

    /**
     * Legacy flat origin-glow alpha, kept only until every call site moves to
     * the per-brand [GlowAlphas.origin]. New code should call that instead.
     */
    const val ORIGIN_ALPHA = 0.08f

    fun color(tone: GlowTone): Color = when (tone) {
        GlowTone.CREDIT_MINT -> creditMint
        GlowTone.DEBIT_COPPER -> debitCopper
        GlowTone.BINANCE_GOLD -> binanceGold
        GlowTone.TABBY_TEAL -> tabbyTeal
        GlowTone.SYSTEM_SLATE -> systemSlate
    }
}

/** Read by any composable that needs to branch on Classic vs Premium without threading a parameter through. */
val LocalDesignMode = staticCompositionLocalOf { DesignMode.CLASSIC }

/**
 * Classic and Premium share the same Material 3 baseline theme.
 * MaterialExpressiveTheme stays `internal` in material3 until the library
 * graduates it out of the 1.5.0-alpha train -- the compose-bom this project
 * pins (stable, 1.4.x material3) can't reach it. Premium's distinct feel
 * (springier entrance motion, AGSL AI-parsing indicator) instead comes from
 * [LocalDesignMode] consumers branching on the mode directly.
 */
@Composable
fun MMbankTheme(
    designMode: DesignMode = DesignMode.CLASSIC,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(LocalDesignMode provides designMode) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ObsidianTypography,
            content = content,
        )
    }
}
