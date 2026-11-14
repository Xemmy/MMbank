package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMSpacing
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.AMOUNT_MASK

/** One slice: its [label] and positive [value]. */
data class DonutSlice(val label: String, val value: Double)

/**
 * Ring chart with real angular gaps between slices, a centre total and a
 * legend that names every slice with its share, so colour is never the only
 * key. Tap a legend row to show that slice's value in the centre. While
 * hide-balances is on it draws one flat ring and hides shares and amounts.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    title: String,
    centerLabel: String,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    centerCaption: String? = null,
    diameter: Dp = 160.dp,
) {
    val colors = MMTheme.colors
    val hidden = LocalHideBalances.current
    var selected by remember(slices) { mutableStateOf<Int?>(null) }
    val values = remember(slices) { slices.map { it.value } }
    val arcs = remember(values) { donutArcs(values) }
    val shares = remember(values) { sharePercents(values) }
    val summary = if (hidden) {
        hiddenChartSummary(title)
    } else {
        donutSummary(title, slices.map { it.label }, values, valueLabel)
    }
    val picked = selected?.let { slices.getOrNull(it) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(MMSpacing.sm)) {
        Box(Modifier.align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(diameter).chartSummary(summary)) {
                val strokeWidth = MMSpacing.xl.toPx()
                val inset = strokeWidth / 2f
                val topLeft = Offset(inset, inset)
                val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
                val flat = hidden || arcs.none { it.sweep > 0f }
                if (flat) {
                    drawArc(
                        color = colors.borderSubtle,
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokeWidth),
                    )
                } else {
                    arcs.forEachIndexed { i, arc ->
                        if (arc.sweep > 0f) {
                            val base = colors.chartSeriesColor(i)
                            drawArc(
                                color = if (selected == null || selected == i) base else base.copy(alpha = 0.35f),
                                startAngle = arc.startAngle,
                                sweepAngle = arc.sweep,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                            )
                        }
                    }
                }
            }
            Column(
                Modifier.size(diameter * 0.6f).clearAndSetSemantics { },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = when {
                        hidden -> AMOUNT_MASK
                        picked != null -> valueLabel(picked.value)
                        else -> centerLabel
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val caption = if (picked != null && !hidden) picked.label else centerCaption
                if (caption != null) {
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        slices.forEachIndexed { i, slice ->
            val isSelected = selected == i
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = "Show ${slice.label} in the centre") {
                        selected = if (isSelected) null else i
                    }
                    .padding(vertical = MMSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MMSpacing.sm),
            ) {
                Box(Modifier.size(MMSpacing.md).background(colors.chartSeriesColor(i), CircleShape))
                Text(
                    text = slice.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!hidden) {
                    Text(
                        text = shareLabel(shares.getOrElse(i) { 0 }, slice.value),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.textSecondary,
                    )
                }
            }
        }
    }
}
