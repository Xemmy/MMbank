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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.theme.glassSurface
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
        // All predicate logic lives in the pure-Kotlin engine so it's JVM-testable.
        val visible = TransactionFilterEngine.filter(
            transactions = transactions,
            filters = f,
            accounts = accounts,
            bankNameOf = { sender -> bankNameFor(sender, senderLabels) },
        )
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

    fun setDateRange(range: DateRangeFilter) { filters.value = filters.value.copy(dateRange = range) }
    fun setQuery(query: String) { filters.value = filters.value.copy(query = query) }
    fun setDirectionFilter(direction: TransactionDirection?) {
        filters.value = filters.value.copy(direction = direction)
    }
    fun setAccountFilter(account: AccountFilter) { filters.value = filters.value.copy(account = account) }
    fun setPersonFilter(person: PersonFilter) { filters.value = filters.value.copy(person = person) }
    fun setTriageOnly(enabled: Boolean) { filters.value = filters.value.copy(triageOnly = enabled) }
    fun setAmountRange(min: java.math.BigDecimal?, max: java.math.BigDecimal?) {
        filters.value = filters.value.copy(minAmount = min, maxAmount = max)
    }
    fun setSourceFilter(source: TransactionSource?) { filters.value = filters.value.copy(source = source) }
    fun setAnalyticsStatus(status: AnalyticsStatusFilter?) {
        filters.value = filters.value.copy(analyticsStatus = status)
    }
    fun clearFilters() { filters.value = ActivityFilters() }

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
        excludeFromStats: Boolean,
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
                excludeFromStats = excludeFromStats,
            )
        )
    }

    /** Saves edits to an existing transaction, preserving its untouched fields. */
    fun updateTransaction(
        original: Transaction,
        amount: java.math.BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        spendingCategory: SpendingCategory,
        timestampMillis: Long,
        excludeFromStats: Boolean,
    ) = safeWrite("updateTransaction") {
        person?.let(container.preferences::addPerson)
        container.transactionRepository.update(
            original.copy(
                amount = amount,
                currencyCode = currencyCode.trim().uppercase(),
                direction = direction,
                merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
                accountId = accountId,
                person = person,
                spendingCategory = spendingCategory,
                timestampMillis = timestampMillis,
                excludeFromStats = excludeFromStats,
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
    var editing by remember { mutableStateOf<Transaction?>(null) }

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
                onDateRange = viewModel::setDateRange,
                onDirectionFilter = viewModel::setDirectionFilter,
                onAccountFilter = viewModel::setAccountFilter,
                onPersonFilter = viewModel::setPersonFilter,
                onTriageOnly = viewModel::setTriageOnly,
                onAmountRange = viewModel::setAmountRange,
                onSourceFilter = viewModel::setSourceFilter,
                onAnalyticsStatus = viewModel::setAnalyticsStatus,
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
                            onEdit = { editing = tx },
                            onDelete = { viewModel.delete(tx.id) },
                        )
                    }
                }
            }
        }
    }

    if (addOpen) {
        TransactionEditorDialog(
            existing = null,
            accounts = state.accounts,
            knownPersons = state.knownPersons,
            defaultCurrency = state.defaultCurrency,
            onDismiss = { addOpen = false },
            onSave = { amount, currency, direction, merchant, accountId, person, category, timestamp, excludeFromStats ->
                viewModel.addManual(amount, currency, direction, merchant, accountId, person, category, timestamp, excludeFromStats)
                addOpen = false
            },
        )
    }

    editing?.let { tx ->
        TransactionEditorDialog(
            existing = tx,
            accounts = state.accounts,
            knownPersons = state.knownPersons,
            defaultCurrency = state.defaultCurrency,
            onDismiss = { editing = null },
            onSave = { amount, currency, direction, merchant, accountId, person, category, timestamp, excludeFromStats ->
                viewModel.updateTransaction(tx, amount, currency, direction, merchant, accountId, person, category, timestamp, excludeFromStats)
                editing = null
            },
        )
    }
}

/**
 * Search box + date/direction/account/person/triage/amount/source/analytics
 * filter chips for the Activity list. Filtering is purely in-memory
 * (personal-scale data), so it's instant and needs no new queries. Chip rows
 * scroll horizontally so they never wrap on narrow phones.
 */
