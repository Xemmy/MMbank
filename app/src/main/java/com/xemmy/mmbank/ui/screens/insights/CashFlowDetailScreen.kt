package com.xemmy.mmbank.ui.screens.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
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
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.BarChart
import com.xemmy.mmbank.ui.designsystem.BarEntry
import com.xemmy.mmbank.ui.designsystem.ListRow
import com.xemmy.mmbank.ui.designsystem.LocalHideBalances
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.Sparkline
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.util.Locale

/** Money in, money out and the net for the period, as bars or a running line. */
@Composable
fun CashFlowDetailScreen(
    onBack: () -> Unit,
    viewModel: PeriodDetailViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val breakdown by viewModel.breakdown.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    var chart by remember { mutableStateOf(DetailChart.BARS) }
    val currency = viewModel.baseCurrency

    MMScaffold(title = "Cash flow", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DetailPeriodBar(viewModel) }
            val b = breakdown
            if (b == null) {
                item { Text("Loading…", color = MMTheme.colors.textSecondary) }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Net in ${period.label(viewModel.today).lowercase(Locale.US)}", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                    Text(
                        (if (b.net < 0) "−" else "") + moneyLabel(kotlin.math.abs(b.net), currency, hidden),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MMTheme.colors.textPrimary,
                    )
                }
            }
            item { ChartToggle(listOf(DetailChart.BARS, DetailChart.LINE), chart) { chart = it } }
            item {
                val unit = if (b.buckets.firstOrNull()?.window?.let { it.start == it.end } == true) "day" else "month"
                MMCard(Modifier.fillMaxWidth()) {
                    CompositionLocalProvider(LocalHideBalances provides hidden) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (chart) {
                                DetailChart.LINE -> Sparkline(
                                    values = b.buckets.runningNet(),
                                    title = "Net cash flow so far in the period",
                                    valueLabel = { moneyLabel(it, currency, hidden) },
                                )
                                else -> {
                                    Text("Money in", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                                    BarChart(
                                        entries = b.buckets.map { BarEntry(bucketLabel(it.window), it.income.coerceAtLeast(0.0)) },
                                        title = "Money in per $unit",
                                        valueLabel = { moneyLabel(it, currency, hidden) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text("Money out", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
                                    BarChart(
                                        entries = b.buckets.map { BarEntry(bucketLabel(it.window), it.spend.coerceAtLeast(0.0)) },
                                        title = "Money out per $unit",
                                        valueLabel = { moneyLabel(it, currency, hidden) },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                MMCard(Modifier.fillMaxWidth()) {
                    ListRow(title = "Money in", trailing = { Text(moneyLabel(b.incomeTotal, currency, hidden), color = MMTheme.colors.moneyIn) })
                    ListRow(title = "Money out", trailing = { Text(moneyLabel(b.spendTotal, currency, hidden), color = MMTheme.colors.moneyOut) })
                }
            }
            if (b.income.isNotEmpty()) {
                item { Text("Received from", style = MaterialTheme.typography.titleSmall) }
                b.income.forEach { share ->
                    item(key = "in_" + share.category.id + share.category.name) {
                        CategoryRow(share, currency, hidden) {}
                    }
                }
            }
        }
    }
}
