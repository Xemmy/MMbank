package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.paylater.PayLaterSummary
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.time.LocalDate
import java.time.ZoneId

/** One plan: every installment with its paid / unpaid state, undo for paid ones, edit and delete. */
@Composable
fun PayLaterDetailScreen(
    planId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    viewModel: PayLaterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val plan = state.plans.firstOrNull { it.id == planId }
    var confirmDelete by remember { mutableStateOf(false) }
    var paying by remember { mutableStateOf<PayLaterSummary.Row?>(null) }

    // A plan deleted from here (or elsewhere) just closes the page.
    LaunchedEffect(state.loaded, plan == null) { if (state.loaded && plan == null) onBack() }
    if (plan == null) {
        MMScaffold(title = "Pay Later", onBack = onBack) { }
        return
    }

    val installments = state.installments.filter { it.planId == planId }.sortedBy { it.number }
    val rows = PayLaterSummary.unpaid(listOf(plan), installments, today, zone) +
        PayLaterSummary.history(listOf(plan), installments, today, zone)
    val rowByInstallment = rows.associateBy { it.installment.id }
    val paidCount = installments.count { it.isPaid }
    val remaining = installments.filter { !it.isPaid }.fold(java.math.BigDecimal.ZERO) { a, i -> a + i.amount }

    MMScaffold(
        title = plan.merchant,
        onBack = onBack,
        actions = {
            TextButton(onClick = { onEdit(plan.id) }) { Text("Edit") }
            TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MMTheme.colors.danger) }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "summary") {
                MMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "${plan.provider} · ${plan.installmentCount} ${plan.cadence.name.lowercase()} payments",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MMTheme.colors.textSecondary,
                        )
                        Text(
                            "Total ${moneyLabel(plan.totalAmount, plan.currencyCode, hidden)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (paidCount == installments.size && installments.isNotEmpty()) {
                                "All paid"
                            } else {
                                "$paidCount of ${installments.size} paid · ${moneyLabel(remaining, plan.currencyCode, hidden)} left"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        plan.note?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MMTheme.colors.textSecondary)
                        }
                    }
                }
            }
            items(installments.size, key = { installments[it].id }) { index ->
                val installment = installments[index]
                val row = rowByInstallment[installment.id]
                MMCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Payment ${installment.number} of ${plan.installmentCount}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                moneyLabel(installment.amount, plan.currencyCode, hidden) +
                                    (row?.let { " · ${it.dueDate.format(PAY_LATER_DATE_LABEL)}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MMTheme.colors.textSecondary,
                            )
                            if (installment.isPaid) {
                                Text("Paid", style = MaterialTheme.typography.labelMedium, color = MMTheme.colors.textSecondary)
                            } else if (row != null && row.daysUntil < 0) {
                                Text(
                                    PayLaterSummary.dueLabel(row.daysUntil),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MMTheme.colors.danger,
                                )
                            }
                        }
                        if (installment.isPaid) {
                            OutlinedButton(onClick = { viewModel.undo(installment.id) }) { Text("Undo") }
                        } else if (row != null) {
                            Button(onClick = { paying = row }) { Text("Pay") }
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
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete plan?") },
            text = {
                Text(
                    "This removes the plan and its schedule. Transactions already recorded for paid " +
                        "installments are kept.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deletePlan(plan.id) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
