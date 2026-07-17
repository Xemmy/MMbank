package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.screens.dashboard.CategorySlice
import com.xemmy.mmbank.ui.theme.ObsidianTokens

/** One drawn arc: [slice]'s share of the ring, in degrees, clockwise from 12 o'clock. */
internal data class SliceAngle(val slice: CategorySlice, val startAngle: Float, val sweepAngle: Float)

/**
 * Converts slice amounts into ring angles, starting at -90 degrees (12
 * o'clock) and accumulating clockwise in the given order. Pure and
 * Compose-free so it's unit-testable off-device; [DonutChart] only reads the
 * result to draw.
 */
internal fun computeSliceAngles(slices: List<CategorySlice>): List<SliceAngle> {
    val total = slices.sumOf { it.amount }
    if (total <= 0.0) return emptyList()
    var cursor = -90f
    return slices.map { slice ->
        val sweep = (slice.amount / total * 360.0).toFloat()
        SliceAngle(slice, cursor, sweep).also { cursor += sweep }
    }
}

/**
 * A donut chart drawn with plain [Canvas] arcs — no charting library. The
 * hollow center falls out naturally from stroking (not filling) the ring, so
 * it always matches whatever sits behind the chart. A thin arc in
 * [ObsidianTokens.surfaceGlass] is drawn over each slice boundary to cut a
 * subtle gap between slices.
 */
@Composable
fun DonutChart(
    slices: List<CategorySlice>,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 22.dp,
    gapWidth: Dp = 2.dp,
) {
    val angles = computeSliceAngles(slices)
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = size.minDimension - stroke
        val radius = diameter / 2f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val gapDegrees = if (radius > 0f) {
            Math.toDegrees((gapWidth.toPx() / radius).toDouble()).toFloat()
        } else {
            0f
        }

        angles.forEach { angle ->
            drawArc(
                color = Color(angle.slice.color),
                startAngle = angle.startAngle,
                sweepAngle = angle.sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Butt),
            )
        }
        if (angles.size > 1) {
            angles.forEach { angle ->
                drawArc(
                    color = ObsidianTokens.surfaceGlass,
                    startAngle = angle.startAngle - gapDegrees / 2f,
                    sweepAngle = gapDegrees,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
            }
        }
    }
}
