package com.xemmy.mmbank.ui.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.AmountText

/** The semantic colour for [tone] from the current theme (calm money colours, Improvement Plan §1.1). */
@Composable
fun moneyColor(tone: MoneyTone): Color = when (tone) {
    MoneyTone.IN -> MMTheme.colors.moneyIn
    MoneyTone.OUT -> MMTheme.colors.moneyOut
    MoneyTone.NEUTRAL -> MMTheme.colors.moneyNeutral
}

/**
 * The one way to show an amount: sign, Dirham glyph / currency code, tabular
 * figures, semantic colour by [kind], masking via [LocalHideBalances], and a
 * spoken description for TalkBack that never leaks a hidden figure.
 *
 * Renders through AmountText, so the AED glyph and code styling stay in one
 * place. [amount] is the magnitude; the sign comes from [kind].
 */
@Composable
fun MoneyText(
    amount: Double,
    currencyCode: String,
    kind: TransactionKind,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    hidden: Boolean = LocalHideBalances.current,
    /** Overrides the tone colour, e.g. for "owes" / "is owed" sentences. */
    color: Color? = null,
) {
    val description = moneyContentDescription(amount, currencyCode, kind, hidden)
    AmountText(
        display = moneyDisplayString(amount, currencyCode, kind, hidden),
        color = color ?: moneyColor(moneyToneFor(kind)),
        modifier = modifier.semantics { contentDescription = description },
        style = style,
    )
}
