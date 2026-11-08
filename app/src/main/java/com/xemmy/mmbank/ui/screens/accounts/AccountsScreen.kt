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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.RowAvatar
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.theme.rowEdgeGlow
import com.xemmy.mmbank.ui.util.AMOUNT_MASK
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.asDate
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.asMoneyOrMask
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

data class AccountWithBalance(
    val account: Account,
    val balance: BigDecimal,
    /** Credit cards only: owed, available, statement and due date. */
    val card: CreditCardStatusCalculator.CardStatus? = null,
)

/**
 * Money that is in the ledger but in no account, so it appears in no balance.
 * Grouped by currency because, unlike an account balance, this total has no
 * single currency to be denominated in.
 */
data class UnassignedTotal(
    val totalsByCurrency: Map<String, BigDecimal> = emptyMap(),
    val entryCount: Int = 0,
) {
    val isEmpty: Boolean get() = entryCount == 0
}

data class AccountsUiState(
    val rows: List<AccountWithBalance> = emptyList(),
    val unassigned: UnassignedTotal = UnassignedTotal(),
    /** Retired accounts, with what's left on them -- see [AccountsViewModel.archive]. */
    val archived: List<AccountWithBalance> = emptyList(),
    /** Books vs the bank's own printed balances; see [IntegrityChecker]. */
    val integrity: IntegrityChecker.Report = IntegrityChecker.Report(emptyList(), emptyMap()),
)

class AccountsViewModel(private val container: AppContainer) : ViewModel() {

