package com.xemmy.mmbank.ui.util

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xemmy.mmbank.ui.theme.AedGlyph
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.ObsidianTokens

private const val AED_GLYPH_ID = "aed_glyph"

/**
 * Renders a money display string with the Obsidian Ledger typography split
 * (spec 5.1): numerals in IBM Plex Mono SemiBold with tabular figures, the
 * trailing currency ISO code distinctly smaller, lighter and low-opacity.
 * Strings without a trailing code (the hide-balances mask, for one) render
 * whole, so this is a drop-in replacement for a plain Text of
 * `asMoney`/`asMoneyOrMask` output.
 *
 * AED gets the banking-app treatment instead of a trailing code: the drawn
 * Dirham symbol ([AedGlyph]) sits BEFORE the digits — after any +/−/≈ sign —
 * at digit cap height, baseline-aligned via inline text content, in the same
 * color as the amount ("-Đ85.00"). Sized in em, so it scales with whatever
 * [style] each call site passes.
 */
@Composable
fun AmountText(
    display: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val parts = remember(display) { splitMoneyDisplay(display) }
    val isAed = parts.currencyCode.equals("AED", ignoreCase = true)

    val numeralStyle = SpanStyle(
        fontFamily = AppFonts.plexMono,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
        // Tabular numerals: figures align instead of wobbling per digit.
        fontFeatureSettings = "tnum",
    )

    Text(
        buildAnnotatedString {
            if (isAed) {
                val (sign, digits) = splitLeadingSign(parts.numeral)
                withStyle(numeralStyle) { append(sign) }
                appendInlineContent(AED_GLYPH_ID, "Đ")
                withStyle(numeralStyle) { append(digits) }
            } else {
                withStyle(numeralStyle) { append(parts.numeral) }
                parts.currencyCode?.let { code ->
                    append(' ')
                    withStyle(
                        SpanStyle(
                            fontFamily = AppFonts.inter,
                            fontSize = ObsidianTokens.currencyFontSize,
                            fontWeight = FontWeight.Light,
                            letterSpacing = ObsidianTokens.currencyTracking,
                            color = color.copy(alpha = 0.55f),
                        )
                    ) { append(code) }
                }
            }
        },
        inlineContent = mapOf(
            AED_GLYPH_ID to InlineTextContent(
                // Cap height of the numerals (~0.72em), bottom on the baseline;
                // width leaves a hair of air before the first digit.
                Placeholder(0.66.em, 0.72.em, PlaceholderVerticalAlign.AboveBaseline),
            ) {
                AedGlyph(color = color, modifier = Modifier.fillMaxSize())
            }
        ),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}
