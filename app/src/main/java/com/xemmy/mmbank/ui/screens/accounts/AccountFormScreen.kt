package com.xemmy.mmbank.ui.screens.accounts

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
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
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BankDetailsParser
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.MMShapes
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.RowAvatar
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.theme.rowEdgeGlow
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.recoverAndRetry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.CardPaymentReminders
import com.xemmy.mmbank.domain.ai.TransactionRetentionPlanner
import com.xemmy.mmbank.domain.ledger.IntegrityChecker
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountDialog(
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
    var cardEndings by remember { mutableStateOf(existing.cardEndings.orEmpty()) }
    var bankName by remember { mutableStateOf(existing.bankName.orEmpty()) }
    var accountTitle by remember { mutableStateOf(existing.accountTitle.orEmpty()) }
    var iban by remember { mutableStateOf(existing.iban.orEmpty()) }
    var swift by remember { mutableStateOf(existing.swift.orEmpty()) }
    var notes by remember { mutableStateOf(existing.notes.orEmpty()) }
    var creditLimit by remember { mutableStateOf(existing.creditLimit?.toPlainString().orEmpty()) }
    var statementDay by remember { mutableStateOf(existing.statementCycleDay?.toString().orEmpty()) }
    var outstanding by remember { mutableStateOf(existing.outstandingBalance?.toPlainString().orEmpty()) }
    var nextInstallment by remember { mutableStateOf(existing.nextInstallmentAmount?.toPlainString().orEmpty()) }
    var dueDay by remember { mutableStateOf(existing.dueDayOfMonth?.toString().orEmpty()) }
    var profitRate by remember { mutableStateOf(existing.profitRatePa?.toString().orEmpty()) }
    var maturity by remember { mutableStateOf(existing.maturityDateMillis?.let(::millisToIsoDate).orEmpty()) }
    var loanNotes by remember { mutableStateOf(existing.loanNotes.orEmpty()) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var showCopy by remember { mutableStateOf(false) }

    val input = AccountFormInput(
        name = name, type = type, currency = currency, openingBalance = openingBalance,
        accountNumber = accountNumber, cardEndings = cardEndings, bankName = bankName,
        accountTitle = accountTitle, iban = iban, swift = swift, notes = notes,
        creditLimit = creditLimit, statementDay = statementDay, dueDay = dueDay,
        outstanding = outstanding, nextInstallment = nextInstallment, profitRate = profitRate,
        maturity = maturity, loanNotes = loanNotes,
    )
    val errors = AccountFormValidator.errors(input)
    // Name/currency only nag once something's been typed, so a fresh form
    // doesn't open covered in red.
    fun errorFor(field: AccountField, typed: String): String? = errors[field]?.takeIf { typed.isNotBlank() || field !in setOf(AccountField.NAME, AccountField.CURRENCY) }
    val valid = errors.isEmpty()

    // Live field list for "Copy details" — reflects whatever is currently
    // typed/pasted in this dialog, not just the last-saved account.
    val copyFields: List<Pair<String, String>> = buildList {
        if (name.isNotBlank()) add("Name" to name)
        add("Type" to accountTypeLabel(type))
        if (currency.isNotBlank()) add("Currency" to currency)
        if (openingBalance.isNotBlank()) add("Opening balance" to openingBalance)
        if (accountNumber.isNotBlank()) add("Account/card number" to accountNumber)
        if (type.hasCards() && cardEndings.isNotBlank()) add("Other card numbers" to cardEndings)
        if (bankName.isNotBlank()) add("Bank name" to bankName)
        if (accountTitle.isNotBlank()) add("Account title" to accountTitle)
        if (iban.isNotBlank()) add("IBAN" to iban)
        if (swift.isNotBlank()) add("SWIFT/BIC" to swift)
        if (type.isCreditCard()) {
            if (creditLimit.isNotBlank()) add("Credit limit" to creditLimit)
            if (statementDay.isNotBlank()) add("Statement day" to statementDay)
            if (dueDay.isNotBlank()) add("Payment due day" to dueDay)
        }
        if (type.isLoan()) {
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
                CompactField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    error = errorFor(AccountField.NAME, name),
                )
                ExposedDropdownMenuBox(expanded = typeMenuOpen, onExpandedChange = { typeMenuOpen = it }) {
                    CompactField(
                        value = accountTypeLabel(type),
                        onValueChange = {},
                        label = "Type",
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuOpen) },
                        modifier = Modifier.menuAnchor(),
                    )
                    ExposedDropdownMenu(expanded = typeMenuOpen, onDismissRequest = { typeMenuOpen = false }) {
                        AccountType.entries.forEach { entry ->
                            DropdownMenuItem(
                                text = { Text(accountTypeLabel(entry), style = MaterialTheme.typography.bodyMedium) },
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
                        error = errorFor(AccountField.CURRENCY, currency),
                        modifier = Modifier.weight(0.42f),
                    )
                    CompactField(
                        value = openingBalance,
                        onValueChange = { openingBalance = it },
                        label = "Opening balance",
                        supporting = if (type.isCreditCard() || type.isLoan()) "Negative = what you owe." else null,
                        error = errorFor(AccountField.OPENING, openingBalance),
                        modifier = Modifier.weight(0.58f),
                    )
                }

                if (type.isCreditCard()) {
                    EditorSectionHeader("Card")
                    CompactField(
                        value = creditLimit,
                        onValueChange = { creditLimit = it },
                        label = "Credit limit",
                        supporting = "Shows available credit and how much you've used.",
                        error = errorFor(AccountField.CREDIT_LIMIT, creditLimit),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactField(
                            value = statementDay,
                            onValueChange = { statementDay = it.filter(Char::isDigit).take(2) },
                            label = "Statement day",
                            error = errorFor(AccountField.STATEMENT_DAY, statementDay),
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = dueDay,
                            onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
                            label = "Payment due day",
                            error = errorFor(AccountField.DUE_DAY, dueDay),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                EditorSectionHeader("Matching")
                CompactField(
                    value = accountNumber,
                    onValueChange = { accountNumber = it },
                    label = if (type.isCreditCard()) "Card number (last digits)" else "Account number",
                    supporting = "Bank messages quoting it are filed here automatically.",
                )
                if (type.hasCards()) {
                    CompactField(
                        value = cardEndings,
                        onValueChange = { cardEndings = it },
                        label = if (type.isCreditCard()) "Supplementary card numbers" else "Debit card numbers",
                        supporting = "Last 4 digits, comma-separated, e.g. 4321, 8765",
                    )
                }

                EditorSectionHeader("Bank details")
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

                if (type.isLoan()) {
                    EditorSectionHeader("Loan / financing")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactField(
                            value = outstanding,
                            onValueChange = { outstanding = it },
                            label = "Outstanding",
                            error = errorFor(AccountField.OUTSTANDING, outstanding),
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = nextInstallment,
                            onValueChange = { nextInstallment = it },
                            label = "Next installment",
                            error = errorFor(AccountField.INSTALLMENT, nextInstallment),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        CompactField(
                            value = dueDay,
                            onValueChange = { dueDay = it.filter(Char::isDigit).take(2) },
                            label = "Due day (1-31)",
                            error = errorFor(AccountField.DUE_DAY, dueDay),
                            modifier = Modifier.weight(1f),
                        )
                        CompactField(
                            value = profitRate,
                            onValueChange = { profitRate = it },
                            label = "Profit rate % p.a.",
                            error = errorFor(AccountField.PROFIT_RATE, profitRate),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    CompactField(
                        value = maturity,
                        onValueChange = { maturity = it },
                        label = "Maturity date (YYYY-MM-DD)",
                        error = errorFor(AccountField.MATURITY, maturity),
                    )
                    CompactField(
                        value = loanNotes,
                        onValueChange = { loanNotes = it },
                        label = "Add-ons / early-settlement notes",
                        singleLine = false,
                    )
                }

                CompactField(value = notes, onValueChange = { notes = it }, label = "Notes", singleLine = false)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(AccountFormValidator.apply(existing, input)) },
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
    /** Shown in place of [supporting], in the error colour, when set. */
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it, style = MaterialTheme.typography.labelSmall) } },
        singleLine = singleLine,
        readOnly = readOnly,
        trailingIcon = trailingIcon,
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = MMShapes.small,
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
internal fun PasteDetailsDialog(
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
                    shape = MMShapes.small,
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

private fun millisToIsoDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
