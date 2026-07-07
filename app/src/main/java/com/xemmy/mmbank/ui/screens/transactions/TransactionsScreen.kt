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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.xemmy.mmbank.ui.util.recoverAndRetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Quick date ranges for the Activity list. */
enum class DateFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    WEEK("7 days"),
    MONTH("This month"),
}

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val senderLabels: Map<String, String> = emptyMap(),
    val dateFilter: DateFilter = DateFilter.ALL,
    /** Every person seen so far, for the quick-assign menu. */
    val knownPersons: List<String> = emptyList(),
    /** True when the filter (not an empty ledger) hid everything. */
    val hasAnyTransactions: Boolean = false,
)

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    private val dateFilter = MutableStateFlow(DateFilter.ALL)

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.senderLabelRepository.observeLabels(),
        dateFilter,
    ) { transactions, accounts, senderLabels, filter ->
        val cutoff = filterCutoffMillis(filter)
        val visible = if (cutoff == null) transactions else transactions.filter { it.timestampMillis >= cutoff }
        // The menu is fed ONLY by the saved list (Settings > Preferences >
        // People) so removing a person there really removes it here. Names
        // dedupe case-insensitively to avoid "Ahmed" vs "ahmed" doubles.
        val persons = buildList {
            addAll(container.preferences.persons)
            container.preferences.defaultPerson?.let(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sorted()
        TransactionsUiState(
            transactions = visible,
            accounts = accounts,
            senderLabels = senderLabels,
            dateFilter = filter,
            knownPersons = persons,
            hasAnyTransactions = transactions.isNotEmpty(),
        )
    }.recoverAndRetry("TransactionsViewModel", container) {
        TransactionsUiState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun setDateFilter(filter: DateFilter) {
        dateFilter.value = filter
    }

    /** Start-of-range in epoch millis; null = no lower bound. */
    private fun filterCutoffMillis(filter: DateFilter): Long? {
        if (filter == DateFilter.ALL) return null
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        val start = when (filter) {
            DateFilter.TODAY -> today
            DateFilter.WEEK -> today.minusDays(6)
            DateFilter.MONTH -> today.withDayOfMonth(1)
            DateFilter.ALL -> return null
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun safeWrite(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { container.recordError("TransactionsViewModel.$what", it) }
        }
    }

    fun assignAccount(transactionId: Long, accountId: Long?) =
        safeWrite("assignAccount") { container.transactionRepository.assignAccount(transactionId, accountId) }

    fun setPerson(transactionId: Long, person: String?) =
        safeWrite("setPerson") {
            // A newly typed name joins the saved list so it's reusable and exported.
            person?.let(container.preferences::addPerson)
            container.transactionRepository.setPerson(transactionId, person)
        }

    fun delete(transactionId: Long) =
        safeWrite("delete") { container.transactionRepository.delete(transactionId) }

    fun setSenderLabel(sender: String, label: String) =
        safeWrite("setSenderLabel") { container.senderLabelRepository.setLabel(sender, label) }

    fun clearSenderLabel(sender: String) =
        safeWrite("clearSenderLabel") { container.senderLabelRepository.clearLabel(sender) }

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

    Column(Modifier.fillMaxSize()) {
        // Date-range chips: filter what's shown without touching the data.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.dateFilter == filter,
                    onClick = { viewModel.setDateFilter(filter) },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        if (state.transactions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.hasAnyTransactions) {
                        "No transactions in this period."
                    } else {
                        "No transactions yet.\nCaptured bank messages will appear here automatically."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
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
                    knownPersons = state.knownPersons,
                    bankName = viewModel.bankNameFor(tx.sender, state.senderLabels),
                    onAssignAccount = { accountId -> viewModel.assignAccount(tx.id, accountId) },
                    onAssignPerson = { person -> viewModel.setPerson(tx.id, person) },
                    onRenameSender = { label -> tx.sender?.let { viewModel.setSenderLabel(it, label) } },
                    onResetSender = { tx.sender?.let { viewModel.clearSenderLabel(it) } },
                    onDelete = { viewModel.delete(tx.id) },
                )
            }
        }
    }
}

@Composable
private fun TransactionCard(
    tx: Transaction,
    accounts: List<Account>,
    knownPersons: List<String>,
    bankName: String?,
    onAssignAccount: (Long?) -> Unit,
    onAssignPerson: (String?) -> Unit,
    onRenameSender: (String) -> Unit,
    onResetSender: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var newPersonOpen by remember { mutableStateOf(false) }
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
                        listOfNotNull(tx.timestampMillis.asDateTime(), tx.matchedRuleName).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

            // Compact chip row: both chips ellipsize long names inside their
            // half of the row instead of wrapping and blowing the card up.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    SelectorChip(
                        icon = Icons.Filled.AccountBalanceWallet,
                        text = accountName ?: "Account",
                        onClick = { accountMenuOpen = true },
                    )
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
                Box(Modifier.weight(1f)) {
                    SelectorChip(
                        icon = Icons.Filled.Person,
                        text = tx.person ?: "Person",
                        onClick = { personMenuOpen = true },
                    )
                    DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nobody") },
                            onClick = {
                                personMenuOpen = false
                                onAssignPerson(null)
                            },
                        )
                        knownPersons.forEach { person ->
                            DropdownMenuItem(
                                text = { Text(person) },
                                onClick = {
                                    personMenuOpen = false
                                    onAssignPerson(person)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("New person…") },
                            onClick = {
                                personMenuOpen = false
                                newPersonOpen = true
                            },
                        )
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete transaction",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (newPersonOpen) {
        NewPersonDialog(
            onDismiss = { newPersonOpen = false },
            onSave = { person ->
                onAssignPerson(person)
                newPersonOpen = false
            },
        )
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

/** Small outlined selector: fixed height, single ellipsized line, never wraps. */
@Composable
private fun SelectorChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun NewPersonDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign person") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
