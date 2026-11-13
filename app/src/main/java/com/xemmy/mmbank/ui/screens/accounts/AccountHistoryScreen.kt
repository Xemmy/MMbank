package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.account.AccountTimeline
import com.xemmy.mmbank.domain.ai.TransactionRetentionPlanner
import com.xemmy.mmbank.domain.ledger.DriftWindow
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.asDateTime
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class AccountHistoryUiState(
    val account: Account? = null,
    val result: AccountTimeline.Result? = null,
    val filter: AccountTimeline.Filter = AccountTimeline.Filter.ALL,
    val loaded: Boolean = false,
    /** Opened from "See what changed": the difference to explain (null window = nothing to explain now). */
    val driftRequested: Boolean = false,
    val driftWindow: DriftWindow.Window? = null,
    val driftSummary: DriftWindow.Summary? = null,
)

/** One account's transactions and change log, merged; see [AccountTimeline]. */
class AccountHistoryViewModel(
    savedStateHandle: SavedStateHandle,
    container: AppContainer,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>("accountId") ?: -1L
    private val driftRequested: Boolean = savedStateHandle.get<Boolean>("drift") ?: false
    private val retentionMonths = container.preferences.transactionRetentionMonths

    private val filter = MutableStateFlow(AccountTimeline.Filter.ALL)

    val hideBalances: StateFlow<Boolean> = container.hideBalances

    val uiState: StateFlow<AccountHistoryUiState> = combine(
        container.accountRepository.observeActive(),
        container.accountRepository.observeArchived(),
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeChanges(accountId),
        filter,
    ) { active, archived, transactions, changes, selected ->
        val account = (active + archived).firstOrNull { it.id == accountId }
        val now = System.currentTimeMillis()
        val window = if (driftRequested && account != null) {
            DriftWindow.find(
                account,
                transactions,
                if (retentionMonths > 0) TransactionRetentionPlanner.cutoffMillis(now, retentionMonths) else null,
            )
        } else {
            null
        }
        val built = account?.let { AccountTimeline.build(it, transactions, changes, selected) }
        AccountHistoryUiState(
            account = account,
            result = if (built != null && window != null) AccountTimeline.restrictTo(built, window) else built,
            filter = selected,
            loaded = true,
            driftRequested = driftRequested,
            driftWindow = window,
            driftSummary = window?.let { DriftWindow.summarize(it, transactions, changes) },
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountHistoryUiState())

    fun setFilter(value: AccountTimeline.Filter) {
        filter.value = value
    }
}

/**
 * "+12.00" / "−12.00", or just the mask while balances are hidden -- a sign
 * alone would leak whether money came or went. [format] renders the magnitude.
 */
internal fun signedMoney(amount: BigDecimal, hidden: Boolean, format: (BigDecimal) -> String): String =
    if (hidden) AMOUNT_MASK else (if (amount.signum() < 0) "−" else "+") + format(amount.abs())

/** A change line's old -> new values, masked while hidden when they carry amounts. */
internal fun valuesLabel(from: String?, to: String?, sensitive: Boolean, hidden: Boolean): String? = when {
    from == null && to == null -> null
    sensitive && hidden -> AMOUNT_MASK
    from != null && to != null -> "$from → $to"
    to != null -> to
    else -> from
}

@Composable
fun AccountHistoryScreen(
    onBack: () -> Unit,
    viewModel: AccountHistoryViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    val account = state.account
    val result = state.result

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Column {
                Text("History", style = MaterialTheme.typography.titleLarge)
                account?.let { MetaLabel(it.name) }
            }
        }

        if (account == null || result == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    if (state.loaded) "This account no longer exists." else "Loading…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MMTheme.colors.textSecondary,
                )
            }
            return@Column
        }

        if (state.driftRequested) {
            DriftBanner(state.driftWindow, state.driftSummary, hidden)
        }
        SummaryCard(result, hidden)
        // Narrowed to the drift window, the filters would only confuse; they come back on the full history.
        if (state.driftWindow == null) FilterRow(selected = state.filter, onSelect = viewModel::setFilter)

        if (result.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(emptyMessage(state.filter, result.changeCount), style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textSecondary)
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(result.items, key = { it.key }) { item ->
                    when (item) {
                        is AccountTimeline.ChangeItem -> ChangeRow(item, result.currencyCode, hidden)
                        is AccountTimeline.TransactionItem -> TransactionRow(item, result.currencyCode, hidden)
                    }
                }
            }
        }
    }
}

