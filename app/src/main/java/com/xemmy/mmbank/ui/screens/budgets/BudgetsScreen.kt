package com.xemmy.mmbank.ui.screens.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.budget.BudgetListBuilder
import com.xemmy.mmbank.domain.budget.BudgetListRow
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.ListRow
import com.xemmy.mmbank.ui.designsystem.MMBottomSheet
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.MMProgressBar
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.util.AmountInput
import java.math.BigDecimal

/** The list of everything you can budget: tap **Set** next to a category and type a number. */
@Composable
fun BudgetsScreen(
    onBack: () -> Unit,
    onOpenCategories: () -> Unit,
    viewModel: BudgetsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAll by remember { mutableStateOf(false) }
    var editingOverall by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BudgetListRow?>(null) }
    val shown = if (showAll) state.rows else state.rows.take(BudgetListBuilder.TOP_COUNT)

    MMScaffold(title = "Budgets", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "overall") {
                BudgetLine(
                    title = "All",
                    subtitle = "Everything you spend in a month",
                    spent = state.overallSpent,
                    cap = state.overallBudget.takeIf { it > 0.0 },
                    currency = state.currency,
                    onClick = { editingOverall = true },
                )
            }
            if (state.loaded && state.rows.isEmpty()) {
                item {
                    Text(
                        "Spending shows up here once you have a few categorised transactions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MMTheme.colors.textSecondary,
                    )
                }
            }
            items(shown, key = { "budget_${it.category.id}" }) { row ->
                BudgetLine(
                    title = "${row.category.icon} ${row.category.name}",
                    subtitle = null,
                    spent = row.spent.toDouble(),
                    cap = row.cap?.toDouble(),
                    currency = state.currency,
                    onClick = { editing = row },
                )
            }
            if (!showAll && state.rows.size > BudgetListBuilder.TOP_COUNT) {
                item { TextButton(onClick = { showAll = true }) { Text("All categories (${state.rows.size})") } }
            }
            item {
                TextButton(onClick = onOpenCategories) { Text("Edit categories") }
            }
        }
    }

    if (editingOverall) {
        CapSheet(
            title = "Monthly budget",
            hint = null,
            current = state.overallBudget.takeIf { it > 0.0 }?.let { BigDecimal.valueOf(it) },
            currency = state.currency,
            onDismiss = { editingOverall = false },
            onSave = { viewModel.setOverall(it?.toDouble() ?: 0.0); editingOverall = false },
        )
    }
    editing?.let { row ->
        CapSheet(
            title = row.category.name,
            hint = BudgetListBuilder.suggestedCap(row)?.let { suggestion ->
                val basis = if (row.monthlyAverage.signum() > 0) row.monthlyAverage else row.spent
                "You spend about ${moneyLabel(basis.toDouble(), state.currency)} a month" to suggestion
            },
            current = row.cap,
            currency = state.currency,
            onDismiss = { editing = null },
            onSave = { viewModel.setCap(row.category, it); editing = null },
        )
    }
}

@Composable
private fun BudgetLine(title: String, subtitle: String?, spent: Double, cap: Double?, currency: String, onClick: () -> Unit) {
    MMCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ListRow(
                title = title,
                subtitle = subtitle ?: "Spent ${moneyLabel(spent, currency)} this month",
                trailing = {
                    if (cap == null) {
                        MMFilterChip(label = "Set", selected = false, onClick = onClick)
                    } else {
                        Text(moneyLabel(cap, currency), style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textPrimary)
                    }
                },
            )
            if (cap != null && cap > 0.0) {
                MMProgressBar(fraction = (spent / cap).toFloat(), modifier = Modifier.padding(horizontal = 16.dp).height(6.dp))
                Text(
                    if (spent > cap) "${moneyLabel(spent - cap, currency)} over" else "${moneyLabel(cap - spent, currency)} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = MMTheme.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun CapSheet(
    title: String,
    hint: Pair<String, BigDecimal>?,
    current: BigDecimal?,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (BigDecimal?) -> Unit,
) {
    var text by remember { mutableStateOf(current?.stripTrailingZeros()?.toPlainString() ?: "") }
    val value = AmountInput.parse(text)
    MMBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Budget for $title", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Monthly amount ($currency)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = text.isNotBlank() && value == null,
                modifier = Modifier.fillMaxWidth(),
            )
            if (hint != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(hint.first, style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { text = hint.second.toPlainString() }) { Text("Use ${hint.second.toPlainString()}") }
                }
            }
            Button(enabled = value != null && value.signum() > 0, onClick = { onSave(value) }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            if (current != null) {
                OutlinedButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth()) { Text("Remove budget") }
            }
        }
    }
}
