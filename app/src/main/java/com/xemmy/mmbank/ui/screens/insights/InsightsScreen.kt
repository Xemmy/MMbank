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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.DonutChart
import com.xemmy.mmbank.ui.screens.budgets.CategoryBudgetSummaryCard
import com.xemmy.mmbank.ui.screens.dashboard.DashboardDateRange
import com.xemmy.mmbank.ui.screens.dashboard.DashboardUiState
import com.xemmy.mmbank.ui.screens.dashboard.DashboardViewModel
import com.xemmy.mmbank.ui.screens.dashboard.GoalState
import com.xemmy.mmbank.ui.screens.dashboard.InsightTone
import com.xemmy.mmbank.ui.screens.dashboard.SmartInsight
import com.xemmy.mmbank.ui.screens.dashboard.netFlow
import com.xemmy.mmbank.ui.theme.GlowPalette
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.ObsidianCard
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.glowBackdrop
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import com.xemmy.mmbank.ui.util.asMoneyWholeOrMask
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The Dashboard tab: a date-filterable period with stat tiles, smart-analysis
 * insights, savings goal, a 6-month cash-flow chart, and per-category/person
 * spend, plus the doorway to cashback rewards. Reuses [DashboardViewModel],
 * which already assembles stats/goal/hide state.
 */
