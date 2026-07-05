package com.xemmy.mmbank.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.asQuantity

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // This single LazyColumn hosts three separate items() blocks (accounts,
        // crypto, recent transactions). Compose requires keys to be unique
        // across the WHOLE list, not just within one items() call — Account
        // and Transaction are different Room tables with independent
        // auto-increment ids, so an unprefixed `it.id` collides the moment an
        // account and a transaction share the same numeric id. Every key below
        // must stay prefixed per section.
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NetWorthCard(
                    netWorth = state.netWorthInBase,
                    currency = state.baseCurrency,
                    approximate = state.hasUnconvertedAmounts,
                    refreshing = state.refreshing,
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
                            Text(
                                row.balance.asMoney(row.account.currencyCode),
                                style = MaterialTheme.typography.titleMedium,
                                color = if (row.balance < 0) LedgerColors.debit else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (state.cryptoBalances.isNotEmpty()) {
                item { SectionTitle("Crypto (Binance spot) — ${state.cryptoTotalUsd.asMoney("USD")}") }
                items(state.cryptoBalances, key = { "crypto_${it.asset}" }) { asset ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(asset.asset, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    asset.total.asQuantity(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                asset.valueUsd?.toDouble()?.asMoney("USD") ?: "—",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
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
                    RecentTransactionRow(tx)
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
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Net worth", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (approximate) "≈ " else "") + netWorth.asMoney(currency),
                    style = MaterialTheme.typography.headlineMedium,
                    color = if (netWorth < 0) LedgerColors.debit else MaterialTheme.colorScheme.primary,
                )
                if (approximate) {
                    Text(
                        "Some balances lack an exchange rate — refresh rates in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (refreshing) {
                CircularProgressIndicator(Modifier.height(24.dp))
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

@Composable
fun RecentTransactionRow(tx: Transaction) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                tx.merchant ?: tx.sender ?: tx.source.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${tx.source.name.lowercase().replace('_', ' ')} · ${tx.timestampMillis.asDateTime()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            (if (isCredit) "+" else "-") + tx.amount.asMoney(tx.currencyCode),
            style = MaterialTheme.typography.bodyLarge,
            color = if (isCredit) LedgerColors.credit else LedgerColors.debit,
        )
    }
}
