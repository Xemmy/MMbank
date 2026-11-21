package com.xemmy.mmbank.ui.screens.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.SubscriptionForm
import com.xemmy.mmbank.domain.intelligence.SubscriptionLinks
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.screens.paylater.DatePickField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val CHARGE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

internal fun cadenceName(cadence: SubscriptionCadence): String = when (cadence) {
    SubscriptionCadence.WEEKLY -> "Weekly"
    SubscriptionCadence.BIWEEKLY -> "Every 2 weeks"
    SubscriptionCadence.MONTHLY -> "Monthly"
    SubscriptionCadence.QUARTERLY -> "Quarterly"
    SubscriptionCadence.SEMI_ANNUAL -> "Every 6 months"
    SubscriptionCadence.YEARLY -> "Yearly"
}

private fun chargeLabel(tx: Transaction): String {
    val date = Instant.ofEpochMilli(tx.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    return "${date.format(CHARGE_DATE)} · ${tx.merchant ?: "No merchant"}"
}

/** Backs the editor's "Add to subscription" picker. */
class SubscriptionLinkViewModel(private val container: AppContainer) : ViewModel() {
    private val repository get() = container.subscriptionRepository

    val subscriptions: StateFlow<List<Subscription>> = repository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The subscription row a transaction is currently linked to, or null. */
    suspend fun linkedSubscriptionId(transactionId: Long): Long? =
        repository.links().firstOrNull { it.transactionId == transactionId }?.subscriptionId

    fun attach(charge: Transaction, subscriptionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.link(charge.id, subscriptionId)
                repository.rescan(container.transactionRepository.observeTransactions().first())
            }.onFailure { container.recordError("SubscriptionLinkViewModel.attach", it) }
            onDone()
        }
    }

    fun attachAsNew(charge: Transaction, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val manual = SubscriptionLinks.manualFrom(charge)
                val id = repository.saveManual(manual)
                repository.link(charge.id, id)
                repository.rescan(container.transactionRepository.observeTransactions().first())
            }.onFailure { container.recordError("SubscriptionLinkViewModel.attachAsNew", it) }
            onDone()
        }
    }

    fun detach(charge: Transaction, onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                repository.unlink(charge.id)
                repository.rescan(container.transactionRepository.observeTransactions().first())
            }.onFailure { container.recordError("SubscriptionLinkViewModel.detach", it) }
            onDone()
        }
    }
}