    /** Set when a database write or the accounts list itself fails, instead of crashing. */
    val lastError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AccountsUiState> = combine(
        container.accountRepository.observeActive(),
        container.accountRepository.observeArchived(),
        container.transactionRepository.observeLedgerEntries(),
        container.transactionRepository.observeTransactions(),
    ) { accounts, archived, entries, transactions ->
        val net = AccountBalanceCalculator.netByAccount(entries)
        val cards = CardPaymentReminders.build(accounts, transactions, entries, LocalDate.now())
            .associate { it.accountId to it.status }
        val now = System.currentTimeMillis()
        val retentionMonths = container.preferences.transactionRetentionMonths
        val integrity = IntegrityChecker.check(
            accounts = accounts,
            transactions = transactions,
            nowMillis = now,
            retentionCutoffMillis = if (retentionMonths > 0) TransactionRetentionPlanner.cutoffMillis(now, retentionMonths) else null,
        )
        AccountsUiState(
            integrity = integrity,
            rows = accounts.map { account ->
                AccountWithBalance(
                    account = account,
                    balance = AccountBalanceCalculator.balanceOf(account, net),
                    card = cards[account.id],
                )
            },
            archived = archived.map { account ->
                AccountWithBalance(account, AccountBalanceCalculator.balanceOf(account, net))
            },
            unassigned = UnassignedTotal(
                totalsByCurrency = AccountBalanceCalculator.unassignedTotals(entries),
                entryCount = AccountBalanceCalculator.unassignedCount(entries),
            ),
        )
    }.recoverAndRetry("AccountsViewModel", container) { e ->
        lastError.value = "Couldn't load accounts: ${e.message}"
        AccountsUiState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    /** Shared, live hide-balances flag — see [AppContainer.hideBalances]. */
    val hideBalances: StateFlow<Boolean> = container.hideBalances

    /** New accounts start in the user's own currency rather than a hard-coded one. */
    val defaultCurrency: String get() = container.preferences.defaultCurrency

    fun unarchive(account: Account) {
        viewModelScope.launch {
            runCatching { container.accountRepository.unarchive(account) }
                .onFailure {
                    container.recordError("AccountsViewModel.unarchive", it)
                    lastError.value = "Couldn't restore account: ${it.message}"
                }
        }
    }

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
    fun adjustBalance(account: Account, computedBalance: BigDecimal, targetBalance: BigDecimal) {
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
 * balance to the real one, or null when they already match. Its kind is
 * [TransactionKind.ADJUSTMENT] by construction: it corrects the ledger, it
 * isn't a real spend or income, so it never shows up in Spent/Received.
 */
internal fun buildBalanceAdjustment(
    account: Account,
    computedBalance: BigDecimal,
    targetBalance: BigDecimal,
    nowMillis: Long = System.currentTimeMillis(),
): Transaction? {
    val current = computedBalance.setScale(2, RoundingMode.HALF_UP)
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
        kind = TransactionKind.ADJUSTMENT,
        kindUserSet = true,
    )
}

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
                            color = ObsidianTokens.textMeta,
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
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    Box(
        Modifier
            .fillMaxWidth()
            // Loan-section expansion and edits glide instead of snapping.
            .animateContentSize()
            .clickable { onEdit() }
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(1.dp, ObsidianTokens.hairline, shape)
            .rowEdgeGlow(originTone),
    ) {
        Column(Modifier.padding(12.dp)) {
            // Row 1: avatar, full (never-truncated) title, actions.
            Row(verticalAlignment = Alignment.Top) {
                RowAvatar(name = a.bankName ?: a.name, tone = originTone, size = 40.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    a.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = ObsidianTokens.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Clip,
                    softWrap = true,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
                IconButton(onClick = onAdjust) {
                    Icon(Icons.Filled.Tune, contentDescription = "Adjust balance", tint = ObsidianTokens.textMeta)
                }
                IconButton(onClick = { confirmingArchive = true }) {
                    Icon(Icons.Filled.Archive, contentDescription = "Archive account", tint = ObsidianTokens.textMeta)
                }
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete account", tint = ObsidianTokens.textMeta)
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
                AmountText(
                    display = row.balance.asMoneyOrMask(a.currencyCode, hidden),
                    color = if (!hidden && row.balance.signum() < 0) ObsidianTokens.debit else ObsidianTokens.textPrimary,
                    style = androidx.compose.ui.text.TextStyle(fontSize = ObsidianTokens.amountFontSize),
                )
            }

            reconciliation?.let { BankCheckLine(it, a.currencyCode, hidden) }

            row.card?.let { CardStatusBlock(it, a.currencyCode, hidden) }

            if (a.outstandingBalance != null || a.nextInstallmentAmount != null) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    a.outstandingBalance?.let {
                        LoanLine("Outstanding", if (hidden) AMOUNT_MASK else it.asMoney(a.currencyCode))
                    }
                    a.nextInstallmentAmount?.let {
                        val due = a.dueDayOfMonth?.let { d -> " · due day $d" }.orEmpty()
                        LoanLine("Next installment", if (hidden) AMOUNT_MASK else it.asMoney(a.currencyCode) + due)
                    }
                    a.profitRatePa?.let { LoanLine("Profit rate", String.format(java.util.Locale.US, "%.2f%% p.a.", it)) }
                    a.maturityDateMillis?.let { LoanLine("Maturity", it.asDate()) }
                    a.earlySettlementFeeEstimate?.let {
                        LoanLine(
                            "Est. early-settlement fee",
                            if (hidden) AMOUNT_MASK else "≈ " + it.asMoney(a.currencyCode) + " (1% of outstanding)",
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
                        "“${a.name}” still shows ${row.balance.asMoney(a.currencyCode)}. Archiving keeps that " +
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
    fun money(v: BigDecimal) = if (hidden) AMOUNT_MASK else v.asMoney(currency)
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        card.utilisation?.let { used ->
            LinearProgressIndicator(
                progress = { used.toFloat().coerceIn(0f, 1f) },
                color = when {
                    used > 0.9 -> ObsidianTokens.debit
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
                ObsidianTokens.textPrimary
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
            color = ObsidianTokens.textMeta,
            modifier = Modifier.padding(top = 4.dp),
        )
        IntegrityChecker.Status.DIFFERS -> Text(
            "Differs from your bank by " +
                (rec.drift?.abs()?.asMoneyOrMask(currency, hidden) ?: "") +
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
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f), shape)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (count == 1) "1 thing to check against your bank" else "$count things to check against your bank",
                style = MaterialTheme.typography.titleSmall,
                color = ObsidianTokens.textPrimary,
            )
            Text(
                "Tap to see what doesn't add up",
                style = MaterialTheme.typography.labelSmall,
                color = ObsidianTokens.textMeta,
            )
        }
    }
}

/** Entry to the debt payoff planner, shown while any card or loan owes money. */
@Composable
private fun DebtPlannerEntryCard(debts: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(1.dp, ObsidianTokens.hairline, shape)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Plan paying off ${if (debts == 1) "your debt" else "your $debts debts"}",
                style = MaterialTheme.typography.titleSmall,
                color = ObsidianTokens.textPrimary,
            )
            Text(
                "See when you'd be debt-free, and how much interest each strategy costs",
                style = MaterialTheme.typography.labelSmall,
                color = ObsidianTokens.textMeta,
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
            Text(row.account.name, style = MaterialTheme.typography.bodyMedium, color = ObsidianTokens.textMeta)
            MetaLabel(accountTypeLabel(row.account.type) + " · " + row.balance.asMoneyOrMask(row.account.currencyCode, hidden))
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
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(1.dp, ObsidianTokens.hairline, shape),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (unassigned.entryCount == 1) {
                    "1 transaction isn't in any account"
                } else {
                    "${unassigned.entryCount} transactions aren't in any account"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = ObsidianTokens.textPrimary,
            )
            unassigned.totalsByCurrency.forEach { (code, total) ->
                Text(
                    total.asMoneyOrMask(code, hidden),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ObsidianTokens.textMeta,
                )
            }
            Text(
                "They're counted in analytics but in no balance. Tap to assign them.",
                style = MaterialTheme.typography.bodySmall,
                color = ObsidianTokens.textMeta,
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
                    "“${account.name}” currently computes to ${row.balance.asMoney(account.currencyCode)} " +
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
                        unfocusedContainerColor = ObsidianTokens.surfaceGlass,
                        focusedContainerColor = ObsidianTokens.surfaceGlass,
                        unfocusedBorderColor = ObsidianTokens.hairline,
                        focusedBorderColor = Color.White.copy(alpha = 0.28f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    delta == null -> Unit
                    delta.signum() == 0 -> Text(
                        "Already matches — no adjustment needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ObsidianTokens.textMeta,
                    )
                    else -> {
                        val credit = delta.signum() > 0
                        Text(
                            (if (credit) "Adds a credit of +" else "Adds a debit of −") +
                                delta.abs().asMoney(account.currencyCode) + ".",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (credit) ObsidianTokens.credit else ObsidianTokens.debit,
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
            color = ObsidianTokens.textPrimary,
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

private fun millisToIsoDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
