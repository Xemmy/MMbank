package com.xemmy.mmbank.ui.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.MMTheme

/**
 * Compact trend line with a dot on the latest value. Announced as a [title]
 * summary that names the direction, so the shape is never the only signal.
 * While hide-balances is on it draws a flat line that carries no trend.
 */
@Composable
fun Sparkline(
    values: List<Double>,
    title: String,
    valueLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    tone: MMTone? = null,
) {
    val colors = MMTheme.colors
    val hidden = LocalHideBalances.current
    val lineColor = if (hidden) colors.borderStrong else if (tone != null) colors.toneColor(tone) else colors.accent
    val summary = if (hidden) hiddenChartSummary(title) else sparklineSummary(title, values, valueLabel)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .chartSummary(summary),
    ) {
        val pad = 4.dp.toPx()
        val strokeWidth = 2.dp.toPx()
        if (hidden || values.isEmpty()) {
            drawLine(lineColor, Offset(pad, size.height / 2f), Offset(size.width - pad, size.height / 2f), strokeWidth, StrokeCap.Round)
            return@Canvas
        }
        val points = sparklinePoints(values, size.width, size.height, pad)
        val path = Path()
        points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
        if (points.size > 1) {
            drawPath(path, lineColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val last = points.last()
        drawCircle(colors.surface, radius = 4.dp.toPx(), center = Offset(last.x, last.y))
        drawCircle(lineColor, radius = 3.dp.toPx(), center = Offset(last.x, last.y))
    }
}
