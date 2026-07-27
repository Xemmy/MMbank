package com.xemmy.mmbank.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** One day's real, analytics-eligible debit spend — the heatmap's unit of data. */
data class DailySpend(val date: LocalDate, val amount: Double)

/** Tile edge length and gap, shared by the grid's own sizing and its tap targets. */
private val TILE_SIZE = 16.dp
private val TILE_GAP = 4.dp
private const val GRID_ROWS = 7

/**
 * Buckets [amount] into a 0-4 heatmap intensity by its percentile rank among
 * every non-zero amount in [allAmounts] (GitHub-contribution-graph style: more
 * buckets would just be noise at personal-finance data volumes). A day with no
 * spend, or a range with no spend at all, is always bucket 0. Pure and
 * top-level so it's JVM-testable; [SpendingHeatmap] only reads the result to
 * pick a tile color.
 */
internal fun spendPercentileBucket(amount: Double, allAmounts: List<Double>): Int {
    if (amount <= 0.0) return 0
    val positive = allAmounts.filter { it > 0.0 }.sorted()
    if (positive.isEmpty()) return 0
    val rank = positive.count { it <= amount }.toDouble() / positive.size
    return when {
        rank <= 0.25 -> 1
        rank <= 0.50 -> 2
        rank <= 0.75 -> 3
        else -> 4
    }
}

/** Light-to-dark accent ramp, index = [spendPercentileBucket]'s 0-4 result. */
private val BUCKET_COLORS = listOf(
    Color(0xFF2A2E37), // 0: no spend
    Color(0xFF1B4332), // 1: low
    Color(0xFF2D6A4F), // 2: below-median
    Color(0xFF40916C), // 3: above-median
    Color(0xFF52B788), // 4: highest
)

/**
 * GitHub-style spending heatmap: one tile per day, colored by its spend
 * percentile within [dailySpend]. Tapping a tile invokes [onDayTapped] with
 * that exact date, so the caller can filter the transaction list below.
 */
@Composable
fun SpendingHeatmap(
    dailySpend: List<DailySpend>,
    selectedDate: LocalDate?,
    onDayTapped: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val amounts = remember(dailySpend) { dailySpend.map { it.amount } }
    val gridHeight = TILE_SIZE * GRID_ROWS + TILE_GAP * (GRID_ROWS - 1)

    LazyHorizontalGrid(
        rows = GridCells.Fixed(GRID_ROWS),
        modifier = modifier.height(gridHeight),
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
    ) {
        items(dailySpend, key = { it.date.toEpochDay() }) { day ->
            val bucket = spendPercentileBucket(day.amount, amounts)
            val isSelected = day.date == selectedDate
            Box(
                Modifier
                    .size(TILE_SIZE)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BUCKET_COLORS[bucket])
                    .then(
                        if (isSelected) {
                            Modifier.border(1.5.dp, Color.White, RoundedCornerShape(3.dp))
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onDayTapped(day.date) },
            )
        }
    }
}
