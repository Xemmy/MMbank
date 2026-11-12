package com.xemmy.mmbank.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.DonutChart
import com.xemmy.mmbank.ui.components.PacingArcGauge
import com.xemmy.mmbank.ui.screens.dashboard.RecentTransactionRow
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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

@Composable
private fun PacingGaugeCard(state: AnalyticsUiState) {
    MMCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PacingArcGauge(
                spent = state.pacing.spentToday,
                allowance = state.pacing.allowance,
                modifier = Modifier.size(84.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Today's pacing", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${state.pacing.spentToday.asMoneyOrMask(state.pacing.currencyCode, state.hideBalances)} of " +
                        "${state.pacing.allowance.asMoneyOrMask(state.pacing.currencyCode, state.hideBalances)} allowance",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
                if (state.pacing.allowance > 0.0) {
                    val percent = (state.pacing.spentToday / state.pacing.allowance * 100.0).roundToInt()
                    Text(
                        "$percent% of today's allowance used",
                        style = MaterialTheme.typography.bodySmall,
                        color = MMTheme.colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryDonutCard(state: AnalyticsUiState) {
    val slices = state.categorySlices
    MMCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Spend by category", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DonutChart(slices = slices, modifier = Modifier.size(110.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slices.forEach { slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(Color(slice.color), MaterialTheme.shapes.extraSmall),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(slice.name, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(12.dp))
                            MoneyText(
                                amount = slice.amount,
                                currencyCode = state.baseCurrency,
                                kind = TransactionKind.TRANSFER,
                                style = MaterialTheme.typography.bodySmall,
                                hidden = state.hideBalances,
                            )
                        }
                    }
                }
            }
        }
    }
}
