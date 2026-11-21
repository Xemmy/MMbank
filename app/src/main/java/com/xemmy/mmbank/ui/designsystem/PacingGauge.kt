package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.AMOUNT_MASK

/**
 * Budget gauge: a bar for spend so far and a tick for where a straight-line
 * spender would be by now. The status is written out ("On pace", "Ahead of
 * pace", "Over budget") next to the bar, so the tone colour only reinforces
 * it. While hide-balances is on it shows an empty track and masks every figure.
 */
@Composable
fun PacingGauge(
    spent: Double,
    budget: Double,
    elapsedFraction: Float,
    title: String,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val colors = MMTheme.colors
    val hidden = LocalHideBalances.current
    val p = pacing(spent, budget, elapsedFraction)
    val tone = pacingTone(p.status)
    val fillColor = if (tone != null) colors.toneColor(tone) else colors.accent
    val summary = if (hidden) hiddenChartSummary(title) else pacingSummary(title, p, valueLabel)

    Column(
        modifier.chartSummary(summary),
        verticalArrangement = Arrangement.spacedBy(MMSpacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            if (!hidden) {
                Text(
                    pacingStatusLabel(p.status),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
            val barHeight = MMSpacing.md.toPx()
            val top = (size.height - barHeight) / 2f
            val radius = CornerRadius(barHeight / 2f)
            drawRoundRect(colors.borderSubtle, Offset(0f, top), Size(size.width, barHeight), radius)
            if (!hidden && p.status != PacingStatus.NO_BUDGET) {
                val fillWidth = size.width * p.spentFraction
                if (fillWidth > 0f) {
                    drawRoundRect(fillColor, Offset(0f, top), Size(fillWidth.coerceAtLeast(barHeight), barHeight), radius)
                }
                val tickX = (size.width * p.paceFraction).coerceIn(0f, size.width)
                drawLine(
                    color = colors.textPrimary,
                    start = Offset(tickX, 0f),
                    end = Offset(tickX, size.height),
                    strokeWidth = MMSpacing.xxs.toPx(),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val spentText = when {
                hidden -> AMOUNT_MASK
                p.status == PacingStatus.NO_BUDGET -> "Spent ${valueLabel(p.spent)}"
                else -> "Spent ${valueLabel(p.spent)} of ${valueLabel(p.budget)}"
            }
            Text(spentText, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            if (!hidden && p.status != PacingStatus.NO_BUDGET) {
                Text(
                    pacingRemainingLabel(p, valueLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}
