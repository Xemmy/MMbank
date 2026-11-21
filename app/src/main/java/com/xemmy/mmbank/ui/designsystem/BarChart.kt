package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** One bar: a short [label] under it and its [value]. */
data class BarEntry(val label: String, val value: Double)

/**
 * Vertical bar chart on a "nice" scale. Tap a bar to read its value below the
 * chart; the whole chart is announced as one [title] summary. While
 * hide-balances is on it draws equal flat bars and no figures.
 */
@Composable
fun BarChart(
    entries: List<BarEntry>,
    title: String,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
) {
    val colors = MMTheme.colors
    val hidden = LocalHideBalances.current
    var selected by remember(entries) { mutableStateOf<Int?>(null) }
    val fractions = remember(entries, hidden) {
        if (hidden) entries.map { 0.35f } else barFractions(entries.map { it.value })
    }
    val summary = if (hidden) {
        hiddenChartSummary(title)
    } else {
        barChartSummary(title, entries.map { it.label }, entries.map { it.value }, valueLabel)
    }
    val picked = selected?.let { entries.getOrNull(it) }
    val readout = when {
        hidden -> "Values hidden"
        picked != null -> "${picked.label}: ${valueLabel(picked.value)}"
        entries.isEmpty() -> "No data"
        else -> "Tap a bar to see its value"
    }

    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(MMSpacing.xs)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .chartSummary(summary)
                .pointerInput(entries, hidden) {
                    if (!hidden) {
                        detectTapGestures { tap ->
                            val hit = barIndexAt(tap.x, size.width.toFloat(), entries.size)
                            selected = if (hit == selected) null else hit
                        }
                    }
                },
        ) {
            val n = fractions.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barWidth = slot * 0.6f
            val radius = CornerRadius(MMSpacing.xs.toPx())
            val minBar = MMSpacing.xxs.toPx()
            fractions.forEachIndexed { i, f ->
                val barHeight = if (f > 0f) (size.height * f).coerceAtLeast(minBar) else 0f
                val topLeft = Offset(slot * i + (slot - barWidth) / 2f, size.height - barHeight)
                val barSize = Size(barWidth, barHeight)
                val isSelected = selected == i
                val fill = when {
                    hidden -> colors.borderStrong
                    selected == null || isSelected -> colors.accent
                    else -> colors.accent.copy(alpha = 0.45f)
                }
                drawRoundRect(fill, topLeft, barSize, radius)
                if (isSelected && barHeight > 0f) {
                    // Outline so the selection is visible without relying on the dimmed neighbours.
                    drawRoundRect(colors.textPrimary, topLeft, barSize, radius, style = Stroke(width = MMSpacing.xxs.toPx()))
                }
            }
            drawLine(
                color = colors.borderStrong,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }
        Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
            entries.forEach { entry ->
                Text(
                    text = entry.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        ChartReadout(readout)
    }
}
