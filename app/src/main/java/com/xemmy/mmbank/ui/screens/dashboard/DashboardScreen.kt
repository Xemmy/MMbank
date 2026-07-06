package com.xemmy.mmbank.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.asQuantity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private const val MASK = "••••••"

/** All dashboard money goes through this so the hide-balances eye works everywhere. */
private fun Double.asMoneyOrMask(currencyCode: String, hidden: Boolean): String =
    if (hidden) MASK else asMoney(currencyCode)

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hidden = state.hideBalances

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // This single LazyColumn hosts several separate items() blocks (accounts,
        // crypto, recent transactions). Compose requires keys to be unique
        // across the WHOLE list, not just within one items() call — Account
        // and Transaction are different Room tables with independent
        // auto-increment ids, so an unprefixed `it.id` collides the moment an
        // account and a transaction share the same numeric id. Every key below
        // must stay prefixed per section.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NetWorthCard(
                    netWorth = state.netWorthInBase,
                    currency = state.baseCurrency,
                    approximate = state.hasUnconvertedAmounts,
                    refreshing = state.refreshing,
                    hidden = hidden,
                    onToggleHide = viewModel::toggleHideBalances,
                    onRefresh = viewModel::refreshRemote,
                )
            }

            item(key = "goal_card") {
                GoalCard(state = state, onSaveGoal = viewModel::saveGoal)
            }

            if (state.stats.monthly.any { it.income > 0.0 || it.spend > 0.0 }) {
                item { SectionTitle("Insights") }
                item(key = "cashflow_card") { CashFlowCard(state) }
                if (state.stats.personSpends.isNotEmpty()) {
                    item(key = "person_card") { PersonSpendCard(state) }
                }
            }

            if (state.accounts.isNotEmpty()) {
                item { SectionTitle("Accounts") }
                items(state.accounts, key = { "account_${it.account.id}" }) { row ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(row.account.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    row.account.type.name.replace('_', ' ').lowercase()
                                        .replaceFirstChar { it.uppercase() },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                row.balance.asMoneyOrMask(row.account.currencyCode, hidden),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (row.balance < 0) LedgerColors.debit else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (state.cryptoBalances.isNotEmpty() || state.cryptoWallets.isNotEmpty()) {
                item { SectionTitle("Crypto (Binance)") }
                item(key = "crypto_group") {
                    CryptoCard(state)
                }
            }

            item { SectionTitle("Recent activity") }
            if (state.recent.isEmpty()) {
                item {
                    Text(
                        "No transactions captured yet. Enable notification access and SMS " +
                            "permission in Settings, then wait for a bank message.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.recent, key = { "recent_${it.id}" }) { tx ->
                    // User-defined label wins, then the built-in bank map — so
                    // "com.adib.mobile" shows as "ADIB", not the raw package.
                    val bankName = tx.sender?.let { state.senderLabels[it] ?: BankNames.displayName(it) }
                    RecentTransactionRow(tx, bankName, hidden)
                    HorizontalDivider()
                }
            }
        }
        SnackbarHost(snackbar)
    }
}

@Composable
private fun NetWorthCard(
    netWorth: Double,
    currency: String,
    approximate: Boolean,
    refreshing: Boolean,
    hidden: Boolean,
    onToggleHide: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Net worth", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (hidden) MASK else (if (approximate) "≈ " else "") + netWorth.asMoney(currency),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (!hidden && netWorth < 0) LedgerColors.debit else MaterialTheme.colorScheme.primary,
                )
                if (approximate && !hidden) {
                    Text(
                        "Some balances lack an exchange rate — refresh rates in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggleHide) {
                Icon(
                    if (hidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (hidden) "Show balances" else "Hide balances",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh rates and balances")
                }
            }
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
                        "the dashboard will track progress and estimate the finish date.",
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

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/**
 * Collapsible Binance overview. Collapsed it is a single total line; expanded
 * it lists every wallet with a balance (Earn, Funding, Trading Bots, …) and
 * the spot per-asset detail — both pre-sorted by value in the view model.
 */
@Composable
private fun CryptoCard(state: DashboardUiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val hidden = state.hideBalances
    val summary = buildList {
        if (state.cryptoWallets.isNotEmpty()) add("${state.cryptoWallets.size} wallets")
        if (state.cryptoBalances.isNotEmpty()) add("${state.cryptoBalances.size} spot assets")
    }.joinToString(" · ")

    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Total across wallets", style = MaterialTheme.typography.titleSmall)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    state.cryptoTotalUsd.asMoneyOrMask("USD", hidden),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(bottom = 6.dp)) {
                    state.cryptoWallets.forEach { wallet ->
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(wallet.walletName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (hidden) MASK else "≈ ${wallet.balanceBtc.asQuantity()} BTC",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (hidden) MASK else wallet.valueUsd?.toDouble()?.asMoney("USD") ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }

                    if (state.cryptoBalances.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Text(
                            "SPOT HOLDINGS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 10.dp),
                        )
                        state.cryptoBalances.forEach { asset ->
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(asset.asset, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (hidden) MASK else asset.total.asQuantity(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (hidden) MASK else asset.valueUsd?.toDouble()?.asMoney("USD") ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecentTransactionRow(tx: Transaction, bankName: String? = null, hidden: Boolean = false) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    val title = tx.merchant ?: bankName ?: tx.sender ?: tx.source.name
    val subtitle = listOfNotNull(
        tx.person,
        bankName?.takeIf { it != title },
        tx.source.name.lowercase().replace('_', ' '),
        tx.timestampMillis.asDateTime(),
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (hidden) MASK else (if (isCredit) "+" else "-") + tx.amount.asMoney(tx.currencyCode),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCredit) LedgerColors.credit else LedgerColors.debit,
        )
    }
}
