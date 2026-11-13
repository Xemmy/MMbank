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
import com.xemmy.mmbank.domain.account.BalanceChangeReason
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
    /** The balance includes foreign-currency charges that were converted (or couldn't be): show "≈". */
    val approximate: Boolean = false,
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
        val approximate = AccountBalanceCalculator.approximateAccounts(entries)
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
                    approximate = account.id in approximate,
                )
            },
            archived = archived.map { account ->
                AccountWithBalance(account, AccountBalanceCalculator.balanceOf(account, net), approximate = account.id in approximate)
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
    fun adjustBalance(
        account: Account,
        computedBalance: BigDecimal,
        targetBalance: BigDecimal,
        reason: BalanceChangeReason? = null,
        note: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                val adjustment = buildBalanceAdjustment(account, computedBalance, targetBalance)
                    ?: return@runCatching
                // The reason and note go to the account's change log with the adjustment.
                container.transactionRepository.recordAdjustment(adjustment, reason?.name, note)
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
