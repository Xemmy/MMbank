package com.xemmy.mmbank.ui.designsystem

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure chart maths and accessibility summaries. Nothing here touches Compose,
 * so scaling, bucketing, pacing and the spoken summaries are all JVM-testable.
 */

private fun Double.finiteOrZero(): Double = if (isNaN() || isInfinite()) 0.0 else this

// ---------------------------------------------------------------- bar chart

/** Rounds [max] up to a "nice" axis maximum (1, 2, 5 or 10 times a power of ten). Non-positive gives 1. */
fun niceMax(max: Double): Double {
    if (max.isNaN() || max.isInfinite() || max <= 0.0) return 1.0
    val base = 10.0.pow(floor(log10(max)))
    val f = max / base
    val nice = when {
        f <= 1.0 -> 1.0
        f <= 2.0 -> 2.0
        f <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * base
}

/** Bar heights as 0..1 fractions of [scaleMax]; negatives and non-finite values draw as 0. */
fun barFractions(
    values: List<Double>,
    scaleMax: Double = niceMax(values.maxOfOrNull { it.finiteOrZero() } ?: 0.0),
): List<Float> {
    if (scaleMax <= 0.0 || scaleMax.isNaN()) return values.map { 0f }
    return values.map { (max(it.finiteOrZero(), 0.0) / scaleMax).coerceIn(0.0, 1.0).toFloat() }
}

/** Which of [count] equal-width slots a tap at [x] hits, or null when outside the chart. */
fun barIndexAt(x: Float, width: Float, count: Int): Int? {
    if (count <= 0 || width <= 0f || x < 0f || x > width) return null
    return min((x / width * count).toInt(), count - 1)
}

/** Index of the largest positive value, or null when nothing is positive. */
fun peakIndex(values: List<Double>): Int? {
    var best: Int? = null
    var bestValue = 0.0
    values.forEachIndexed { i, v ->
        val f = v.finiteOrZero()
        if (f > bestValue) {
            bestValue = f
            best = i
        }
    }
    return best
}

// -------------------------------------------------------------- donut chart

/** One drawn arc: [startAngle] in degrees (0 = 3 o'clock, -90 = 12 o'clock) and a sweep of 0 when the slice is empty. */
data class ArcSpec(val startAngle: Float, val sweep: Float)

/**
 * Arcs for [values], one per input in order. Slices are separated by a real
 * angular gap of [gapDegrees] (centred on the slice boundary) when more than
 * one slice is visible. Non-positive and non-finite values get a zero sweep.
 */
fun donutArcs(values: List<Double>, gapDegrees: Float = 3f, startAngle: Float = -90f): List<ArcSpec> {
    val clean = values.map { max(it.finiteOrZero(), 0.0) }
    val total = clean.sum()
    if (total <= 0.0) return clean.map { ArcSpec(startAngle, 0f) }
    val visible = clean.count { it > 0.0 }
    val gap = if (visible > 1) gapDegrees else 0f
    var cursor = startAngle
    return clean.map { v ->
        if (v <= 0.0) {
            ArcSpec(cursor, 0f)
        } else {
            val slice = (360.0 * v / total).toFloat()
            val drawn = if (slice > gap * 2f) slice - gap else slice * 0.5f
            val spec = ArcSpec(cursor + (slice - drawn) / 2f, drawn)
            cursor += slice
            spec
        }
    }
}

/** Whole-number percentages of [values] that sum to 100 (largest remainder); all 0 when the total is not positive. */
fun sharePercents(values: List<Double>): List<Int> {
    val clean = values.map { max(it.finiteOrZero(), 0.0) }
    val total = clean.sum()
    if (total <= 0.0) return clean.map { 0 }
    val raw = clean.map { it / total * 100.0 }
    val result = raw.map { floor(it).toInt() }.toMutableList()
    var left = 100 - result.sum()
    val order = raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }
    for (i in order) {
        if (left <= 0) break
        result[i] = result[i] + 1
        left--
    }
    return result
}

/** "34%", or "<1%" for a positive value that rounds to nothing. */
fun shareLabel(percent: Int, value: Double): String =
    if (percent == 0 && value > 0.0) "<1%" else "$percent%"

// ---------------------------------------------------------------- sparkline

/** A point in a chart's own pixel space. */
data class ChartPoint(val x: Float, val y: Float)

/**
 * Maps [values] into a [width] x [height] box inset by [padding]. The first
 * value is at the left, the highest is nearest the top. A flat series runs along
 * the middle; a single value sits at the centre.
 */