@Composable
private fun ActivityFilterBar(
    state: TransactionsUiState,
    onQuery: (String) -> Unit,
    onDateRange: (DateRangeFilter) -> Unit,
    onDirectionFilter: (TransactionDirection?) -> Unit,
    onAccountFilter: (AccountFilter) -> Unit,
    onPersonFilter: (PersonFilter) -> Unit,
    onTriageOnly: (Boolean) -> Unit,
    onAmountRange: (java.math.BigDecimal?, java.math.BigDecimal?) -> Unit,
    onSourceFilter: (TransactionSource?) -> Unit,
    onAnalyticsStatus: (AnalyticsStatusFilter?) -> Unit,
    onClear: () -> Unit,
) {
    val f = state.filters
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var analyticsMenuOpen by remember { mutableStateOf(false) }
    var customRangeOpen by remember { mutableStateOf(false) }
    var amountOpen by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = f.query,
            onValueChange = onQuery,
            // Placeholder (not a floating label) + bodySmall keeps the field short.
            placeholder = { Text("Search merchant, bank, amount…", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp)) },
            trailingIcon = {
                if (f.query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search", Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // Date range: fixed presets, the data-driven statement cycle, and a
        // twin-calendar custom range.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val presets = listOf(
                DateRangeFilter.All to "All",
                DateRangeFilter.Today to "Today",
                DateRangeFilter.Past7Days to "Past 7 days",
                DateRangeFilter.Past30Days to "Past 30 days",
                DateRangeFilter.YearToDate to "Year-to-date",
            )
            presets.forEach { (range, label) ->
                FilterChip(
                    selected = f.dateRange == range,
                    onClick = { onDateRange(range) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                )
            }
            // Label the statement chip with the cycle when one applies, so the
            // rolling-30-day fallback (no account / no cycle day) is visible.
            val cycleDay = TransactionFilterEngine
                .activeAccount(f.account, state.accounts)?.statementCycleDay
            FilterChip(
                selected = f.dateRange == DateRangeFilter.StatementCycle,
                onClick = { onDateRange(DateRangeFilter.StatementCycle) },
                label = {
                    Text(
                        if (cycleDay != null) "Statement (day $cycleDay)" else "Statement",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
            val custom = f.dateRange as? DateRangeFilter.Custom
            FilterChip(
                selected = custom != null,
                onClick = { customRangeOpen = true },
                label = {
                    Text(
                        custom?.let { "${it.start.format(chipDateFormat)} – ${it.end.format(chipDateFormat)}" }
                            ?: "Custom…",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                leadingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(16.dp)) },
            )
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
                label = { Text("All", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = f.direction == TransactionDirection.DEBIT,
                onClick = { onDirectionFilter(TransactionDirection.DEBIT) },
                label = { Text("Spent", style = MaterialTheme.typography.labelSmall) },
            )
            FilterChip(
                selected = f.direction == TransactionDirection.CREDIT,
                onClick = { onDirectionFilter(TransactionDirection.CREDIT) },
                label = { Text("Received", style = MaterialTheme.typography.labelSmall) },
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
                    label = { Text(accountLabel, style = MaterialTheme.typography.labelSmall) },
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
                    label = { Text(personLabel, style = MaterialTheme.typography.labelSmall) },
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

        }

        // Triage + amount + source + analytics status.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Inbox Zero": rows still needing review — uncategorized captures
            // and system-generated balance adjustments.
            FilterChip(
                selected = f.triageOnly,
                onClick = { onTriageOnly(!f.triageOnly) },
                label = { Text("Inbox Zero", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Filled.Inbox, contentDescription = null, Modifier.size(16.dp)) },
            )

            // Amount threshold / range.
            val amountLabel = when {
                f.minAmount != null && f.maxAmount != null -> "${f.minAmount}–${f.maxAmount}"
                f.minAmount != null -> "≥ ${f.minAmount}"
                f.maxAmount != null -> "≤ ${f.maxAmount}"
                else -> "Amount"
            }
            FilterChip(
                selected = f.minAmount != null || f.maxAmount != null,
                onClick = { amountOpen = true },
                label = { Text(amountLabel, style = MaterialTheme.typography.labelSmall) },
            )

            // Capture source.
            Box {
                FilterChip(
                    selected = f.source != null,
                    onClick = { sourceMenuOpen = true },
                    label = {
                        Text(
                            f.source?.let(::sourceLabel) ?: "Source",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any source") },
                        onClick = { sourceMenuOpen = false; onSourceFilter(null) },
                    )
                    TransactionSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(sourceLabel(source)) },
                            onClick = { sourceMenuOpen = false; onSourceFilter(source) },
                        )
                    }
                }
            }

            // Analytics status (the Phase 1 excludeFromStats flag).
            Box {
                FilterChip(
                    selected = f.analyticsStatus != null,
                    onClick = { analyticsMenuOpen = true },
                    label = {
                        Text(
                            f.analyticsStatus?.label ?: "Analytics",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                DropdownMenu(expanded = analyticsMenuOpen, onDismissRequest = { analyticsMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("All rows") },
                        onClick = { analyticsMenuOpen = false; onAnalyticsStatus(null) },
                    )
                    AnalyticsStatusFilter.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.label) },
                            onClick = { analyticsMenuOpen = false; onAnalyticsStatus(status) },
                        )
                    }
                }
            }

            if (f.active) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }

    if (customRangeOpen) {
        CustomDateRangeDialog(
            initial = f.dateRange as? DateRangeFilter.Custom,
            onDismiss = { customRangeOpen = false },
            onSelect = { start, end ->
                onDateRange(DateRangeFilter.Custom(start, end))
                customRangeOpen = false
            },
        )
    }

    if (amountOpen) {
        AmountRangeDialog(
            currentMin = f.minAmount,
            currentMax = f.maxAmount,
            onDismiss = { amountOpen = false },
            onApply = { min, max ->
                onAmountRange(min, max)
                amountOpen = false
            },
        )
    }
}

