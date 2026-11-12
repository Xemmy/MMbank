package com.xemmy.mmbank.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.xemmy.mmbank.domain.model.DesignMode

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
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val mmColors = if (darkTheme) darkMMColors() else lightMMColors()
    val colorScheme = mmColors.toColorScheme()
    CompositionLocalProvider(LocalDesignMode provides designMode, LocalMMColors provides mmColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ObsidianTypography,
        ) {
            // The app root Scaffold (MainActivity) uses a transparent
            // container so the ambient background shows through; that
            // Color.Transparent doesn't match any ColorScheme role, so M3's
            // contentColorFor() falls back to LocalContentColor's own
            // default (Color.Black) instead of onSurface. Any Text() below
            // it without an explicit color would render black on the dark
            // canvas. Providing onSurface here fixes that fallback for the
            // whole tree; nested Surface/Scaffold/Card composables still
            // override it correctly per their own container color.
            CompositionLocalProvider(LocalContentColor provides colorScheme.onSurface) {
                content()
            }
        }
    }
}
