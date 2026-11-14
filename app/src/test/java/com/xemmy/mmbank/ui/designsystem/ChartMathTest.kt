package com.xemmy.mmbank.ui.designsystem

import com.xemmy.mmbank.ui.theme.darkMMColors
import com.xemmy.mmbank.ui.theme.lightMMColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {
    private val fmt: (Double) -> String = { "AED ${it.toLong()}" }

    // ---- bar chart
    @Test
    fun `niceMax rounds up to 1 2 5 or 10 times a power of ten`() {
        assertEquals(1.0, niceMax(0.0), 0.0)
        assertEquals(1.0, niceMax(-5.0), 0.0)
        assertEquals(1.0, niceMax(Double.NaN), 0.0)
        assertEquals(100.0, niceMax(100.0), 0.0)
        assertEquals(200.0, niceMax(101.0), 0.0)
        assertEquals(500.0, niceMax(320.0), 0.0)
        assertEquals(1000.0, niceMax(900.0), 0.0)
        assertEquals(5000.0, niceMax(2100.0), 0.0)
    }

    @Test
    fun `barFractions scale to the nice max and clamp bad values`() {
        val f = barFractions(listOf(250.0, 500.0, 0.0, -10.0, Double.NaN))
        assertEquals(listOf(0.5f, 1f, 0f, 0f, 0f), f)
        assertEquals(listOf(0.25f), barFractions(listOf(25.0), scaleMax = 100.0))
        assertEquals(listOf(0f, 0f), barFractions(listOf(1.0, 2.0), scaleMax = 0.0))
        assertTrue(barFractions(emptyList()).isEmpty())
    }

    @Test
    fun `barIndexAt maps taps to equal slots`() {
        assertEquals(0, barIndexAt(0f, 100f, 4))
        assertEquals(1, barIndexAt(30f, 100f, 4))
        assertEquals(3, barIndexAt(100f, 100f, 4))
        assertNull(barIndexAt(-1f, 100f, 4))
        assertNull(barIndexAt(101f, 100f, 4))
        assertNull(barIndexAt(10f, 100f, 0))
        assertNull(barIndexAt(10f, 0f, 4))
    }

    @Test
    fun `peakIndex finds the largest positive value`() {
        assertEquals(2, peakIndex(listOf(1.0, 5.0, 9.0, 3.0)))
        assertNull(peakIndex(listOf(0.0, -1.0)))
        assertNull(peakIndex(emptyList()))
    }

    // ---- donut
    @Test
    fun `donutArcs leave an angular gap and cover the ring in order`() {
        val arcs = donutArcs(listOf(50.0, 25.0, 25.0), gapDegrees = 4f)
        assertEquals(3, arcs.size)
        assertEquals(176f, arcs[0].sweep, 0.001f)
        assertEquals(86f, arcs[1].sweep, 0.001f)
        // Gap is centred on the boundary: first arc starts half a gap after 12 o'clock.
        assertEquals(-88f, arcs[0].startAngle, 0.001f)
        assertEquals(90f + 2f, arcs[1].startAngle, 0.001f)
        // Drawn arcs plus gaps add up to the full circle.
        assertEquals(360f, arcs.sumOf { it.sweep.toDouble() }.toFloat() + 3 * 4f, 0.001f)
    }

    @Test
    fun `donutArcs single slice is a full ring with no gap and empty slices are skipped`() {
        val single = donutArcs(listOf(10.0, 0.0))
        assertEquals(360f, single[0].sweep, 0.001f)
        assertEquals(0f, single[1].sweep, 0f)
        assertTrue(donutArcs(listOf(0.0, -3.0, Double.NaN)).all { it.sweep == 0f })
        assertTrue(donutArcs(emptyList()).isEmpty())
    }

    @Test
    fun `donutArcs keeps a tiny slice visible`() {
        val arcs = donutArcs(listOf(1000.0, 1.0), gapDegrees = 3f)
        assertTrue(arcs[1].sweep > 0f)
    }

    @Test
    fun `sharePercents sum to 100 with largest remainder`() {
        val p = sharePercents(listOf(1.0, 1.0, 1.0))
        assertEquals(100, p.sum())
        assertEquals(listOf(34, 33, 33), p)
        assertEquals(listOf(0, 0), sharePercents(listOf(0.0, 0.0)))
        assertEquals(listOf(100, 0), sharePercents(listOf(5.0, -2.0)))
    }

    @Test
    fun `shareLabel marks tiny positive shares`() {
        assertEquals("34%", shareLabel(34, 340.0))
        assertEquals("<1%", shareLabel(0, 0.2))
        assertEquals("0%", shareLabel(0, 0.0))
    }

    // ---- sparkline
    @Test
    fun `sparklinePoints put the highest value at the top and spread x evenly`() {
        val pts = sparklinePoints(listOf(0.0, 10.0, 5.0), width = 100f, height = 40f, padding = 0f)
        assertEquals(listOf(0f, 50f, 100f), pts.map { it.x })
        assertEquals(40f, pts[0].y, 0.001f)
        assertEquals(0f, pts[1].y, 0.001f)
        assertEquals(20f, pts[2].y, 0.001f)
    }

    @Test
    fun `sparklinePoints honour padding and handle flat single and empty series`() {
        val padded = sparklinePoints(listOf(1.0, 3.0), 100f, 40f, padding = 4f)
        assertEquals(4f, padded[0].x, 0.001f)
        assertEquals(96f, padded[1].x, 0.001f)
        assertEquals(36f, padded[0].y, 0.001f)
        assertEquals(4f, padded[1].y, 0.001f)
        assertTrue(sparklinePoints(listOf(7.0, 7.0, 7.0), 100f, 40f).all { it.y == 20f })
        assertEquals(listOf(ChartPoint(50f, 20f)), sparklinePoints(listOf(3.0), 100f, 40f))
        assertTrue(sparklinePoints(emptyList(), 100f, 40f).isEmpty())
    }

    @Test
    fun `trendOf compares first and last with a 1 percent dead band`() {
        assertEquals(Trend.UP, trendOf(listOf(10.0, 5.0, 20.0)))
        assertEquals(Trend.DOWN, trendOf(listOf(20.0, 30.0, 10.0)))
        assertEquals(Trend.FLAT, trendOf(listOf(100.0, 50.0, 100.5)))
        assertEquals(Trend.FLAT, trendOf(listOf(0.0, 0.0)))
        assertEquals(Trend.FLAT, trendOf(listOf(4.0)))
    }

    // ---- heatmap
    @Test
    fun `heatLevel buckets into zero plus four levels`() {
        assertEquals(0, heatLevel(0.0, 100.0))
        assertEquals(0, heatLevel(-5.0, 100.0))
        assertEquals(0, heatLevel(5.0, 0.0))
        assertEquals(1, heatLevel(1.0, 100.0))
        assertEquals(1, heatLevel(25.0, 100.0))
        assertEquals(2, heatLevel(26.0, 100.0))
        assertEquals(3, heatLevel(75.0, 100.0))
        assertEquals(4, heatLevel(100.0, 100.0))
        assertEquals(4, heatLevel(500.0, 100.0))
    }

    @Test
    fun `heatAlpha steps from faint to solid`() {
        assertEquals(0f, heatAlpha(0), 0f)
        assertEquals(0.25f, heatAlpha(1), 0.0001f)
        assertEquals(1f, heatAlpha(HEAT_LEVELS), 0.0001f)
        assertTrue(heatAlpha(2) > heatAlpha(1) && heatAlpha(3) > heatAlpha(2))
    }

    @Test
    fun `grid helpers compute rows and tap targets`() {
        assertEquals(0, gridRows(0, 7))
        assertEquals(3, gridRows(15, 7))
        assertEquals(2, gridRows(14, 7))
        // 3 columns x 2 rows, 5 cells (last slot empty), 30x20 area.
        assertEquals(0, gridIndexAt(1f, 1f, 30f, 20f, 3, 5))
        assertEquals(2, gridIndexAt(29f, 5f, 30f, 20f, 3, 5))
        assertEquals(3, gridIndexAt(5f, 15f, 30f, 20f, 3, 5))
        assertEquals(4, gridIndexAt(15f, 15f, 30f, 20f, 3, 5))
        assertNull(gridIndexAt(25f, 15f, 30f, 20f, 3, 5))
        assertNull(gridIndexAt(-1f, 5f, 30f, 20f, 3, 5))
        assertNull(gridIndexAt(5f, 5f, 30f, 20f, 3, 0))
    }

    // ---- pacing
    @Test
    fun `pacing is on pace within the slack and ahead beyond it`() {
        assertEquals(PacingStatus.ON_PACE, pacing(500.0, 1000.0, 0.5f).status)
        assertEquals(PacingStatus.ON_PACE, pacing(549.0, 1000.0, 0.5f).status)
        assertEquals(PacingStatus.AHEAD, pacing(560.0, 1000.0, 0.5f).status)
        assertEquals(PacingStatus.ON_PACE, pacing(0.0, 1000.0, 0f).status)
        assertEquals(PacingStatus.AHEAD, pacing(100.0, 1000.0, 0f).status)
    }

    @Test
    fun `pacing over budget wins and reports a negative remainder`() {
        val p = pacing(1200.0, 1000.0, 0.9f)
        assertEquals(PacingStatus.OVER, p.status)
        assertEquals(-200.0, p.remaining, 0.0001)
        assertEquals(1f, p.spentFraction, 0f)
    }

    @Test
    fun `pacing projects the current rate and clamps inputs`() {
        val p = pacing(300.0, 1000.0, 0.25f)
        assertEquals(1200.0, p.projected, 0.0001)
        assertEquals(250.0, p.expected, 0.0001)
        assertEquals(0.3f, p.spentFraction, 0.0001f)
        assertEquals(1f, pacing(1.0, 10.0, 5f).paceFraction, 0f)
        assertEquals(0f, pacing(1.0, 10.0, Float.NaN).paceFraction, 0f)
        assertEquals(0.0, pacing(-50.0, 10.0, 0.5f).spent, 0.0)
    }

    @Test
    fun `pacing without a budget is NO_BUDGET`() {
        assertEquals(PacingStatus.NO_BUDGET, pacing(50.0, 0.0, 0.5f).status)
        assertEquals(PacingStatus.NO_BUDGET, pacing(50.0, -1.0, 0.5f).status)
        assertEquals(PacingStatus.NO_BUDGET, pacing(50.0, Double.NaN, 0.5f).status)
    }

    @Test
    fun `pacing status has text and only escalating statuses have a tone`() {
        assertEquals(PacingStatus.values().size, PacingStatus.values().map { pacingStatusLabel(it) }.toSet().size)
        assertNull(pacingTone(PacingStatus.ON_PACE))
        assertNull(pacingTone(PacingStatus.NO_BUDGET))
        assertEquals(MMTone.WARNING, pacingTone(PacingStatus.AHEAD))
        assertEquals(MMTone.DANGER, pacingTone(PacingStatus.OVER))
    }

    // ---- summaries
    @Test
    fun `barChartSummary lists every bar and names the highest`() {
        val s = barChartSummary("Spend by month", listOf("Jan", "Feb", "Mar"), listOf(100.0, 300.0, 200.0), fmt)
        assertEquals("Spend by month: Jan AED 100, Feb AED 300, Mar AED 200. Highest: Feb", s)
        assertEquals("T: no data", barChartSummary("T", emptyList(), emptyList(), fmt))
        assertEquals("T: A AED 5", barChartSummary("T", listOf("A"), listOf(5.0), fmt))
    }

    @Test
    fun `donutSummary gives shares amounts and the total`() {
        val s = donutSummary("Spend by category", listOf("Dining", "Groceries", "Skipped"), listOf(340.0, 660.0, 0.0), fmt)
        assertEquals("Spend by category: Dining 34%, AED 340, Groceries 66%, AED 660. Total AED 1000", s)
        assertEquals("T: no data", donutSummary("T", listOf("A"), listOf(0.0), fmt))
    }

    @Test
    fun `sparklineSummary names the direction and range`() {
        val up = sparklineSummary("Daily spend", listOf(10.0, 30.0, 20.0, 40.0), fmt)
        assertEquals("Daily spend: trending up, from AED 10 to AED 40, low AED 10, high AED 40", up)
        assertTrue(sparklineSummary("T", listOf(5.0, 1.0), fmt).contains("trending down"))
        assertTrue(sparklineSummary("T", listOf(5.0, 5.0), fmt).contains("flat"))
        assertEquals("T: AED 7", sparklineSummary("T", listOf(7.0), fmt))
        assertEquals("T: no data", sparklineSummary("T", emptyList(), fmt))
    }

    @Test
    fun `heatmapSummary names the busiest cell and counts quiet ones`() {
        val s = heatmapSummary("By day", listOf("Mon", "Tue", "Wed"), listOf(0.0, 80.0, 20.0), fmt)
        assertEquals("By day: busiest Tue AED 80. 1 of 3 cells have no activity", s)
        assertEquals("T: no activity in 2 cells", heatmapSummary("T", listOf("a", "b"), listOf(0.0, 0.0), fmt))
        assertEquals("T: no data", heatmapSummary("T", emptyList(), emptyList(), fmt))
    }

    @Test
    fun `pacingSummary spells out status spend elapsed time and remainder`() {
        val ahead = pacingSummary("Dining", pacing(640.0, 1000.0, 0.5f), fmt)
        assertEquals("Dining: Ahead of pace. Spent AED 640 of AED 1000, 50% of the period elapsed, AED 360 left", ahead)
        val over = pacingSummary("Dining", pacing(1200.0, 1000.0, 0.9f), fmt)
        assertTrue(over.startsWith("Dining: Over budget."))
        assertTrue(over.endsWith("AED 200 over"))
        assertEquals("Dining: No budget set", pacingSummary("Dining", pacing(5.0, 0.0, 0.5f), fmt))
    }

    @Test
    fun `hidden summary carries no figures`() {
        assertEquals("Spend: values hidden", hiddenChartSummary("Spend"))
    }

    // ---- palette
    @Test
    fun `chart series has distinct colours in both themes and cycles by index`() {
        for (c in listOf(darkMMColors(), lightMMColors())) {
            val series = c.chartSeries()
            assertTrue(series.size >= 8)
            assertEquals(series.size, series.toSet().size)
            assertEquals(series[0], c.chartSeriesColor(series.size))
            assertEquals(series.last(), c.chartSeriesColor(-1))
        }
    }
}
