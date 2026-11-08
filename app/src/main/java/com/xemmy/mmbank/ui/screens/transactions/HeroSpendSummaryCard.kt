package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.util.asMoney
import kotlin.math.roundToInt

/**
 * Premium-only hero card above [TransactionFilterBar]: the currently
 * filtered feed's total spend, total received and the resulting net
 * (received minus spent), and — once a monthly budget threshold is set
 * in Settings (see [com.xemmy.mmbank.data.prefs.AppPreferences.monthlySpendBudget])
 * — a bar showing how much of it that total consumes. Gated at the call site
 * in [TransactionsScreen] on [com.xemmy.mmbank.ui.theme.LocalDesignMode];
 * Classic never renders it.
 */
@Composable
internal fun HeroSpendSummaryCard(
    state: SpendSummaryState,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    val overBudget = state.hasBudget && state.budgetConsumedPercent > 100.0
    val progress = (state.budgetConsumedPercent / 100.0).toFloat().coerceIn(0f, 1f)

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(1.dp, ObsidianTokens.hairline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MetaLabel("Spent this view")
        Text(
            state.totalSpent.asMoney(state.currencyCode),
            color = ObsidianTokens.textPrimary,
            fontFamily = AppFonts.inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = ObsidianTokens.titleFontSize,
        )
        heroComparisonCaption(state.totalSpent, state.previousSpent, state.comparisonDays)?.let { caption ->
            Text(caption, color = ObsidianTokens.textMeta, fontFamily = AppFonts.inter, fontSize = ObsidianTokens.metaFontSize)
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MetaLabel("Received")
                Text(
                    state.totalReceived.asMoney(state.currencyCode),
                    color = ObsidianTokens.credit,
                    fontFamily = AppFonts.inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = ObsidianTokens.bodyFontSize,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                MetaLabel("Remaining")
                Text(
                    (if (state.netAmount >= 0) "+" else "") + state.netAmount.asMoney(state.currencyCode),
                    color = if (state.netAmount >= 0) ObsidianTokens.credit else ObsidianTokens.debit,
                    fontFamily = AppFonts.inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = ObsidianTokens.bodyFontSize,
                )
            }
        }
        if (state.hasBudget) {
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (overBudget) ObsidianTokens.debit else ObsidianTokens.credit,
                trackColor = ObsidianTokens.hairline,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${state.budgetConsumedPercent.roundToInt()}% of budget",
                    color = if (overBudget) ObsidianTokens.debit else ObsidianTokens.textMeta,
                    fontFamily = AppFonts.inter,
                    fontSize = ObsidianTokens.bodyFontSize,
                )
                Text(
                    "Budget ${state.budgetAmount.asMoney(state.currencyCode)}",
                    color = ObsidianTokens.textMeta,
                    fontFamily = AppFonts.inter,
                    fontSize = ObsidianTokens.bodyFontSize,
                )
            }
        } else {
            Text(
                "Set a monthly budget in Settings > Preferences to track it here.",
                color = ObsidianTokens.textMeta,
                fontFamily = AppFonts.inter,
                fontSize = ObsidianTokens.bodyFontSize,
            )
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
