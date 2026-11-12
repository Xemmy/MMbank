package com.xemmy.mmbank.ui.screens.integrity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.xemmy.mmbank.domain.ai.TransactionRetentionPlanner
import com.xemmy.mmbank.domain.ledger.IntegrityChecker
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Issue
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Severity
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Type
import com.xemmy.mmbank.domain.ledger.IntegrityWording
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.screens.accounts.buildBalanceAdjustment
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.recoverAndRetry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class IntegrityUiState(
    val report: IntegrityChecker.Report = IntegrityChecker.Report(emptyList(), emptyMap()),
    val accounts: List<Account> = emptyList(),
    val transactions: Map<Long, Transaction> = emptyMap(),
    val loaded: Boolean = false,
)

/**
 * Runs [IntegrityChecker] over the live ledger and offers the one-tap fixes.
 * Every fix goes through an existing, reversible path (a balance adjustment
 * row, a type change), and the report recomputes by itself when it lands.
 */
class IntegrityViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<IntegrityUiState> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeTransactions(),
    ) { accounts, transactions ->
        val now = System.currentTimeMillis()
        val months = container.preferences.transactionRetentionMonths
        val cutoff = if (months > 0) TransactionRetentionPlanner.cutoffMillis(now, months) else null
        IntegrityUiState(
            report = IntegrityChecker.check(accounts, transactions, now, cutoff),
            accounts = accounts,
            transactions = transactions.associateBy { it.id },
            loaded = true,
        )
    }.recoverAndRetry("IntegrityViewModel", container) { IntegrityUiState(loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntegrityUiState())

    val hideBalances: StateFlow<Boolean> = container.hideBalances

    /**
     * Writes the missing difference as a balance adjustment dated at the
     * moment the bank stated its balance, which is exactly what makes the
     * check pass again (it compares the books as of that moment).
     */
    fun recordDifference(issue: Issue) = launchSafely("recordDifference") {
        val account = uiState.value.accounts.firstOrNull { it.id == issue.accountId } ?: return@launchSafely
        val adjustment = buildBalanceAdjustment(
            account = account,
            computedBalance = issue.computedBalance ?: return@launchSafely,
            targetBalance = issue.bankBalance ?: return@launchSafely,
            nowMillis = issue.asOfMillis ?: return@launchSafely,
        ) ?: return@launchSafely
        container.transactionRepository.recordManual(adjustment)
    }

    /** "Yes, this really is a transfer / card payment": the user vouches for it, so it stops being flagged. */
    fun markCorrect(issue: Issue) = launchSafely("markCorrect") {
        val tx = issue.transactionIds.firstOrNull()?.let { uiState.value.transactions[it] } ?: return@launchSafely
        container.ledgerRepository.setKind(tx.id, tx.kind, rememberForMerchant = false)
    }

    /** "No, it's an ordinary purchase / income": re-types it, which also releases any link. */
    fun makeOrdinary(issue: Issue) = launchSafely("makeOrdinary") {
        issue.transactionIds.mapNotNull { uiState.value.transactions[it] }.forEach { tx ->
            container.ledgerRepository.setKind(tx.id, TransactionKind.defaultFor(tx.direction), rememberForMerchant = false)
        }
    }

    private fun launchSafely(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { container.recordError("IntegrityViewModel.$what", it) }
        }
    }
}

@Composable
fun IntegrityScreen(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenUnassigned: () -> Unit,
    onOpenStatement: () -> Unit,
    viewModel: IntegrityViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    val names = state.accounts.associate { it.id to it.name }
    val reconciled = state.report.reconciliations.values.count { it.status == IntegrityChecker.Status.MATCHES }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Integrity check", style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "summary") {
                Text(
                    when {
                        !state.loaded -> "Checking…"
                        state.report.issues.isEmpty() && reconciled > 0 ->
                            "Everything adds up. $reconciled ${if (reconciled == 1) "account matches" else "accounts match"} " +
                                "the balance your bank last reported."
                        state.report.issues.isEmpty() ->
                            "Nothing to flag. MMbank checks accounts against the balance your bank prints in its " +
                                "messages; none has reported one yet."
                        else -> "${state.report.actionableCount} to look at, " +
                            "${state.report.issues.size - state.report.actionableCount} hint(s). " +
                            "MMbank compares its books with the balance your bank prints in its messages."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "statement") {
                androidx.compose.material3.OutlinedButton(onClick = onOpenStatement) {
                    Text("Compare with a bank statement")
                }
            }
            items(state.report.issues, key = { it.hashCode() }) { issue ->
                val text = IntegrityWording.describe(
                    issue = issue,
                    accountName = issue.accountId?.let(names::get),
                    money = { v, c -> moneyLabel(v, c, hidden) },
                    date = { it.asDate() },
                )
                IssueCard(
                    severity = issue.severity,
                    title = text.title,
                    detail = text.detail,
                ) {
                    IssueActions(
                        issue = issue,
                        onRecordDifference = { viewModel.recordDifference(issue) },
                        onMarkCorrect = { viewModel.markCorrect(issue) },
                        onMakeOrdinary = { viewModel.makeOrdinary(issue) },
                        onOpenAccounts = onOpenAccounts,
                        onOpenUnassigned = onOpenUnassigned,
                    )
                }
            }
        }
    }
}

@Composable
private fun IssueCard(severity: Severity, title: String, detail: String, actions: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (severity) {
                Severity.PROBLEM -> MaterialTheme.colorScheme.errorContainer
                Severity.WARNING -> MaterialTheme.colorScheme.secondaryContainer
                Severity.INFO -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) { actions() }
        }
    }
}

@Composable
private fun IssueActions(
    issue: Issue,
    onRecordDifference: () -> Unit,
    onMarkCorrect: () -> Unit,
    onMakeOrdinary: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenUnassigned: () -> Unit,
) {
    when (issue.type) {
        Type.BALANCE_DRIFT -> Button(onClick = onRecordDifference) { Text("Record the difference") }
        Type.UNPAIRED_MOVE -> {
            OutlinedButton(onClick = onMarkCorrect) { Text("It's correct") }
            OutlinedButton(onClick = onMakeOrdinary) { Text("It's an ordinary one") }
        }
        Type.BROKEN_LINK -> OutlinedButton(onClick = onMakeOrdinary) { Text("Unlink") }
        Type.UNASSIGNED -> OutlinedButton(onClick = onOpenUnassigned) { Text("Assign accounts") }
        Type.CURRENCY_MISMATCH, Type.CARD_SETUP -> OutlinedButton(onClick = onOpenAccounts) { Text("Open accounts") }
    }
}
