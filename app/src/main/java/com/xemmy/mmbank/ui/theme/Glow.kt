package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

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

/**
 * Faint radial bloom drawn behind content. Direction glows sit at the top end
 * (behind the amount); origin brand glows anchor at the bottom start. A null
 * tone draws nothing, so call sites can pass the mapping result straight in.
 */
fun Modifier.glowBackdrop(
    tone: GlowTone?,
    alpha: Float,
    atTopEnd: Boolean = true,
): Modifier {
    if (tone == null) return this
    return drawBehind {
        if (size.minDimension <= 0f) return@drawBehind
        val color = GlowPalette.color(tone).copy(alpha = alpha)
        val center = if (atTopEnd) {
            Offset(size.width * 0.92f, 0f)
        } else {
            Offset(size.width * 0.08f, size.height)
        }
        val radius = size.width * 0.55f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}
