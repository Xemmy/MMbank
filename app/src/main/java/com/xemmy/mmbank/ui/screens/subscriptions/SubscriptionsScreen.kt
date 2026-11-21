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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.HelpOutline
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
    /** Low-confidence detections the user can Track or dismiss as "Not a subscription". */
    val possible: List<Subscription> = emptyList(),
    val scanning: Boolean = false,
    /** True only after a scan has run at least once this session/launch with nothing detected. */
    val hasScannedOnce: Boolean = false,
)

class SubscriptionsViewModel(private val container: AppContainer) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    /** The last rescan's counts; the screen turns them into the "Found 2 new..." message. */
    val lastResult = MutableStateFlow<com.xemmy.mmbank.domain.repository.RescanCounts?>(null)
    private val hasScannedOnce = MutableStateFlow(false)

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        container.subscriptionRepository.observeActive(),
        container.subscriptionRepository.observePossible(),
        scanning,
        hasScannedOnce,
    ) { subscriptions, possible, isScanning, scannedOnce ->
        SubscriptionsUiState(subscriptions, possible, isScanning, scannedOnce)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubscriptionsUiState())

    init {
        // Opening the screen re-checks the ledger, at most once every 10 minutes per app run.
        val now = System.currentTimeMillis()
        if (now - lastAutoScanMillis > AUTO_SCAN_INTERVAL_MILLIS) {
            lastAutoScanMillis = now
            scan()
        }
    }

    /** Accepts a possible subscription as the user's own. */
    fun track(id: Long) {
        viewModelScope.launch {
            runCatching { container.subscriptionRepository.track(id) }
                .onFailure { container.recordError("SubscriptionsViewModel.track", it) }
        }
    }

    private val _verdicts = MutableStateFlow<List<SubscriptionRadarEngine.Verdict>?>(null)
    val verdicts: StateFlow<List<SubscriptionRadarEngine.Verdict>?> = _verdicts

    /** Loads the radar's verdict on every merchant with repeat charges: detected, possible, or why not. */
    fun openExplain() {
        viewModelScope.launch {
            runCatching { _verdicts.value = SubscriptionRadarEngine.explain(container.transactionRepository.getAllReal()) }
                .onFailure { container.recordError("SubscriptionsViewModel.openExplain", it) }
        }
    }

    fun closeExplain() {
        _verdicts.value = null
    }

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
    private companion object {
        const val AUTO_SCAN_INTERVAL_MILLIS = 10 * 60 * 1000L
        @Volatile var lastAutoScanMillis = 0L
    }

    fun scan() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            runCatching {
                val transactions = container.transactionRepository.observeTransactions().first()
                lastResult.value = container.subscriptionRepository.rescan(transactions)
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

    fun addCharge(charge: Transaction) = changeLink { sub, repository -> repository.link(charge.id, sub.id) }

    /** Links every unlinked charge with the same brand key and an amount within 5% to the open subscription. */
    fun linkSimilar() = changeLink { sub, repository ->
        val all = container.transactionRepository.observeTransactions().first()
        SubscriptionLinks.similar(all, repository.links(), sub).forEach { repository.link(it.id, sub.id) }
    }

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
                val fresh = repository.observeActive().first().firstOrNull { it.id == open.id } ?: open
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
            linked = repository.linkedTransactions(subscription.id).sortedByDescending { it.timestampMillis },
            candidates = SubscriptionLinks.candidates(all, links, subscription),
            similar = SubscriptionLinks.similar(all, links, subscription),
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
                val id = repository.saveManual(manual)
                repository.link(charge.id, id)
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
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()

    verdicts?.let { list -> WhyNotDetectedDialog(list, onDismiss = viewModel::closeExplain) }
    charges?.let { open ->
        SubscriptionChargesDialog(
            state = open,
            onDismiss = viewModel::closeCharges,
            onAdd = viewModel::addCharge,
            onRemove = viewModel::removeCharge,
            onLinkSimilar = viewModel::linkSimilar,
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
            IconButton(onClick = viewModel::openExplain) {
                Icon(Icons.Outlined.HelpOutline, contentDescription = "Why isn't something detected?")
            }
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
        if (state.subscriptions.isEmpty() && state.possible.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (state.hasScannedOnce) {
                        "No recurring charges found. A subscription needs 3 charges on a regular " +
                            "rhythm (weekly to yearly) at a similar amount, or 2 monthly charges that are " +
                            "listed as possible. Tap the ? to see why a merchant was not detected."
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
                if (state.possible.isNotEmpty()) {
                    item(key = "possible-header") {
                        Text(
                            "Possible subscriptions",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            "Two charges about a cycle apart, or a price that drifts. Track the ones that are real.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.possible, key = { "p${it.id}" }) { subscription ->
                        PossibleRow(
                            subscription,
                            onTrack = { viewModel.track(subscription.id) },
                            onReject = { viewModel.dismiss(subscription.id) },
                        )
                    }
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

@Composable
private fun PossibleRow(subscription: Subscription, onTrack: () -> Unit, onReject: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(subscription.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${cadenceLabel(subscription.cadence)} · ${subscription.occurrenceCount} charges · ${renewalLabel(subscription.nextDueDateMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    moneyLabel(subscription.averageAmount, subscription.currencyCode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextButton(onClick = onTrack) { Text("Track") }
                androidx.compose.material3.TextButton(onClick = onReject) { Text("Not a subscription") }
            }
        }
    }
}

/** The radar's verdict per merchant: detected, possible, or the reason it was not. */
@Composable
private fun WhyNotDetectedDialog(verdicts: List<SubscriptionRadarEngine.Verdict>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = verdicts.filter { query.isBlank() || it.merchant.contains(query.trim(), ignoreCase = true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Why isn't it detected?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Search merchant") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isEmpty()) {
                    Text(
                        "No merchant has two or more similar charges yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shown, key = { "${it.merchant}|${it.currencyCode}|${it.amount}" }) { v ->
                        Column {
                            Text(
                                "${v.merchant} · ${moneyLabel(v.amount, v.currencyCode)} × ${v.charges}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                verdictLabel(v.outcome),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Plain-language outcome for the "Why isn't it detected?" list. */
internal fun verdictLabel(outcome: SubscriptionRadarEngine.Outcome): String = when (outcome) {
    SubscriptionRadarEngine.Outcome.Detected -> "Detected as a subscription"
    is SubscriptionRadarEngine.Outcome.Possible -> "Possible subscription (${(outcome.score * 100).toInt()}% sure)"
    is SubscriptionRadarEngine.Outcome.Rejected -> when (outcome.reason) {
        SubscriptionRadarEngine.Reason.TOO_FEW -> "Too few charges: ${outcome.detail}"
        SubscriptionRadarEngine.Reason.IRREGULAR_GAPS -> "Not on a regular rhythm: ${outcome.detail}"
        SubscriptionRadarEngine.Reason.PRICE_VARIES -> "The price varies: ${outcome.detail}"
        SubscriptionRadarEngine.Reason.MIXED_WITH_OTHER_PURCHASES -> "Mixed with other purchases: ${outcome.detail}"
    }
}
