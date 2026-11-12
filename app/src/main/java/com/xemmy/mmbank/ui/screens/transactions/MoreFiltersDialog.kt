package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.glassSurface
import java.math.BigDecimal

/**
 * Lower-traffic Activity filters (person, triage, amount, source, analytics
 * status) opened from the single-row bar's "More filters" chip. Built as a
 * real [AlertDialog] window, not a `ModalBottomSheet` — see
 * [TransactionDetailSheet]'s doc comment: a `ModalBottomSheet` here could never
 * blur its backdrop and rendered as a translucent, then empty, panel on some
 * devices, which is exactly why that overlay was rewritten as a dialog. Every
 * filter here applies live via its callback, same as [TransactionFilterBar];
 * "Clear all" resets the whole [ActivityFilters][com.xemmy.mmbank.domain.filter.ActivityFilters]
 * set, including Time/Type/Account.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoreFiltersDialog(
    state: TransactionsUiState,
    onPersonFilter: (PersonFilter) -> Unit,
    onTriageOnly: (Boolean) -> Unit,
    onAmountRange: (BigDecimal?, BigDecimal?) -> Unit,
    onSourceFilter: (TransactionSource?) -> Unit,
    onAnalyticsStatus: (AnalyticsStatusFilter?) -> Unit,
    onKindFilter: (com.xemmy.mmbank.domain.model.TransactionKind?) -> Unit = {},
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val f = state.filters
    var amountDialogOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = { Text("More filters", style = MaterialTheme.typography.titleLarge) },
        text = {
            GlassDialogBackdrop()
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Person")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterBarChip(
                            selected = f.person == PersonFilter.Any,
                            onClick = { onPersonFilter(PersonFilter.Any) },
                            label = "Any person",
                        )
                        FilterBarChip(
                            selected = f.person == PersonFilter.Unassigned,
                            onClick = { onPersonFilter(PersonFilter.Unassigned) },
                            label = "Unassigned",
                        )
                        state.knownPersons.forEach { name ->
                            FilterBarChip(
                                selected = f.person == PersonFilter.Specific(name),
                                onClick = { onPersonFilter(PersonFilter.Specific(name)) },
                                label = name,
                            )
                        }
                    }
                }

                HorizontalDivider(color = ObsidianTokens.hairlineDialog)

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Inbox Zero", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Only rows still needing review.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = f.triageOnly, onCheckedChange = onTriageOnly)
                }

                HorizontalDivider(color = ObsidianTokens.hairlineDialog)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Amount range")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(amountRangeSummary(f.minAmount, f.maxAmount), style = MaterialTheme.typography.bodyMedium)
                        FilterBarChip(
                            selected = f.minAmount != null || f.maxAmount != null,
                            onClick = { amountDialogOpen = true },
                            label = "Edit",
                        )
                    }
                }

                HorizontalDivider(color = ObsidianTokens.hairlineDialog)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Source")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterBarChip(
                            selected = f.source == null,
                            onClick = { onSourceFilter(null) },
                            label = "Any source",
                        )
                        TransactionSource.entries.forEach { source ->
                            FilterBarChip(
                                selected = f.source == source,
                                onClick = { onSourceFilter(source) },
                                label = sourceLabel(source),
                            )
                        }
                    }
                }

                HorizontalDivider(color = ObsidianTokens.hairlineDialog)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("What it is")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterBarChip(
                            selected = f.kind == null,
                            onClick = { onKindFilter(null) },
                            label = "Any",
                        )
                        com.xemmy.mmbank.domain.model.TransactionKind.entries.forEach { kind ->
                            FilterBarChip(
                                selected = f.kind == kind,
                                onClick = { onKindFilter(kind) },
                                label = kind.label,
                            )
                        }
                    }
                }

                HorizontalDivider(color = ObsidianTokens.hairlineDialog)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Analytics")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterBarChip(
                            selected = f.analyticsStatus == null,
                            onClick = { onAnalyticsStatus(null) },
                            label = "All rows",
                        )
                        AnalyticsStatusFilter.entries.forEach { status ->
                            FilterBarChip(
                                selected = f.analyticsStatus == status,
                                onClick = { onAnalyticsStatus(status) },
                                label = status.label,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { onClearAll(); onDismiss() }) { Text("Clear all") }
        },
    )

    if (amountDialogOpen) {
        AmountRangeDialog(
            currentMin = f.minAmount,
            currentMax = f.maxAmount,
            onDismiss = { amountDialogOpen = false },
            onApply = { min, max ->
                onAmountRange(min, max)
                amountDialogOpen = false
            },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = ObsidianTokens.textMeta)
}

/** Same open/closed-bound summary text used by the classic bar's amount chip. */
private fun amountRangeSummary(min: BigDecimal?, max: BigDecimal?): String = when {
    min != null && max != null -> "$min–$max"
    min != null -> "≥ $min"
    max != null -> "≤ $max"
    else -> "No limit"
}
