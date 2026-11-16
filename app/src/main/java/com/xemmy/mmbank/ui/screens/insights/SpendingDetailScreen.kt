package com.xemmy.mmbank.ui.screens.insights

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.filter.ActivityDrillDown
import com.xemmy.mmbank.domain.report.CategoryShare
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.BarChart
import com.xemmy.mmbank.ui.designsystem.BarEntry
import com.xemmy.mmbank.ui.designsystem.DonutChart
import com.xemmy.mmbank.ui.designsystem.DonutSlice
import com.xemmy.mmbank.ui.designsystem.ListRow
import com.xemmy.mmbank.ui.designsystem.LocalHideBalances
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.Sparkline
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.util.Locale

/** Where the period's spending went: a donut / bars / line and every category, tappable. */
@Composable
fun SpendingDetailScreen(
    onBack: () -> Unit,
    onOpenActivity: (String) -> Unit,
    viewModel: PeriodDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    var chart by remember { mutableStateOf(DetailChart.DONUT) }
    val currency = viewModel.baseCurrency

    MMScaffold(title = "Spending", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DetailPeriodBar(viewModel)
            }
            val b = breakdown
            if (b == null) {
                item { Text("Loading…", color = MMTheme.colors.textSecondary) }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Spent in ${period.label(viewModel.today).lowercase(Locale.US)}", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                    Text(moneyLabel(b.spendTotal, currency, hidden), style = MaterialTheme.typography.headlineMedium, color = MMTheme.colors.textPrimary)
                }
            }
            if (b.spending.isEmpty()) {
                item { Text("Nothing spent in this period.", color = MMTheme.colors.textSecondary) }
            } else {
                item { ChartToggle(DetailChart.entries, chart) { chart = it } }
                item {
                    MMCard(Modifier.fillMaxWidth()) {
                        CompositionLocalProvider(LocalHideBalances provides hidden) {
                            when (chart) {
                                DetailChart.DONUT -> DonutChart(
                                    slices = b.spending.map { DonutSlice(it.category.name, it.amount) },
                                    title = "Spending by category",
                                    centerLabel = moneyLabel(b.spendTotal, currency, hidden),
                                    valueLabel = { moneyLabel(it, currency, hidden) },
                                    modifier = Modifier.fillMaxWidth(),
                                    centerCaption = "spent",
                                )
                                DetailChart.BARS -> BarChart(
                                    entries = b.buckets.map { BarEntry(bucketLabel(it.window), it.spend.coerceAtLeast(0.0)) },
                                    title = "Spending per ${if (b.buckets.firstOrNull()?.window?.let { it.start == it.end } == true) "day" else "month"}",
                                    valueLabel = { moneyLabel(it, currency, hidden) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                DetailChart.LINE -> Sparkline(
                                    values = b.buckets.map { it.spend }.runningTotal(),
                                    title = "Spending so far in the period",
                                    valueLabel = { moneyLabel(it, currency, hidden) },
                                )
                            }
                        }
                    }
                }
                items(b.spending, key = { it.category.id.toString() + it.category.name }) { share ->
                    CategoryRow(share, currency, hidden) {
                        val range = b.window
                        onOpenActivity(
                            Uri.encode(
                                ActivityDrillDown(categoryIds = share.categoryIds, from = range?.start, to = range?.end).encode(),
                            ),
                        )
                    }
                }
            }
            if (b.notCounted.total > 0) {
                item {
                    Text(
                        "Not counted: ${b.notCounted.total} transfers, card payments and adjustments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MMTheme.colors.textSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CategoryRow(share: CategoryShare, currency: String, hidden: Boolean, onClick: () -> Unit) {
    MMCard(Modifier.fillMaxWidth(), onClick = onClick) {
        ListRow(
            title = share.category.name,
            subtitle = "${share.count} transaction${if (share.count == 1) "" else "s"} · ${String.format(Locale.US, "%.0f", share.share)}%",
            leading = { Text(share.category.icon) },
            trailing = { Text(moneyLabel(share.amount, currency, hidden), style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textPrimary) },
        )
    }
}

private fun List<Double>.runningTotal(): List<Double> {
    var sum = 0.0
    return map { sum += it; sum }
}
