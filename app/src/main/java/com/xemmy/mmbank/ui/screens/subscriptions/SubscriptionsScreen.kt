package com.xemmy.mmbank.ui.screens.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.intelligence.SubscriptionRadarEngine
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.util.asMoney
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
                val detected = SubscriptionRadarEngine.detect(transactions)
                container.subscriptionRepository.replaceAll(detected)
            }.onFailure { container.recordError("SubscriptionsViewModel.scan", it) }
            hasScannedOnce.value = true
            scanning.value = false
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

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Subscriptions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            if (state.scanning) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            } else {
                OutlinedButton(onClick = viewModel::scan) {
                    Icon(Icons.Filled.Autorenew, contentDescription = null, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan")
                }
            }
        }

        if (state.subscriptions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.hasScannedOnce) {
                        "No recurring charges found. Subscriptions need at least 3 matching " +
                            "charges on a weekly or monthly rhythm to be detected."
                    } else {
                        "Tap Scan to detect recurring charges (subscriptions, memberships, " +
                            "bills) from your transaction history."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(state.subscriptions, key = { it.id }) { subscription ->
                    SubscriptionRow(
                        subscription,
                        onDismiss = { viewModel.dismiss(subscription.id) },
                        onClick = { onOpenMerchant(subscription.merchant) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(subscription: Subscription, onDismiss: () -> Unit, onClick: () -> Unit) {
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
                    subscription.averageAmount.asMoney(subscription.currencyCode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                // A price change used to make a subscription vanish from the
                // radar entirely; now it's the headline.
                subscription.previousAmount?.let { previous ->
                    val rose = subscription.averageAmount > previous
                    Text(
                        (if (rose) "↑ was " else "↓ was ") + previous.asMoney(subscription.currencyCode),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (rose) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss ${subscription.merchant}")
            }
        }
    }
}

private fun cadenceLabel(cadence: SubscriptionCadence): String = when (cadence) {
    SubscriptionCadence.WEEKLY -> "Weekly"
    SubscriptionCadence.MONTHLY -> "Monthly"
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
