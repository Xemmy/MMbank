package com.xemmy.mmbank.ui.screens.bills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.BillDetector
import com.xemmy.mmbank.domain.intelligence.BillHistory
import com.xemmy.mmbank.domain.intelligence.BillReminders
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.theme.MMTheme
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BillsUiState(
    val bills: List<Bill> = emptyList(),
    val currency: String = "USD",
    /** Telecom / utility providers paid most months that are not tracked yet. */
    val suggestions: List<BillDetector.DetectedBill> = emptyList(),
    /** Bill payments recorded twice (bank SMS + provider receipt) before they were merged at capture. */
    val duplicateCount: Int = 0,
    /** Real transactions, for each bill's payment history. */
    val ledger: List<com.xemmy.mmbank.domain.model.Transaction> = emptyList(),
)

class BillsViewModel(private val container: AppContainer) : ViewModel() {

    private val detected = MutableStateFlow<List<BillDetector.DetectedBill>>(emptyList())
    private val notBills = MutableStateFlow(container.preferences.notBillIds)
    private val duplicates = MutableStateFlow<List<com.xemmy.mmbank.domain.intelligence.BillDuplicateFinder.Duplicate>>(emptyList())

    private val ledgerFlow = MutableStateFlow<List<com.xemmy.mmbank.domain.model.Transaction>>(emptyList())

    val uiState: StateFlow<BillsUiState> = combine(container.billRepository.observeAll(), detected, notBills, duplicates, ledgerFlow) { bills, found, hidden, dups, ledger ->
        BillsUiState(
            bills = bills,
            currency = container.preferences.defaultCurrency,
            suggestions = found.filter { d -> d.id !in hidden && bills.none { BillDetector.tracks(it, d) } },
            duplicateCount = dups.size,
            ledger = ledger,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BillsUiState())

    init {
        refreshDetection()
    }

    private fun refreshDetection() {
        viewModelScope.launch {
            runCatching {
                val ledger = container.transactionRepository.getAllReal()
                ledgerFlow.value = ledger
                detected.value = BillDetector.detect(ledger, java.time.LocalDate.now())
                duplicates.value = com.xemmy.mmbank.domain.intelligence.BillDuplicateFinder.pairs(ledger)
            }.onFailure { container.recordError("BillsViewModel.detect", it) }
        }
    }

    /** Deletes the second copy of each doubly recorded bill payment, keeping its line number on the row that stays. */
    fun mergeDuplicates() {
        val pairs = duplicates.value
        viewModelScope.launch {
            runCatching {
                pairs.forEach { d ->
                    container.transactionRepository.setBillRef(d.keep.id, d.ref)
                    container.transactionRepository.delete(d.remove.id)
                }
            }.onFailure { container.recordError("BillsViewModel.mergeDuplicates", it) }
            refreshDetection()
        }
    }

    /** Tracks a detected provider as a monthly bill that its captured payments mark paid. */
    fun track(suggestion: BillDetector.DetectedBill) {
        viewModelScope.launch {
            runCatching { container.billRepository.save(BillDetector.toBill(suggestion, System.currentTimeMillis())) }
                .onFailure { container.recordError("BillsViewModel.track", it) }
        }
    }

    fun notABill(suggestion: BillDetector.DetectedBill) {
        val next = container.preferences.notBillIds + suggestion.id
        container.preferences.notBillIds = next
        notBills.value = next
    }

    fun addBill(name: String, amount: BigDecimal, dueInDays: Long, recurrence: BillRecurrence?) {
        val clean = name.trim()
        if (clean.isEmpty() || amount.signum() <= 0) return
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                val due = now + dueInDays * TimeUnit.DAYS.toMillis(1)
                container.billRepository.save(
                    Bill(
                        name = clean,
                        amount = amount,
                        currencyCode = container.preferences.defaultCurrency,
                        recurrence = recurrence,
                        nextDueDateMillis = due,
                        createdAt = now,
                        dueDayAnchor = java.time.Instant.ofEpochMilli(due)
                            .atZone(java.time.ZoneId.systemDefault()).dayOfMonth,
                    )
                )
            }.onFailure { container.recordError("BillsViewModel.addBill", it) }
        }
    }

    /** Attaches an earlier number-less payment to [bill]'s line so it counts in the bill's history. Spending totals are unchanged. */
    fun attachPayment(bill: Bill, tx: com.xemmy.mmbank.domain.model.Transaction) {
        val ref = bill.accountRef ?: return
        viewModelScope.launch {
            runCatching {
                container.transactionRepository.setBillRef(tx.id, ref)
                val latest = maxOf(bill.lastPaidMillis ?: 0L, tx.timestampMillis)
                container.billRepository.save(bill.copy(lastPaidMillis = latest))
            }.onFailure { container.recordError("BillsViewModel.attachPayment", it) }
            refreshDetection()
        }
    }

    fun markPaid(billId: Long) {
        viewModelScope.launch {
            runCatching { container.billRepository.markPaid(billId) }
                .onFailure { container.recordError("BillsViewModel.markPaid", it) }
        }
    }

    fun delete(billId: Long) {
        viewModelScope.launch {
            runCatching { container.billRepository.delete(billId) }
                .onFailure { container.recordError("BillsViewModel.delete", it) }
        }
    }
}

