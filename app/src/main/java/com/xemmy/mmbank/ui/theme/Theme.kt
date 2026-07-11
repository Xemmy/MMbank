package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344A),
    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332E),
    tertiary = Color(0xFFFFB74D),
    background = ObsidianTokens.canvas,
    surface = Color(0xFF12161F),
    surfaceVariant = Color(0xFF1B2130),
    onBackground = ObsidianTokens.textPrimary,
    onSurface = ObsidianTokens.textPrimary,
    error = Color(0xFFFF8A80),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    secondary = Color(0xFF00796B),
    tertiary = Color(0xFFEF6C00),
)

/** Colors used for transaction direction across all screens. */
object LedgerColors {
    val credit = Color(0xFF66BB6A)
    val debit = Color(0xFFEF5350)
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

@Composable
fun MMbankTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ObsidianTypography,
        content = content,
    )
}
