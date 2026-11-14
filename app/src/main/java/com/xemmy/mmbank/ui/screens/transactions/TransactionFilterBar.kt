package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal

/**
 * Single-row Activity filter bar: Time / Type / Account collapse
 * into one horizontally scrollable [LazyRow] of dropdown chips, with the
 * lower-traffic filters (person, triage, amount, source, analytics status)
 * tucked behind a "More filters" chip that opens [MoreFiltersDialog].
 */
@Composable
internal fun TransactionFilterBar(
    state: TransactionsUiState,
    onQuery: (String) -> Unit,
    onDateRange: (DateRangeFilter) -> Unit,
    onDirectionFilter: (TransactionDirection?) -> Unit,
    onAccountFilter: (AccountFilter) -> Unit,
    onPersonFilter: (PersonFilter) -> Unit,
    onTriageOnly: (Boolean) -> Unit,
    onAmountRange: (BigDecimal?, BigDecimal?) -> Unit,
    onSourceFilter: (TransactionSource?) -> Unit,
    onAnalyticsStatus: (AnalyticsStatusFilter?) -> Unit,
    onClear: () -> Unit,
    onKindFilter: (com.xemmy.mmbank.domain.model.TransactionKind?) -> Unit = {},
) {
    val f = state.filters
    var timeMenuOpen by remember { mutableStateOf(false) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var customRangeOpen by remember { mutableStateOf(false) }
    var moreFiltersOpen by remember { mutableStateOf(false) }

    val secondaryCount = remember(f) { TransactionFilterEngine.activeSecondaryFilterCount(f) }
    val cycleDay = remember(f.account, state.accounts) {
        TransactionFilterEngine.activeAccount(f.account, state.accounts)?.statementCycleDay
    }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CompactSearchField(
            query = f.query,
            onQuery = onQuery,
            modifier = Modifier.fillMaxWidth(),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = 2.dp),
        ) {
            // --- Time chip ---
            item {
                Box {
                    FilterBarChip(
                        selected = f.dateRange != DateRangeFilter.All,
                        onClick = { timeMenuOpen = true },
                        label = dateRangeChipLabel(f.dateRange, cycleDay),
                        icon = Icons.Filled.DateRange,
                    )
                    DropdownMenu(expanded = timeMenuOpen, onDismissRequest = { timeMenuOpen = false }) {
                        val presets = listOf(
                            DateRangeFilter.All, DateRangeFilter.Today, DateRangeFilter.Past7Days,
                            DateRangeFilter.Past30Days, DateRangeFilter.YearToDate, DateRangeFilter.StatementCycle,
                        )
                        presets.forEach { range ->
                            DropdownMenuItem(
                                text = { Text(dateRangeChipLabel(range, cycleDay)) },
                                onClick = { timeMenuOpen = false; onDateRange(range) },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    (f.dateRange as? DateRangeFilter.Custom)
                                        ?.let { dateRangeChipLabel(it, cycleDay) } ?: "Custom…"
                                )
                            },
                            onClick = { timeMenuOpen = false; customRangeOpen = true },
                        )
                    }
                }
            }

            // --- Type chip ---
            item {
                Box {
                    FilterBarChip(
                        selected = f.direction != null,
                        onClick = { typeMenuOpen = true },
                        label = directionChipLabel(f.direction),
                        icon = Icons.Filled.SwapVert,
                    )
                    DropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        val options = listOf(null, TransactionDirection.DEBIT, TransactionDirection.CREDIT)
                        options.forEach { direction ->
                            DropdownMenuItem(
                                text = { Text(directionChipLabel(direction)) },
                                onClick = { typeMenuOpen = false; onDirectionFilter(direction) },
                            )
                        }
                    }
                }
            }

            // --- Account chip ---
            item {
                Box {
                    val accountLabel = when (val a = f.account) {
                        AccountFilter.Any -> "Account"
                        AccountFilter.Unassigned -> "Unassigned"
                        AccountFilter.HistoryOnly -> "History only"
                        is AccountFilter.Specific ->
                            state.accounts.firstOrNull { it.id == a.id }?.name ?: "Account"
                    }
                    FilterBarChip(
                        selected = f.account != AccountFilter.Any,
                        onClick = { accountMenuOpen = true },
                        label = accountLabel,
                        icon = Icons.Filled.AccountBalanceWallet,
                    )
                    DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Any account") },
                            onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Any) },
                        )
                        DropdownMenuItem(
                            text = { Text("Unassigned") },
                            onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Unassigned) },
                        )
                        DropdownMenuItem(
                            text = { Text("History only") },
                            onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.HistoryOnly) },
                        )
                        state.accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    accountMenuOpen = false
                                    onAccountFilter(AccountFilter.Specific(account.id))
                                },
                            )
                        }
                    }
                }
            }

            // --- More filters chip ---
            item {
                FilterBarChip(
                    selected = secondaryCount > 0,
                    onClick = { moreFiltersOpen = true },
                    label = if (secondaryCount > 0) "More filters ($secondaryCount)" else "More filters",
                    icon = Icons.Filled.FilterList,
                )
            }

            // --- Clear all, only when something is active ---
            if (f.active) {
                item {
                    FilterBarChip(
                        selected = false,
                        onClick = onClear,
                        label = "Clear",
                        icon = Icons.Filled.Close,
                    )
                }
            }
        }
    }

    if (customRangeOpen) {
        CustomDateRangeDialog(
            initial = f.dateRange as? DateRangeFilter.Custom,
            onDismiss = { customRangeOpen = false },
            onSelect = { start, end ->
                onDateRange(DateRangeFilter.Custom(start, end))
                customRangeOpen = false
            },
        )
    }

    if (moreFiltersOpen) {
        MoreFiltersDialog(
            state = state,
            onPersonFilter = onPersonFilter,
            onTriageOnly = onTriageOnly,
            onAmountRange = onAmountRange,
            onSourceFilter = onSourceFilter,
            onAnalyticsStatus = onAnalyticsStatus,
            onKindFilter = onKindFilter,
            onClearAll = onClear,
            onDismiss = { moreFiltersOpen = false },
        )
    }
}