fun sparklinePoints(values: List<Double>, width: Float, height: Float, padding: Float = 0f): List<ChartPoint> {
    if (values.isEmpty()) return emptyList()
    val clean = values.map { it.finiteOrZero() }
    if (clean.size == 1) return listOf(ChartPoint(width / 2f, height / 2f))
    val lo = clean.min()
    val hi = clean.max()
    val range = hi - lo
    val innerW = width - 2f * padding
    val innerH = height - 2f * padding
    return clean.mapIndexed { i, v ->
        val x = padding + innerW * i / (clean.size - 1)
        val y = if (range <= 0.0) height / 2f else padding + innerH * (1.0 - (v - lo) / range).toFloat()
        ChartPoint(x, y)
    }
}

enum class Trend { UP, DOWN, FLAT }

/** First-to-last direction; changes under 1% of the larger endpoint count as flat. */
fun trendOf(values: List<Double>): Trend {
    if (values.size < 2) return Trend.FLAT
    val first = values.first().finiteOrZero()
    val last = values.last().finiteOrZero()
    val delta = last - first
    val base = max(abs(first), abs(last))
    return when {
        base == 0.0 || abs(delta) <= base * 0.01 -> Trend.FLAT
        delta > 0 -> Trend.UP
        else -> Trend.DOWN
    }
}

// ------------------------------------------------------------------ heatmap

const val HEAT_LEVELS = 4

/** 0 for no activity, otherwise 1..[levels] in proportion to [value] / [maxValue]. */
fun heatLevel(value: Double, maxValue: Double, levels: Int = HEAT_LEVELS): Int {
    val v = value.finiteOrZero()
    val m = maxValue.finiteOrZero()
    if (v <= 0.0 || m <= 0.0 || levels < 1) return 0
    return ceil(v / m * levels).toInt().coerceIn(1, levels)
}

/** Fill alpha for a [heatLevel]: 0 for level 0, then evenly from 0.25 to 1. */
fun heatAlpha(level: Int, levels: Int = HEAT_LEVELS): Float = when {
    level <= 0 -> 0f
    levels <= 1 -> 1f
    else -> 0.25f + 0.75f * (level.coerceAtMost(levels) - 1) / (levels - 1)
}

/** Rows needed for [count] cells in [columns] columns. */
fun gridRows(count: Int, columns: Int): Int =
    if (count <= 0 || columns <= 0) 0 else (count + columns - 1) / columns

/** Cell index under a tap at ([x], [y]) in a [width] x [height] grid, or null outside it or on an empty trailing cell. */
fun gridIndexAt(x: Float, y: Float, width: Float, height: Float, columns: Int, count: Int): Int? {
    val rows = gridRows(count, columns)
    if (rows == 0 || width <= 0f || height <= 0f) return null
    if (x < 0f || y < 0f || x > width || y > height) return null
    val col = min((x / (width / columns)).toInt(), columns - 1)
    val row = min((y / (height / rows)).toInt(), rows - 1)
    val index = row * columns + col
    return if (index < count) index else null
}

// ------------------------------------------------------------------ pacing

enum class PacingStatus { NO_BUDGET, ON_PACE, AHEAD, OVER }

/**
 * Budget pacing. [expected] is what a straight-line spender would have spent
 * by now; [projected] extrapolates the current rate to the end of the period.
 * [remaining] is negative once the budget is exceeded.
 */
data class Pacing(
    val status: PacingStatus,
    val spent: Double,
    val budget: Double,
    val spentFraction: Float,
    val paceFraction: Float,
    val expected: Double,
    val projected: Double,
    val remaining: Double,
)

/** Spending above the straight-line pace by more than this share of the budget counts as ahead. */
const val PACING_SLACK = 0.05

/**
 * [elapsedFraction] is how much of the period has passed (clamped to 0..1).
 * Over budget wins over ahead of pace; a missing budget is [PacingStatus.NO_BUDGET].
 */
