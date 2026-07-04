package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountWithBalance(val account: Account, val balance: Double)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<List<AccountWithBalance>> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeNetByAccount(),
    ) { accounts, netByAccount ->
        accounts.map { account ->
            AccountWithBalance(
                account = account,
                balance = account.openingBalance.toDouble() + (netByAccount[account.id] ?: 0.0),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(account: Account) {
        viewModelScope.launch { container.accountRepository.save(account) }
    }

    fun archive(account: Account) {
        viewModelScope.launch { container.accountRepository.archive(account) }
    }
}

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rows by viewModel.uiState.collectAsStateWithLifecycle()
    // null = closed; an Account with id 0 = "new"; an existing Account = "edit".
    var editing by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Account(name = "", type = AccountType.CHECKING, currencyCode = "USD") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No accounts yet. Add your checking accounts, credit cards and loans, " +
                            "then assign captured transactions to them.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.account.id }) { row ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { editing = row.account },
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(row.account.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append(row.account.type.name.replace('_', ' '))
                                    append(" · ")
                                    append(row.account.currencyCode)
                                    row.account.accountNumber?.takeIf { it.isNotBlank() }?.let {
                                        append(" · ••")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            row.balance.asMoney(row.account.currencyCode),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (row.balance < 0) LedgerColors.debit else MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = { viewModel.archive(row.account) }) {
                            Icon(Icons.Filled.Archive, contentDescription = "Archive account")
                        }
                    }
                }
            }
        }
    }

    editing?.let { account ->
        AccountDialog(
            existing = account,
            onDismiss = { editing = null },
            onSave = { saved ->
                viewModel.save(saved)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDialog(
    existing: Account,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    val isNew = existing.id == 0L
    var name by remember { mutableStateOf(existing.name) }
    var type by remember { mutableStateOf(existing.type) }
    var currency by remember { mutableStateOf(existing.currencyCode) }
    var openingBalance by remember { mutableStateOf(existing.openingBalance.toPlainString()) }
    var accountNumber by remember { mutableStateOf(existing.accountNumber.orEmpty()) }
    var typeMenuOpen by remember { mutableStateOf(false) }

    val opening = openingBalance.toBigDecimalOrNull()
    val valid = name.isNotBlank() && currency.trim().length == 3 && opening != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New account" else "Edit account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                ExposedDropdownMenuBox(expanded = typeMenuOpen, onExpandedChange = { typeMenuOpen = it }) {
                    OutlinedTextField(
                        value = type.name.replace('_', ' '),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AccountType.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.name.replace('_', ' ')) },
                                onClick = {
                                    type = entry
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Currency (ISO code)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = openingBalance,
                    onValueChange = { openingBalance = it },
                    label = { Text("Opening balance (negative for debt)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    label = { Text("Account/card number (last digits, e.g. 617)") },
                    supportingText = {
                        Text("Used to auto-assign captured transactions to this account.")
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        existing.copy(
                            name = name.trim(),
                            type = type,
                            currencyCode = currency.trim().uppercase(),
                            openingBalance = opening ?: BigDecimal.ZERO,
                            accountNumber = accountNumber.trim().takeIf { it.isNotBlank() },
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