/**
 * Upcoming Bills: manually tracked bills (rent, EMI, variable-amount
 * utilities, one-off payments) that [com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine]
 * can't detect on its own, each with a due date and an opt-in reminder
 * (Settings -> Preferences).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BillsScreen(
    onBack: () -> Unit,
    /** Opens the transaction page prefilled from this bill; saving it also marks the bill paid. */
    onLogPayment: (Long) -> Unit = {},
    viewModel: BillsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var historyFor by remember { mutableStateOf<Bill?>(null) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("Upcoming bills") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add bill")
            }
        },
    ) { padding ->
        if (state.bills.isEmpty() && state.suggestions.isEmpty() && state.duplicateCount == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No bills tracked yet. Tap + to add rent, an EMI, a variable-amount " +
                        "utility, or any one-off payment worth a reminder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.duplicateCount > 0) {
                    item(key = "duplicates") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "${state.duplicateCount} bill payments were recorded twice (bank SMS + provider SMS)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Merging deletes the second copy, so your spending stops counting them twice. The line number is kept.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MMTheme.colors.textSecondary,
                                )
                                TextButton(onClick = viewModel::mergeDuplicates) { Text("Merge") }
                            }
                        }
                    }
                }
                if (state.suggestions.isNotEmpty()) {
                    item(key = "suggested-header") {
                        Text(
                            "Suggested bills",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Paid most months. Track one and its payment SMS will mark it paid automatically, whatever the amount or day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.suggestions, key = { "s-${it.id}" }) { suggestion ->
                        SuggestedBillRow(
                            suggestion,
                            otherLine = suggestion.accountRef == null &&
                                state.suggestions.any { it.providerKey == suggestion.providerKey && it.accountRef != null },
                            onTrack = { viewModel.track(suggestion) },
                            onNotABill = { viewModel.notABill(suggestion) },
                        )
                    }
                    if (state.bills.isNotEmpty()) {
                        item(key = "tracked-header") {
                            Text(
                                "Your bills",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                items(state.bills, key = { it.id }) { bill ->
                    BillRow(
                        bill = bill,
                        onOpen = { historyFor = bill },
                        onMarkPaid = { viewModel.markPaid(bill.id) },
                        onLogPayment = { onLogPayment(bill.id) },
                        onDelete = { viewModel.delete(bill.id) },
                    )
                }
            }
        }
    }

    historyFor?.let { shown ->
        // Re-read the bill so the dialog follows saves made while it is open.
        val live = state.bills.firstOrNull { it.id == shown.id } ?: shown
        BillHistoryDialog(
            bill = live,
            payments = BillHistory.paymentsFor(live, state.ledger),
            candidates = BillHistory.candidates(live, state.ledger, System.currentTimeMillis()),
            onAttach = { viewModel.attachPayment(live, it) },
            onDismiss = { historyFor = null },
        )
    }

    if (addOpen) {
        AddBillDialog(
            currency = state.currency,
            onDismiss = { addOpen = false },
            onAdd = { name, amount, dueInDays, recurrence ->
                viewModel.addBill(name, amount, dueInDays, recurrence)
                addOpen = false
            },
        )
    }
}

@Composable
private fun BillRow(bill: Bill, onOpen: () -> Unit, onMarkPaid: () -> Unit, onLogPayment: () -> Unit, onDelete: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Delete bill?") },
            text = { Text("This removes the bill permanently.") },
            confirmButton = { TextButton(onClick = { confirming = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
    val now = remember { System.currentTimeMillis() }
    val days = BillReminders.daysUntil(bill, now)
    val whenText = when {
        days < 0L -> "overdue by ${-days}d"
        days == 0L -> "due today"
        days == 1L -> "due tomorrow"
        else -> "due in ${days}d"
    }
    val repeats = bill.recurrence?.let { it.name.lowercase().replaceFirstChar(Char::uppercase) } ?: "One-off"

    Card(Modifier.fillMaxWidth().clickable(enabled = bill.providerKey != null, onClick = onOpen)) {
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Receipt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    bill.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More for ${bill.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete bill") }, onClick = { menu = false; confirming = true })
                    }
                }
            }
            MoneyText(
                amount = bill.amount.toDouble(),
                currencyCode = bill.currencyCode,
                kind = TransactionKind.TRANSFER,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "$repeats · $whenText" + (if (bill.providerKey != null) " · usual amount" else ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MMTheme.colors.textSecondary,
            )
            bill.lastPaidMillis?.let { paid ->
                Text(
                    "Last paid " + java.time.Instant.ofEpochMilli(paid).atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")) +
                        if (bill.providerKey != null) ". Tap for history." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLogPayment) { Text("Log payment") }
                FilledTonalButton(onClick = onMarkPaid) { Text("Mark paid") }
            }
        }
    }
}

@Composable
private fun BillHistoryDialog(
    bill: Bill,
    payments: List<com.xemmy.mmbank.domain.model.Transaction>,
    candidates: List<com.xemmy.mmbank.domain.model.Transaction>,
    onAttach: (com.xemmy.mmbank.domain.model.Transaction) -> Unit,
    onDismiss: () -> Unit,
) {
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
    fun day(t: com.xemmy.mmbank.domain.model.Transaction) =
        java.time.Instant.ofEpochMilli(t.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(fmt)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(bill.name) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Payments counted", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (payments.isEmpty()) {
                    item { Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary) }
                }
                items(payments, key = { "p-${it.id}" }) { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(day(t), style = MaterialTheme.typography.bodyMedium)
                        MoneyText(
                            amount = t.amount.toDouble(), currencyCode = t.currencyCode, kind = TransactionKind.TRANSFER,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (candidates.isNotEmpty()) {
                    item {
                        Text("Earlier payments to add", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "Bank SMS with no line number. Tap Add to count one for this line. Your spending totals do not change.",
                            style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary,
                        )
                    }
                    items(candidates, key = { "c-${it.id}" }) { t ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(day(t), style = MaterialTheme.typography.bodyMedium)
                                MoneyText(
                                    amount = t.amount.toDouble(), currencyCode = t.currencyCode, kind = TransactionKind.TRANSFER,
                                    style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary,
                                )
                            }
                            TextButton(onClick = { onAttach(t) }) { Text("Add") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AddBillDialog(
    currency: String,
    onDismiss: () -> Unit,
    onAdd: (name: String, amount: BigDecimal, dueInDays: Long, recurrence: BillRecurrence?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var dueInDays by remember { mutableStateOf("7") }
    var recurrence by remember { mutableStateOf<BillRecurrence?>(BillRecurrence.MONTHLY) }
    var expanded by remember { mutableStateOf(false) }
    val amountValue = amount.toDoubleOrNull()
    val dueInDaysValue = dueInDays.toLongOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New bill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount ($currency)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = dueInDays,
                    onValueChange = { dueInDays = it },
                    label = { Text("Due in how many days") },
                    singleLine = true,
                )
                Box {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                recurrence?.let { "Repeats: ${it.name.lowercase().replaceFirstChar(Char::uppercase)}" }
                                    ?: "One-off (doesn't repeat)",
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose recurrence")
                            }
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text("One-off (doesn't repeat)") },
                            onClick = { recurrence = null; expanded = false },
                        )
                        BillRecurrence.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                onClick = { recurrence = option; expanded = false },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && amountValue != null && amountValue > 0.0 &&
                    dueInDaysValue != null,
                onClick = {
                    onAdd(name, BigDecimal.valueOf(amountValue ?: 0.0), dueInDaysValue ?: 0L, recurrence)
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SuggestedBillRow(suggestion: BillDetector.DetectedBill, otherLine: Boolean, onTrack: () -> Unit, onNotABill: () -> Unit) {
    val money = { a: BigDecimal -> com.xemmy.mmbank.ui.designsystem.moneyLabel(a, suggestion.currencyCode) }
    val range = if (suggestion.minAmount.compareTo(suggestion.maxAmount) == 0) {
        money(suggestion.typicalAmount)
    } else {
        "${money(suggestion.minAmount)}–${money(suggestion.maxAmount)}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                suggestion.displayName + (suggestion.accountRef?.let { " · line …$it" } ?: if (otherLine) " · other line (no number in SMS)" else ""),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Usually $range · around the ${ordinal(suggestion.typicalDay)}" +
                    (if (suggestion.paymentsPerMonth > 1) " · ${suggestion.paymentsPerMonth} payments a month" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
            Row {
                TextButton(onClick = onTrack) { Text("Track") }
                TextButton(onClick = onNotABill) { Text("Not a bill") }
            }
        }
    }
}

internal fun ordinal(day: Int): String {
    val suffix = if (day % 100 in 11..13) "th" else when (day % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$day$suffix"
}
