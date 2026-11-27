package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme

/** One grid cell: a [label] (e.g. "Mon 12 May") and its activity [value]. */
data class HeatCell(val label: String, val value: Double)

/**
 * Intensity grid filled row by row in [columns] columns, in [HEAT_LEVELS]
 * steps of the accent colour. Tap a cell for its exact value; the summary
 * names the busiest cell, and a Less / More key explains the scale. While
 * hide-balances is on every cell is flat and nothing is selectable.
 */
@Composable
fun Heatmap(
    cells: List<HeatCell>,
    title: String,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    columns: Int = 7,
) {
    val colors = MMTheme.colors
    val hidden = LocalHideBalances.current
    val cols = columns.coerceAtLeast(1)
    val rows = gridRows(cells.size, cols)
    var selected by remember(cells) { mutableStateOf<Int?>(null) }
    val maxValue = remember(cells) { cells.maxOfOrNull { if (it.value.isNaN()) 0.0 else it.value } ?: 0.0 }
    val summary = if (hidden) {
        hiddenChartSummary(title)
    } else {
        heatmapSummary(title, cells.map { it.label }, cells.map { it.value }, valueLabel)
    }
    val picked = selected?.let { cells.getOrNull(it) }
    val readout = when {
        hidden -> "Values hidden"
        picked != null -> "${picked.label}: ${valueLabel(picked.value)}"
        cells.isEmpty() -> "No data"
        else -> "Tap a cell to see its value"
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MMSpacing.xs)) {
        if (rows > 0) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(cols.toFloat() / rows.toFloat())
                    .chartSummary(summary)
                    .pointerInput(cells, hidden) {
                        if (!hidden) {
                            detectTapGestures { tap ->
                                val hit = gridIndexAt(
                                    tap.x, tap.y, size.width.toFloat(), size.height.toFloat(), cols, cells.size,
                                )
                                selected = if (hit == selected) null else hit
                            }
                        }
                    },
            ) {
                val cellW = size.width / cols
                val cellH = size.height / rows
                val gap = MMSpacing.xxs.toPx()
                val radius = CornerRadius(MMSpacing.xxs.toPx())
                cells.forEachIndexed { i, cell ->
                    val topLeft = Offset((i % cols) * cellW + gap / 2f, (i / cols) * cellH + gap / 2f)
                    val cellSize = Size(cellW - gap, cellH - gap)
                    val level = if (hidden) 0 else heatLevel(cell.value, maxValue)
                    val fill = if (level == 0) colors.borderSubtle else colors.accent.copy(alpha = heatAlpha(level))
                    drawRoundRect(fill, topLeft, cellSize, radius)
                    if (selected == i) {
                        drawRoundRect(colors.textPrimary, topLeft, cellSize, radius, style = Stroke(width = MMSpacing.xxs.toPx()))
                    }
                }
            }
        }
        if (!hidden && rows > 0) {
            Row(
                Modifier.clearAndSetSemantics { },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MMSpacing.xs),
            ) {
                Text("Less", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                for (level in 0..HEAT_LEVELS) {
                    val fill = if (level == 0) colors.borderSubtle else colors.accent.copy(alpha = heatAlpha(level))
                    Box(Modifier.size(MMSpacing.md).background(fill))
                }
                Text("More", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
        }
        ChartReadout(readout)
    }
}
