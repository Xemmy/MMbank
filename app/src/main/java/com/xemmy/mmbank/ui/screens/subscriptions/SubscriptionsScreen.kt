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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
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
import com.xemmy.mmbank.domain.intelligence.SubscriptionGroups
import com.xemmy.mmbank.domain.intelligence.SubscriptionLinks
import com.xemmy.mmbank.domain.intelligence.SubscriptionTotals
import com.xemmy.mmbank.domain.intelligence.SubscriptionSnapshot
import com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMCard
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
    /** Rows the user dismissed, listed at the bottom with Restore. */
    val dismissed: List<Subscription> = emptyList(),
    val hideBalances: Boolean = false,
    val scanning: Boolean = false,
    /** True only after a scan has run at least once this session/launch with nothing detected. */
    val hasScannedOnce: Boolean = false,
)

class SubscriptionsViewModel(private val container: AppContainer) : ViewModel() {

    /** A message with an Undo action, shown as a snackbar. */
    class UndoEvent(val message: String, val undo: (() -> Unit)?)

    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<UndoEvent>(extraBufferCapacity = 4)
    val events: kotlinx.coroutines.flow.SharedFlow<UndoEvent> = _events

    private val scanning = MutableStateFlow(false)
    /** The last rescan's counts; the screen turns them into the "Found 2 new..." message. */
    val lastResult = MutableStateFlow<com.xemmy.mmbank.domain.repository.RescanCounts?>(null)
    private val hasScannedOnce = MutableStateFlow(false)

    val uiState: StateFlow<SubscriptionsUiState> = combine(
        container.subscriptionRepository.observeActive(),
        container.subscriptionRepository.observePossible(),
        container.subscriptionRepository.observeDismissed(),
        combine(scanning, hasScannedOnce, container.hideBalances) { a, b, c -> Triple(a, b, c) },
    ) { subscriptions, possible, dismissed, flags ->
        SubscriptionsUiState(subscriptions, possible, dismissed, flags.third, flags.first, flags.second)
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
            runCatching { _verdicts.value = SubscriptionRadarEngine.explain(container.transactionRepository.getAllReal(), never = container.preferences.neverSubscriptionKeys) }
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
                val counts = container.subscriptionRepository.rescan(transactions)
                lastResult.value = counts
                _events.tryEmit(UndoEvent(scanMessage(counts), null))
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
            candidates = SubscriptionLinks.candidates(all, links, subscription, limit = Int.MAX_VALUE),
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
                _pickable.value = SubscriptionLinks.pickable(all, container.subscriptionRepository.links(), limit = Int.MAX_VALUE)
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

    /** Merchants the user marked "never a subscription", as brand keys. */
    val neverKeys = MutableStateFlow(container.preferences.neverSubscriptionKeys)

    private fun setNever(key: String, never: Boolean) {
        val next = container.preferences.neverSubscriptionKeys.let { if (never) it + key else it - key }
        container.preferences.neverSubscriptionKeys = next
        neverKeys.value = next
    }

    /** Hides a merchant from detection for good (removing its detected rows); Undo allows it again. */
    fun markNever(subscription: Subscription) {
        val key = com.xemmy.mmbank.domain.intelligence.SubscriptionRescan.keyOf(subscription.merchant)
        viewModelScope.launch {
            runCatching {
                setNever(key, true)
                container.subscriptionRepository.rescan(container.transactionRepository.observeTransactions().first())
                _events.tryEmit(UndoEvent("${subscription.merchant} will never be detected") { allow(key) })
            }.onFailure { container.recordError("SubscriptionsViewModel.markNever", it) }
            _charges.value = null
        }
    }

    /** Lets a merchant be detected again and re-scans. */
    fun allow(key: String) {
        viewModelScope.launch {
            runCatching {
                setNever(key, false)
                container.subscriptionRepository.rescan(container.transactionRepository.observeTransactions().first())
            }.onFailure { container.recordError("SubscriptionsViewModel.allow", it) }
        }
    }

    /** The merchant being renamed and how many transactions carry that name, or null. */
    val renaming = MutableStateFlow<Pair<String, Int>?>(null)

    fun startRename(merchant: String) {
        viewModelScope.launch {
            runCatching { renaming.value = merchant to container.merchantAliasRepository.countMerchant(merchant) }
                .onFailure { container.recordError("SubscriptionsViewModel.startRename", it) }
        }
    }

    fun confirmRename(from: String, to: String, alsoFuture: Boolean) {
        renaming.value = null
        viewModelScope.launch {
            runCatching { container.merchantAliasRepository.renameMerchant(from, to, alsoFuture) }
                .onFailure { container.recordError("SubscriptionsViewModel.confirmRename", it) }
            _charges.value = null
        }
    }

    /** Removes a row and its links, with an Undo that puts both back. */
    fun deleteSubscription(subscription: Subscription) {
        viewModelScope.launch {
            runCatching {
                val repository = container.subscriptionRepository
                val snapshot = SubscriptionSnapshot.of(subscription, repository.links())
                repository.delete(subscription.id)
                _events.tryEmit(
                    UndoEvent("Removed ${subscription.merchant}") {
                        viewModelScope.launch {
                            runCatching { repository.restoreRow(snapshot) }
                                .onFailure { container.recordError("SubscriptionsViewModel.undoRemove", it) }
                        }
                    },
                )
            }.onFailure { container.recordError("SubscriptionsViewModel.deleteSubscription", it) }
            _charges.value = null
        }
    }

    fun dismiss(subscription: Subscription) {
        viewModelScope.launch {
            runCatching {
                container.subscriptionRepository.dismiss(subscription.id)
                _events.tryEmit(
                    UndoEvent("Dismissed ${subscription.merchant}") {
                        viewModelScope.launch {
                            runCatching { container.subscriptionRepository.restore(subscription.id) }
                                .onFailure { container.recordError("SubscriptionsViewModel.undoDismiss", it) }
                        }
                    },
                )
            }.onFailure { container.recordError("SubscriptionsViewModel.dismiss", it) }
            _charges.value = null
        }
    }

    fun restore(id: Long) {
        viewModelScope.launch {
            runCatching { container.subscriptionRepository.restore(id) }
                .onFailure { container.recordError("SubscriptionsViewModel.restore", it) }
        }
    }

    /** Saves an edit (keeping the row's id), rescans so its figures follow, and refreshes the open dialog. */
    fun saveEdit(subscription: Subscription) {
        viewModelScope.launch {
            runCatching {
                val repository = container.subscriptionRepository
                repository.saveManual(subscription)
                repository.rescan(container.transactionRepository.observeTransactions().first())
                val fresh = repository.observeActive().first().firstOrNull { it.id == subscription.id } ?: subscription
                if (_charges.value != null) _charges.value = loadCharges(fresh)
            }.onFailure { container.recordError("SubscriptionsViewModel.saveEdit", it) }
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
    onEditTransaction: (Long) -> Unit = {},
    viewModel: SubscriptionsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val charges by viewModel.charges.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    var dismissedOpen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(event.message, actionLabel = if (event.undo != null) "Undo" else null)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) event.undo?.invoke()
        }
    }
    var chooserOpen by remember { mutableStateOf(false) }
    val pickable by viewModel.pickable.collectAsStateWithLifecycle()
    val verdicts by viewModel.verdicts.collectAsStateWithLifecycle()

