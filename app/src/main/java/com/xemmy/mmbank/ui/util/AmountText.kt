package com.xemmy.mmbank.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.ObsidianTokens

/**
 * Renders a money display string with the Obsidian Ledger typography split
 * (spec 5.1): numerals in IBM Plex Mono SemiBold with tabular figures, the
 * trailing currency ISO code distinctly smaller, lighter and low-opacity.
 * Strings without a trailing code (the hide-balances mask, for one) render
 * whole, so this is a drop-in replacement for a plain Text of
 * `asMoney`/`asMoneyOrMask` output.
 */
@Composable
fun AmountText(
    display: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
) {
    val parts = remember(display) { splitMoneyDisplay(display) }
    Text(
        buildAnnotatedString {
            withStyle(
                SpanStyle(
                    fontFamily = AppFonts.plexMono,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp,
                    // Tabular numerals: figures align instead of wobbling per digit.
                    fontFeatureSettings = "tnum",
                )
            ) { append(parts.numeral) }
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
        },
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}
