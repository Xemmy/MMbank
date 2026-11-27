package com.xemmy.mmbank.ui.util

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xemmy.mmbank.ui.theme.AedGlyph
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.ObsidianTokens

internal const val AED_GLYPH_ID = "aed_glyph"

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
private val numeralSpanStyle = SpanStyle(
    fontFamily = AppFonts.plexMono,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.2.sp,
    // Tabular numerals: figures align instead of wobbling per digit.
    fontFeatureSettings = "tnum",
)

/**
 * Appends one money display string ("−46.12 AED", "••••••") using the
 * AmountText typography split. AED gets the inline Dirham glyph [glyphId]
 * (supply it via [aedGlyphContent]); other codes trail small and muted.
 */
internal fun AnnotatedString.Builder.appendMoney(display: String, color: Color, glyphId: String = AED_GLYPH_ID) {
    val parts = splitMoneyDisplay(display)
    if (parts.currencyCode.equals("AED", ignoreCase = true)) {
        val (sign, digits) = splitLeadingSign(parts.numeral)
        withStyle(numeralSpanStyle) { append(sign) }
        appendInlineContent(glyphId, "Đ")
        withStyle(numeralSpanStyle) { append(digits) }
    } else {
        withStyle(numeralSpanStyle) { append(parts.numeral) }
        parts.currencyCode?.let { code ->
            append(' ')
            withStyle(
                SpanStyle(
                    fontFamily = AppFonts.inter,
                    fontSize = ObsidianTokens.currencyFontSize,
                    fontWeight = FontWeight.Normal,
                    letterSpacing = ObsidianTokens.currencyTracking,
                    color = color.copy(alpha = 0.55f),
                )
            ) { append(code) }
        }
    }
}

/** The drawn Dirham symbol as inline content: cap height of the numerals, bottom on the baseline. */
internal fun aedGlyphContent(color: Color): InlineTextContent = InlineTextContent(
    // Width matches the vector's 20:24 viewport exactly so the glyph fills
    // the placeholder with no letterboxing.
    Placeholder(0.60.em, 0.72.em, PlaceholderVerticalAlign.AboveBaseline),
) {
    AedGlyph(color = color, modifier = Modifier.fillMaxSize())
}

/**
 * [fitWidth] shrinks the text (down to 11sp, from [style]'s size) instead of
 * truncating when the container is narrow, e.g. a 1/3-width stat tile.
 */
@Composable
fun AmountText(
    display: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fitWidth: Boolean = false,
) {
    val text = remember(display, color) { buildAnnotatedString { appendMoney(display, color) } }
    val inline = remember(color) { mapOf(AED_GLYPH_ID to aedGlyphContent(color)) }

    if (fitWidth && style.fontSize.isSp) {
        BasicText(
            text,
            modifier = modifier,
            style = style.copy(color = color),
            inlineContent = inline,
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize),
        )
        return
    }
    Text(
        text,
        inlineContent = inline,
        style = style,
        color = color,
        maxLines = 1,
        // In a narrow container a numeral too wide to fit after the inline AED
        // glyph on line 1 would otherwise soft-wrap onto a line 2 that
        // maxLines=1 then drops entirely. softWrap=false keeps it one line.
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
