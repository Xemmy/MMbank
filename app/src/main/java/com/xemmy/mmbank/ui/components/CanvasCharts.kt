package com.xemmy.mmbank.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.theme.ObsidianTokens

/** How far along [allowance] [spent] has consumed, clamped to a drawable 0..1 sweep; [overConsumed] flags the rest. */
internal data class ArcGaugeState(val consumedFraction: Float, val overConsumed: Boolean)

/**
 * Fraction of a spending allowance already consumed, for [PacingArcGauge]'s
 * sweep. Pure and top-level so it's JVM-testable; a zero-or-negative
 * [allowance] (no budget configured) always reads as empty rather than
 * dividing by zero.
 */
internal fun computeArcGaugeState(spent: Double, allowance: Double): ArcGaugeState {
    if (allowance <= 0.0) return ArcGaugeState(consumedFraction = 0f, overConsumed = false)
    val fraction = (spent / allowance).toFloat()
    return ArcGaugeState(consumedFraction = fraction.coerceIn(0f, 1f), overConsumed = fraction > 1f)
}

private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_DEGREES = 270f

/**
 * Speedometer-style arc gauge: how much of today's safe-to-spend daily
 * allowance ([allowance], from [com.xemmy.mmbank.domain.budget.DailyBudgetPacing])
 * has already been consumed by [spent]. A 270-degree track (drawn in
 * [ObsidianTokens.surfaceGlass]) with a colored fill arc on top — green while
 * on track, the debit tone once [spent] has overrun [allowance].
 */
@Composable
fun PacingArcGauge(
    spent: Double,
    allowance: Double,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 14.dp,
) {
    val state = computeArcGaugeState(spent, allowance)
    val fillColor = if (state.overConsumed) ObsidianTokens.debit else ObsidianTokens.credit

    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val diameter = size.minDimension - stroke
        val radius = diameter / 2f
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = ObsidianTokens.surfaceGlass,
            startAngle = GAUGE_START_ANGLE,
            sweepAngle = GAUGE_SWEEP_DEGREES,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        if (state.consumedFraction > 0f && radius > 0f) {
            drawArc(
                color = fillColor,
                startAngle = GAUGE_START_ANGLE,
                sweepAngle = GAUGE_SWEEP_DEGREES * state.consumedFraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}

/** One sparkline vertex in canvas pixels. */
internal data class SparkPoint(val x: Float, val y: Float)

/**
 * Maps [values] onto a [width] × [height] box for [Sparkline]: evenly spaced
 * left to right, the smallest value at the bottom and the largest at the top,
 * [inset] pixels clear of the top and bottom edges. A null value (no data for
 * that slot) stays null so the line breaks there; a flat series sits on the
 * middle line; a single value sits in the centre. Pure and JVM-testable.
 */
internal fun sparklinePoints(values: List<Double?>, width: Float, height: Float, inset: Float = 0f): List<SparkPoint?> {
    val known = values.filterNotNull().filter { it.isFinite() }
    if (known.isEmpty()) return values.map { null }
    val min = known.min()
    val max = known.max()
    val usable = (height - 2 * inset).coerceAtLeast(0f)
    val step = if (values.size > 1) width / (values.size - 1) else 0f
    return values.mapIndexed { i, v ->
        if (v == null || !v.isFinite()) return@mapIndexed null
        val x = if (values.size > 1) i * step else width / 2f
        val fraction = if (max == min) 0.5f else ((v - min) / (max - min)).toFloat()
        SparkPoint(x, inset + (1f - fraction) * usable)
    }
}

/**
 * A minimal trend line -- no axes, no labels -- for a series such as month-end
 * net worth. Gaps (null values) break the line; the latest point gets a dot.
 */
@Composable
fun Sparkline(
    values: List<Double?>,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = ObsidianTokens.credit,
    strokeWidth: Dp = 2.dp,
) {
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val points = sparklinePoints(values, size.width, size.height, inset = stroke * 2)
        var previous: SparkPoint? = null
        points.forEach { point ->
            if (point != null && previous != null) {
                drawLine(
                    color = color,
                    start = Offset(previous!!.x, previous!!.y),
                    end = Offset(point.x, point.y),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            previous = point
        }
        points.lastOrNull { it != null }?.let { last ->
            drawCircle(color = color, radius = stroke * 1.8f, center = Offset(last.x, last.y))
        }
    }
}
