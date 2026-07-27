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
