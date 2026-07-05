package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.parser.BankDetailsParser
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AccountWithBalance(val account: Account, val balance: Double)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {

    /** Set when a database write or the accounts list itself fails, instead of crashing. */
    val lastError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<List<AccountWithBalance>> = combine(
        container.accountRepository.observeActive(),
        container.transactionRepository.observeNetByAccount(),
    ) { accounts, netByAccount ->
        accounts.map { account ->
            AccountWithBalance(
                account = account,
                balance = account.openingBalance.toDouble() + (netByAccount[account.id] ?: 0.0),
            )
        }
    }.catch { e ->
        container.recordError("AccountsViewModel", e)
        lastError.value = "Couldn't load accounts: ${e.message}"
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(account: Account) {
        viewModelScope.launch {
            runCatching { container.accountRepository.save(account) }
                .onFailure {
                    container.recordError("AccountsViewModel.save", it)
                    lastError.value = "Couldn't save account: ${it.message}"
                }
        }
    }

    fun archive(account: Account) {
        viewModelScope.launch {
            runCatching { container.accountRepository.archive(account) }
                .onFailure {
                    container.recordError("AccountsViewModel.archive", it)
                    lastError.value = "Couldn't archive account: ${it.message}"
                }
        }
    }

    fun delete(account: Account) {
        viewModelScope.launch {
            runCatching { container.accountRepository.delete(account) }
                .onFailure {
                    container.recordError("AccountsViewModel.delete", it)
                    lastError.value = "Couldn't delete account: ${it.message}"
                }
        }
    }
}

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rows by viewModel.uiState.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    // null = closed; an Account with id 0 = "new"; an existing Account = "edit".
    var editing by remember { mutableStateOf<Account?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Account(name = "", type = AccountType.CHECKING, currencyCode = "AED") }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            lastError?.let { error ->
                item {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable { viewModel.lastError.value = null },
                    )
                }
            }
            if (rows.isEmpty()) {
                item {
                    Text(
                        "No accounts yet. Add your checking accounts, credit cards and loans, " +
                            "then assign captured transactions to them. Tap “+” to add one, or use " +
                            "“Paste bank details” to import an account from a copied statement.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(rows, key = { it.account.id }) { row ->
                AccountCard(
                    row = row,
                    onEdit = { editing = row.account },
                    onDelete = { viewModel.delete(row.account) },
                )
            }
        }
    }

    editing?.let { account ->
        AccountDialog(
            existing = account,
            onDismiss = { editing = null },
            onSave = { saved ->
                viewModel.save(saved)
                editing = null
            },
        )
    }
}

