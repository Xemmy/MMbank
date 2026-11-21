package com.xemmy.mmbank.ui.screens.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.SubscriptionLinks
import com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SubscriptionsUiState(
    val subscriptions: List<Subscription> = emptyList(),
    val scanning: Boolean = false,
    /** True only after a scan has run at least once this session/launch with nothing detected. */
    val hasScannedOnce: Boolean = false,
)

class SubscriptionsViewModel(private val container: AppContainer) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val hasScannedOnce = MutableStateFlow(false)

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        container.subscriptionRepository.observeActive(),
        scanning,
        hasScannedOnce,
    ) { subscriptions, isScanning, scannedOnce ->
        SubscriptionsUiState(subscriptions, isScanning, scannedOnce)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionsUiState())

    /**
     * Re-runs [SubscriptionRadarEngine] over the whole real ledger and persists
     * what it finds.
     *
     * Detection also runs by itself now, scoped to one merchant, whenever a
     * debit from that merchant is captured (see
     * [com.xemmy.mmbank.di.AppContainer]'s post-insert hook). This full sweep
     * stays useful for picking up history that predates that hook, and for
     * re-checking every merchant at once on demand.
     */
    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            runCatching {
                val transactions = container.transactionRepository.observeTransactions().first()
                container.subscriptionRepository.rescan(transactions)
            }.onFailure { container.recordError("SubscriptionsViewModel.scan", it) }
            hasScannedOnce.value = true
            scanning.value = false
        }
    }

    private val _charges = MutableStateFlow<ChargesState?>(null)
    val charges: StateFlow<ChargesState?> = _charges

    val defaultCurrency: String get() = container.preferences.defaultCurrency

    /** Opens the charges dialog for [subscription], loading what is linked and what could be. */
    fun openCharges(subscription: Subscription) {
        viewModelScope.launch {
            runCatching { _charges.value = loadCharges(subscription) }
                .onFailure { container.recordError("SubscriptionsViewModel.openCharges", it) }
        }
    }

    fun closeCharges() {
        _charges.value = null
    }

    fun addCharge(charge: Transaction) = changeLink { sub, repository -> repository.link(charge.id, sub.merchant) }

    fun removeCharge(charge: Transaction) = changeLink { _, repository -> repository.unlink(charge.id) }

    /** Applies a link change, rescans so the subscription reflects it, and refreshes the dialog. */
    private fun changeLink(change: suspend (Subscription, com.xemmy.mmbank.domain.repository.SubscriptionRepository) -> Unit) {
        val open = _charges.value?.subscription ?: return
        viewModelScope.launch {
            runCatching {
                val repository = container.subscriptionRepository
                change(open, repository)
                repository.rescan(container.transactionRepository.observeTransactions().first())
                // The rescan may have refreshed the row; show its current figures.
                val fresh = repository.observeActive().first().firstOrNull { it.merchant == open.merchant } ?: open
                _charges.value = loadCharges(fresh)
            }.onFailure { container.recordError("SubscriptionsViewModel.changeLink", it) }
        }
    }

    private suspend fun loadCharges(subscription: Subscription): ChargesState {
        val repository = container.subscriptionRepository
        val links = repository.links()
        val all = container.transactionRepository.observeTransactions().first()
        return ChargesState(
            subscription = subscription,
            linked = repository.linkedTransactions(subscription.merchant).sortedByDescending { it.timestampMillis },
            candidates = SubscriptionLinks.candidates(all, links, subscription),
        )
    }

    private val _pickable = MutableStateFlow<List<Transaction>?>(null)
    val pickable: StateFlow<List<Transaction>?> = _pickable

    /** Loads the purchases "Add from a transaction" lets the user choose from. */
    fun openPicker() {
        viewModelScope.launch {
            runCatching {
                val all = container.transactionRepository.observeTransactions().first()
                _pickable.value = SubscriptionLinks.pickable(all, container.subscriptionRepository.links())
            }.onFailure { container.recordError("SubscriptionsViewModel.openPicker", it) }
        }
    }

    fun closePicker() {
        _pickable.value = null
    }

    /** Starts a subscription from [charge], linking it so the charge counts toward the series. */
    fun createFrom(charge: Transaction) {
        _pickable.value = null
        viewModelScope.launch {
            runCatching {
                val repository = container.subscriptionRepository
                val manual = SubscriptionLinks.manualFrom(charge)
                repository.saveManual(manual)
                repository.link(charge.id, manual.merchant)
                repository.rescan(container.transactionRepository.observeTransactions().first())
            }.onFailure { container.recordError("SubscriptionsViewModel.createFrom", it) }
        }
    }

    fun addManual(subscription: Subscription) {
        viewModelScope.launch {
            runCatching { container.subscriptionRepository.saveManual(subscription) }
                .onFailure { container.recordError("SubscriptionsViewModel.addManual", it) }
        }
    }

    fun deleteSubscription(id: Long) {
        viewModelScope.launch {
            runCatching { container.subscriptionRepository.delete(id) }
                .onFailure { container.recordError("SubscriptionsViewModel.deleteSubscription", it) }
            _charges.value = null
        }
    }

    fun dismiss(id: Long) {
        viewModelScope.launch {
            runCatching { container.subscriptionRepository.dismiss(id) }
                .onFailure { container.recordError("SubscriptionsViewModel.dismiss", it) }
        }
    }
}