@Composable
fun InsightsScreen(
    onOpenRewards: () -> Unit,
    onOpenSubscriptions: () -> Unit = {},
    onOpenAnalytics: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenReport: () -> Unit = {},
    onOpenCopilot: () -> Unit = {},
    onOpenBills: () -> Unit = {},
    onOpenSplits: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hasData = state.stats.monthly.any { it.income > 0.0 || it.spend > 0.0 }

    LazyColumn(
        modifier = Modifier.fillMaxSize().obsidianCanvas(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Low-profile switch between "clean" analytics (excluded rows filtered
        // out, the default) and the raw ledger with every transaction counted.
        // The (i) reveals what "Raw ledger" means, since it isn't self-evident.
        item(key = "ledger_toggle") {
            var showInfo by remember { mutableStateOf(false) }
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { showInfo = !showInfo }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "What is Raw ledger?",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(4.dp))
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
                if (showInfo) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Includes all transactions, like balance adjustments and cashback, " +
                            "which are normally hidden from spending totals.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "date_filter_row") {
            DateRangeFilterRow(selected = state.dateRange, onSelect = viewModel::setDateRange)
        }

        item(key = "period_tiles") { PeriodTiles(state) }

        if (state.stats.insights.isNotEmpty()) {
            item(key = "insights_card") { SmartInsightsCard(state) }
        }

        item(key = "goal_card") { GoalCard(state = state, onSaveGoal = viewModel::saveGoal) }

        // Renders nothing until at least one category carries a monthly cap.
        item(key = "category_budgets_card") { CategoryBudgetSummaryCard(onOpenBudgets = onOpenBudgets) }

        if (hasData) {
            item(key = "cashflow_card") { CashFlowCard(state) }
            if (state.stats.categorySlices.isNotEmpty()) {
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

        item(key = "analytics_row") {
            NavigationRow(
                icon = Icons.Outlined.Insights,
                title = "Spending analytics",
                subtitle = "Heatmap, category donut and pacing gauge",
                onClick = onOpenAnalytics,
            )
        }
        item(key = "subscriptions_row") {
            NavigationRow(
                icon = Icons.Filled.Repeat,
                title = "Subscriptions radar",
                subtitle = "Detected recurring charges and renewal dates",
                onClick = onOpenSubscriptions,
            )
        }
        item(key = "bills_row") {
            NavigationRow(
                icon = Icons.Filled.Receipt,
                title = "Upcoming bills",
                subtitle = "Manually tracked bills, due dates and reminders",
                onClick = onOpenBills,
            )
        }
        item(key = "goals_row") {
            NavigationRow(
                icon = Icons.Filled.Savings,
                title = "Savings goals",
                subtitle = "Progress bars, debt payoff and surplus allocation",
                onClick = onOpenGoals,
            )
        }
        item(key = "splits_row") {
            NavigationRow(
                icon = Icons.Filled.Groups,
                title = "Splits & groups",
                subtitle = "Shared expenses, balances and settle-up suggestions",
                onClick = onOpenSplits,
            )
        }
        item(key = "budgets_row") {
            NavigationRow(
                icon = Icons.Outlined.PieChart,
                title = "Categories",
                subtitle = "Manage categories and hierarchy, with monthly caps and alerts at 80% and 100%",
                onClick = onOpenBudgets,
            )
        }
        item(key = "report_row") {
            NavigationRow(
                icon = Icons.Outlined.Description,
                title = "Monthly report",
                subtitle = "A month's statement, shareable as a file",
                onClick = onOpenReport,
            )
        }
        item(key = "copilot_row") {
            NavigationRow(
                icon = Icons.Filled.SmartToy,
                title = "Financial Copilot",
                subtitle = "Ask questions about your spending in plain English",
                onClick = onOpenCopilot,
            )
        }
        item(key = "rewards_row") { RewardsRow(onOpenRewards) }
    }
}

/** Generic doorway row into a secondary screen, sharing [RewardsRow]'s layout with a swappable icon/label. */
@Composable
private fun NavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    ObsidianCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
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
 * Chips for the Dashboard's own date filter (month-oriented, unlike the
 * Activity tab's day-oriented presets) — drives [DashboardUiState.stats]'s
 * period-scoped fields: the stat tiles, category/person breakdowns, and the
 * smart-analysis insights. The 6-month cash-flow trend chart is unaffected.
 */
@Composable
private fun DateRangeFilterRow(selected: DashboardDateRange, onSelect: (DashboardDateRange) -> Unit) {
    var customOpen by remember { mutableStateOf(false) }
    val presets = listOf(
        "This month" to DashboardDateRange.ThisMonth,
        "3mo" to DashboardDateRange.Last3Months,
        "6mo" to DashboardDateRange.Last6Months,
        "YTD" to DashboardDateRange.YearToDate,
        "1Y" to DashboardDateRange.Last12Months,
        "All" to DashboardDateRange.AllTime,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(presets) { (label, range) ->
            FilterChip(
                selected = selected == range,
                onClick = { onSelect(range) },
                label = { Text(label) },
            )
        }
        item {
            val isCustom = selected is DashboardDateRange.Custom
            FilterChip(
                selected = isCustom,
                onClick = { customOpen = true },
                label = {
                    Text(
                        if (isCustom) {
                            val custom = selected as DashboardDateRange.Custom
                            "${custom.start.format(chipDateFormat)} – ${custom.end.format(chipDateFormat)}"
                        } else {
                            "Custom"
                        },
                    )
                },
                leadingIcon = {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }

    if (customOpen) {
        DashboardCustomDateRangeDialog(
            initial = selected as? DashboardDateRange.Custom,
            onDismiss = { customOpen = false },
            onSelect = { start, end ->
                onSelect(DashboardDateRange.Custom(start, end))
                customOpen = false
            },
        )
    }
}

private val chipDateFormat = DateTimeFormatter.ofPattern("d MMM")

/** Twin-calendar picker for the Dashboard's custom date range. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardCustomDateRangeDialog(
    initial: DashboardDateRange.Custom?,
    onDismiss: () -> Unit,
    onSelect: (LocalDate, LocalDate) -> Unit,
) {
    // The Material picker works in UTC day-millis, so conversions stay in UTC.
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

/** Smart analysis of the selected period's money flow: trend, savings rate, top mover, standouts. */
@Composable
private fun SmartInsightsCard(state: DashboardUiState) {
    val hidden = state.hideBalances
    val currency = state.baseCurrency

    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Smart insights", style = MaterialTheme.typography.titleSmall)
            state.stats.insights.forEach { insight ->
                Row(verticalAlignment = Alignment.Top) {
                    val dotColor = when (insight.tone) {
                        InsightTone.POSITIVE -> ObsidianTokens.credit
                        InsightTone.NEGATIVE -> ObsidianTokens.debit
                        InsightTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        Modifier
                            .padding(top = 5.dp)
                            .size(6.dp)
                            .background(dotColor, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        insightText(insight, currency, hidden),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** Renders one [SmartInsight] into plain English, applying money formatting and the hide-balances mask. */
private fun insightText(insight: SmartInsight, currency: String, hidden: Boolean): String = when (insight) {
    is SmartInsight.SpendTrend -> {
        val direction = if (insight.percentChange > 0) "up" else "down"
        "Spending is ${abs(insight.percentChange).roundToInt()}% $direction vs. the previous period"
    }
    is SmartInsight.SavingsRate ->
        if (insight.ratePercent >= 0) {
            "You saved ${insight.ratePercent.roundToInt()}% of your income this period"
        } else {
            "You spent ${(-insight.ratePercent).roundToInt()}% more than you earned this period"
        }
    is SmartInsight.TopCategory ->
        "${insight.category.name} is your biggest expense at ${insight.sharePercent.roundToInt()}% " +
            "(${insight.amount.asMoneyOrMask(currency, hidden)})"
    is SmartInsight.CategorySpike ->
        "${insight.category.name} spending jumped ${insight.percentChange.roundToInt()}% to " +
            insight.amount.asMoneyOrMask(currency, hidden)
    is SmartInsight.NoIncomeSpend ->
        "No income recorded this period — ${insight.spend.asMoneyOrMask(currency, hidden)} went out"
    is SmartInsight.BiggestExpense ->
        "Your biggest expense was ${insight.amount.asMoneyOrMask(currency, hidden)} at ${insight.merchant}"
    is SmartInsight.UnusualCharge ->
        "Unusual: ${insight.amount.asMoneyOrMask(insight.currencyCode, hidden)} at ${insight.merchant} is " +
            "${String.format(java.util.Locale.US, "%.1f", insight.ratio)}× your usual " +
            "(about ${insight.usual.asMoneyOrMask(insight.currencyCode, hidden)})"
}

/** Income / Spent / Net for the selected period as three stat tiles. */
@Composable
private fun PeriodTiles(state: DashboardUiState) {
    val income = state.stats.periodIncome
    val spend = state.stats.periodSpend
    val net = netFlow(income, spend)
    val hidden = state.hideBalances

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            "Income", income.asMoneyWholeOrMask(state.baseCurrency, hidden), ObsidianTokens.credit,
            Modifier.weight(1f), glow = GlowTones.forDirection(isCredit = true),
        )
        StatTile(
            "Spent", spend.asMoneyWholeOrMask(state.baseCurrency, hidden), ObsidianTokens.debit,
            Modifier.weight(1f), glow = GlowTones.forDirection(isCredit = false),
        )
        StatTile(
            "Net",
            net.asMoneyWholeOrMask(state.baseCurrency, hidden),
            if (net < 0) ObsidianTokens.debit else MaterialTheme.colorScheme.primary,
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    glow: GlowTone? = null,
) {
    ObsidianCard(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .glowBackdrop(glow, GlowPalette.DIRECTION_ALPHA)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
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
    ObsidianCard(Modifier.fillMaxWidth()) {
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

    ObsidianCard(Modifier.fillMaxWidth()) {
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

    ObsidianCard(Modifier.fillMaxWidth()) {
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
                            ChartBar(fraction = (month.income / maxValue).toFloat(), color = ObsidianTokens.credit)
                            ChartBar(fraction = (month.spend / maxValue).toFloat(), color = ObsidianTokens.debit)
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
                LegendDot(ObsidianTokens.credit)
                Text(
                    "In " + current.income.asMoneyOrMask(state.baseCurrency, hidden),
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.width(8.dp))
                LegendDot(ObsidianTokens.debit)
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

/** This month's spend per category as a donut chart with a legend, biggest first. */
@Composable
private fun CategorySpendCard(state: DashboardUiState) {
    val slices = state.stats.categorySlices
    val hidden = state.hideBalances
    val total = slices.sumOf { it.amount }

    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${state.stats.periodLabel} by category",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    total.asMoneyOrMask(state.baseCurrency, hidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DonutChart(slices = slices, modifier = Modifier.size(120.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEach { slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(Color(slice.color), RoundedCornerShape(3.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(slice.name, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(16.dp))
                            Text(
                                slice.amount.asMoneyOrMask(state.baseCurrency, hidden),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
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

    ObsidianCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${state.stats.periodLabel} by person", style = MaterialTheme.typography.titleSmall)
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
