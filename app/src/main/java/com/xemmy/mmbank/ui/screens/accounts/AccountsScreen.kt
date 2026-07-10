package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BankDetailsParser
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.LedgerColors
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    }.recoverAndRetry("AccountsViewModel", container) { e ->
        lastError.value = "Couldn't load accounts: ${e.message}"
        emptyList()
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

    /**
     * Corrects a drifted balance (e.g. after a missed or mis-parsed capture) by
     * posting a single "Balance adjustment" transaction for the gap between the
     * computed balance and the real one, pinned to this account. It's an ordinary
     * row, so the fix is visible in Activity and reversible (just delete it).
     */
    fun adjustBalance(account: Account, computedBalance: Double, targetBalance: BigDecimal) {
        viewModelScope.launch {
            runCatching {
                val adjustment = buildBalanceAdjustment(account, computedBalance, targetBalance)
                    ?: return@runCatching
                container.transactionRepository.recordManual(adjustment)
            }.onFailure {
                container.recordError("AccountsViewModel.adjustBalance", it)
                lastError.value = "Couldn't adjust balance: ${it.message}"
            }
        }
    }
}

/**
 * Builds the "Balance adjustment" transaction that reconciles a drifted computed
 * balance to the real one, or null when they already match. The row is excluded
 * from analytics by construction: it corrects the ledger, it isn't a real spend
 * or income, so it must never show up in stats.
 */
internal fun buildBalanceAdjustment(
    account: Account,
    computedBalance: Double,
    targetBalance: BigDecimal,
    nowMillis: Long = System.currentTimeMillis(),
): Transaction? {
    val current = BigDecimal.valueOf(computedBalance).setScale(2, RoundingMode.HALF_UP)
    val delta = targetBalance.setScale(2, RoundingMode.HALF_UP).subtract(current)
    if (delta.signum() == 0) return null
    return Transaction(
        accountId = account.id,
        amount = delta.abs(),
        currencyCode = account.currencyCode,
        direction = if (delta.signum() > 0) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        merchant = "Balance adjustment",
        source = TransactionSource.MANUAL,
        timestampMillis = nowMillis,
        matchedRuleName = "Balance adjustment",
        spendingCategory = SpendingCategory.OTHER,
        excludeFromStats = true,
    )
}

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val rows by viewModel.uiState.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    // null = closed; an Account with id 0 = "new"; an existing Account = "edit".
    var editing by remember { mutableStateOf<Account?>(null) }
    // The account row whose balance is being corrected; null = dialog closed.
    var adjusting by remember { mutableStateOf<AccountWithBalance?>(null) }

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
                    onAdjust = { adjusting = row },
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

    adjusting?.let { row ->
        AdjustBalanceDialog(
            row = row,
            onDismiss = { adjusting = null },
            onConfirm = { target ->
                viewModel.adjustBalance(row.account, row.balance, target)
                adjusting = null
            },
        )
    }
}

