package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
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
    val senderLabels: Map<String, String> = emptyMap(),
)

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.senderLabelRepository.observeLabels(),
    ) { transactions, accounts, senderLabels ->
        TransactionsUiState(transactions, accounts, senderLabels)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun assignAccount(transactionId: Long, accountId: Long?) {
        viewModelScope.launch { container.transactionRepository.assignAccount(transactionId, accountId) }
    }

    fun delete(transactionId: Long) {
        viewModelScope.launch { container.transactionRepository.delete(transactionId) }
    }

    fun setSenderLabel(sender: String, label: String) {
        viewModelScope.launch { container.senderLabelRepository.setLabel(sender, label) }
    }

    fun clearSenderLabel(sender: String) {
        viewModelScope.launch { container.senderLabelRepository.clearLabel(sender) }
    }

    /** Resolved display name: user override wins, then the built-in bank map, then the raw sender. */
    fun bankNameFor(sender: String?, labels: Map<String, String>): String? {
        if (sender.isNullOrBlank()) return null
        return labels[sender] ?: BankNames.displayName(sender)
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
                bankName = viewModel.bankNameFor(tx.sender, state.senderLabels),
                onAssignAccount = { accountId -> viewModel.assignAccount(tx.id, accountId) },
                onRenameSender = { label -> tx.sender?.let { viewModel.setSenderLabel(it, label) } },
                onResetSender = { tx.sender?.let { viewModel.clearSenderLabel(it) } },
                onDelete = { viewModel.delete(tx.id) },
            )
        }
    }
}

@Composable
private fun TransactionCard(
    tx: Transaction,
    accounts: List<Account>,
    bankName: String?,
    onAssignAccount: (Long?) -> Unit,
    onRenameSender: (String) -> Unit,
    onResetSender: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // Primary line: merchant if we have one, else the friendly bank name.
                    Text(
                        tx.merchant ?: bankName ?: tx.sender ?: tx.source.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // Bank + account line, tappable to rename the bank/sender.
                    val bankLine = buildString {
                        append(bankName ?: tx.sender ?: tx.source.name)
                        tx.accountNumberSuffix?.takeIf { it.isNotBlank() }?.let {
                            append(" · ••")
                            append(it)
                        }
                    }
                    Text(
                        bankLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = tx.sender != null) { renameOpen = true },
                    )
                    Text(
                        tx.timestampMillis.asDateTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

    if (renameOpen && tx.sender != null) {
        RenameSenderDialog(
            sender = tx.sender,
            current = bankName ?: tx.sender,
            onDismiss = { renameOpen = false },
            onSave = { label ->
                onRenameSender(label)
                renameOpen = false
            },
            onReset = {
                onResetSender()
                renameOpen = false
            },
        )
    }
}

@Composable
private fun RenameSenderDialog(
    sender: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var label by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Show a friendly name for “$sender” everywhere it appears.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onSave(label.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset to default") } },
    )
}
