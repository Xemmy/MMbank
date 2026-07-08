package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.SpendingCategory
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

/** Shown as the "rule" on hand-entered rows so they read clearly in the list. */
const val MANUAL_ENTRY_LABEL = "Manual entry"

/** Quick date ranges for the Activity list. */
enum class DateFilter(val label: String) {
    ALL("All"),
    TODAY("Today"),
    WEEK("7 days"),
    MONTH("This month"),
}

/** Account dimension of the Activity filter: any / only unassigned / one account. */
sealed interface AccountFilter {
    data object Any : AccountFilter
    data object Unassigned : AccountFilter
    data class Specific(val id: Long) : AccountFilter
}

/** Person dimension of the Activity filter: any / only unassigned / one person. */
sealed interface PersonFilter {
    data object Any : PersonFilter
    data object Unassigned : PersonFilter
    data class Specific(val name: String) : PersonFilter
}

/** All Activity-tab filters, folded into one flow so the combine stays small. */
data class ActivityFilters(
    val dateFilter: DateFilter = DateFilter.ALL,
    val query: String = "",
    val direction: TransactionDirection? = null,
    val account: AccountFilter = AccountFilter.Any,
    val person: PersonFilter = PersonFilter.Any,
) {
    /** True when anything beyond the default (show-everything) is set. */
    val active: Boolean
        get() = dateFilter != DateFilter.ALL || query.isNotBlank() || direction != null ||
            account != AccountFilter.Any || person != PersonFilter.Any
}

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val senderLabels: Map<String, String> = emptyMap(),
    val filters: ActivityFilters = ActivityFilters(),
    /** Every person seen so far, for the quick-assign menu. */
    val knownPersons: List<String> = emptyList(),
    /** True when the filter (not an empty ledger) hid everything. */
    val hasAnyTransactions: Boolean = false,
    /** Currency a new manual entry defaults to. */
    val defaultCurrency: String = "USD",
)

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.senderLabelRepository.observeLabels(),
        filters,
    ) { transactions, accounts, senderLabels, f ->
        val cutoff = filterCutoffMillis(f.dateFilter)
        val visible = transactions.filter { tx -> matches(tx, f, cutoff, senderLabels) }
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
            filters = f,
            knownPersons = persons,
            hasAnyTransactions = transactions.isNotEmpty(),
            defaultCurrency = container.preferences.defaultCurrency,
        )
    }.recoverAndRetry("TransactionsViewModel", container) {
        TransactionsUiState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /** Applies every active filter to one transaction. */
    private fun matches(
        tx: Transaction,
        f: ActivityFilters,
        cutoff: Long?,
        senderLabels: Map<String, String>,
    ): Boolean {
        if (cutoff != null && tx.timestampMillis < cutoff) return false
        if (f.direction != null && tx.direction != f.direction) return false
        when (val a = f.account) {
            AccountFilter.Any -> Unit
            AccountFilter.Unassigned -> if (tx.accountId != null) return false
            is AccountFilter.Specific -> if (tx.accountId != a.id) return false
        }
        when (val p = f.person) {
            PersonFilter.Any -> Unit
            PersonFilter.Unassigned -> if (!tx.person.isNullOrBlank()) return false
            is PersonFilter.Specific -> if (!tx.person.equals(p.name, ignoreCase = true)) return false
        }
        val q = f.query.trim()
        if (q.isNotEmpty()) {
            val haystack = listOfNotNull(
                tx.merchant,
                tx.rawMessage,
                tx.sender,
                bankNameFor(tx.sender, senderLabels),
                tx.amount.toPlainString(),
                tx.currencyCode,
                tx.spendingCategory.label,
            )
            if (haystack.none { it.contains(q, ignoreCase = true) }) return false
        }
        return true
    }

    fun setDateFilter(filter: DateFilter) { filters.value = filters.value.copy(dateFilter = filter) }
    fun setQuery(query: String) { filters.value = filters.value.copy(query = query) }
    fun setDirectionFilter(direction: TransactionDirection?) {
        filters.value = filters.value.copy(direction = direction)
    }
    fun setAccountFilter(account: AccountFilter) { filters.value = filters.value.copy(account = account) }
    fun setPersonFilter(person: PersonFilter) { filters.value = filters.value.copy(person = person) }
    fun clearFilters() { filters.value = ActivityFilters() }

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

    fun setCategory(transactionId: Long, category: SpendingCategory) =
        safeWrite("setCategory") { container.transactionRepository.setSpendingCategory(transactionId, category) }

    fun delete(transactionId: Long) =
        safeWrite("delete") { container.transactionRepository.delete(transactionId) }

    /** Adds a transaction the user typed in by hand (a missed/uncaptured one). */
    fun addManual(
        amount: java.math.BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        spendingCategory: SpendingCategory,
        timestampMillis: Long,
    ) = safeWrite("addManual") {
        person?.let(container.preferences::addPerson)
        container.transactionRepository.recordManual(
            Transaction(
                amount = amount,
                currencyCode = currencyCode.trim().uppercase(),
                direction = direction,
                merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
                source = com.xemmy.mmbank.domain.model.TransactionSource.MANUAL,
                timestampMillis = timestampMillis,
                accountId = accountId,
                person = person,
                spendingCategory = spendingCategory,
                matchedRuleName = MANUAL_ENTRY_LABEL,
            )
        )
    }

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
    var addOpen by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ActivityFilterBar(
                state = state,
                onQuery = viewModel::setQuery,
                onDateFilter = viewModel::setDateFilter,
                onDirectionFilter = viewModel::setDirectionFilter,
                onAccountFilter = viewModel::setAccountFilter,
                onPersonFilter = viewModel::setPersonFilter,
                onClear = viewModel::clearFilters,
            )

            if (state.transactions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.hasAnyTransactions) {
                            "No transactions match your filters."
                        } else {
                            "No transactions yet.\nCaptured bank messages appear here automatically — " +
                                "or tap + to add one the app missed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
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
                            onAssignCategory = { category -> viewModel.setCategory(tx.id, category) },
                            onRenameSender = { label -> tx.sender?.let { viewModel.setSenderLabel(it, label) } },
                            onResetSender = { tx.sender?.let { viewModel.clearSenderLabel(it) } },
                            onDelete = { viewModel.delete(tx.id) },
                        )
                    }
                }
            }
        }
    }

    if (addOpen) {
        AddTransactionDialog(
            accounts = state.accounts,
            knownPersons = state.knownPersons,
            defaultCurrency = state.defaultCurrency,
            onDismiss = { addOpen = false },
            onSave = { amount, currency, direction, merchant, accountId, person, category, timestamp ->
                viewModel.addManual(amount, currency, direction, merchant, accountId, person, category, timestamp)
                addOpen = false
            },
        )
    }
}

