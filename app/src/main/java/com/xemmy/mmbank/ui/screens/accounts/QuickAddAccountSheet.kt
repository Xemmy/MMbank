package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xemmy.mmbank.domain.account.AccountUpsertResolver
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.ui.designsystem.MMBottomSheet
import com.xemmy.mmbank.ui.designsystem.MMFilterChip
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import java.math.BigDecimal

/**
 * The short way to add anything that holds money: a type, a name, today's
 * balance and, if you have it, the last digits. Adding something that already
 * exists never makes a copy -- [plan] finds it and the prompt offers to update
 * it instead (see [QuickAddPlanner]). "More details" hands whatever is typed to
 * the full form.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickAddAccountSheet(
    defaultCurrency: String,
    hidden: Boolean,
    plan: (QuickAddInput) -> QuickAddPlan,
    onCreate: (Account) -> Unit,
    onUpdate: (QuickAddPlan.Update, updateBalance: Boolean) -> Unit,
    planUpdateOf: (QuickAddPlan.Choose, Account, BigDecimal?) -> QuickAddPlan.Update,
    onMoreDetails: (Account) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(QuickAddInput(currency = defaultCurrency)) }
    var showErrors by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<QuickAddPlan?>(null) }
    val errors = AccountFormValidator.errors(AccountFormInput(input.name, input.type, input.currency, input.balance))
    val owed = input.type.isCreditCard() || input.type.isLoan()

    MMBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = true) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Add an account", style = MaterialTheme.typography.titleLarge, color = MMTheme.colors.textPrimary)
            Text(
                "Add it again later and it updates what you have instead of making a copy.",
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.xemmy.mmbank.domain.model.AccountType.entries.forEach { type ->
                    MMFilterChip(label = accountTypeLabel(type), selected = type == input.type, onClick = { input = input.copy(type = type) })
                }
            }
            OutlinedTextField(
                value = input.name,
                onValueChange = { input = input.copy(name = it) },
                label = { Text("Name") },
                isError = showErrors && errors.containsKey(AccountField.NAME),
                supportingText = (if (showErrors) errors[AccountField.NAME] else null)?.let { { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = input.currency,
                    onValueChange = { input = input.copy(currency = it.uppercase().take(3)) },
                    label = { Text("Currency") },
                    isError = showErrors && errors.containsKey(AccountField.CURRENCY),
                    singleLine = true,
                    modifier = Modifier.weight(0.4f),
                )
                OutlinedTextField(
                    value = input.balance,
                    onValueChange = { input = input.copy(balance = it) },
                    label = { Text("Current balance") },
                    isError = showErrors && errors.containsKey(AccountField.OPENING),
                    supportingText = { Text(if (owed) "Negative = what you owe." else "What it shows right now.") },
                    singleLine = true,
                    modifier = Modifier.weight(0.6f),
                )
            }
            OutlinedTextField(
                value = input.lastDigits,
                onValueChange = { input = input.copy(lastDigits = it.filter(Char::isDigit).take(24)) },
                label = { Text("Last digits (optional)") },
                supportingText = { Text("Lets the app recognise it again, and file captured messages here.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) { Text("Paste bank details") }
                OutlinedButton(
                    onClick = { onMoreDetails(QuickAddPlanner.draftForFullForm(input, defaultCurrency)) },
                    modifier = Modifier.weight(1f),
                ) { Text("More details") }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when (val result = plan(input)) {
                        is QuickAddPlan.Invalid -> showErrors = true
                        is QuickAddPlan.Create -> {
                            onCreate(result.account)
                            onDismiss()
                        }
                        else -> pending = result
                    }
                },
            ) { Text("Save") }
        }
    }

    if (showPaste) {
        PasteDetailsDialog(
            onDismiss = { showPaste = false },
            onParsed = { d ->
                input = input.copy(
                    bankName = d.bankName ?: input.bankName,
                    accountTitle = d.accountTitle ?: input.accountTitle,
                    accountNumber = d.accountNumber ?: input.accountNumber,
                    iban = d.iban ?: input.iban,
                    swift = d.swift ?: input.swift,
                    currency = d.currencyCode ?: input.currency,
                    name = input.name.ifBlank { d.bankName.orEmpty() },
                )
                showPaste = false
            },
        )
    }

    when (val p = pending) {
        is QuickAddPlan.Update -> UpdatePrompt(
            plan = p,
            hidden = hidden,
            onUpdate = {
                onUpdate(p, true)
                pending = null
                onDismiss()
            },
            onCreateSeparate = {
                onCreate(p.draft)
                pending = null
                onDismiss()
            },
            onCancel = { pending = null },
        )
        is QuickAddPlan.Choose -> DuplicateAccountDialog(
            title = "Which one is it?",
            lines = listOf("Several accounts match the ${p.matchedOn.label} you typed."),
            actions = p.accounts.map { account ->
                DuplicateAction("Update ${account.name}", primary = true) {
                    val entered = if (input.balance.isBlank()) null else parseMoneyInput(input.balance)
                    onUpdate(planUpdateOf(p, account, entered), true)
                    pending = null
                    onDismiss()
                }
            } + DuplicateAction("None of these, create new") {
                onCreate(p.draft)
                pending = null
                onDismiss()
            },
            onCancel = { pending = null },
        )
        else -> Unit
    }
}

@Composable
private fun UpdatePrompt(
    plan: QuickAddPlan.Update,
    hidden: Boolean,
    onUpdate: () -> Unit,
    onCreateSeparate: () -> Unit,
    onCancel: () -> Unit,
) {
    val account = plan.existing
    val weak = plan.strength == AccountUpsertResolver.Strength.WEAK
    DuplicateAccountDialog(
        title = if (weak) "Same as “${account.name}”?" else "You already have “${account.name}”",
        lines = QuickAddPlanner.summary(plan) { moneyLabel(it, account.currencyCode, hidden) },
        actions = buildList {
            add(DuplicateAction(QuickAddPlanner.primaryLabel(plan), primary = true, onClick = onUpdate))
            if (weak) add(DuplicateAction("No, create a separate one", onClick = onCreateSeparate))
        },
        onCancel = onCancel,
    )
}

internal data class DuplicateAction(val label: String, val primary: Boolean = false, val onClick: () -> Unit)

/** "That already exists" prompt shared by quick add and the full form: a message, a stack of choices, Cancel. */
@Composable
internal fun DuplicateAccountDialog(
    title: String,
    lines: List<String>,
    actions: List<DuplicateAction>,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textSecondary)
                }
                actions.forEach { action ->
                    if (action.primary) {
                        FilledTonalButton(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
                    } else {
                        OutlinedButton(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
                    }
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        },
        confirmButton = {},
    )
}
