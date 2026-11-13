package com.xemmy.mmbank.ui.screens.upcoming

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.intelligence.UpcomingPayments
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.navigation.Routes
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class UpcomingUiState(val items: List<UpcomingPayments.Item> = emptyList(), val loaded: Boolean = false)

/** Merges bills, detected subscriptions and card dues; see [UpcomingPayments]. */
class UpcomingPaymentsViewModel(container: AppContainer) : ViewModel() {

    private data class Cards(
        val accounts: List<com.xemmy.mmbank.domain.model.Account>,
        val transactions: List<com.xemmy.mmbank.domain.model.Transaction>,
        val entries: List<com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry>,
    )

    private val cards = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
        container.transactionRepository.observeLedgerEntries(),
    ) { accounts, transactions, entries -> Cards(accounts, transactions, entries) }

    val hideBalances: StateFlow<Boolean> = container.hideBalances

    val uiState: StateFlow<UpcomingUiState> = combine(
        container.billRepository.observeAll(),
        container.subscriptionRepository.observeActive(),
        cards,
    ) { bills, subscriptions, c ->
        val today = LocalDate.now()
        UpcomingUiState(
            items = UpcomingPayments.build(
                bills = bills,
                subscriptions = subscriptions,
                cards = CardPaymentReminders.build(c.accounts, c.transactions, c.entries, today),
                today = today,
            ),
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpcomingUiState())
}

@Composable
fun UpcomingPaymentsScreen(
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: UpcomingPaymentsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    val groups = UpcomingPayments.grouped(state.items)

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Upcoming payments", style = MaterialTheme.typography.titleLarge)
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    if (state.loaded) {
                        "Nothing coming up. Bills you add, subscriptions MMbank detects and credit-card dues show here, in date order."
                    } else {
                        "Loading…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                groups.forEach { (section, items) ->
                    item(key = "h_${section.name}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (section == UpcomingPayments.Section.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items.forEachIndexed { index, item ->
                        item(key = "${section.name}_$index") {
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(routeFor(item.kind)) }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${item.kind.label} · ${whenLabel(item)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                item.amount?.let { Text(moneyLabel(it, item.currencyCode, hidden), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun routeFor(kind: UpcomingPayments.Kind): String = when (kind) {
    UpcomingPayments.Kind.BILL -> Routes.BILLS
    UpcomingPayments.Kind.SUBSCRIPTION -> Routes.SUBSCRIPTIONS
    UpcomingPayments.Kind.CARD -> Routes.ACCOUNTS
}

/** "Today", "Tomorrow", "in 5 days", "3 days overdue", with the date. */
internal fun whenLabel(item: UpcomingPayments.Item): String {
    val rel = when {
        item.daysUntil == 0L -> "today"
        item.daysUntil == 1L -> "tomorrow"
        item.daysUntil > 1L -> "in ${item.daysUntil} days"
        item.daysUntil == -1L -> "1 day overdue"
        else -> "${-item.daysUntil} days overdue"
    }
    return "$rel (${item.dueDate})"
}