/**
 * Search box + date/direction/account/person filter chips for the Activity list.
 * Filtering is purely in-memory (personal-scale data), so it's instant and needs
 * no new queries. Chip rows scroll horizontally so they never wrap on narrow phones.
 */
@Composable
private fun ActivityFilterBar(
    state: TransactionsUiState,
    onQuery: (String) -> Unit,
    onDateFilter: (DateFilter) -> Unit,
    onDirectionFilter: (TransactionDirection?) -> Unit,
    onAccountFilter: (AccountFilter) -> Unit,
    onPersonFilter: (PersonFilter) -> Unit,
    onClear: () -> Unit,
) {
    val f = state.filters
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = f.query,
            onValueChange = onQuery,
            label = { Text("Search merchant, bank, amount…") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (f.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Date range.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateFilter.entries.forEach { filter ->
                FilterChip(
                    selected = f.dateFilter == filter,
                    onClick = { onDateFilter(filter) },
                    label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }

        // Direction + account + person.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = f.direction == null,
                onClick = { onDirectionFilter(null) },
                label = { Text("All", style = MaterialTheme.typography.labelMedium) },
            )
            FilterChip(
                selected = f.direction == TransactionDirection.DEBIT,
                onClick = { onDirectionFilter(TransactionDirection.DEBIT) },
                label = { Text("Spent", style = MaterialTheme.typography.labelMedium) },
            )
            FilterChip(
                selected = f.direction == TransactionDirection.CREDIT,
                onClick = { onDirectionFilter(TransactionDirection.CREDIT) },
                label = { Text("Received", style = MaterialTheme.typography.labelMedium) },
            )

            // Account filter chip + menu.
            Box {
                val accountLabel = when (val a = f.account) {
                    AccountFilter.Any -> "Account"
                    AccountFilter.Unassigned -> "Unassigned"
                    is AccountFilter.Specific ->
                        state.accounts.firstOrNull { it.id == a.id }?.name ?: "Account"
                }
                FilterChip(
                    selected = f.account != AccountFilter.Any,
                    onClick = { accountMenuOpen = true },
                    label = { Text(accountLabel, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, Modifier.size(16.dp)) },
                )
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any account") },
                        onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Any) },
                    )
                    DropdownMenuItem(
                        text = { Text("Unassigned") },
                        onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Unassigned) },
                    )
                    state.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Specific(account.id)) },
                        )
                    }
                }
            }

            // Person filter chip + menu.
            Box {
                val personLabel = when (val p = f.person) {
                    PersonFilter.Any -> "Person"
                    PersonFilter.Unassigned -> "Unassigned"
                    is PersonFilter.Specific -> p.name
                }
                FilterChip(
                    selected = f.person != PersonFilter.Any,
                    onClick = { personMenuOpen = true },
                    label = { Text(personLabel, style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, Modifier.size(16.dp)) },
                )
                DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any person") },
                        onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Any) },
                    )
                    DropdownMenuItem(
                        text = { Text("Unassigned") },
                        onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Unassigned) },
                    )
                    state.knownPersons.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Specific(name)) },
                        )
                    }
                }
            }

            if (f.active) {
                TextButton(onClick = onClear) { Text("Clear") }
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
    onAssignCategory: (SpendingCategory) -> Unit,
    onRenameSender: (String) -> Unit,
    onResetSender: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
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

            // Spending category — its own row so it never crowds the chips above.
            Box(Modifier.padding(top = 2.dp)) {
                AssistChip(
                    onClick = { categoryMenuOpen = true },
                    leadingIcon = {
                        Text(tx.spendingCategory.emoji, style = MaterialTheme.typography.labelMedium)
                    },
                    label = {
                        Text(
                            tx.spendingCategory.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
                DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                    SpendingCategory.entries.forEach { category ->
                        DropdownMenuItem(
                            text = { Text("${category.emoji}  ${category.label}") },
                            onClick = {
                                categoryMenuOpen = false
                                onAssignCategory(category)
                            },
                        )
                    }
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

/**
 * Hand-enters a transaction the capture pipeline missed. Amount + currency are
 * the only required fields; everything else mirrors what a captured row carries
 * so a manual entry behaves identically in balances, filters and the dashboard.
 */
@Composable
private fun AddTransactionDialog(
    accounts: List<Account>,
    knownPersons: List<String>,
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onSave: (
        amount: java.math.BigDecimal,
        currency: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        spendingCategory: SpendingCategory,
        timestampMillis: Long,
    ) -> Unit,
) {
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf(defaultCurrency) }
    var direction by remember { mutableStateOf(TransactionDirection.DEBIT) }
    var merchant by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var person by remember { mutableStateOf<String?>(null) }
    // null = follow the merchant-derived suggestion; a value = user override.
    var categoryOverride by remember { mutableStateOf<SpendingCategory?>(null) }
    var dateIso by remember { mutableStateOf(java.time.LocalDate.now().toString()) }

    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }

    val amountValue = amount.replace(",", "").toBigDecimalOrNull()
    val date = runCatching { java.time.LocalDate.parse(dateIso.trim()) }.getOrNull()
    val valid = amountValue != null && amountValue.signum() > 0 &&
        currency.trim().length == 3 && date != null
    val accountName = accounts.firstOrNull { it.id == accountId }?.name
    val suggestedCategory = remember(merchant) {
        MerchantCategorizer.categorize(merchant.takeIf { it.isNotBlank() })
    }
    val effectiveCategory = categoryOverride ?: suggestedCategory

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add transaction", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Debit / credit picker: drives the sign in every balance sum.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TransactionDirection.entries.forEach { entry ->
                        FilterChip(
                            selected = direction == entry,
                            onClick = { direction = entry },
                            label = { Text(if (entry == TransactionDirection.DEBIT) "Spent" else "Received") },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Amount") },
                        singleLine = true,
                        isError = amount.isNotBlank() && amountValue == null,
                        modifier = Modifier.weight(0.6f),
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(3) },
                        label = { Text("Currency") },
                        singleLine = true,
                        modifier = Modifier.weight(0.4f),
                    )
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant / description (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateIso,
                    onValueChange = { dateIso = it },
                    label = { Text("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = date == null,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Account picker — same target the capture pipeline assigns to.
                Box {
                    OutlinedButton(onClick = { accountMenuOpen = true }) {
                        Text("Account: " + (accountName ?: "Unassigned"))
                    }
                    DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Unassigned") },
                            onClick = { accountMenuOpen = false; accountId = null },
                        )
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = { accountMenuOpen = false; accountId = account.id },
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(onClick = { personMenuOpen = true }) {
                        Text("Person: " + (person ?: "Nobody"))
                    }
                    DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nobody") },
                            onClick = { personMenuOpen = false; person = null },
                        )
                        knownPersons.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { personMenuOpen = false; person = name },
                            )
                        }
                    }
                }

                // Category picker — defaults to the merchant-derived guess ("auto"),
                // overridable. Mirrors the auto-categorization done on capture.
                Box {
                    OutlinedButton(onClick = { categoryMenuOpen = true }) {
                        val autoTag = if (categoryOverride == null) " (auto)" else ""
                        Text("Category: ${effectiveCategory.emoji} ${effectiveCategory.label}$autoTag")
                    }
                    DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto (from merchant)") },
                            onClick = { categoryMenuOpen = false; categoryOverride = null },
                        )
                        SpendingCategory.entries.forEach { category ->
                            DropdownMenuItem(
                                text = { Text("${category.emoji}  ${category.label}") },
                                onClick = { categoryMenuOpen = false; categoryOverride = category },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    // Button is only enabled when both parse, so this is safe.
                    val parsedDate = date
                    val value = amountValue
                    if (parsedDate != null && value != null) {
                        // Stamp the chosen day at the current time-of-day so ordering
                        // within a day stays natural and the date filters line up.
                        val timestamp = parsedDate
                            .atTime(java.time.LocalTime.now())
                            .atZone(java.time.ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                        onSave(
                            value,
                            currency.trim().uppercase(),
                            direction,
                            merchant.trim().takeIf { it.isNotBlank() },
                            accountId,
                            person,
                            effectiveCategory,
                            timestamp,
                        )
                    }
                },
            ) { Text("Add") }
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
