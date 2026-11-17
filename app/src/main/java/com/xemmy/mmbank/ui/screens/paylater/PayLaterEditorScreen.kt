package com.xemmy.mmbank.ui.screens.paylater

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.PayLaterCadence
import com.xemmy.mmbank.domain.model.PayLaterPlan
import com.xemmy.mmbank.domain.paylater.PayLaterSchedule
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MMScaffold
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.util.AmountInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Add or edit a Pay Later plan. Editing changes the plan's details only (merchant, provider,
 * accounts, note) -- its schedule is fixed once created; delete and re-add to change it.
 * With [transactionId] the form is prefilled from that captured purchase ("Split with Pay Later").
 */
@Composable
fun PayLaterEditorScreen(
    planId: Long?,
    transactionId: Long?,
    onClose: () -> Unit,
    viewModel: PayLaterViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val zone = remember { ZoneId.systemDefault() }
    val isEdit = planId != null
    var existing by remember { mutableStateOf<PayLaterPlan?>(null) }

    var merchant by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("Tabby") }
    var total by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf(viewModel.defaultCurrency) }
    var count by rememberSaveable { mutableStateOf("4") }
    var cadence by rememberSaveable { mutableStateOf(PayLaterCadence.MONTHLY.name) }
    var firstDueDay by rememberSaveable { mutableStateOf(LocalDate.now(zone).plusMonths(1).toEpochDay()) }
    var firstPaid by rememberSaveable { mutableStateOf(false) }
    var payFromId by rememberSaveable { mutableStateOf<Long?>(null) }
    var payToId by rememberSaveable { mutableStateOf<Long?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var sourceTransactionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var prefilled by rememberSaveable { mutableStateOf(false) }
    var cadenceOpen by remember { mutableStateOf(false) }

    LaunchedEffect(planId, transactionId) {
        // The loaded plan is not saved across rotation, so it is always re-read; the fields are filled once.
        if (planId != null) {
            val p = viewModel.loadPlan(planId)
            existing = p
            if (!prefilled && p != null) {
                merchant = p.merchant; provider = p.provider; note = p.note.orEmpty()
                payFromId = p.payFromAccountId; payToId = p.payToAccountId
                currency = p.currencyCode; total = p.totalAmount.toPlainString()
                count = p.installmentCount.toString(); cadence = p.cadence.name
                sourceTransactionId = p.sourceTransactionId; categoryId = p.categoryId
            }
        } else if (transactionId != null && !prefilled) {
            viewModel.loadTransaction(transactionId)?.let { tx ->
                merchant = tx.merchant.orEmpty()
                total = tx.amount.toPlainString()
                currency = tx.currencyCode
                sourceTransactionId = tx.id
                categoryId = tx.categoryId
                firstDueDay = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate().plusMonths(1).toEpochDay()
                // Tabby takes the first payment at checkout.
                firstPaid = true
                val account = viewModel.loadAccounts().firstOrNull { it.id == tx.accountId }
                if (account != null && (account.type == AccountType.CREDIT_CARD || account.type == AccountType.LOAN)) {
                    payToId = account.id
                }
            }
        }
        prefilled = true
    }

    val totalValue = AmountInput.parse(total)
    val countValue = count.trim().toIntOrNull()
    val cadenceValue = PayLaterCadence.entries.firstOrNull { it.name == cadence } ?: PayLaterCadence.MONTHLY
    val valid = merchant.isNotBlank() && (
        isEdit || (
            totalValue != null && totalValue.signum() > 0 &&
                countValue != null && countValue in PayLaterSchedule.MIN_COUNT..PayLaterSchedule.MAX_COUNT &&
                currency.isNotBlank()
            )
        )

    fun save() {
        if (!valid) return
        val current = existing
        if (isEdit) {
            if (current != null) {
                viewModel.updatePlan(
                    current.copy(
                        merchant = merchant.trim(),
                        provider = provider.trim().ifEmpty { "Tabby" },
                        payFromAccountId = payFromId,
                        payToAccountId = payToId,
                        note = note.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            }
        } else {
            val amount = totalValue ?: return
            val n = countValue ?: return
            val plan = PayLaterPlan(
                merchant = merchant.trim(),
                provider = provider.trim().ifEmpty { "Tabby" },
                totalAmount = amount,
                currencyCode = currency.trim().uppercase(),
                installmentCount = n,
                cadence = cadenceValue,
                payFromAccountId = payFromId,
                payToAccountId = payToId,
                sourceTransactionId = sourceTransactionId,
                categoryId = categoryId,
                note = note.trim().takeIf { it.isNotEmpty() },
                createdAt = System.currentTimeMillis(),
            )
            viewModel.createPlan(
                plan,
                PayLaterSchedule.generate(amount, n, LocalDate.ofEpochDay(firstDueDay), cadenceValue, zone, firstPaid),
            )
        }
        onClose()
    }

    MMScaffold(
        title = if (isEdit) "Edit plan" else "New Pay Later plan",
        onBack = onClose,
        actions = { TextButton(enabled = valid, onClick = ::save) { Text("Save") } },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = merchant, onValueChange = { merchant = it }, label = { Text("Merchant") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = provider, onValueChange = { provider = it }, label = { Text("Provider") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (!isEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = total, onValueChange = { total = it }, label = { Text("Total") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = total.isNotBlank() && totalValue == null,
                        modifier = Modifier.weight(0.62f),
                    )
                    OutlinedTextField(
                        value = currency, onValueChange = { currency = it.uppercase().take(3) }, label = { Text("Currency") },
                        singleLine = true, modifier = Modifier.weight(0.38f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = count, onValueChange = { count = it.filter(Char::isDigit).take(2) },
                        label = { Text("Payments") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = countValue == null || countValue !in PayLaterSchedule.MIN_COUNT..PayLaterSchedule.MAX_COUNT,
                        modifier = Modifier.weight(0.4f),
                    )
                    Column(Modifier.weight(0.6f)) {
                        Text("Repeats", style = MaterialTheme.typography.labelMedium, color = MMTheme.colors.textSecondary)
                        OutlinedButton(onClick = { cadenceOpen = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(cadenceValue.name.lowercase().replaceFirstChar(Char::uppercase))
                        }
                        DropdownMenu(expanded = cadenceOpen, onDismissRequest = { cadenceOpen = false }) {
                            PayLaterCadence.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.name.lowercase().replaceFirstChar(Char::uppercase)) },
                                    onClick = { cadence = option.name; cadenceOpen = false },
                                )
                            }
                        }
                    }
                }
                DatePickField("First payment due", LocalDate.ofEpochDay(firstDueDay), onPick = { firstDueDay = it.toEpochDay() })
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("First payment already paid", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Tabby takes the first payment at checkout",
                            style = MaterialTheme.typography.bodySmall,
                            color = MMTheme.colors.textSecondary,
                        )
                    }
                    Switch(checked = firstPaid, onCheckedChange = { firstPaid = it })
                }
            }
            AccountPickField("Pay from", state.accounts, payFromId, allowNone = true, onPick = { payFromId = it })
            AccountPickField("Provider account (optional)", state.accounts, payToId, allowNone = true, onPick = { payToId = it })
            Text(
                if (sourceTransactionId != null) {
                    "The purchase is already in your transactions, so payments won't be counted as spending again."
                } else {
                    "Payments are recorded as spending on the merchant when you pay them."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
            OutlinedTextField(
                value = note, onValueChange = { note = it }, label = { Text("Note (optional)") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
