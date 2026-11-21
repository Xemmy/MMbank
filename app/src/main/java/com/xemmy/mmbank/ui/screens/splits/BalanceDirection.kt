package com.xemmy.mmbank.ui.screens.splits

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.theme.MMTheme
import java.math.BigDecimal

/** Which way a split balance points: positive is owed to the user, negative is owed by them. */
enum class BalanceDirection { OWED, OWES, SETTLED }

fun balanceDirection(balance: BigDecimal): BalanceDirection = when {
    balance.signum() > 0 -> BalanceDirection.OWED
    balance.signum() < 0 -> BalanceDirection.OWES
    else -> BalanceDirection.SETTLED
}

/**
 * One-line balance sentence: "<prefix><amount>" tinted by direction, or
 * [settledText] when nothing is owed. The amount renders through MoneyText so
 * it honours hide-balances and the Dirham glyph.
 */
@Composable
fun BalanceSentence(
    balance: BigDecimal,
    currency: String,
    owedPrefix: String,
    owesPrefix: String,
    settledText: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val colors = MMTheme.colors
    val direction = balanceDirection(balance)
    val color = when (direction) {
        BalanceDirection.OWED -> colors.accent
        BalanceDirection.OWES -> colors.danger
        BalanceDirection.SETTLED -> colors.textSecondary
    }
    if (direction == BalanceDirection.SETTLED) {
        Text(settledText, style = style, fontWeight = fontWeight, color = color, modifier = modifier)
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (direction == BalanceDirection.OWED) owedPrefix else owesPrefix,
            style = style,
            fontWeight = fontWeight,
            color = color,
        )
        MoneyText(
            amount = balance.abs().toDouble(),
            currencyCode = currency,
            kind = TransactionKind.TRANSFER,
            style = style,
            color = color,
        )
    }
}
