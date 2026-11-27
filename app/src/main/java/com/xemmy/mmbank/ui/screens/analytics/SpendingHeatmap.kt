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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.ui.designsystem.heatAlpha
import com.xemmy.mmbank.ui.theme.MMTheme
import java.time.LocalDate

/** One day's real, analytics-eligible debit spend — the heatmap's unit of data. */
data class DailySpend(val date: LocalDate, val amount: Double)

/** Tile edge length and gap, shared by the grid's own sizing and its tap targets. */
private val TILE_SIZE = 16.dp
private val TILE_GAP = 4.dp
private const val GRID_ROWS = 7

/** Non-zero intensity buckets from [spendPercentileBucket] (1..4); bucket 0 is the empty tile. */
private const val SPEND_BUCKETS = 4

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
    if (positive.size == 1) return 4
    // 0-indexed rank of the highest element <= amount, normalized to [0, 1] by
    // the min/max of positive -- using positive.size (rather than size - 1) as
    // the denominator would push the minimum of a small set past the first
    // quartile cutoff (e.g. 1/3 > 0.25) even though it's the lowest value.
    val rank = (positive.count { it <= amount } - 1).toDouble() / (positive.size - 1)
    return when {
        rank <= 0.25 -> 1
        rank <= 0.50 -> 2
        rank <= 0.75 -> 3
        else -> 4
    }
}

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
    val colors = MMTheme.colors
    val tileShape = MaterialTheme.shapes.extraSmall
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
                    .clip(tileShape)
                    .background(if (bucket == 0) colors.borderSubtle else colors.accent.copy(alpha = heatAlpha(bucket, SPEND_BUCKETS)))
                    .then(
                        if (isSelected) {
                            Modifier.border(1.5.dp, colors.textPrimary, tileShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onDayTapped(day.date) },
            )
        }
    }
}
