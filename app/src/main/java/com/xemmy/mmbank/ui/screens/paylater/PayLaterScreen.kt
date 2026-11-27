package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.PayLaterInstallment
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.paylater.PayLaterSchedule
import com.xemmy.mmbank.domain.paylater.PayLaterSummary
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PayLaterUiState(
    val plans: List<PayLaterPlan> = emptyList(),
    val installments: List<PayLaterInstallment> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val loaded: Boolean = false,
)

/** Shared by the Pay Later list, plan detail and plan editor. */
class PayLaterViewModel(private val container: AppContainer) : ViewModel() {

    private val repo get() = container.payLaterRepository

    val uiState: StateFlow<PayLaterUiState> = combine(
        repo.observePlans(),
        repo.observeInstallments(),
        container.accountRepository.observeActive(),
    ) { plans, installments, accounts -> PayLaterUiState(plans, installments, accounts, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PayLaterUiState())

    val hideBalances: StateFlow<Boolean> = container.hideBalances

    val defaultCurrency: String get() = container.preferences.defaultCurrency

    private fun run(tag: String, block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { container.recordError(tag, it) } }
    }

    fun pay(installmentId: Long, fromAccountId: Long, paidAtMillis: Long) =
        run("PayLaterViewModel.pay") { repo.pay(installmentId, fromAccountId, paidAtMillis) }

    fun undo(installmentId: Long) = run("PayLaterViewModel.undo") { repo.undoPayment(installmentId) }

    fun reschedule(installmentId: Long, dueDateMillis: Long) =
        run("PayLaterViewModel.reschedule") { repo.reschedule(installmentId, dueDateMillis) }

    fun deletePlan(planId: Long) = run("PayLaterViewModel.deletePlan") { repo.deletePlan(planId) }

    fun createPlan(plan: PayLaterPlan, installments: List<PayLaterInstallment>) =
        run("PayLaterViewModel.createPlan") { repo.createPlan(plan, installments) }

    fun updatePlan(plan: PayLaterPlan) = run("PayLaterViewModel.updatePlan") { repo.updatePlan(plan) }

    suspend fun loadPlan(planId: Long): PayLaterPlan? = repo.getPlan(planId)

    suspend fun loadAccounts(): List<Account> = container.accountRepository.observeActive().first()

    suspend fun loadTransaction(id: Long): Transaction? =
        container.transactionRepository.observeTransactions().first().firstOrNull { it.id == id }
}

/**
 * Pay Later purchases: what is due next, the total still owed, and every upcoming installment
 * grouped by date, each with a Pay (records a real transaction) and Reschedule action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayLaterScreen(
    onBack: () -> Unit,
    onAddPlan: () -> Unit,
    onOpenPlan: (Long) -> Unit,
    viewModel: PayLaterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = com.xemmy.mmbank.ui.util.rememberCurrentDate(zone)
    var showHistory by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<PayLaterSummary.Row?>(null) }
    var rescheduling by remember { mutableStateOf<PayLaterSummary.Row?>(null) }

    val unpaid = remember(state.plans, state.installments) { PayLaterSummary.unpaid(state.plans, state.installments, today, zone) }
    val history = remember(state.plans, state.installments) { PayLaterSummary.history(state.plans, state.installments, today, zone) }
    val next = PayLaterSummary.nextDue(unpaid)
    val totals = PayLaterSummary.totalsByCurrency(unpaid)
    val shown = if (showHistory) history else unpaid
    val groups = PayLaterSummary.groupByDate(shown).let { if (showHistory) it.reversed() else it }

    MMScaffold(title = "Pay Later", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") {
                MMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (next == null) {
                            Text("Nothing due", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Add a Pay Later purchase to track what you owe and when it is due.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MMTheme.colors.textSecondary,
                            )
                        } else {
                            Text(
                                PayLaterSummary.dueLabel(next.daysUntil),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (next.daysUntil < 0) MMTheme.colors.danger else MMTheme.colors.textSecondary,
                            )
                            Text(
                                moneyLabel(next.amount, next.currencyCode, hidden),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                            )
                            totals.forEach { (currency, total) ->
                                Text(
                                    "Total due ${moneyLabel(total, currency, hidden)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MMTheme.colors.textSecondary,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            Button(onClick = onAddPlan) { Text("Add plan") }
                            OutlinedButton(onClick = { showHistory = !showHistory }) {
                                Text(if (showHistory) "Upcoming" else "View history")
                            }
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Text(
                        when {
                            !state.loaded -> "Loading…"
                            showHistory -> "No payments made yet."
                            else -> "No upcoming payments."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MMTheme.colors.textSecondary,
                    )
                }
            }
            groups.forEach { (date, rows) ->
                item(key = "d_${showHistory}_$date") {
                    Text(
                        date.format(PAY_LATER_DATE_LABEL),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                item(key = "c_${showHistory}_$date") {
                    MMCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            rows.forEach { row ->
                                InstallmentRow(
                                    row = row,
                                    hidden = hidden,
                                    history = showHistory,
                                    onOpen = { onOpenPlan(row.plan.id) },
                                    onPay = { paying = row },
                                    onReschedule = { rescheduling = row },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    paying?.let { row ->
        PayDialog(
            row = row,
            accounts = state.accounts,
            onDismiss = { paying = null },
            onConfirm = { accountId, millis ->
                viewModel.pay(row.installment.id, accountId, millis)
                paying = null
            },
        )
    }
    rescheduling?.let { row ->
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = row.dueDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { rescheduling = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.reschedule(row.installment.id, PayLaterSchedule.millisOf(date, zone))
                    }
                    rescheduling = null
                }) { Text("Reschedule") }
            },
            dismissButton = { TextButton(onClick = { rescheduling = null }) { Text("Cancel") } },
        ) { DatePicker(state = pickerState) }
    }
}

@Composable
private fun InstallmentRow(
    row: PayLaterSummary.Row,
    hidden: Boolean,
    history: Boolean,
    onOpen: () -> Unit,
    onPay: () -> Unit,
    onReschedule: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.plan.merchant, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(row.label, style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)
            Text(
                moneyLabel(row.installment.amount, row.plan.currencyCode, hidden),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!history) {
            TextButton(onClick = onReschedule) { Text("Reschedule") }
            Button(onClick = onPay) { Text("Pay") }
        } else {
            Text("Paid", style = MaterialTheme.typography.labelLarge, color = MMTheme.colors.textSecondary)
        }
    }
}

/** Pick the account an installment is paid from and the date it was paid; confirming records the transaction. */
@Composable
internal fun PayDialog(
    row: PayLaterSummary.Row,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (accountId: Long, paidAtMillis: Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    var accountId by remember { mutableStateOf(row.plan.payFromAccountId?.takeIf { id -> accounts.any { it.id == id } }) }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pay ${row.plan.merchant}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${row.label} · ${moneyLabel(row.installment.amount, row.plan.currencyCode)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AccountPickField("Pay from", accounts, accountId, allowNone = false, onPick = { accountId = it })
                DatePickField("Paid on", date, onPick = { date = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = accountId != null,
                onClick = { accountId?.let { onConfirm(it, PayLaterSchedule.millisOf(date, zone)) } },
            ) { Text("Record payment") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
