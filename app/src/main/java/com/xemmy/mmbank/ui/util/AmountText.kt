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

/**
 * Renders a money display string with the Phase 5 typography split: numerals in
 * a high-contrast weight with tabular figures and geometric tracking, the
 * trailing currency ISO code distinctly smaller and lighter. Strings without a
 * trailing code (the hide-balances mask, for one) render whole, so this is a
 * drop-in replacement for a plain Text of `asMoney`/`asMoneyOrMask` output.
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
                        fontSize = style.fontSize * 0.68f,
                        fontWeight = FontWeight.Light,
                        letterSpacing = 1.sp,
                        color = color.copy(alpha = 0.72f),
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
