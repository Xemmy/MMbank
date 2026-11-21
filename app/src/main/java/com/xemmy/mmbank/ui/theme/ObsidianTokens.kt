package com.xemmy.mmbank.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Obsidian Ledger" design tokens (Phase 5 spec 5.1). Every color, radius and
 * text treatment in the premium UI pass resolves through here — screens never
 * hardcode a hex value or a magic sp/dp number for these roles.
 */
object ObsidianTokens {

    // -- Color --------------------------------------------------------------
    val canvas = Color(0xFF0B0E14)
    val surfaceGlass = Color.White.copy(alpha = 0.055f)
    val hairline = Color.White.copy(alpha = 0.09f)
    val hairlineDialog = Color.White.copy(alpha = 0.12f)
    val textPrimary = Color(0xFFEDF1F6)
    val textMeta = textPrimary.copy(alpha = 0.48f)
    val credit = Color(0xFF86E3B8)
    val debit = Color(0xFFE0906F)

    val glowBinance = Color(0xFFF0BE5A)
    val glowTabby = Color(0xFF2DD4BF)
    val glowSystem = Color(0xFF96A0BE)
    const val GLOW_BINANCE_ALPHA = 0.28f
    const val GLOW_TABBY_ALPHA = 0.26f
    const val GLOW_SYSTEM_ALPHA = 0.18f

    val dialogPanelBlur = Color(0xFF12161F).copy(alpha = 0.72f)
    val dialogPanelFallback = Color(0xFF151A24).copy(alpha = 0.96f)
    val scrim = Color.Black.copy(alpha = 0.40f)

    /** Ambient canvas wash: three static radial glows drawn once behind the feed. */
    val ambientWash = listOf(
        Color(0xFF2DD4BF).copy(alpha = 0.13f) to Offset(0.05f, 0.02f),
        Color(0xFFF0BE5A).copy(alpha = 0.10f) to Offset(0.95f, 0.28f),
        Color(0xFF788CFF).copy(alpha = 0.07f) to Offset(0.35f, 0.95f),
    )

    // -- Shape ----------------------------------------------------------------
    val rowRadius = 18.dp
    val dialogRadius = 24.dp
    val pillRadius = 99.dp
    val avatarSize = 34.dp
    val avatarRadius = 11.dp

    // -- Type -----------------------------------------------------------------
    /** Uppercase meta label letter-spacing, e.g. category/account rows. */
    val metaTracking: TextUnit = 0.06.em
    val displayTracking: TextUnit = (-0.01).em
    val pillTracking: TextUnit = 0.18.em
    val currencyTracking: TextUnit = 0.1.em

    val metaFontSize = 11.sp
    val currencyFontSize = 11.sp
    val amountFontSize = 14.5.sp
    val titleFontSize = 21.sp
    val bodyFontSize = 13.sp

    val metaWeight = FontWeight.Medium
}

/**
 * Draws the three static ambient radial glows behind the Obsidian canvas.
 * Apply once at the screen root, behind scrollable content — never per-row.
 */
fun Modifier.obsidianCanvas(): Modifier = this
    .background(ObsidianTokens.canvas)
    .drawBehind {
        val w = size.width
        val h = size.height
        val radius = w * 0.7f
        for ((color, fraction) in ObsidianTokens.ambientWash) {
            val center = Offset(w * fraction.x, h * fraction.y)
            drawCircle(
                brush = Brush.radialGradient(colors = listOf(color, Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center,
            )
        }
    }

/** Uppercase, letter-spaced micro label used for meta rows and field labels. */
@Composable
fun MetaLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ObsidianTokens.textMeta,
    maxLines: Int = 1,
) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = color,
        fontFamily = AppFonts.inter,
        fontWeight = ObsidianTokens.metaWeight,
        fontSize = ObsidianTokens.metaFontSize,
        letterSpacing = ObsidianTokens.metaTracking,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
