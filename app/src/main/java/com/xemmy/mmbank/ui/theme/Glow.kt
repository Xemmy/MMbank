package com.xemmy.mmbank.ui.theme

/**
 * Semantic identity of a contextual glow. Screens pick a tone; only the theme
 * ([GlowPalette]) knows what color a tone renders as. Pure Kotlin so the
 * tone-selection rules are JVM-testable.
 */
enum class GlowTone {
    /** Desaturated mint behind credits. */
    CREDIT_MINT,

    /** Soft copper/crimson behind debits. */
    DEBIT_COPPER,

    /** Faint gold hint for Binance-originated accounts/cards. */
    BINANCE_GOLD,

    /** Soft teal for Tabby-originated accounts/cards. */
    TABBY_TEAL,
}

object GlowTones {

    /** Direction glow: mint for money in, copper for money out. */
    fun forDirection(isCredit: Boolean): GlowTone =
        if (isCredit) GlowTone.CREDIT_MINT else GlowTone.DEBIT_COPPER

    /**
     * Brand glow for an account's origin, matched case-insensitively across
     * whatever labels we know it by (account name, bank name, sender). Null
     * when the origin has no registered brand tint — most banks stay neutral.
     */
    fun forAccountOrigin(vararg labels: String?): GlowTone? {
        val haystack = labels.filterNotNull().joinToString(" ").lowercase()
        return when {
            "binance" in haystack -> GlowTone.BINANCE_GOLD
            "tabby" in haystack -> GlowTone.TABBY_TEAL
            else -> null
        }
    }
}