fun pacing(spent: Double, budget: Double, elapsedFraction: Float): Pacing {
    val s = max(spent.finiteOrZero(), 0.0)
    val elapsed = if (elapsedFraction.isNaN()) 0f else elapsedFraction.coerceIn(0f, 1f)
    if (budget.isNaN() || budget.isInfinite() || budget <= 0.0) {
        return Pacing(PacingStatus.NO_BUDGET, s, 0.0, 0f, elapsed, 0.0, s, 0.0)
    }
    val expected = budget * elapsed
    val projected = if (elapsed > 0f) s / elapsed else s
    val status = when {
        s > budget -> PacingStatus.OVER
        s > expected + budget * PACING_SLACK -> PacingStatus.AHEAD
        else -> PacingStatus.ON_PACE
    }
    return Pacing(
        status = status,
        spent = s,
        budget = budget,
        spentFraction = (s / budget).coerceIn(0.0, 1.0).toFloat(),
        paceFraction = elapsed,
        expected = expected,
        projected = projected,
        remaining = budget - s,
    )
}

/** Visible status text, so the state never relies on colour alone. */
fun pacingStatusLabel(status: PacingStatus): String = when (status) {
    PacingStatus.NO_BUDGET -> "No budget set"
    PacingStatus.ON_PACE -> "On pace"
    PacingStatus.AHEAD -> "Ahead of pace"
    PacingStatus.OVER -> "Over budget"
}

/** Tone for a status; null (the accent colour) while on pace or without a budget. */
fun pacingTone(status: PacingStatus): MMTone? = when (status) {
    PacingStatus.AHEAD -> MMTone.WARNING
    PacingStatus.OVER -> MMTone.DANGER
    PacingStatus.NO_BUDGET, PacingStatus.ON_PACE -> null
}

/** "AED 40 left" or "AED 40 over". */
fun pacingRemainingLabel(p: Pacing, format: (Double) -> String): String =
    if (p.remaining >= 0.0) "${format(p.remaining)} left" else "${format(-p.remaining)} over"

// -------------------------------------------------------- a11y summaries

/** What a chart announces while hide-balances is on: no figures, no proportions. */
fun hiddenChartSummary(title: String): String = "$title: values hidden"

fun barChartSummary(title: String, labels: List<String>, values: List<Double>, format: (Double) -> String): String {
    val n = min(labels.size, values.size)
    if (n == 0) return "$title: no data"
    val parts = (0 until n).joinToString(", ") { "${labels[it]} ${format(values[it])}" }
    val peak = peakIndex(values.take(n))
    val tail = if (n > 1 && peak != null) ". Highest: ${labels[peak]}" else ""
    return "$title: $parts$tail"
}

fun donutSummary(title: String, labels: List<String>, values: List<Double>, format: (Double) -> String): String {
    val n = min(labels.size, values.size)
    val vals = values.take(n)
    val total = vals.sumOf { max(it.finiteOrZero(), 0.0) }
    if (n == 0 || total <= 0.0) return "$title: no data"
    val shares = sharePercents(vals)
    val parts = (0 until n).filter { vals[it] > 0.0 }
        .joinToString(", ") { "${labels[it]} ${shareLabel(shares[it], vals[it])}, ${format(vals[it])}" }
    return "$title: $parts. Total ${format(total)}"
}

fun sparklineSummary(title: String, values: List<Double>, format: (Double) -> String): String {
    if (values.isEmpty()) return "$title: no data"
    val clean = values.map { it.finiteOrZero() }
    if (clean.size == 1) return "$title: ${format(clean[0])}"
    val trend = when (trendOf(clean)) {
        Trend.UP -> "trending up"
        Trend.DOWN -> "trending down"
        Trend.FLAT -> "flat"
    }
    return "$title: $trend, from ${format(clean.first())} to ${format(clean.last())}, " +
        "low ${format(clean.min())}, high ${format(clean.max())}"
}

fun heatmapSummary(title: String, labels: List<String>, values: List<Double>, format: (Double) -> String): String {
    val n = min(labels.size, values.size)
    if (n == 0) return "$title: no data"
    val vals = values.take(n)
    val peak = peakIndex(vals) ?: return "$title: no activity in $n ${if (n == 1) "cell" else "cells"}"
    val quiet = vals.count { it.finiteOrZero() <= 0.0 }
    return "$title: busiest ${labels[peak]} ${format(vals[peak])}. " +
        "$quiet of $n ${if (n == 1) "cell has" else "cells have"} no activity"
}

fun pacingSummary(title: String, p: Pacing, format: (Double) -> String): String {
    if (p.status == PacingStatus.NO_BUDGET) return "$title: ${pacingStatusLabel(p.status)}"
    return "$title: ${pacingStatusLabel(p.status)}. Spent ${format(p.spent)} of ${format(p.budget)}, " +
        "${(p.paceFraction * 100).toInt()}% of the period elapsed, ${pacingRemainingLabel(p, format)}"
}
