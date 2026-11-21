package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMProgressBar
import com.xemmy.mmbank.ui.designsystem.MMTone
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MetaLabel
import kotlin.math.roundToInt

/**
 * Premium-only hero card above [TransactionFilterBar]: the currently
 * filtered feed's total spend, total received and the resulting net
 * (received minus spent), and — once a monthly budget threshold is set
 * in Settings (see [com.xemmy.mmbank.data.prefs.AppPreferences.monthlySpendBudget])
 * — a bar showing how much of it that total consumes. Gated at the call site
 * in [TransactionsScreen]: it is hidden in bulk mode.
 */
@Composable
internal fun HeroSpendSummaryCard(
    state: SpendSummaryState,
    modifier: Modifier = Modifier,
) {
    val colors = MMTheme.colors
    val overBudget = state.hasBudget && state.budgetConsumedPercent > 100.0
    val progress = (state.budgetConsumedPercent / 100.0).toFloat().coerceIn(0f, 1f)

    MMCard(
        modifier
            .fillMaxWidth()
            .padding(horizontal = MMSpacing.md, vertical = MMSpacing.xs),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MetaLabel("Spent this view")
            MoneyText(
                amount = state.totalSpent,
                currencyCode = state.currencyCode,
                kind = TransactionKind.TRANSFER,
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary,
            )
            heroComparisonCaption(state.totalSpent, state.previousSpent, state.comparisonDays)?.let { caption ->
                Text(caption, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(MMSpacing.xxs))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(MMSpacing.xxs)) {
                    MetaLabel("Received")
                    MoneyText(
                        amount = state.totalReceived,
                        currencyCode = state.currencyCode,
                        kind = TransactionKind.TRANSFER,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.moneyIn,
                    )
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(MMSpacing.xxs)) {
                    MetaLabel("Remaining")
                    // ADJUSTMENT takes its sign from the amount: "+" when net >= 0, "−" otherwise.
                    MoneyText(
                        amount = state.netAmount,
                        currencyCode = state.currencyCode,
                        kind = TransactionKind.ADJUSTMENT,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.netAmount >= 0) colors.moneyIn else colors.danger,
                    )
                }
            }
            if (state.hasBudget) {
                Spacer(Modifier.height(MMSpacing.xxs))
                MMProgressBar(
                    fraction = progress,
                    tone = if (overBudget) MMTone.DANGER else null,
                    modifier = Modifier.height(6.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${state.budgetConsumedPercent.roundToInt()}% of budget",
                        color = if (overBudget) colors.danger else colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Budget ${moneyLabel(state.budgetAmount, state.currencyCode)}",
                        color = colors.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    "Set a monthly budget in Settings > Preferences to track it here.",
                    color = colors.textSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * "↑12% vs the 7 days before" under the hero card's Spent figure; null when
 * there's nothing to compare with (unbounded filter, or no spend before).
 */
internal fun heroComparisonCaption(current: Double, previous: Double?, days: Int?): String? {
    if (previous == null || days == null || previous <= 0.0) return null
    val change = (current - previous) / previous * 100.0
    val period = when (days) {
        1 -> "the day before"
        else -> "the $days days before"
    }
    val rounded = kotlin.math.abs(change).let { Math.round(it) }
    return if (rounded == 0L) "≈ same as $period" else "${if (change > 0) "↑" else "↓"}$rounded% vs $period"
}