internal fun emptyMessage(filter: AccountTimeline.Filter, changeCount: Int): String = when {
    filter == AccountTimeline.Filter.ALL ->
        "Nothing here yet. Transactions on this account, and every change to it, appear in this list."
    changeCount == 0 ->
        "No changes recorded yet. From now on, anything that moves this balance (edits, moves, deletions, adjustments) is logged here. Earlier changes weren't recorded."
    else -> "Nothing under this filter."
}

@Composable
private fun SummaryCard(result: AccountTimeline.Result, hidden: Boolean) {
    MMCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MetaLabel("Balance")
            Text(
                (if (result.mixedCurrency) "≈ " else "") + moneyLabel(result.currentBalance, result.currencyCode, hidden),
                style = MaterialTheme.typography.titleLarge,
                color = MMTheme.colors.textPrimary,
            )
            Text(
                "Opening balance ${moneyLabel(result.openingBalance, result.currencyCode, hidden)}, then every transaction below.",
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
            if (result.mixedCurrency) {
                Text(
                    "Some transactions are in another currency and are added at face value here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
        }
    }
}

/** Says what the list below is narrowed to: when the books and the bank's balance parted ways. */
@Composable
private fun DriftBanner(window: DriftWindow.Window?, summary: DriftWindow.Summary?, hidden: Boolean) {
    MMCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MetaLabel("What changed")
            Text(
                if (window == null || summary == null) {
                    "No difference to explain right now: the books match the bank's last balance, or there isn't a bank balance to compare."
                } else {
                    DriftWindow.describe(
                        window = window,
                        summary = summary,
                        hidden = hidden,
                        money = { v, c -> moneyLabel(v, c, hidden) },
                        date = { it.asDateTime() },
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MMTheme.colors.textPrimary,
            )
            if (window != null) {
                Text(
                    "Showing only what could explain it. Edits made before the last matching balance can't have caused it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(selected: AccountTimeline.Filter, onSelect: (AccountTimeline.Filter) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AccountTimeline.Filter.entries.forEach { f ->
            MMFilterChip(label = f.label, selected = f == selected, onClick = { onSelect(f) })
        }
    }
}

@Composable
private fun ChangeRow(item: AccountTimeline.ChangeItem, currencyCode: String, hidden: Boolean) {
    val line = item.line
    MMCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.headline, style = MaterialTheme.typography.titleSmall, color = MMTheme.colors.textPrimary)
                valuesLabel(line.from, line.to, line.sensitive, hidden)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textPrimary)
                }
                line.extra?.takeIf { !(line.sensitive && hidden) }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)
                }
                Text(
                    "${item.timestampMillis.asDateTime()} · by ${line.by}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
            item.delta?.let { d ->
                Text(
                    signedMoney(d, hidden) { moneyLabel(it, currencyCode) },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hidden || d.signum() <= 0) MMTheme.colors.textPrimary else MMTheme.colors.moneyIn,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TransactionRow(item: AccountTimeline.TransactionItem, currencyCode: String, hidden: Boolean) {
    val tx = item.transaction
    MMCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    tx.merchant?.takeIf { it.isNotBlank() } ?: tx.kind.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MMTheme.colors.textPrimary,
                )
                Text(
                    "${tx.timestampMillis.asDateTime()} · ${tx.kind.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    signedMoney(item.delta, hidden) { moneyLabel(it, tx.currencyCode) },
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hidden || tx.direction == TransactionDirection.DEBIT) MMTheme.colors.textPrimary else MMTheme.colors.moneyIn,
                )
                Text(
                    "Balance ${moneyLabel(item.balanceAfter, currencyCode, hidden)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MMTheme.colors.textSecondary,
                )
            }
        }
    }
}
