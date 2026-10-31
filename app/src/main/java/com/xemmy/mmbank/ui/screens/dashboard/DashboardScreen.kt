package com.xemmy.mmbank.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.intelligence.AiWeeklyDigestGenerator
import com.xemmy.mmbank.domain.intelligence.CashFlowForecaster
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.FinancialHealthMetrics
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.screens.accounts.UnassignedTotal
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.AccountCarousel
import com.xemmy.mmbank.ui.components.ManageAccountsBottomSheet
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import com.xemmy.mmbank.ui.util.asMoneyWholeOrMask
import com.xemmy.mmbank.ui.util.asQuantity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home: net worth, account balances, crypto and recent activity. Stats, the
 * savings goal and charts live on the Dashboard tab (InsightsScreen).
 */
@Composable
fun DashboardScreen(
    onOpenReview: () -> Unit = {},
    onOpenUnassigned: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val weeklyDigest by viewModel.weeklyDigest.collectAsStateWithLifecycle()
    val financialHealth by viewModel.financialHealth.collectAsStateWithLifecycle()
    val pendingReviewCount by viewModel.pendingReviewCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val hidden = state.hideBalances
    var showManageAccounts by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        DashboardHeader()
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
            if (pendingReviewCount > 0) {
                item(key = "review_badge") {
                    ReviewBadgeCard(count = pendingReviewCount, onClick = onOpenReview)
                }
            }

            weeklyDigest?.let { digest ->
                item(key = "weekly_digest") {
                    WeeklyDigestCard(digest = digest, currency = state.baseCurrency, hidden = hidden)
                }
            }

            item(key = "net_worth") {
                NetWorthCard(
                    netWorth = state.netWorthInBase,
                    currency = state.baseCurrency,
                    approximate = state.hasUnconvertedAmounts,
                    cryptoExcluded = state.cryptoExcludedFromTotal,
                    creditCardExcluded = state.creditCardExcludedFromTotal,
                    refreshing = state.refreshing,
                    hidden = hidden,
                    onToggleHide = viewModel::toggleHideBalances,
                    onRefresh = viewModel::refreshRemote,
                )
            }

            financialHealth.metrics?.let { metrics ->
                item(key = "financial_health") {
                    FinancialHealthCard(metrics, financialHealth.cashFlow, state.baseCurrency, hidden)
                }
            }

            item(key = "account_carousel") {
                AccountCarousel(
                    accounts = state.accounts,
                    selectedAccountId = state.selectedAccountId,
                    hidden = hidden,
                    allAccountsTotal = state.fiatTotalInBase,
                    baseCurrency = state.baseCurrency,
                    onSelectAccount = viewModel::selectAccount,
                    onManageAccounts = { showManageAccounts = true },
                )
            }

            // Money in no account is in no balance above -- say so rather than
            // letting it silently vanish from the net worth figure.
            if (!state.unassigned.isEmpty) {
                item(key = "unassigned_total") {
                    UnassignedRow(
                        unassigned = state.unassigned,
                        hidden = hidden,
                        onClick = onOpenUnassigned,
                    )
                }
            }

            state.selectedAccountId?.let { accountId ->
                val accountName = state.accounts.firstOrNull { it.account.id == accountId }?.account?.name
                item(key = "account_filter_chip") {
                    AccountFilterChip(
                        label = accountName ?: "Account",
                        onClear = { viewModel.selectAccount(null) },
                    )
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

    if (showManageAccounts) {
        ManageAccountsBottomSheet(
            accounts = state.manageableAccounts,
            hidden = hidden,
            onRename = viewModel::renameAccount,
            onToggleVisibility = viewModel::toggleAccountVisibility,
            onDismiss = { showManageAccounts = false },
        )
    }
}

/** Dashboard header: screen title. Ambient background style now lives in Settings > Appearance. */
@Composable
private fun DashboardHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Home", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

/** Compact "filtered by [label]" chip above the feed, with an X to clear the carousel's account selection. */
@Composable
private fun AccountFilterChip(label: String, onClear: () -> Unit) {
    AssistChip(
        onClick = onClear,
        label = { Text("Filtered: $label") },
        trailingIcon = {
            Icon(Icons.Filled.Close, contentDescription = "Clear filter", modifier = Modifier.size(16.dp))
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    )
}

/** "N items to review" entry point into the High-Detail Review Transactions screen; hidden by the caller when count is 0. */
@Composable
private fun ReviewBadgeCard(count: Int, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                "$count ${if (count == 1) "item" else "items"} to review",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * "N transactions aren't in any account" -- money that is in the ledger but in
 * no balance, so it is missing from the net worth above with nothing to
 * explain the gap. Tapping opens Activity already filtered to those rows.
 *
 * Shaped like [ReviewBadgeCard]: both are "here is a pile of things needing
 * your attention, tap to go fix them".
 */
@Composable
private fun UnassignedRow(
    unassigned: UnassignedTotal,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.HelpOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    if (unassigned.entryCount == 1) {
                        "1 transaction isn't in any account"
                    } else {
                        "${unassigned.entryCount} transactions aren't in any account"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    unassigned.totalsByCurrency.entries.joinToString("  ") { (code, total) ->
                        total.asMoneyOrMask(code, hidden)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetWorthCard(
    netWorth: Double,
    currency: String,
    approximate: Boolean,
    cryptoExcluded: Boolean,
    creditCardExcluded: Boolean,
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
                AmountText(
                    display = if (hidden) AMOUNT_MASK else (if (approximate) "≈ " else "") + netWorth.asMoney(currency),
                    color = if (!hidden && netWorth < 0) LedgerColors.debit else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (approximate && !hidden) {
                    Text(
                        "Some balances lack an exchange rate — refresh rates in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (cryptoExcluded && !hidden) {
                    Text(
                        "Crypto excluded from total — change in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (creditCardExcluded && !hidden) {
                    Text(
                        "Credit card excluded from total — change in Settings.",
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
 * "AI Weekly Digest" hero card at the top of the Dashboard: a plain-English
 * read of this week's spend vs last week's plus the single biggest expense —
 * e.g. "Spent 12% less this week ($320 vs $365). Top spend: $85 at Target."
 */
@Composable
private fun WeeklyDigestCard(
    digest: AiWeeklyDigestGenerator.WeeklyDigest,
    currency: String,
    hidden: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("This week", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    weeklyDigestText(digest, currency, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Renders a [AiWeeklyDigestGenerator.WeeklyDigest] into plain English, same split as [SmartInsight]'s formatting. */
private fun weeklyDigestText(digest: AiWeeklyDigestGenerator.WeeklyDigest, currency: String, hidden: Boolean): String {
    val trend = digest.percentChange?.let { change ->
        val direction = if (change > 0) "more" else "less"
        "Spent ${abs(change).roundToInt()}% $direction this week " +
            "(${digest.thisWeekSpend.asMoneyOrMask(currency, hidden)} vs ${digest.lastWeekSpend.asMoneyOrMask(currency, hidden)})"
    } ?: "Spent ${digest.thisWeekSpend.asMoneyOrMask(currency, hidden)} this week"
    val top = digest.topSpend?.let { top ->
        " Top spend: ${top.amount.asMoneyOrMask(currency, hidden)} at ${top.merchant}."
    } ?: ""
    return "$trend.$top"
}

/**
 * Financial Health Summary Card: this month's savings rate, cash runway and
 * net cash flow (see [FinancialHealthEngine]), plus a "safe to spend until
 * payday" cash-flow forecast (see [CashFlowForecaster]) when a payday can be
 * determined.
 */
@Composable
private fun FinancialHealthCard(
    metrics: FinancialHealthMetrics,
    cashFlow: CashFlowForecaster.SafeToSpendResult?,
    currency: String,
    hidden: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Financial health", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                HealthMetricTile(
                    label = "Savings rate",
                    value = "${metrics.savingsRatePercent.roundToInt()}%",
                    valueColor = if (metrics.savingsRatePercent >= 0) LedgerColors.credit else LedgerColors.debit,
                )
                HealthMetricTile(
                    label = "Cash runway",
                    value = metrics.cashRunwayMonths?.let { "${it.roundToInt()} mo" } ?: "—",
                    valueColor = MaterialTheme.colorScheme.onSurface,
                )
                HealthMetricTile(
                    label = "Net cash flow",
                    value = metrics.netCashFlow.asMoneyWholeOrMask(currency, hidden),
                    valueColor = if (metrics.netCashFlow >= 0) LedgerColors.credit else LedgerColors.debit,
                )
            }
            if (cashFlow != null) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(10.dp))
                val paydayLabel = Instant.ofEpochMilli(cashFlow.nextPaydayMillis).atZone(ZoneId.systemDefault())
                    .toLocalDate().format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
                Text(
                    "Safe to spend until payday ($paydayLabel, ${cashFlow.daysUntilPayday}d): " +
                        "${cashFlow.safeToSpend.toDouble().asMoneyOrMask(currency, hidden)} " +
                        "(${cashFlow.dailyAllowance.toDouble().asMoneyOrMask(currency, hidden)}/day)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HealthMetricTile(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        AmountText(display = value, color = valueColor, style = MaterialTheme.typography.titleMedium)
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
                                    if (hidden) AMOUNT_MASK else "≈ ${wallet.balanceBtc.asQuantity()} BTC",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                if (hidden) AMOUNT_MASK else wallet.valueUsd?.toDouble()?.asMoney("USD") ?: "—",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }

                    if (state.cryptoBalances.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                        Text(
                            "HOLDINGS (SPOT · FUNDING · EARN)",
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
                                        if (hidden) AMOUNT_MASK else asset.total.asQuantity(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (hidden) AMOUNT_MASK else asset.valueUsd?.toDouble()?.asMoney("USD") ?: "—",
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
    // Same audit-friendly muting as the Activity feed: excluded rows stay
    // listed but render dimmed with a slashed-eye marker.
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (tx.excludeFromStats) 0.55f else 1f)
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
        AmountText(
            display = if (hidden) AMOUNT_MASK else (if (isCredit) "+" else "-") + tx.amount.asMoney(tx.currencyCode),
            color = if (isCredit) LedgerColors.credit else LedgerColors.debit,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (tx.excludeFromStats) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = "Excluded from analytics",
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
