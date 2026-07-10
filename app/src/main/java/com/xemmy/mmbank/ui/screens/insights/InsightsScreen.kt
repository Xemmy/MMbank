package com.xemmy.mmbank.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.screens.dashboard.DashboardUiState
import com.xemmy.mmbank.ui.screens.dashboard.DashboardViewModel
import com.xemmy.mmbank.ui.screens.dashboard.GoalState
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/**
 * The Dashboard tab: this-month stat tiles, savings goal, cash-flow chart,
 * per-person spend, and the doorway to cashback rewards. Reuses
 * [DashboardViewModel], which already assembles stats/goal/hide state.
 */
@Composable
fun InsightsScreen(
    onOpenRewards: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasData = state.stats.monthly.any { it.income > 0.0 || it.spend > 0.0 }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Low-profile switch between "clean" analytics (excluded rows filtered
        // out, the default) and the raw ledger with every transaction counted.
        item(key = "ledger_toggle") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilterChip(
                    selected = state.showAllLedgerTransactions,
                    onClick = viewModel::toggleShowAllLedgerTransactions,
                    label = { Text("Raw ledger") },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.VisibilityOff,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }

        item(key = "month_tiles") { MonthTiles(state) }

        item(key = "goal_card") { GoalCard(state = state, onSaveGoal = viewModel::saveGoal) }

        if (hasData) {
            item(key = "cashflow_card") { CashFlowCard(state) }
            if (state.stats.categorySpends.isNotEmpty()) {
                item(key = "category_card") { CategorySpendCard(state) }
            }
            if (state.stats.personSpends.isNotEmpty()) {
                item(key = "person_card") { PersonSpendCard(state) }
            }
        } else {
            item {
                Text(
                    "Charts appear here once transactions are captured.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "rewards_row") { RewardsRow(onOpenRewards) }
    }
}

/** Income / Spent / Net for the current month as three stat tiles. */
@Composable
private fun MonthTiles(state: DashboardUiState) {
    val month = state.stats.monthly.lastOrNull()
    val income = month?.income ?: 0.0
    val spend = month?.spend ?: 0.0
    val net = income - spend
    val hidden = state.hideBalances

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile("Income", income.asMoneyOrMask(state.baseCurrency, hidden), LedgerColors.credit, Modifier.weight(1f))
        StatTile("Spent", spend.asMoneyOrMask(state.baseCurrency, hidden), LedgerColors.debit, Modifier.weight(1f))
        StatTile(
            "Net",
            net.asMoneyOrMask(state.baseCurrency, hidden),
            if (net < 0) LedgerColors.debit else MaterialTheme.colorScheme.primary,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            AmountText(
                display = value,
                color = valueColor,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

/** Card row that opens the full cashback Rewards screen. */
@Composable
private fun RewardsRow(onOpenRewards: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRewards)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Redeem, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Cashback rewards", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Earned cashback and category planner",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Savings goal: progress toward the target plus a months-to-go estimate. The
 * pace comes from the configured monthly saving, or — when that's zero — from
 * the average net flow of recent completed months.
 */
@Composable
private fun GoalCard(state: DashboardUiState, onSaveGoal: (Double, Double) -> Unit) {
    var editOpen by remember { mutableStateOf(false) }
    val goal = state.goal
    val hidden = state.hideBalances
    val currency = state.baseCurrency

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Savings goal",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { editOpen = true }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit goal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (!goal.isSet) {
                Text(
                    "Set a target amount and how much you plan to save each month — " +
                        "progress and the estimated finish date will show here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { editOpen = true }) { Text("Set a goal") }
            } else {
                val saved = state.netWorthInBase
                val progress = (saved / goal.targetAmount).toFloat().coerceIn(0f, 1f)
                val remaining = (goal.targetAmount - saved).coerceAtLeast(0.0)
                val pace = if (goal.monthlySaving > 0.0) goal.monthlySaving else state.stats.avgMonthlyNet

                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 8.dp)
                        .height(8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.padding(end = 8.dp)) {
                    Text(
                        saved.asMoneyOrMask(currency, hidden) + " of " +
                            goal.targetAmount.asMoneyOrMask(currency, hidden),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val etaLine = when {
                    remaining <= 0.0 -> "Goal reached 🎉"
                    pace > 0.0 -> {
                        val months = ceil(remaining / pace).toInt()
                        val eta = LocalDate.now().plusMonths(months.toLong())
                            .format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US))
                        val paceLabel = if (goal.monthlySaving > 0.0) "planned" else "recent average"
                        "≈ $months month${if (months == 1) "" else "s"} to go (by $eta, " +
                            "saving ${pace.asMoneyOrMask(currency, hidden)}/month $paceLabel)"
                    }
                    else -> "Set a monthly saving (or build up history) to estimate the finish date."
                }
                Text(
                    etaLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (editOpen) {
        GoalEditDialog(
            goal = goal,
            currency = currency,
            onDismiss = { editOpen = false },
            onSave = { target, monthly ->
                onSaveGoal(target, monthly)
                editOpen = false
            },
        )
    }
}

@Composable
private fun GoalEditDialog(
    goal: GoalState,
    currency: String,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit,
) {
    var target by remember {
        mutableStateOf(if (goal.targetAmount > 0.0) goal.targetAmount.toLong().toString() else "")
    }
    var monthly by remember {
        mutableStateOf(if (goal.monthlySaving > 0.0) goal.monthlySaving.toLong().toString() else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Savings goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = target,
                    onValueChange = { target = it },
                    label = { Text("Target amount ($currency)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = monthly,
                    onValueChange = { monthly = it },
                    label = { Text("Saving per month (optional)") },
                    supportingText = { Text("Empty = estimate from your recent months.") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = target.toDoubleOrNull()?.let { it > 0.0 } == true || target.isBlank(),
                onClick = { onSave(target.toDoubleOrNull() ?: 0.0, monthly.toDoubleOrNull() ?: 0.0) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Six months of income vs spend as paired columns, base-currency valued. */
@Composable
private fun CashFlowCard(state: DashboardUiState) {
    val monthly = state.stats.monthly
    val hidden = state.hideBalances
    val maxValue = monthly.maxOf { maxOf(it.income, it.spend) }.takeIf { it > 0.0 } ?: 1.0

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Cash flow — last 6 months", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                monthly.forEach { month ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            ChartBar(fraction = (month.income / maxValue).toFloat(), color = LedgerColors.credit)
                            ChartBar(fraction = (month.spend / maxValue).toFloat(), color = LedgerColors.debit)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            month.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            val current = monthly.last()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendDot(LedgerColors.credit)
                Text(
                    "In " + current.income.asMoneyOrMask(state.baseCurrency, hidden),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                LegendDot(LedgerColors.debit)
                Text(
                    "Out " + current.spend.asMoneyOrMask(state.baseCurrency, hidden),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "this month",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartBar(fraction: Float, color: Color) {
    val barHeight = (fraction.coerceIn(0f, 1f) * 88).dp
    Box(
        Modifier
            .width(12.dp)
            .height(maxOf(barHeight, 2.dp))
            .background(
                if (barHeight > 2.dp) color else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
            ),
    )
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape),
    )
}

/** This month's spend per category as proportional bars, biggest first. */
@Composable
private fun CategorySpendCard(state: DashboardUiState) {
    val spends = state.stats.categorySpends
    val hidden = state.hideBalances
    val maxAmount = spends.maxOf { it.amount }.takeIf { it > 0.0 } ?: 1.0
    val total = spends.sumOf { it.amount }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("This month by category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    total.asMoneyOrMask(state.baseCurrency, hidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            spends.forEach { entry ->
                Column {
                    Row {
                        Text(
                            "${entry.category.emoji}  ${entry.category.label}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.amount.asMoneyOrMask(state.baseCurrency, hidden),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((entry.amount / maxAmount).toFloat().coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

/** This month's spend per person as proportional bars, biggest first. */
@Composable
private fun PersonSpendCard(state: DashboardUiState) {
    val spends = state.stats.personSpends
    val hidden = state.hideBalances
    val maxAmount = spends.maxOf { it.amount }.takeIf { it > 0.0 } ?: 1.0
    val barColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
    )

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("This month by person", style = MaterialTheme.typography.titleSmall)
            spends.forEachIndexed { index, entry ->
                Column {
                    Row {
                        Text(
                            entry.person,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            entry.amount.asMoneyOrMask(state.baseCurrency, hidden),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp)),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((entry.amount / maxAmount).toFloat().coerceIn(0.02f, 1f))
                                .height(8.dp)
                                .background(barColors[index % barColors.size], RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}
