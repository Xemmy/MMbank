package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
)

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
    ) { transactions, accounts ->
        TransactionsUiState(transactions, accounts)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun assignAccount(transactionId: Long, accountId: Long?) {
        viewModelScope.launch { container.transactionRepository.assignAccount(transactionId, accountId) }
    }

    fun delete(transactionId: Long) {
        viewModelScope.launch { container.transactionRepository.delete(transactionId) }
    }
}

@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    if (state.transactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No transactions yet.\nCaptured bank messages will appear here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(state.transactions, key = { it.id }) { tx ->
            TransactionCard(
                tx = tx,
                accounts = state.accounts,
                onAssignAccount = { accountId -> viewModel.assignAccount(tx.id, accountId) },
                onDelete = { viewModel.delete(tx.id) },
            )
        }
    }
}

@Composable
private fun TransactionCard(
    tx: Transaction,
    accounts: List<Account>,
    onAssignAccount: (Long?) -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tx.merchant ?: tx.sender ?: tx.source.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        tx.timestampMillis.asDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tx.accountNumberSuffix != null) {
                        Text(
                            "Account: ${tx.accountNumberSuffix}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    (if (isCredit) "+" else "-") + tx.amount.asMoney(tx.currencyCode),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCredit) LedgerColors.credit else LedgerColors.debit,
                )
            }

            tx.rawMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { accountMenuOpen = true }) {
                        Text(accountName ?: "Assign account")
                    }
                    DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Unassigned") },
                            onClick = {
                                accountMenuOpen = false
                                onAssignAccount(null)
                            },
                        )
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = {
                                    accountMenuOpen = false
                                    onAssignAccount(account.id)
                                },
                            )
                        }
                    }
                }
                Text(
                    tx.matchedRuleName ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete transaction")
                }
            }
        }
    }
}
