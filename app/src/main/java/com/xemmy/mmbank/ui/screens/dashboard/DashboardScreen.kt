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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
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
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.asMoneyOrMask
import com.xemmy.mmbank.ui.util.asQuantity

/**
 * Home: net worth, account balances, crypto and recent activity. Stats, the
 * savings goal and charts live on the Dashboard tab (InsightsScreen).
 */
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
                    cryptoExcluded = state.cryptoExcludedFromTotal,
                    creditCardExcluded = state.creditCardExcludedFromTotal,
                    refreshing = state.refreshing,
                    hidden = hidden,
                    onToggleHide = viewModel::toggleHideBalances,
                    onRefresh = viewModel::refreshRemote,
                )
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
                            AmountText(
                                display = row.balance.asMoneyOrMask(row.account.currencyCode, hidden),
                                color = if (row.balance < 0) LedgerColors.debit else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
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