@Composable
private fun AccountCard(
    row: AccountWithBalance,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val a = row.account
    var confirmingDelete by remember { mutableStateOf(false) }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            a.bankName?.takeIf { it.isNotBlank() }?.let { append(it); append(" · ") }
                            append(a.type.name.replace('_', ' '))
                            append(" · ")
                            append(a.currencyCode)
                            a.accountNumber?.takeIf { it.isNotBlank() }?.let {
                                append(" · ••")
                                append(it.takeLast(4))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    row.balance.asMoney(a.currencyCode),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.balance < 0) LedgerColors.debit else MaterialTheme.colorScheme.onSurface,
                )
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete account")
                }
            }

            if (a.outstandingBalance != null || a.nextInstallmentAmount != null) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    a.outstandingBalance?.let {
                        LoanLine("Outstanding", it.asMoney(a.currencyCode))
                    }
                    a.nextInstallmentAmount?.let {
                        val due = a.dueDayOfMonth?.let { d -> " · due day $d" }.orEmpty()
                        LoanLine("Next installment", it.asMoney(a.currencyCode) + due)
                    }
                    a.profitRatePa?.let { LoanLine("Profit rate", String.format(java.util.Locale.US, "%.2f%% p.a.", it)) }
                    a.maturityDateMillis?.let { LoanLine("Maturity", it.asDate()) }
                    a.earlySettlementFeeEstimate?.let {
                        LoanLine("Est. early-settlement fee", "≈ " + it.asMoney(a.currencyCode) + " (1% of outstanding)")
                    }
                    settlementRecommendation(a)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete this account?") },
            text = {
                Text("“${a.name}” will be removed from your accounts list. Its captured transactions are kept but unlinked from it.")
            },
            confirmButton = {
                TextButton(onClick = { onDelete(); confirmingDelete = false }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LoanLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** Light, non-advisory heuristic to prompt the user to review an early settlement. */
private fun settlementRecommendation(a: Account): String? {
    val rate = a.profitRatePa ?: return null
    val outstanding = a.outstandingBalance ?: return null
    if (outstanding.signum() <= 0) return null
    return if (rate >= 5.0) {
        "High profit rate — review early settlement; it may beat the ~1% fee."
    } else {
        "Low profit rate — early settlement rarely worth the fee."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountDialog(
    existing: Account,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    val isNew = existing.id == 0L
    var name by remember { mutableStateOf(existing.name) }
    var type by remember { mutableStateOf(existing.type) }
    var currency by remember { mutableStateOf(existing.currencyCode) }
    var openingBalance by remember { mutableStateOf(existing.openingBalance.toPlainString()) }
    var accountNumber by remember { mutableStateOf(existing.accountNumber.orEmpty()) }
    var bankName by remember { mutableStateOf(existing.bankName.orEmpty()) }
    var accountTitle by remember { mutableStateOf(existing.accountTitle.orEmpty()) }
    var iban by remember { mutableStateOf(existing.iban.orEmpty()) }
    var swift by remember { mutableStateOf(existing.swift.orEmpty()) }
    var outstanding by remember { mutableStateOf(existing.outstandingBalance?.toPlainString().orEmpty()) }
    var nextInstallment by remember { mutableStateOf(existing.nextInstallmentAmount?.toPlainString().orEmpty()) }
    var dueDay by remember { mutableStateOf(existing.dueDayOfMonth?.toString().orEmpty()) }
    var profitRate by remember { mutableStateOf(existing.profitRatePa?.toString().orEmpty()) }
    var maturity by remember { mutableStateOf(existing.maturityDateMillis?.let(::millisToIsoDate).orEmpty()) }
    var loanNotes by remember { mutableStateOf(existing.loanNotes.orEmpty()) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }

    val opening = openingBalance.toBigDecimalOrNull()
    val valid = name.isNotBlank() && currency.trim().length == 3 && opening != null
    val showLoanFields = type == AccountType.LOAN || type == AccountType.CREDIT_CARD

    // Live field list for "Copy details" — reflects whatever is currently
    // typed/pasted in this dialog, not just the last-saved account.
    val copyFields: List<Pair<String, String>> = buildList {
        if (name.isNotBlank()) add("Name" to name)
        add("Type" to type.name.replace('_', ' '))
        if (currency.isNotBlank()) add("Currency" to currency)
        if (openingBalance.isNotBlank()) add("Opening balance" to openingBalance)
        if (accountNumber.isNotBlank()) add("Account/card number" to accountNumber)
        if (bankName.isNotBlank()) add("Bank name" to bankName)
        if (accountTitle.isNotBlank()) add("Account title" to accountTitle)
        if (iban.isNotBlank()) add("IBAN" to iban)
        if (swift.isNotBlank()) add("SWIFT/BIC" to swift)
        if (showLoanFields) {
            if (outstanding.isNotBlank()) add("Outstanding balance" to outstanding)
            if (nextInstallment.isNotBlank()) add("Next installment amount" to nextInstallment)
            if (dueDay.isNotBlank()) add("Installment due day" to dueDay)
            if (profitRate.isNotBlank()) add("Profit rate % p.a." to profitRate)
            if (maturity.isNotBlank()) add("Maturity date" to maturity)
            if (loanNotes.isNotBlank()) add("Loan notes" to loanNotes)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New account" else "Edit account") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showPaste = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Text("  Paste")
                    }
                    OutlinedButton(onClick = { showCopy = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null)
                        Text("  Copy details")
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                ExposedDropdownMenuBox(expanded = typeMenuOpen, onExpandedChange = { typeMenuOpen = it }) {
                    OutlinedTextField(
                        value = type.name.replace('_', ' '),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AccountType.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.name.replace('_', ' ')) },
                                onClick = {
                                    type = entry
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = currency,
                    onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Currency (ISO code)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = openingBalance,
                    onValueChange = { openingBalance = it },
                    label = { Text("Opening balance (negative for debt)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    label = { Text("Account/card number") },
                    supportingText = { Text("Used to auto-assign captured transactions to this account.") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = bankName,
                    onValueChange = { bankName = it },
                    label = { Text("Bank name") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = accountTitle,
                    onValueChange = { accountTitle = it },
                    label = { Text("Account title / holder") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = iban,
                    onValueChange = { iban = it },
                    label = { Text("IBAN") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = swift,
                    onValueChange = { swift = it },
                    label = { Text("SWIFT / BIC") },
                    singleLine = true,
                )

                if (showLoanFields) {
                    Text(
                        "Loan / financing",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    OutlinedTextField(
                        value = outstanding,
                        onValueChange = { outstanding = it },
                        label = { Text("Outstanding balance") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = nextInstallment,
                        onValueChange = { nextInstallment = it },
                        label = { Text("Next installment amount") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = dueDay,
                        onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
                        label = { Text("Installment due day (1-31)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = profitRate,
                        onValueChange = { profitRate = it },
                        label = { Text("Profit rate % p.a.") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = maturity,
                        onValueChange = { maturity = it },
                        label = { Text("Maturity date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = loanNotes,
                        onValueChange = { loanNotes = it },
                        label = { Text("Add-ons / early-settlement notes") },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        existing.copy(
                            name = name.trim(),
                            type = type,
                            currencyCode = currency.trim().uppercase(),
                            openingBalance = opening ?: BigDecimal.ZERO,
                            accountNumber = accountNumber.trim().takeIf { it.isNotBlank() },
                            bankName = bankName.trim().takeIf { it.isNotBlank() },
                            accountTitle = accountTitle.trim().takeIf { it.isNotBlank() },
                            iban = iban.trim().takeIf { it.isNotBlank() },
                            swift = swift.trim().takeIf { it.isNotBlank() },
                            outstandingBalance = outstanding.toBigDecimalOrNull(),
                            nextInstallmentAmount = nextInstallment.toBigDecimalOrNull(),
                            dueDayOfMonth = dueDay.toIntOrNull()?.takeIf { it in 1..31 },
                            profitRatePa = profitRate.toDoubleOrNull(),
                            maturityDateMillis = parseIsoDateToMillis(maturity),
                            loanNotes = loanNotes.trim().takeIf { it.isNotBlank() },
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (showPaste) {
        PasteDetailsDialog(
            onDismiss = { showPaste = false },
            onParsed = { d ->
                d.bankName?.let { bankName = it }
                d.accountTitle?.let { accountTitle = it }
                d.accountNumber?.let { accountNumber = it }
                d.iban?.let { iban = it }
                d.swift?.let { swift = it }
                d.currencyCode?.let { currency = it }
                if (name.isBlank()) name = d.bankName ?: name
                showPaste = false
            },
        )
    }

    if (showCopy) {
        CopyDetailsDialog(fields = copyFields, onDismiss = { showCopy = false })
    }
}

@Composable
private fun PasteDetailsDialog(
    onDismiss: () -> Unit,
    onParsed: (com.xemmy.mmbank.domain.model.ParsedBankDetails) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste bank details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a block like:\nBank Name: ADIB\nAccount Number: 17525963\nIBAN: AE90...\nSWIFT: ABDIAEADXXX\nCurrency: AED",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Pasted text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onParsed(BankDetailsParser.parse(text)) },
            ) { Text("Fill fields") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CopyDetailsDialog(
    fields: List<Pair<String, String>>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // Default: everything checked, so "copy all" is the zero-tap path;
    // unchecking individual rows is how you copy just a subset.
    val checked = remember(fields) {
        mutableStateMapOf<String, Boolean>().apply { fields.forEach { (label, _) -> put(label, true) } }
    }
    val allChecked = fields.isNotEmpty() && fields.all { checked[it.first] == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy details") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable {
                        val next = !allChecked
                        fields.forEach { (label, _) -> checked[label] = next }
                    },
                ) {
                    Checkbox(
                        checked = allChecked,
                        onCheckedChange = { v -> fields.forEach { (label, _) -> checked[label] = v } },
                    )
                    Text("Select all", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                fields.forEach { (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { checked[label] = !(checked[label] ?: false) },
                    ) {
                        Checkbox(
                            checked = checked[label] ?: false,
                            onCheckedChange = { v -> checked[label] = v },
                        )
                        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fields.any { checked[it.first] == true },
                onClick = {
                    val text = fields.filter { checked[it.first] == true }
                        .joinToString("\n") { (label, value) -> "$label: $value" }
                    clipboard.setText(AnnotatedString(text))
                    onDismiss()
                },
            ) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseIsoDateToMillis(s: String): Long? = try {
    s.trim().takeIf { it.isNotBlank() }
        ?.let { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
} catch (_: Exception) {
    null
}

private fun millisToIsoDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
