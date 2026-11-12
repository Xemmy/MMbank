package com.xemmy.mmbank.ui.screens.analytics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.screens.dashboard.RecentTransactionRow
import com.xemmy.mmbank.ui.designsystem.DonutChart
import com.xemmy.mmbank.ui.designsystem.DonutSlice
import com.xemmy.mmbank.ui.designsystem.LocalHideBalances
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.PacingGauge
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Interactive spending analytics: a Daily/Weekly/Monthly/Custom date filter
 * driving a GitHub-style spending heatmap, a category donut and a daily
 * pacing arc gauge. Tapping a heatmap tile filters the transaction list below
 * to that exact date.
 */
@Composable
fun AnalyticsScreen(
    onBack: () -> Unit,
    viewModel: AnalyticsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Analytics", style = MaterialTheme.typography.titleLarge)
            }
        }

        item(key = "range_selector") {
            AnalyticsDateRangeRow(selected = state.dateRange, onSelect = viewModel::setDateRange)
        }

        if (state.pacing.hasBudget) {
            item(key = "pacing_gauge") { PacingGaugeCard(state) }
        }

        item(key = "heatmap") {
            MMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Daily spend", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(10.dp))
                    if (state.dailySpend.isEmpty()) {
                        Text(
                            "No days in range.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MMTheme.colors.textSecondary,
                        )
                    } else {
                        SpendingHeatmap(
                            dailySpend = state.dailySpend,
                            selectedDate = state.selectedDate,
                            onDayTapped = viewModel::selectDate,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        if (state.categorySlices.isNotEmpty()) {
            item(key = "category_donut") { CategoryDonutCard(state) }
        }

        item(key = "list_header") {
            Text(
                if (state.selectedDate != null) {
                    "Transactions on ${state.selectedDate!!.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
                } else {
                    "All transactions in range"
                },
                style = MaterialTheme.typography.titleSmall,
            )
        }

        if (state.transactions.isEmpty()) {
            item {
                Text(
                    "No spend to show here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
        } else {
            items(state.transactions, key = { it.id }) { tx ->
                Column {
                    RecentTransactionRow(tx = tx, hidden = state.hideBalances)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AnalyticsDateRangeRow(selected: AnalyticsDateRange, onSelect: (AnalyticsDateRange) -> Unit) {
    var customOpen by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        MMFilterChip(
            label = "Daily",
            selected = selected == AnalyticsDateRange.Daily,
            onClick = { onSelect(AnalyticsDateRange.Daily) },
        )
        MMFilterChip(
            label = "Weekly",
            selected = selected == AnalyticsDateRange.Weekly,
            onClick = { onSelect(AnalyticsDateRange.Weekly) },
        )
        MMFilterChip(
            label = "Monthly",
            selected = selected == AnalyticsDateRange.Monthly,
            onClick = { onSelect(AnalyticsDateRange.Monthly) },
        )
        val isCustom = selected is AnalyticsDateRange.Custom
        MMFilterChip(
            label = "Custom",
            selected = isCustom,
            onClick = { customOpen = true },
        )
    }

    if (customOpen) {
        AnalyticsCustomRangeDialog(
            initial = selected as? AnalyticsDateRange.Custom,
            onDismiss = { customOpen = false },
            onSelect = { start, end ->
                onSelect(AnalyticsDateRange.Custom(start, end))
                customOpen = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsCustomRangeDialog(
    initial: AnalyticsDateRange.Custom?,
    onDismiss: () -> Unit,
    onSelect: (LocalDate, LocalDate) -> Unit,
) {
    fun LocalDate.toUtcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toUtcDate() = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null && pickerState.selectedEndDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis
                    if (start != null && end != null) onSelect(start.toUtcDate(), end.toUtcDate())
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state = pickerState, modifier = Modifier.weight(1f), showModeToggle = false)
    }
}

/** Share of the local day that has passed at [secondOfDay] (0..86399), clamped to 0..1. */
internal fun dayElapsedFraction(secondOfDay: Int): Float =
    (secondOfDay / SECONDS_PER_DAY.toFloat()).coerceIn(0f, 1f)

private const val SECONDS_PER_DAY = 86_400

@Composable
private fun PacingGaugeCard(state: AnalyticsUiState) {
    val pacing = state.pacing
    val elapsed = dayElapsedFraction(LocalTime.now().toSecondOfDay())
    MMCard(Modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalHideBalances provides state.hideBalances) {
            PacingGauge(
                spent = pacing.spentToday,
                budget = pacing.allowance,
                elapsedFraction = elapsed,
                title = "Today's pacing",
                valueLabel = { moneyLabel(it, pacing.currencyCode, state.hideBalances) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CategoryDonutCard(state: AnalyticsUiState) {
    val slices = state.categorySlices
    val total = slices.sumOf { it.amount }
    MMCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Spend by category", style = MaterialTheme.typography.titleSmall)
            CompositionLocalProvider(LocalHideBalances provides state.hideBalances) {
                DonutChart(
                    slices = slices.map { DonutSlice(it.name, it.amount) },
                    title = "Spend by category",
                    centerLabel = moneyLabel(total, state.baseCurrency, state.hideBalances),
                    valueLabel = { moneyLabel(it, state.baseCurrency, state.hideBalances) },
                    modifier = Modifier.fillMaxWidth(),
                    centerCaption = "total",
                )
            }
        }
    }
}