/**
 * Subscription Radar & Renewal Calendar: every recurring charge
 * [SubscriptionRadarEngine] has detected in the ledger, soonest renewal
 * first, with a price-change badge where a subscription's cost moved, plus a
 * manual "Scan" to re-check every merchant at once.
 */
@Composable
fun SubscriptionsScreen(
    onBack: () -> Unit,
    onOpenMerchant: (String) -> Unit = {},
    viewModel: SubscriptionsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val charges by viewModel.charges.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var chooserOpen by remember { mutableStateOf(false) }
    val pickable by viewModel.pickable.collectAsStateWithLifecycle()

    charges?.let { open ->
        SubscriptionChargesDialog(
            state = open,
            onDismiss = viewModel::closeCharges,
            onAdd = viewModel::addCharge,
            onRemove = viewModel::removeCharge,
            // Only a hand-added row is really removed; a detected one would just return on the next scan.
            onDeleteSubscription = if (open.subscription.isManual) {
                { viewModel.deleteSubscription(open.subscription.id) }
            } else {
                null
            },
        )
    }
    if (chooserOpen) {
        AddSubscriptionChooser(
            onDismiss = { chooserOpen = false },
            onFromTransaction = { chooserOpen = false; viewModel.openPicker() },
            onManual = { chooserOpen = false; addOpen = true },
        )
    }
    pickable?.let { list ->
        TransactionPickDialog(
            transactions = list,
            onDismiss = viewModel::closePicker,
            onPick = viewModel::createFrom,
        )
    }
    if (addOpen) {
        AddSubscriptionDialog(
            defaultCurrency = viewModel.defaultCurrency,
            onDismiss = { addOpen = false },
            onSave = { viewModel.addManual(it); addOpen = false },
        )
    }

    MMScaffold(
        title = "Subscriptions",
        onBack = onBack,
        actions = {
            if (state.scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).width(24.dp))
            } else {
                IconButton(onClick = viewModel::scan) {
                    Icon(Icons.Filled.Autorenew, contentDescription = "Scan for subscriptions")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { chooserOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add subscription")
            }
        },
    ) { padding ->
        if (state.subscriptions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (state.hasScannedOnce) {
                        "No recurring charges found. Subscriptions need at least 3 matching " +
                            "charges on a weekly or monthly rhythm to be detected."
                    } else {
                        "Tap the scan icon to detect recurring charges (subscriptions, memberships, " +
                            "bills) from your transaction history, or tap + to add one yourself."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = com.xemmy.mmbank.ui.theme.MMSpacing.fabClearance),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.subscriptions, key = { it.id }) { subscription ->
                    SubscriptionRow(
                        subscription,
                        onDismiss = { viewModel.dismiss(subscription.id) },
                        onCharges = { viewModel.openCharges(subscription) },
                        onClick = { onOpenMerchant(subscription.merchant) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(subscription: Subscription, onDismiss: () -> Unit, onCharges: () -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Repeat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(subscription.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "${cadenceLabel(subscription.cadence)} · ${renewalLabel(subscription.nextDueDateMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    moneyLabel(subscription.averageAmount, subscription.currencyCode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // A price change used to make a subscription vanish from the
                // radar entirely; now it's the headline.
                subscription.previousAmount?.let { previous ->
                    val rose = subscription.averageAmount > previous
                    Text(
                        (if (rose) "↑ was " else "↓ was ") + moneyLabel(previous, subscription.currencyCode),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rose) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onCharges) {
                Icon(Icons.Filled.Link, contentDescription = "Linked charges for ${subscription.merchant}")
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss ${subscription.merchant}")
            }
        }
    }
}

private fun cadenceLabel(cadence: SubscriptionCadence): String = when (cadence) {
    SubscriptionCadence.WEEKLY -> "Weekly"
    SubscriptionCadence.BIWEEKLY -> "Every 2 weeks"
    SubscriptionCadence.MONTHLY -> "Monthly"
    SubscriptionCadence.QUARTERLY -> "Quarterly"
    SubscriptionCadence.SEMI_ANNUAL -> "Every 6 months"
    SubscriptionCadence.YEARLY -> "Yearly"
}

/** "Renews today" / "Renews in N days" / "Renewal overdue" for the next-due timestamp, relative to today. */
private fun renewalLabel(nextDueDateMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val today = LocalDate.now(zone)
    val dueDate = Instant.ofEpochMilli(nextDueDateMillis).atZone(zone).toLocalDate()
    val daysUntil = ChronoUnit.DAYS.between(today, dueDate)
    return when {
        daysUntil < 0 -> "Renewal overdue"
        daysUntil == 0L -> "Renews today"
        daysUntil == 1L -> "Renews tomorrow"
        else -> "Renews in $daysUntil days"
    }
}