/** Short human label for a capture source in the filter UI. */
private fun sourceLabel(source: TransactionSource): String = when (source) {
    TransactionSource.MANUAL -> "Manual"
    TransactionSource.SMS -> "SMS"
    TransactionSource.PUSH_NOTIFICATION -> "Notification"
    TransactionSource.BINANCE_SYNC -> "Binance sync"
}

private val chipDateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM")

/** Twin-calendar picker for an arbitrary inclusive Start→End window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateRangeDialog(
    initial: DateRangeFilter.Custom?,
    onDismiss: () -> Unit,
    onSelect: (java.time.LocalDate, java.time.LocalDate) -> Unit,
) {
    // The Material picker works in UTC day-millis, so conversions stay in UTC.
    fun java.time.LocalDate.toUtcMillis() =
        atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toUtcDate() =
        java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null &&
                    pickerState.selectedEndDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis
                    if (start != null && end != null) onSelect(start.toUtcDate(), end.toUtcDate())
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            showModeToggle = false,
        )
    }
}

/** Min/max amount bounds; either side may be left blank for an open threshold. */
@Composable
private fun AmountRangeDialog(
    currentMin: java.math.BigDecimal?,
    currentMax: java.math.BigDecimal?,
    onDismiss: () -> Unit,
    onApply: (java.math.BigDecimal?, java.math.BigDecimal?) -> Unit,
) {
    var minText by remember { mutableStateOf(currentMin?.toPlainString().orEmpty()) }
    var maxText by remember { mutableStateOf(currentMax?.toPlainString().orEmpty()) }
    val min = minText.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val max = maxText.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val minValid = minText.isBlank() || min != null
    val maxValid = maxText.isBlank() || max != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Leave a field blank for an open bound (e.g. only “Min” = everything above it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it },
                        label = { Text("Min") },
                        singleLine = true,
                        isError = !minValid,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("Max") },
                        singleLine = true,
                        isError = !maxValid,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = minValid && maxValid,
                onClick = { onApply(min, max) },
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(null, null) }) { Text("Clear") }
        },
    )
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
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var newPersonOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name

    // Audit-friendly presentation: rows excluded from analytics stay in the
    // feed (never filtered here) but read as muted — dimmed card plus a
    // slashed-eye marker beside the amount.
    val cardAlpha = if (tx.excludeFromStats) 0.55f else 1f
    Card(Modifier.fillMaxWidth().alpha(cardAlpha)) {
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
                if (tx.excludeFromStats) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Excluded from analytics",
                        modifier = Modifier.padding(start = 4.dp).size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            tx.rawMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // One compact meta row: account · person · category chips scroll
            // horizontally so long names never blow up the card, with edit &
            // delete pinned on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        MetaChip(
                            text = accountName ?: "Account",
                            active = tx.accountId != null,
                            icon = Icons.Filled.AccountBalanceWallet,
                            onClick = { accountMenuOpen = true },
                        )
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { accountMenuOpen = false; onAssignAccount(null) })
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text(account.name) }, onClick = { accountMenuOpen = false; onAssignAccount(account.id) })
                            }
                        }
                    }
                    Box {
                        MetaChip(
                            text = tx.person ?: "Person",
                            active = tx.person != null,
                            icon = Icons.Filled.Person,
                            onClick = { personMenuOpen = true },
                        )
                        DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Nobody") }, onClick = { personMenuOpen = false; onAssignPerson(null) })
                            knownPersons.forEach { person ->
                                DropdownMenuItem(text = { Text(person) }, onClick = { personMenuOpen = false; onAssignPerson(person) })
                            }
                            DropdownMenuItem(text = { Text("New person…") }, onClick = { personMenuOpen = false; newPersonOpen = true })
                        }
                    }
                    Box {
                        MetaChip(
                            text = tx.spendingCategory.label,
                            active = tx.spendingCategory != SpendingCategory.UNCATEGORIZED,
                            emoji = tx.spendingCategory.emoji,
                            onClick = { categoryMenuOpen = true },
                        )
                        DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                            SpendingCategory.entries.forEach { category ->
                                DropdownMenuItem(text = { Text("${category.emoji}  ${category.label}") }, onClick = { categoryMenuOpen = false; onAssignCategory(category) })
                            }
                        }
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit transaction",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete transaction",
                        modifier = Modifier.size(17.dp),
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