    val renaming by viewModel.renaming.collectAsStateWithLifecycle()
    val neverKeys by viewModel.neverKeys.collectAsStateWithLifecycle()
    renaming?.let { (from, count) ->
        RenameMerchantDialog(
            from = from,
            count = count,
            initialTo = from,
            onDismiss = { viewModel.renaming.value = null },
            onConfirm = { to, future -> viewModel.confirmRename(from, to, future) },
        )
    }
    verdicts?.let { list -> WhyNotDetectedDialog(list, onDismiss = viewModel::closeExplain) }
    charges?.let { open ->
        SubscriptionChargesDialog(
            state = open,
            onDismiss = viewModel::closeCharges,
            onAdd = viewModel::addCharge,
            onRemove = viewModel::removeCharge,
            onLinkSimilar = viewModel::linkSimilar,
            onEdit = { editing = open.subscription },
            onRename = { viewModel.startRename(open.subscription.merchant) },
            // The dialog closes so it never shows stale charges after the edit; reopen the row to see the change.
            onEditTransaction = { tx -> viewModel.closeCharges(); onEditTransaction(tx.id) },
            onOpenMerchant = { viewModel.closeCharges(); onOpenMerchant(open.subscription.merchant) },
            // A detected row is dismissed (it would just return on the next scan); a user-owned one is removed.
            onDismissRow = if (open.subscription.isManual) null else { { viewModel.dismiss(open.subscription) } },
            onNever = if (open.subscription.isManual) null else { { viewModel.markNever(open.subscription) } },
            onDeleteSubscription = if (open.subscription.isManual) {
                { viewModel.deleteSubscription(open.subscription) }
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
        SubscriptionEditorDialog(
            initial = null,
            defaultCurrency = viewModel.defaultCurrency,
            onDismiss = { addOpen = false },
            onSave = { viewModel.addManual(it); addOpen = false },
        )
    }
    editing?.let { row ->
        SubscriptionEditorDialog(
            initial = row,
            defaultCurrency = viewModel.defaultCurrency,
            onDismiss = { editing = null },
            onSave = { viewModel.saveEdit(it); editing = null },
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
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp).size(24.dp))
            } else {
                IconButton(onClick = viewModel::scan) {
                    Icon(Icons.Filled.Autorenew, contentDescription = "Scan for subscriptions")
                }
            }
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { chooserOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add subscription")
            }
        },
    ) { padding ->
        if (state.subscriptions.isEmpty() && state.possible.isEmpty() && state.dismissed.isEmpty() && neverKeys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    if (state.hasScannedOnce) {
                        "No recurring charges found. A subscription needs 3 charges on a regular " +
                            "rhythm (weekly, every 2 weeks, monthly, quarterly, every 6 months or yearly) at a similar " +
                            "amount; 2 monthly charges show as possible. Tap the ? to see why a merchant was not detected."
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
                item(key = "summary") { SummaryCard(state.subscriptions, state.hideBalances) }
                val groups = SubscriptionGroups.group(state.subscriptions, LocalDate.now())
                listOf("Overdue" to groups.overdue, "Due this week" to groups.thisWeek, "Later" to groups.later)
                    .filter { it.second.isNotEmpty() }
                    .forEach { (label, rows) ->
                        item(key = "h-$label") {
                            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                        items(rows, key = { it.id }) { subscription ->
                            SubscriptionRow(subscription, state.hideBalances, onClick = { viewModel.openCharges(subscription) })
                        }
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
                            onReject = { viewModel.dismiss(subscription) },
                            onNever = { viewModel.markNever(subscription) },
                        )
                    }
                }
                if (neverKeys.isNotEmpty()) {
                    item(key = "never-header") {
                        Text("Never a subscription", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                    }
                    items(neverKeys.sorted(), key = { "n$it" }) { key ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(key, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = { viewModel.allow(key) }) { Text("Allow again") }
                            }
                        }
                    }
                }
                if (state.dismissed.isNotEmpty()) {
                    item(key = "dismissed-header") {
                        androidx.compose.material3.TextButton(onClick = { dismissedOpen = !dismissedOpen }) {
                            Text("Dismissed (${state.dismissed.size}) ${if (dismissedOpen) "▲" else "▼"}")
                        }
                    }
                    if (dismissedOpen) {
                        items(state.dismissed, key = { "d${it.id}" }) { subscription ->
                            DismissedRow(subscription, onRestore = { viewModel.restore(subscription.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DismissedRow(subscription: Subscription, onRestore: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(subscription.merchant, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${cadenceLabel(subscription.cadence)} · ${moneyLabel(subscription.averageAmount, subscription.currencyCode)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            androidx.compose.material3.TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

@Composable
private fun SubscriptionRow(subscription: Subscription, hidden: Boolean, onClick: () -> Unit) {
    MMCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
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
                    "${cadenceLabel(subscription.cadence)}${if (subscription.lines > 1) " ×${subscription.lines}" else ""} · " +
                        "${renewalLabel(subscription.nextDueDateMillis)} · ${if (subscription.isManual) "Yours" else "Detected"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    maskedMoney(subscription.averageAmount, subscription.currencyCode, hidden),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                // A price change used to make a subscription vanish from the
                // radar entirely; now it's the headline.
                subscription.previousAmount?.let { previous ->
                    val rose = subscription.averageAmount > previous
                    Text(
                        (if (rose) "↑ was " else "↓ was ") + maskedMoney(previous, subscription.currencyCode, hidden),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rose) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

private fun cadenceLabel(cadence: SubscriptionCadence): String = cadenceName(cadence)

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
private fun PossibleRow(subscription: Subscription, onTrack: () -> Unit, onReject: () -> Unit, onNever: () -> Unit) {
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
                androidx.compose.material3.TextButton(onClick = onReject) { Text("Not this one") }
                androidx.compose.material3.TextButton(onClick = onNever) { Text("Never") }
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
        SubscriptionRadarEngine.Reason.NOT_A_SUBSCRIPTION -> outcome.detail
        SubscriptionRadarEngine.Reason.PRICE_VARIES -> "The price varies: ${outcome.detail}"
        SubscriptionRadarEngine.Reason.MIXED_WITH_OTHER_PURCHASES -> "Mixed with other purchases: ${outcome.detail}"
    }
}

private fun maskedMoney(amount: java.math.BigDecimal, currency: String, hidden: Boolean): String =
    if (hidden) "••••" else moneyLabel(amount, currency)

/** "Found 2 new · 1 updated · 1 removed", or "Nothing changed". */
internal fun scanMessage(counts: com.xemmy.mmbank.domain.repository.RescanCounts): String {
    val parts = buildList {
        if (counts.created > 0) add("Found ${counts.created} new")
        if (counts.updated > 0) add("${counts.updated} updated")
        if (counts.removed > 0) add("${counts.removed} removed")
    }
    return if (parts.isEmpty()) "Nothing changed" else parts.joinToString(" · ")
}

@Composable
private fun SummaryCard(subscriptions: List<Subscription>, hidden: Boolean) {
    val lines = SubscriptionTotals.summaryLines(subscriptions) { amount, currency -> maskedMoney(amount, currency, hidden) }
    if (lines.isEmpty()) return
    MMCard(Modifier.fillMaxWidth()) {
        Text(lines.first(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        lines.drop(1).forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