@Composable
private fun AccountCard(
    row: AccountWithBalance,
    onEdit: () -> Unit,
    onAdjust: () -> Unit,
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
                IconButton(onClick = onAdjust) {
                    Icon(Icons.Filled.Tune, contentDescription = "Adjust balance")
                }
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

/**
 * Reconciles a drifted balance: the user types the account's real balance and a
 * single "Balance adjustment" entry is posted for the difference. Nothing happens
 * when the numbers already match.
 */
@Composable
private fun AdjustBalanceDialog(
    row: AccountWithBalance,
    onDismiss: () -> Unit,
    onConfirm: (BigDecimal) -> Unit,
) {
    val account = row.account
    var text by remember { mutableStateOf(String.format(java.util.Locale.US, "%.2f", row.balance)) }
    val target = text.replace(",", "").trim().toBigDecimalOrNull()
    val current = BigDecimal.valueOf(row.balance).setScale(2, RoundingMode.HALF_UP)
    val delta = target?.setScale(2, RoundingMode.HALF_UP)?.subtract(current)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust balance", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "“${account.name}” currently computes to ${row.balance.asMoney(account.currencyCode)} " +
                        "from its opening balance and transactions. Enter the real balance and a matching " +
                        "“Balance adjustment” entry is added so they line up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Actual balance (${account.currencyCode})") },
                    supportingText = { Text("Use a negative number for money owed.") },
                    singleLine = true,
                    isError = target == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    delta == null -> Unit
                    delta.signum() == 0 -> Text(
                        "Already matches — no adjustment needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        val credit = delta.signum() > 0
                        Text(
                            (if (credit) "Adds a credit of +" else "Adds a debit of −") +
                                delta.abs().asMoney(account.currencyCode) + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (credit) LedgerColors.credit else LedgerColors.debit,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = delta != null && delta.signum() != 0,
                onClick = { target?.let(onConfirm) },
            ) { Text("Adjust") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
        title = {
            Text(
                if (isNew) "New account" else "Edit account",
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactActionButton(
                        label = "Paste",
                        icon = { Icon(Icons.Filled.ContentPaste, contentDescription = null, Modifier.size(16.dp)) },
                        onClick = { showPaste = true },
                        modifier = Modifier.weight(1f),
                    )
                    CompactActionButton(
                        label = "Copy details",
                        icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null, Modifier.size(16.dp)) },
                        onClick = { showCopy = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                EditorSectionHeader("Essentials")
                CompactField(value = name, onValueChange = { name = it }, label = "Name")
                ExposedDropdownMenuBox(expanded = typeMenuOpen, onExpandedChange = { typeMenuOpen = it }) {
                    CompactField(
                        value = type.name.replace('_', ' '),
                        onValueChange = {},
                        label = "Type",
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AccountType.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(entry.name.replace('_', ' '), style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    type = entry
                                    typeMenuOpen = false
                                },
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(3) },
                        label = "Currency",
                        modifier = Modifier.weight(0.42f),
                    )
                    CompactField(
                        value = openingBalance,
                        onValueChange = { openingBalance = it },
                        label = "Opening balance",
                        supporting = "Negative for debt.",
                        modifier = Modifier.weight(0.58f),
                    )
                }

                EditorSectionHeader("Bank details")
                CompactField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    label = "Account/card number",
                    supporting = "Used to auto-assign captured transactions.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactField(
                        value = bankName,
                        onValueChange = { bankName = it },
                        label = "Bank name",
                        modifier = Modifier.weight(0.58f),
                    )
                    CompactField(
                        value = swift,
                        onValueChange = { swift = it },
                        label = "SWIFT / BIC",
                        modifier = Modifier.weight(0.42f),
                    )
                }
                CompactField(value = accountTitle, onValueChange = { accountTitle = it }, label = "Account title / holder")
                CompactField(value = iban, onValueChange = { iban = it }, label = "IBAN")

                if (showLoanFields) {
                    EditorSectionHeader("Loan / financing")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactField(
                            value = outstanding,
                            onValueChange = { outstanding = it },
                            label = "Outstanding",
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = nextInstallment,
                            onValueChange = { nextInstallment = it },
                            label = "Next installment",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactField(
                            value = dueDay,
                            onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
                            label = "Due day (1-31)",
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = profitRate,
                            onValueChange = { profitRate = it },
                            label = "Profit rate % p.a.",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CompactField(value = maturity, onValueChange = { maturity = it }, label = "Maturity date (YYYY-MM-DD)")
                    CompactField(
                        value = loanNotes,
                        onValueChange = { loanNotes = it },
                        label = "Add-ons / early-settlement notes",
                        singleLine = false,
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

/** Outlined text field tuned for dense dialog forms: smaller type, softer corners. */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        supportingText = supporting?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        singleLine = singleLine,
        readOnly = readOnly,
        trailingIcon = trailingIcon,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Small uppercase section label with a hairline rule, to group the form. */
@Composable
private fun EditorSectionHeader(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Low-height tonal button so the paste/copy row doesn't dominate the dialog. */
@Composable
private fun CompactActionButton(
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        icon()
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
        title = { Text("Paste bank details", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    // Fictional example values — never real account details.
                    "Paste a block like:\nBank Name: ADIB\nAccount Title: FATIMA SALEM ALSUWAIDI\n" +
                        "Account Number: 12345678\nIBAN: AE35...\nSWIFT: ABDIAEADXXX\nCurrency: AED",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Pasted text", style = MaterialTheme.typography.labelMedium) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(12.dp),
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
        title = { Text("Copy details", style = MaterialTheme.typography.titleLarge) },
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