/** "Add to subscription" on a saved purchase: pick a subscription, or start a new one from it. */
@Composable
fun SubscriptionPickerDialog(
    charge: Transaction,
    onDismiss: () -> Unit,
    viewModel: SubscriptionLinkViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    var currentId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(charge.id) { currentId = viewModel.linkedSubscriptionId(charge.id) }
    val current = subscriptions.firstOrNull { it.id == currentId }?.let { "${it.merchant} · ${moneyLabel(it.averageAmount, it.currencyCode)}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to subscription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Counts this charge toward the subscription's history and next renewal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current?.let { Text("Linked to $it", style = MaterialTheme.typography.labelLarge) }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(subscriptions, key = { it.id }) { sub ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.attach(charge, sub.id, onDismiss) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(sub.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${cadenceName(sub.cadence)} · ${moneyLabel(sub.averageAmount, sub.currencyCode)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = { viewModel.attachAsNew(charge, onDismiss) }) { Text("New subscription from this charge") }
                if (currentId != null) {
                    TextButton(onClick = { viewModel.detach(charge, onDismiss) }) { Text("Remove link") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Add (initial == null) or edit a subscription: name, amount, currency, how often, and when it renews next. */
@Composable
fun SubscriptionEditorDialog(
    initial: Subscription?,
    defaultCurrency: String,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var merchant by remember { mutableStateOf(initial?.merchant.orEmpty()) }
    var amount by remember { mutableStateOf(initial?.averageAmount?.toPlainString().orEmpty()) }
    var currency by remember { mutableStateOf(initial?.currencyCode ?: defaultCurrency) }
    var cadence by remember { mutableStateOf(initial?.cadence ?: SubscriptionCadence.MONTHLY) }
    var cadenceOpen by remember { mutableStateOf(false) }
    var nextDue by remember {
        mutableStateOf(
            initial?.nextDueDateMillis?.takeIf { it > 0L }
                ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                ?: LocalDate.now().plusMonths(1),
        )
    }
    val parsed = SubscriptionForm.validate(merchant, amount, currency)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add subscription" else "Edit subscription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = merchant, onValueChange = { merchant = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it },
                        label = { Text("Amount") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = amount.isNotBlank() && SubscriptionForm.validate("x", amount, "AED") == null,
                        modifier = Modifier.weight(0.6f),
                    )
                    OutlinedTextField(
                        value = currency, onValueChange = { currency = it.uppercase().take(3) },
                        label = { Text("Currency") }, singleLine = true, modifier = Modifier.weight(0.4f),
                    )
                }
                Column {
                    Text("Repeats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { cadenceOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(cadenceName(cadence)) }
                    DropdownMenu(expanded = cadenceOpen, onDismissRequest = { cadenceOpen = false }) {
                        SubscriptionCadence.entries.forEach { option ->
                            DropdownMenuItem(text = { Text(cadenceName(option)) }, onClick = { cadence = option; cadenceOpen = false })
                        }
                    }
                }
                DatePickField("Next renewal", nextDue, onPick = { nextDue = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null,
                onClick = {
                    val nextMillis = nextDue.atStartOfDay(zone).toInstant().toEpochMilli()
                    onSave(SubscriptionForm.build(initial, parsed!!, cadence, nextMillis))
                },
            ) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the charges dialog shows for one subscription. */
data class ChargesState(
    val subscription: Subscription,
    val linked: List<Transaction> = emptyList(),
    val candidates: List<Transaction> = emptyList(),
    /** Unlinked charges with this subscription's brand key, currency and ~amount ("Link similar"). */
    val similar: List<Transaction> = emptyList(),
)

/**
 * A subscription's detail: summary, Edit, its linked charges (unlink), recent debits to add,
 * the merchant page, and Dismiss (detected rows) or Remove (user-owned rows).
 */
@Composable
fun SubscriptionChargesDialog(
    state: ChargesState,
    onDismiss: () -> Unit,
    onAdd: (Transaction) -> Unit,
    onRemove: (Transaction) -> Unit,
    onLinkSimilar: () -> Unit,
    onEdit: () -> Unit,
    onOpenMerchant: () -> Unit,
    onDismissRow: (() -> Unit)?,
    onDeleteSubscription: (() -> Unit)?,
) {
    val sub = state.subscription
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(sub.merchant) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    Text(
                        "${moneyLabel(sub.averageAmount, sub.currencyCode)} · ${cadenceName(sub.cadence)}" +
                            (if (sub.lines > 1) " · ${sub.lines} per cycle" else ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Next renewal ${Instant.ofEpochMilli(sub.nextDueDateMillis).atZone(ZoneId.systemDefault()).toLocalDate().format(CHARGE_DATE)}" +
                            " · ${if (sub.isManual) "Yours" else "Detected"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onEdit) { Text("Edit") }
                        TextButton(onClick = onOpenMerchant) { Text("Merchant page") }
                    }
                }
                item { SectionLabel("Linked charges") }
                if (state.similar.isNotEmpty()) {
                    item {
                        TextButton(onClick = onLinkSimilar) { Text("Link ${state.similar.size} similar charges") }
                    }
                }
                if (state.linked.isEmpty()) {
                    item {
                        Text(
                            "None yet. Charges the app already matched by name are not listed here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.linked, key = { "l${it.id}" }) { tx ->
                    ChargeRow(tx, action = "Unlink", onAction = { onRemove(tx) })
                }
                item { SectionLabel("Add a charge") }
                if (state.candidates.isEmpty()) {
                    item {
                        Text(
                            "No other recent ${sub.currencyCode} purchases to add.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.candidates, key = { "c${it.id}" }) { tx ->
                    ChargeRow(tx, action = "Add", onAction = { onAdd(tx) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            Row {
                onDismissRow?.let { TextButton(onClick = it) { Text("Dismiss") } }
                onDeleteSubscription?.let { TextButton(onClick = it) { Text("Remove") } }
            }
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun ChargeRow(tx: Transaction, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(chargeLabel(tx), style = MaterialTheme.typography.bodyMedium)
            Text(
                moneyLabel(tx.amount, tx.currencyCode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) { Text(action) }
    }
}

/** First step of "Add subscription": pick from existing transactions, or type one in. */
@Composable
fun AddSubscriptionChooser(onDismiss: () -> Unit, onFromTransaction: () -> Unit, onManual: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add subscription") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onFromTransaction, modifier = Modifier.fillMaxWidth()) { Text("From an existing transaction") }
                OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("Add new") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A searchable list of purchases; tapping one starts a subscription from it. */
@Composable
fun TransactionPickDialog(transactions: List<Transaction>, onDismiss: () -> Unit, onPick: (Transaction) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(transactions, query) {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) transactions else transactions.filter { it.merchant?.lowercase()?.contains(needle) == true }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        "No matching purchases.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.id }) { tx ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(tx) }.padding(vertical = 8.dp)) {
                            Text(chargeLabel(tx), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                moneyLabel(tx.amount, tx.currencyCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
