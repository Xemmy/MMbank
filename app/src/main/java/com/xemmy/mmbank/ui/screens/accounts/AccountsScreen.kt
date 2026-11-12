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
import com.xemmy.mmbank.ui.designsystem.MMCard
import com.xemmy.mmbank.ui.designsystem.MMTone
import com.xemmy.mmbank.ui.designsystem.MoneyText
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.MMTheme
import com.xemmy.mmbank.ui.theme.RowAvatar
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.theme.rowEdgeGlow
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

@Composable
fun AccountsScreen(
    onOpenUnassigned: () -> Unit = {},
    onOpenIntegrity: () -> Unit = {},
    onOpenDebtPlanner: () -> Unit = {},
    viewModel: AccountsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = state.rows
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val hidden by viewModel.hideBalances.collectAsStateWithLifecycle()
    // null = closed; an Account with id 0 = "new"; an existing Account = "edit".
    var editing by remember { mutableStateOf<Account?>(null) }
    // The account row whose balance is being corrected; null = dialog closed.
    var adjusting by remember { mutableStateOf<AccountWithBalance?>(null) }
    var showArchived by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = Account(name = "", type = AccountType.CHECKING, currencyCode = viewModel.defaultCurrency) }) {
                Icon(Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .obsidianCanvas()
                .padding(padding),
            // Bottom clearance so the "+" FAB never floats over the last card's
            // adjust/delete actions (56dp FAB + 16dp margin + 16dp + extra buffer).
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            if (state.integrity.actionableCount > 0) {
                item(key = "integrity_summary") {
                    IntegritySummaryCard(count = state.integrity.actionableCount, onClick = onOpenIntegrity)
                }
            }
            val owedDebts = rows.count { r ->
                (r.account.type == AccountType.CREDIT_CARD || r.account.type == AccountType.LOAN) &&
                    (r.balance.signum() < 0 || (r.account.outstandingBalance?.signum() ?: 0) > 0)
            }
            if (owedDebts > 0) {
                item(key = "debt_planner") {
                    DebtPlannerEntryCard(debts = owedDebts, onClick = onOpenDebtPlanner)
                }
            }
            if (!state.unassigned.isEmpty) {
                item(key = "unassigned_total") {
                    UnassignedCard(
                        unassigned = state.unassigned,
                        hidden = hidden,
                        onClick = onOpenUnassigned,
                    )
                }
            }
            items(rows, key = { it.account.id }) { row ->
                AccountCard(
                    row = row,
                    reconciliation = state.integrity.reconciliations[row.account.id],
                    hidden = hidden,
                    onEdit = { editing = row.account },
                    onAdjust = { adjusting = row },
                    onArchive = { viewModel.archive(row.account) },
                    onDelete = { viewModel.delete(row.account) },
                )
            }
            if (state.archived.isNotEmpty()) {
                item(key = "archived_header") {
                    TextButton(onClick = { showArchived = !showArchived }) {
                        Text(
                            (if (showArchived) "Hide" else "Show") + " archived (${state.archived.size})",
                            color = MMTheme.colors.textSecondary,
                        )
                    }
                }
                if (showArchived) {
                    items(state.archived, key = { "archived_${it.account.id}" }) { row ->
                        ArchivedAccountRow(row = row, hidden = hidden, onRestore = { viewModel.unarchive(row.account) })
                    }
                }
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
    reconciliation: IntegrityChecker.Reconciliation?,
    hidden: Boolean,
    onEdit: () -> Unit,
    onAdjust: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val a = row.account
    var confirmingDelete by remember { mutableStateOf(false) }
    var confirmingArchive by remember { mutableStateOf(false) }
    // Edge glow (spec 5.3): the account's brand origin, or a neutral slate
    // for accounts with none registered.
    val originTone = GlowTones.forAccountOrigin(a.name, a.bankName) ?: GlowTone.SYSTEM_SLATE
    MMCard(
        modifier = Modifier
            .fillMaxWidth()
            // Loan-section expansion and edits glide instead of snapping.
            .animateContentSize(),
        onClick = onEdit,
    ) {
        Column(Modifier.rowEdgeGlow(originTone)) {
            // Row 1: avatar, full (never-truncated) title, actions.
            Row(verticalAlignment = Alignment.Top) {
                RowAvatar(name = a.bankName ?: a.name, tone = originTone, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    a.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MMTheme.colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
                IconButton(onClick = onAdjust) {
                    Icon(Icons.Filled.Tune, contentDescription = "Adjust balance", tint = MMTheme.colors.textSecondary)
                }
                IconButton(onClick = { confirmingArchive = true }) {
                    Icon(Icons.Filled.Archive, contentDescription = "Archive account", tint = MMTheme.colors.textSecondary)
                }
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete account", tint = MMTheme.colors.textSecondary)
                }
            }

            // Row 2: institution/type/number footer, balance on the right.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaLabel(
                    buildString {
                        a.bankName?.takeIf { it.isNotBlank() }?.let { append(it); append(" · ") }
                        append(accountTypeLabel(a.type))
                        append(" · ")
                        append(a.currencyCode)
                        a.accountNumber?.takeIf { it.isNotBlank() }?.let {
                            append(" · ••")
                            append(it.takeLast(4))
                        }
                    },
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                MoneyText(
                    amount = balanceMagnitude(row.balance),
                    currencyCode = a.currencyCode,
                    kind = balanceKind(row.balance),
                    hidden = hidden,
                    color = if (!hidden && row.balance.signum() < 0) MMTheme.colors.danger else MMTheme.colors.textPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (row.approximate && !hidden) {
                MetaLabel("≈ Includes foreign-currency charges, converted at today's exchange rate where one is stored", maxLines = 2)
            }

            reconciliation?.let { BankCheckLine(it, a.currencyCode, hidden) }

            row.card?.let { CardStatusBlock(it, a.currencyCode, hidden) }

            if (a.outstandingBalance != null || a.nextInstallmentAmount != null) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    a.outstandingBalance?.let {
                        LoanLine("Outstanding", moneyLabel(it, a.currencyCode, hidden))
                    }
                    a.nextInstallmentAmount?.let {
                        val due = a.dueDayOfMonth?.let { d -> " · due day $d" }.orEmpty()
                        LoanLine("Next installment", moneyLabel(it, a.currencyCode, hidden) + (if (hidden) "" else due))
                    }
                    a.profitRatePa?.let { LoanLine("Profit rate", String.format(java.util.Locale.US, "%.2f%% p.a.", it)) }
                    a.maturityDateMillis?.let { LoanLine("Maturity", it.asDate()) }
                    a.earlySettlementFeeEstimate?.let {
                        LoanLine(
                            "Est. early-settlement fee",
                            if (hidden) moneyLabel(it, a.currencyCode, true) else "≈ " + moneyLabel(it, a.currencyCode) + " (1% of outstanding)",
                        )
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

    if (confirmingArchive) {
        AlertDialog(
            onDismissRequest = { confirmingArchive = false },
            title = { Text("Archive this account?") },
            text = {
                Text(
                    if (row.balance.signum() == 0) {
                        "“${a.name}” moves to Archived: out of your lists and pickers, history kept. You can restore it any time."
                    } else {
                        "“${a.name}” still shows ${moneyLabel(row.balance, a.currencyCode)}. Archiving keeps that " +
                            "in your history but hides the account from lists and totals. If it's closed, " +
                            "adjust the balance to 0 first."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { onArchive(); confirmingArchive = false }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { confirmingArchive = false }) { Text("Cancel") } },
        )
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
 * A credit card at a glance: how much of the limit is used, what's left, and
 * whether this statement is paid.
 */
@Composable
private fun CardStatusBlock(card: CreditCardStatusCalculator.CardStatus, currency: String, hidden: Boolean) {
    fun money(v: BigDecimal) = moneyLabel(v, currency, hidden)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        card.utilisation?.let { used ->
            LinearProgressIndicator(
                progress = { used.toFloat().coerceIn(0f, 1f) },
                color = when {
                    used > 0.9 -> MMTheme.colors.danger
                    used > 0.3 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LoanLine(
                "Available",
                "${money(card.available ?: BigDecimal.ZERO)} of ${money(card.creditLimit ?: BigDecimal.ZERO)} · " +
                    String.format(java.util.Locale.US, "%.0f%% used", used * 100),
            )
        }
        card.statementBalance?.let { LoanLine("Last statement", money(it)) }
        LoanLine("Spent this cycle", money(card.spentThisCycle))
        val dueText = when (card.status) {
            CreditCardStatusCalculator.Status.NOTHING_OWED -> "Nothing owed"
            CreditCardStatusCalculator.Status.PAID -> "Statement paid ✓"
            CreditCardStatusCalculator.Status.NO_DUE_DATE ->
                money(card.amountDue) + " owed · set a payment due day to get reminders"
            else -> {
                val days = card.daysUntilDue ?: 0L
                money(card.amountDue) + (if (card.estimated) " (all owed)" else "") + " · " +
                    when {
                        days < 0 -> "overdue by ${-days}d"
                        days == 0L -> "due today"
                        else -> "due ${card.dueDate?.asShortDate()} (${days}d)"
                    }
            }
        }
        Text(
            dueText,
            style = MaterialTheme.typography.labelSmall,
            color = if (card.status == CreditCardStatusCalculator.Status.OVERDUE) {
                MaterialTheme.colorScheme.error
            } else {
                MMTheme.colors.textPrimary
            },
        )
    }
}

private fun LocalDate.asShortDate(): String =
    "${dayOfMonth} ${month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())}"

/**
 * One line on an account card: does the app's balance agree with the one the
 * bank last printed? Silent when the bank has never reported one.
 */
@Composable
private fun BankCheckLine(rec: IntegrityChecker.Reconciliation, currency: String, hidden: Boolean) {
    val asOf = rec.asOfMillis?.asDate()
    when (rec.status) {
        IntegrityChecker.Status.MATCHES -> Text(
            "Matches your bank ✓ (${asOf.orEmpty()})",
            style = MaterialTheme.typography.labelSmall,
            color = MMTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
        IntegrityChecker.Status.DIFFERS -> Text(
            "Differs from your bank by " +
                (rec.drift?.abs()?.let { moneyLabel(it, currency, hidden) } ?: "") +
                (asOf?.let { " (as of $it)" }.orEmpty()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
        IntegrityChecker.Status.NO_BANK_BALANCE, IntegrityChecker.Status.CANNOT_CHECK -> Unit
    }
}

/** Entry to the integrity screen, shown only while something needs a look. */
@Composable
private fun IntegritySummaryCard(count: Int, onClick: () -> Unit) {
    MMCard(modifier = Modifier.fillMaxWidth(), onClick = onClick, tone = MMTone.DANGER) {
        Column {
            Text(
                if (count == 1) "1 thing to check against your bank" else "$count things to check against your bank",
                style = MaterialTheme.typography.titleSmall,
                color = MMTheme.colors.textPrimary,
            )
            Text(
                "Tap to see what doesn't add up",
                style = MaterialTheme.typography.labelSmall,
                color = MMTheme.colors.textSecondary,
            )
        }
    }
}

/** Entry to the debt payoff planner, shown while any card or loan owes money. */
@Composable
private fun DebtPlannerEntryCard(debts: Int, onClick: () -> Unit) {
    MMCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column {
            Text(
                "Plan paying off ${if (debts == 1) "your debt" else "your $debts debts"}",
                style = MaterialTheme.typography.titleSmall,
                color = MMTheme.colors.textPrimary,
            )
            Text(
                "See when you'd be debt-free, and how much interest each strategy costs",
                style = MaterialTheme.typography.labelSmall,
                color = MMTheme.colors.textSecondary,
            )
        }
    }
}

/** One retired account, with what's left on it and a way back. */
@Composable
private fun ArchivedAccountRow(row: AccountWithBalance, hidden: Boolean, onRestore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.account.name, style = MaterialTheme.typography.bodyMedium, color = MMTheme.colors.textSecondary)
            MetaLabel(accountTypeLabel(row.account.type) + " · " + moneyLabel(row.balance, row.account.currencyCode, hidden))
        }
        TextButton(onClick = onRestore) { Text("Restore") }
    }
}

/**
 * Money in the ledger that belongs to no account, and so appears in no balance.
 *
 * Without this the total is simply invisible: a capture whose card number the
 * app could not match to an account is filed, counted in analytics, and missing
 * from every balance, with nothing on screen to say so. Tapping opens the
 * Activity tab already filtered to those rows, which is where they get fixed.
 */
@Composable
private fun UnassignedCard(
    unassigned: UnassignedTotal,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    MMCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (unassigned.entryCount == 1) {
                    "1 transaction isn't in any account"
                } else {
                    "${unassigned.entryCount} transactions aren't in any account"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MMTheme.colors.textPrimary,
            )
            unassigned.totalsByCurrency.forEach { (code, total) ->
                Text(
                    moneyLabel(total, code, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MMTheme.colors.textSecondary,
                )
            }
            Text(
                "They're counted in analytics but in no balance. Tap to assign them.",
                style = MaterialTheme.typography.bodySmall,
                color = MMTheme.colors.textSecondary,
            )
        }
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
    var text by remember { mutableStateOf(row.balance.setScale(2, RoundingMode.HALF_UP).toPlainString()) }
    val target = parseMoneyInput(text)
    val current = row.balance.setScale(2, RoundingMode.HALF_UP)
    val delta = target?.setScale(2, RoundingMode.HALF_UP)?.subtract(current)

    AlertDialog(
        onDismissRequest = onDismiss,
        // Glass overlay: translucent container + hairline border, with a real
        // backdrop blur on API 31+ (falls back to a more opaque surface below).
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = { Text("Adjust balance", style = MaterialTheme.typography.titleLarge) },
        text = {
            GlassDialogBackdrop()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "“${account.name}” currently computes to ${moneyLabel(row.balance, account.currencyCode)} " +
                        "from its opening balance and transactions. Enter the real balance and a matching " +
                        "“Balance adjustment” entry is added so they line up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { MetaLabel("Actual balance (${account.currencyCode})") },
                    supportingText = { Text("Use a negative number for money owed.") },
                    singleLine = true,
                    isError = target == null,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MMTheme.colors.surface,
                        focusedContainerColor = MMTheme.colors.surface,
                        unfocusedBorderColor = MMTheme.colors.borderSubtle,
                        focusedBorderColor = MMTheme.colors.borderStrong,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    delta == null -> Unit
                    delta.signum() == 0 -> Text(
                        "Already matches — no adjustment needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MMTheme.colors.textSecondary,
                    )
                    else -> {
                        val credit = delta.signum() > 0
                        Text(
                            (if (credit) "Adds a credit of +" else "Adds a debit of −") +
                                moneyLabel(delta.abs(), account.currencyCode) + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (credit) MMTheme.colors.moneyIn else MMTheme.colors.danger,
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
        MetaLabel(label)
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            color = MMTheme.colors.textPrimary,
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