/**
 * Dense tappable chip for the per-transaction account/person/category selectors.
 * Smaller than a Material AssistChip (labelSmall text, tighter padding) and tinted when
 * a value is set, so a card's meta row stays compact instead of looking cluttered.
 * Long values ellipsize within a capped width rather than widening the card.
 */
@Composable
private fun MetaChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .widthIn(max = 150.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when {
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                emoji != null -> Text(emoji, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
 * Add or edit a transaction. With [existing] null it hand-enters one the capture
 * pipeline missed; with [existing] set it edits that row's fields in place. Either
 * way the result behaves identically to a captured row in balances and filters.
 */
@Composable
private fun TransactionEditorDialog(
    existing: Transaction?,
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
        excludeFromStats: Boolean,
    ) -> Unit,
) {
    val zone = java.time.ZoneId.systemDefault()
    val isEdit = existing != null
    var amount by remember { mutableStateOf(existing?.amount?.stripTrailingZeros()?.toPlainString() ?: "") }
    var currency by remember { mutableStateOf(existing?.currencyCode ?: defaultCurrency) }
    var direction by remember { mutableStateOf(existing?.direction ?: TransactionDirection.DEBIT) }
    var merchant by remember { mutableStateOf(existing?.merchant.orEmpty()) }
    var accountId by remember { mutableStateOf(existing?.accountId) }
    var person by remember { mutableStateOf(existing?.person) }
    // null = follow the merchant-derived suggestion; a value = explicit choice.
    // Editing starts from the row's saved category so it never silently changes.
    var categoryOverride by remember { mutableStateOf(existing?.spendingCategory) }
    var excludeFromStats by remember { mutableStateOf(existing?.excludeFromStats ?: false) }
    var dateIso by remember {
        mutableStateOf(
            java.time.Instant.ofEpochMilli(existing?.timestampMillis ?: System.currentTimeMillis())
                .atZone(zone).toLocalDate().toString()
        )
    }

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
        // Glass overlay: translucent container + hairline border, with a real
        // backdrop blur on API 31+ (falls back to a more opaque surface below).
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = { Text(if (isEdit) "Edit transaction" else "Add transaction", style = MaterialTheme.typography.titleLarge) },
        text = {
            GlassDialogBackdrop()
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

                // Analytics purity control: internal transfers, cash refunds and
                // the like stay in the ledger but shouldn't skew the stats.
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Exclude from Analytics", style = MaterialTheme.typography.bodyLarge)
                    Switch(
                        checked = excludeFromStats,
                        onCheckedChange = { excludeFromStats = it },
                    )
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
                        // Keep the original time-of-day when editing (so unchanged
                        // dates keep their exact timestamp and intra-day order);
                        // use the current time for a fresh manual entry.
                        val timeOfDay = existing
                            ?.let { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalTime() }
                            ?: java.time.LocalTime.now()
                        val timestamp = parsedDate.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                        onSave(
                            value,
                            currency.trim().uppercase(),
                            direction,
                            merchant.trim().takeIf { it.isNotBlank() },
                            accountId,
                            person,
                            effectiveCategory,
                            timestamp,
                            excludeFromStats,
                        )
                    }
                },
            ) { Text(if (isEdit) "Save" else "Add") }
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
