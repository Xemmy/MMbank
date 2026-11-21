package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.aedGlyphContent
import com.xemmy.mmbank.ui.util.appendMoney

/** One piece of a sentence that mixes words and amounts, so amounts can draw the Dirham symbol. */
sealed interface MoneySegment {
    data class Text(val text: String) : MoneySegment

    /** [amount] keeps its own sign; [tone] overrides the surrounding text colour. */
    data class Money(val amount: Double, val currency: String, val tone: MoneyTone? = null) : MoneySegment
}

fun text(s: String): MoneySegment = MoneySegment.Text(s)

fun money(amount: Double, currency: String, tone: MoneyTone? = null): MoneySegment =
    MoneySegment.Money(amount, currency, tone)

/** The string AmountText-style rendering takes for one money segment: "−46.12 AED", or the mask. */
fun moneySegmentDisplay(m: MoneySegment.Money, hidden: Boolean): String {
    if (hidden) return AMOUNT_MASK
    return (if (m.amount < 0) "−" else "") + formatMoneyNumber(m.amount, m.currency) + " " + m.currency
}

/** Plain-text form (code instead of the glyph) for accessibility labels and tests. */
fun plainText(segments: List<MoneySegment>, hidden: Boolean): String = segments.joinToString("") {
    when (it) {
        is MoneySegment.Text -> it.text
        is MoneySegment.Money -> moneySegmentDisplay(it, hidden)
    }
}

/** A sentence whose amounts render with the Dirham symbol and the app's numeral style. */
@Composable
fun MoneySentenceText(
    segments: List<MoneySegment>,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MMTheme.colors.textPrimary,
    hidden: Boolean = LocalHideBalances.current,
    maxLines: Int = Int.MAX_VALUE,
) {
    val inkIn = MMTheme.colors.moneyIn
    val inkOut = MMTheme.colors.moneyOut
    val inkNeutral = MMTheme.colors.moneyNeutral
    fun colorOf(m: MoneySegment.Money): Color = when (m.tone) {
        MoneyTone.IN -> inkIn
        MoneyTone.OUT -> inkOut
        MoneyTone.NEUTRAL -> inkNeutral
        null -> color
    }
    val built = remember(segments, hidden, color, inkIn, inkOut, inkNeutral) {
        val inline = mutableMapOf<String, androidx.compose.foundation.text.InlineTextContent>()
        val text = buildAnnotatedString {
            segments.forEachIndexed { i, seg ->
                when (seg) {
                    is MoneySegment.Text -> append(seg.text)
                    is MoneySegment.Money -> {
                        val c = colorOf(seg)
                        val id = "aed_$i"
                        inline[id] = aedGlyphContent(c)
                        withStyle(SpanStyle(color = c)) { appendMoney(moneySegmentDisplay(seg, hidden), c, id) }
                    }
                }
            }
        }
        text to inline.toMap()
    }
    val description = plainText(segments, hidden)
    BasicText(
        built.first,
        modifier = modifier.semantics { contentDescription = description },
        style = style.copy(color = color),
        inlineContent = built.second,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
