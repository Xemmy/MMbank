package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category as CategoryIcon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.roundToInt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityDrillDown
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.DesignMode
import com.xemmy.mmbank.domain.model.ExchangeRate
import com.xemmy.mmbank.domain.model.FilterBarLayout
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.ledger.TransferLegs
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.ai.AiResponseParser
import com.xemmy.mmbank.domain.ai.AiTransactionPrompt
import com.xemmy.mmbank.domain.parser.GenericExtractors
import com.xemmy.mmbank.domain.parser.IgnoreKeywordSuggester
import com.xemmy.mmbank.domain.parser.MerchantCategorizer
import com.xemmy.mmbank.domain.usecase.BulkTransactionPatch
import com.xemmy.mmbank.domain.usecase.FieldUpdate
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.AiParsingIndicator
import com.xemmy.mmbank.ui.components.CategoryEditorDialog
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.theme.AppFonts
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.GlowPalette
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.LocalDesignMode
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.RowAvatar
import com.xemmy.mmbank.ui.theme.dottedLeader
import com.xemmy.mmbank.ui.theme.glassSurface
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.theme.rowEdgeGlow
import com.xemmy.mmbank.ui.util.AmountText
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MAX_LENGTH
import com.xemmy.mmbank.ui.util.CURRENCY_CODE_MIN_LENGTH
import com.xemmy.mmbank.ui.util.CurrencyMath
import com.xemmy.mmbank.ui.util.asDateTime
import com.xemmy.mmbank.ui.util.asMoney
import com.xemmy.mmbank.ui.util.dayPillLabel
import com.xemmy.mmbank.ui.util.recoverAndRetry
import com.xemmy.mmbank.ui.util.toLocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Shown as the "rule" on hand-entered rows so they read clearly in the list. */
const val MANUAL_ENTRY_LABEL = "Manual entry"

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val accounts: List<Account> = emptyList(),
    val senderLabels: Map<String, String> = emptyMap(),
    val filters: ActivityFilters = ActivityFilters(),
    /** Every person seen so far, for the quick-assign menu. */
    val knownPersons: List<String> = emptyList(),
    /** True when the filter (not an empty ledger) hid everything. */
    val hasAnyTransactions: Boolean = false,
    /** Currency a new manual entry defaults to. */
    val defaultCurrency: String = "USD",
    /** True once a long-press has entered multi-select bulk-edit mode. */
    val isInBulkMode: Boolean = false,
    /** Ids currently checked while in bulk mode. */
    val selectedTransactionIds: Set<Long> = emptySet(),
    /** Single-row (Premium) vs Classic wrapped-chip filter bar; see Settings > Design. */
    val filterBarLayout: FilterBarLayout = FilterBarLayout.SINGLE_ROW,
    /** Dynamic categories, for the row/bulk-bar category chip and picker. */
    val categories: List<Category> = emptyList(),
)

/** Bulk-mode selection is UI-only state, layered onto the Room-backed pipeline below. */
private data class BulkModeState(
    val isInBulkMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
)

/** Adds or removes [id] from [current] — the pure toggle behind bulk-mode row taps. */
internal fun toggleSelection(current: Set<Long>, id: Long): Set<Long> =
    if (id in current) current - id else current + id

/** Editor field values extracted by the local AI from a pasted message. */
data class AiEditorFill(
    val amount: java.math.BigDecimal,
    val currency: String,
    val merchant: String?,
    val direction: TransactionDirection,
)

/**
 * [TransactionsViewModel.spendSummaryState]: the currently filtered feed's
 * total spend, total received and net (Time/Type/Account chips plus any
 * secondary filter — see [TransactionsUiState.transactions]), and how much
 * of the monthly budget threshold
 * ([com.xemmy.mmbank.data.prefs.AppPreferences.monthlySpendBudget]) the
 * spend consumes. Premium-only; see [HeroSpendSummaryCard].
 */
data class SpendSummaryState(
    val totalSpent: Double = 0.0,
    val totalReceived: Double = 0.0,
    /** [totalReceived] minus [totalSpent] for the currently filtered feed; can be negative. */
    val netAmount: Double = 0.0,
    val currencyCode: String = "USD",
    /** Monthly budget threshold from Settings; <= 0 means none is set. */
    val budgetAmount: Double = 0.0,
    val hasBudget: Boolean = false,
    /** 0 when [hasBudget] is false. Can exceed 100 when over budget. */
    val budgetConsumedPercent: Double = 0.0,
)

/**
 * Sums the DEBIT and CREDIT amounts of every already-filtered [transactions]
 * row into [baseCurrency] using the latest Forex [rates] — the same FX
 * handling as [com.xemmy.mmbank.ui.screens.dashboard.computeDashboardStats].
 * A row whose currency has no rate to [baseCurrency] is skipped, same as the
 * Dashboard. Pure and top-level so it's JVM-testable off the main thread.
 */
internal fun computeSpendSummary(
    transactions: List<Transaction>,
    rates: List<ExchangeRate>,
    baseCurrency: String,
    monthlyBudget: Double,
): SpendSummaryState {
    val ratesFromBase = rates
        .filter { it.baseCode.equals(baseCurrency, ignoreCase = true) }
        .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
    // The same definition every other total uses (see LedgerMath): this card
    // used to sum raw debits/credits, so card payments, transfers and balance
    // corrections inflated Spent/Received here and nowhere else.
    fun inBase(tx: Transaction) = CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, baseCurrency, ratesFromBase)
    val totalSpent = LedgerMath.netSpend(transactions, value = ::inBase)
    val totalReceived = LedgerMath.income(transactions, value = ::inBase)
    val hasBudget = monthlyBudget > 0.0
    return SpendSummaryState(
        totalSpent = totalSpent,
        totalReceived = totalReceived,
        netAmount = totalReceived - totalSpent,
        currencyCode = baseCurrency,
        budgetAmount = monthlyBudget,
        hasBudget = hasBudget,
        budgetConsumedPercent = if (hasBudget) totalSpent / monthlyBudget * 100.0 else 0.0,
    )
}

/** Entry point into [UncategorizedQueueScreen] -- shown only while there's something to review. */
@Composable
private fun UncategorizedBanner(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CategoryIcon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(
                if (count == 1) "1 transaction needs a category" else "$count transactions need a category",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Review",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())
    private val bulkMode = MutableStateFlow(BulkModeState())

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.senderLabelRepository.observeLabels(),
        filters,
        container.categoryRepository.observeAll(),
    ) { transactions, accounts, senderLabels, f, categories ->
        // All predicate logic lives in the pure-Kotlin engine so it's JVM-testable.
        val visible = TransactionFilterEngine.filter(
            transactions = transactions,
            filters = f,
            accounts = accounts,
            bankNameOf = { sender -> bankNameFor(sender, senderLabels) },
        )
        // The menu is fed ONLY by the saved list (Settings > Preferences >
        // People) so removing a person there really removes it here. Names
        // dedupe case-insensitively to avoid "Ahmed" vs "ahmed" doubles.
        val persons = buildList {
            addAll(container.preferences.persons)
            container.preferences.defaultPerson?.let(::add)
        }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .sorted()
        TransactionsUiState(
            transactions = visible,
            accounts = accounts,
            senderLabels = senderLabels,
            filters = f,
            knownPersons = persons,
            hasAnyTransactions = transactions.isNotEmpty(),
            defaultCurrency = container.preferences.defaultCurrency,
            filterBarLayout = container.preferences.filterBarLayout,
            categories = categories,
        )
    }.combine(bulkMode) { base, bulk ->
        base.copy(isInBulkMode = bulk.isInBulkMode, selectedTransactionIds = bulk.selectedIds)
    }.recoverAndRetry("TransactionsViewModel", container) {
        TransactionsUiState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    /**
     * Premium hero card's total spend + budget consumption for the currently
     * filtered feed (see [computeSpendSummary]). Riding [uiState] means it
     * reflects every active filter, not just Time/Type/Account.
     */
    val spendSummaryState: StateFlow<SpendSummaryState> = combine(
        uiState,
        container.ratesRepository.observeRates(),
    ) { state, rates ->
        computeSpendSummary(
            transactions = state.transactions,
            rates = rates,
            baseCurrency = container.preferences.defaultCurrency,
            monthlyBudget = container.preferences.monthlySpendBudget,
        )
    }.recoverAndRetry("TransactionsViewModel.spendSummary", container) {
        SpendSummaryState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendSummaryState())

    /** How many real transactions still need a dynamic category -- drives the Uncategorized queue entry point. */
    val uncategorizedCount: StateFlow<Int> = container.transactionRepository.observeUncategorized()
        .map { it.size }
        .recoverAndRetry("TransactionsViewModel.uncategorizedCount", container) { 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Manual "Parse with AI" is offered only when the feature is on + a valid loopback endpoint is set. */
    val aiParsingConfigured: Boolean get() = container.localAiManager.isConfigured()

    /**
     * Runs the local (loopback-only) AI over a pasted message and maps the
     * result to editor fields. Returns null when the feature is off/unreachable
     * or the text isn't a transaction — the caller then leaves the form as-is.
     * Direction (absent from the AI's JSON keyset) is inferred with the same
     * shared parser vocabulary the self-healing path uses.
     */
    suspend fun parseWithAi(raw: String): AiEditorFill? {
        if (raw.isBlank()) return null
        val engine = container.localAiManager.engine()
        if (!engine.isReady) return null
        val generated = engine.generate(AiTransactionPrompt.build(raw)).getOrNull() ?: return null
        val parsed = AiResponseParser.parse(generated, container.preferences.defaultCurrency) ?: return null
        if (!parsed.transactionIntent) return null
        val amount = parsed.amount ?: return null
        val currency = parsed.currency ?: return null
        val direction = GenericExtractors.detectDirectionOverride(raw) ?: TransactionDirection.DEBIT
        return AiEditorFill(amount, currency, parsed.merchant, direction)
    }

    fun setDateRange(range: DateRangeFilter) { filters.value = filters.value.copy(dateRange = range) }
    fun setQuery(query: String) { filters.value = filters.value.copy(query = query) }
    fun setDirectionFilter(direction: TransactionDirection?) {
        filters.value = filters.value.copy(direction = direction)
    }
    fun setAccountFilter(account: AccountFilter) { filters.value = filters.value.copy(account = account) }
    fun setPersonFilter(person: PersonFilter) { filters.value = filters.value.copy(person = person) }
    fun setTriageOnly(enabled: Boolean) { filters.value = filters.value.copy(triageOnly = enabled) }
    fun setAmountRange(min: java.math.BigDecimal?, max: java.math.BigDecimal?) {
        filters.value = filters.value.copy(minAmount = min, maxAmount = max)
    }
    fun setSourceFilter(source: TransactionSource?) { filters.value = filters.value.copy(source = source) }
    fun setKindFilter(kind: TransactionKind?) {
        filters.value = filters.value.copy(kind = kind)
    }

    fun setAnalyticsStatus(status: AnalyticsStatusFilter?) {
        filters.value = filters.value.copy(analyticsStatus = status)
    }

    /** Category drill-down filter; null clears it. There's no picker -- it arrives via [applyDrillDown]. */
    fun setCategoryFilter(categoryIds: Set<Long>?) {
        filters.value = filters.value.copy(categoryIds = categoryIds)
    }

    /**
     * Replaces every filter with exactly [drill] (e.g. a Copilot answer's
     * "See transactions"), so filters left over from earlier don't silently
     * narrow what the answer was about.
     */
    fun applyDrillDown(drill: ActivityDrillDown) {
        filters.value = drill.applyTo(ActivityFilters())
    }
    fun clearFilters() { filters.value = ActivityFilters() }

    fun enterBulkMode(initialId: Long) {
        bulkMode.value = BulkModeState(isInBulkMode = true, selectedIds = setOf(initialId))
    }

    fun exitBulkMode() { bulkMode.value = BulkModeState() }

    fun toggleSelected(transactionId: Long) {
        bulkMode.value = bulkMode.value.let { it.copy(selectedIds = toggleSelection(it.selectedIds, transactionId)) }
    }

    fun selectAll(ids: List<Long>) {
        bulkMode.value = bulkMode.value.copy(selectedIds = ids.toSet())
    }

    fun bulkSetCategoryId(categoryId: Long?) = safeWrite("bulkSetCategoryId") {
        container.bulkUpdateTransactions(
            bulkMode.value.selectedIds.toList(),
            BulkTransactionPatch(categoryId = FieldUpdate.Set(categoryId)),
        )
        bulkMode.value = BulkModeState()
    }

    fun bulkCreateAndAssignCategory(
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: java.math.BigDecimal?,
        parentCategoryId: Long?,
    ) = safeWrite("bulkCreateAndAssignCategory") {
        val categoryId = container.categoryRepository.save(
            Category(
                name = name,
                icon = icon,
                colorHex = colorHex,
                isCustom = true,
                monthlyCap = monthlyCap,
                parentCategoryId = parentCategoryId,
            )
        )
        container.bulkUpdateTransactions(
            bulkMode.value.selectedIds.toList(),
            BulkTransactionPatch(categoryId = FieldUpdate.Set(categoryId)),
        )
        bulkMode.value = BulkModeState()
    }

    fun bulkSetExcludeFromStats(excluded: Boolean) = safeWrite("bulkSetExcludeFromStats") {
        container.bulkUpdateTransactions(bulkMode.value.selectedIds.toList(), BulkTransactionPatch(excludeFromStats = excluded))
        bulkMode.value = BulkModeState()
    }

    fun bulkAssignAccount(accountId: Long?) = safeWrite("bulkAssignAccount") {
        container.bulkUpdateTransactions(bulkMode.value.selectedIds.toList(), BulkTransactionPatch(accountId = FieldUpdate.Set(accountId)))
        bulkMode.value = BulkModeState()
    }

    fun bulkSetPerson(person: String?) = safeWrite("bulkSetPerson") {
        container.bulkUpdateTransactions(bulkMode.value.selectedIds.toList(), BulkTransactionPatch(person = FieldUpdate.Set(person)))
        bulkMode.value = BulkModeState()
    }

    fun bulkDelete() = safeWrite("bulkDelete") {
        container.bulkUpdateTransactions.delete(bulkMode.value.selectedIds.toList())
        bulkMode.value = BulkModeState()
    }

    private fun safeWrite(what: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }
                .onFailure { container.recordError("TransactionsViewModel.$what", it) }
        }
    }

    /**
     * Files one transaction under an account, and remembers that this merchant
     * belongs there.
     *
     * Before this, assigning an account taught the app nothing: a merchant
     * whose messages carry no card number the app can match stayed unassigned
     * forever, however many times you fixed it -- and an unassigned transaction
     * sits in no balance at all.
     *
     * Clearing the account ([accountId] null) deliberately teaches nothing:
     * "not this one" is not a rule. Bulk assignment does not learn either -- a
     * multi-select spanning several merchants is the worst possible thing to
     * infer one rule from.
     *
     * The rule is reversible from the merchant's own screen ("Forget"), which
     * matters more here than for categories: a wrong account silently moves
     * money between balances.
     */
    fun assignAccount(transaction: Transaction, accountId: Long?) =
        safeWrite("assignAccount") {
            container.transactionRepository.assignAccount(transaction.id, accountId)
            if (accountId != null) {
                transaction.merchant?.takeIf { it.isNotBlank() }?.let {
                    container.merchantAccountRepository.learnMapping(it, accountId)
                }
            }
        }

    fun setPerson(transactionId: Long, person: String?) =
        safeWrite("setPerson") {
            // A newly typed name joins the saved list so it's reusable and exported.
            person?.let(container.preferences::addPerson)
            container.transactionRepository.setPerson(transactionId, person)
        }

    /** Swipe-to-flag: quick toggle of the analytics-exclusion flag without opening the edit dialog. */
    fun toggleExcludeFromStats(transaction: Transaction) =
        safeWrite("toggleExcludeFromStats") {
            container.transactionRepository.update(transaction.copy(excludeFromStats = !transaction.excludeFromStats))
        }

    /**
     * Picks a category for one transaction from the Activity feed, and -- this
     * is the part that used to be missing -- remembers it.
     *
     * The Review queue (`ReviewViewModel.learnCategory`), the Uncategorized
     * queue and the Merchant screen all already taught the app from a
     * correction. This path, the one people actually use day to day, only set
     * the row and forgot, so the same merchant arrived uncategorized forever.
     *
     * [CategoryRepository.learnMapping] backfills only rows whose category is
     * still unset, so a category picked deliberately somewhere else is never
     * overwritten by this.
     *
     * Bulk mode deliberately does NOT call this: a multi-select spanning
     * several merchants is the worst possible thing to learn one rule from.
     */
    fun setCategoryId(transaction: Transaction, categoryId: Long?) =
        safeWrite("setCategoryId") {
            container.transactionRepository.setCategory(transaction.id, categoryId)
            learnCategory(transaction, categoryId)
        }

    fun createAndAssignCategory(
        transaction: Transaction,
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: java.math.BigDecimal?,
        parentCategoryId: Long?,
    ) = safeWrite("createAndAssignCategory") {
        val categoryId = container.categoryRepository.save(
            Category(
                name = name,
                icon = icon,
                colorHex = colorHex,
                isCustom = true,
                monthlyCap = monthlyCap,
                parentCategoryId = parentCategoryId,
            )
        )
        container.transactionRepository.setCategory(transaction.id, categoryId)
        learnCategory(transaction, categoryId)
    }

    /**
     * Teaches merchant -> category from a manual correction. Clearing a
     * category ([categoryId] null) teaches nothing: "not this" is not a rule.
     */
    private suspend fun learnCategory(transaction: Transaction, categoryId: Long?) {
        if (categoryId == null) return
        val merchant = transaction.merchant?.takeIf { it.isNotBlank() } ?: return
        container.categoryRepository.learnMapping(merchant, categoryId)
    }

    fun delete(transactionId: Long) =
        safeWrite("delete") { container.transactionRepository.delete(transactionId) }

    /** Deletes [tx] and mutes messages shaped like its original text. */
    fun deleteAndIgnoreSimilar(tx: Transaction) = safeWrite("deleteAndIgnoreSimilar") {
        tx.rawMessage?.let { container.ignoredMessageRepository.add(it, System.currentTimeMillis()) }
        container.transactionRepository.delete(tx.id)
    }

    /** Deletes [tx] and mutes every future message containing [keyword]. */
    fun deleteAndIgnoreKeyword(tx: Transaction, keyword: String) = safeWrite("deleteAndIgnoreKeyword") {
        container.ignoredKeywordRepository.add(keyword, System.currentTimeMillis())
        container.transactionRepository.delete(tx.id)
    }

    /** Bulk variant of [deleteAndIgnoreSimilar]: mutes the template of every selected message. */
    fun bulkDeleteAndIgnoreSimilar() = safeWrite("bulkDeleteAndIgnoreSimilar") {
        val ids = bulkMode.value.selectedIds
        val now = System.currentTimeMillis()
        container.transactionRepository.observeTransactions().first()
            .filter { it.id in ids }
            .forEach { tx -> tx.rawMessage?.let { container.ignoredMessageRepository.add(it, now) } }
        container.bulkUpdateTransactions.delete(ids.toList())
        bulkMode.value = BulkModeState()
    }

    /** Persists a brand-new dynamic category and returns it (id included) -- for the editor's "+ New" chip. */
    suspend fun createCategory(
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: java.math.BigDecimal?,
        parentCategoryId: Long?,
    ): Category {
        val category = Category(
            name = name,
            icon = icon,
            colorHex = colorHex,
            isCustom = true,
            monthlyCap = monthlyCap,
            parentCategoryId = parentCategoryId,
        )
        val id = container.categoryRepository.save(category)
        return category.copy(id = id)
    }

    /**
     * Keeps the legacy [SpendingCategory] field consistent for a manually
     * entered/edited transaction: reverses [categoryId] onto its dynamic
     * category's name, then that name back onto an enum value via
     * [CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM]. A custom category
     * (no seeded-name match) or no category at all falls back to
     * UNCATEGORIZED, same as an ordinary uncategorized capture.
     */
    private fun resolveLegacySpendingCategory(categoryId: Long?): SpendingCategory {
        val name = categoryId?.let { id -> uiState.value.categories.firstOrNull { it.id == id }?.name }
        return name?.let { CategoryMigrationMapping.CATEGORY_NAME_TO_ENUM[it] } ?: SpendingCategory.UNCATEGORIZED
    }

    /** Adds a transaction the user typed in by hand (a missed/uncaptured one). */
    fun addManual(
        amount: java.math.BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        categoryId: Long?,
        timestampMillis: Long,
        excludeFromStats: Boolean,
        kind: TransactionKind = TransactionKind.defaultFor(direction),
    ) = safeWrite("addManual") {
        person?.let(container.preferences::addPerson)
        container.transactionRepository.recordManual(
            Transaction(
                amount = amount,
                currencyCode = currencyCode.trim().uppercase(),
                direction = direction,
                merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
                source = com.xemmy.mmbank.domain.model.TransactionSource.MANUAL,
                timestampMillis = timestampMillis,
                accountId = accountId,
                person = person,
                categoryId = categoryId,
                spendingCategory = resolveLegacySpendingCategory(categoryId),
                matchedRuleName = MANUAL_ENTRY_LABEL,
                excludeFromStats = excludeFromStats,
                // The user picked it, so automation never second-guesses it.
                kind = com.xemmy.mmbank.domain.ledger.TransactionKindDetector.coerce(kind, direction),
                kindUserSet = true,
            )
        )
    }

    /** Saves both sides of a transfer/card payment the user typed in, as one linked pair. */
    fun addTransfer(draft: TransferLegs.Draft) = safeWrite("addTransfer") {
        draft.person?.let(container.preferences::addPerson)
        container.ledgerRepository.recordTransfer(draft)
    }

    /**
     * Re-classifies a transaction (spend / refund / transfer / card payment...).
     * With [remember], the merchant's other automatically-classified rows
     * follow, and future captures from it arrive with this kind.
     */
    fun setKind(transaction: Transaction, kind: TransactionKind, remember: Boolean) = safeWrite("setKind") {
        container.ledgerRepository.setKind(transaction.id, kind, remember)
    }

    /** Saves edits to an existing transaction, preserving its untouched fields. */
    fun updateTransaction(
        original: Transaction,
        amount: java.math.BigDecimal,
        currencyCode: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        categoryId: Long?,
        timestampMillis: Long,
        excludeFromStats: Boolean,
        kind: TransactionKind = original.kind,
    ) = safeWrite("updateTransaction") {
        person?.let(container.preferences::addPerson)
        container.transactionRepository.update(
            original.copy(
                amount = amount,
                currencyCode = currencyCode.trim().uppercase(),
                direction = direction,
                merchant = merchant?.trim()?.takeIf { it.isNotBlank() },
                accountId = accountId,
                person = person,
                categoryId = categoryId,
                spendingCategory = resolveLegacySpendingCategory(categoryId),
                timestampMillis = timestampMillis,
                excludeFromStats = excludeFromStats,
                // Flipping Spent <-> Received must not leave a one-sided kind
                // on the wrong side (a "spend" credit would count as spending).
                kind = com.xemmy.mmbank.domain.ledger.TransactionKindDetector.coerce(original.kind, direction),
            )
        )
        // A new kind goes through the ledger so transfer links are made or
        // released consistently.
        if (kind != original.kind) container.ledgerRepository.setKind(original.id, kind, rememberForMerchant = false)
    }

    fun setSenderLabel(sender: String, label: String) =
        safeWrite("setSenderLabel") { container.senderLabelRepository.setLabel(sender, label) }

    fun clearSenderLabel(sender: String) =
        safeWrite("clearSenderLabel") { container.senderLabelRepository.clearLabel(sender) }

    /** Resolved display name: user override wins, then the built-in bank map, then the raw sender. */
    fun bankNameFor(sender: String?, labels: Map<String, String>): String? {
        if (sender.isNullOrBlank()) return null
        return labels[sender] ?: BankNames.displayName(sender)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    /**
     * Optional deep-link filter from the nav route. "unassigned" opens the
     * screen already showing only transactions that belong to no account --
     * what the Home and Accounts screens' "not in any account" card links to.
     */
    initialAccountFilter: String = "",
    /** Optional [ActivityDrillDown.encode]d filter set, e.g. from a Copilot answer. */
    initialDrill: String = "",
    onOpenUncategorizedQueue: () -> Unit = {},
    onOpenMerchant: (String) -> Unit = {},
    viewModel: TransactionsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    // Keyed on the arg so re-navigating with it re-applies the filter, while a
    // recomposition (or the user clearing the filter) does not fight the user.
    LaunchedEffect(initialAccountFilter) {
        if (initialAccountFilter == "unassigned") {
            viewModel.setAccountFilter(AccountFilter.Unassigned)
        }
    }
    LaunchedEffect(initialDrill) {
        ActivityDrillDown.parse(initialDrill)?.let(viewModel::applyDrillDown)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val spendSummary by viewModel.spendSummaryState.collectAsStateWithLifecycle()
    val uncategorizedCount by viewModel.uncategorizedCount.collectAsStateWithLifecycle()
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Transaction?>(null) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var deleteTarget by remember { mutableStateOf<Transaction?>(null) }
    // Dynamic-category picker target: set by either an end-to-start swipe (see
    // SwipeToActionBox) or tapping a row's category chip, cleared once a
    // category is picked/created or the picker is dismissed.
    var categoryPickerFor by remember { mutableStateOf<Transaction?>(null) }
    // Bulk-mode's own category picker -- applies to every selected id at once,
    // so it's a plain flag rather than a per-transaction target.
    var bulkCategoryPickerOpen by remember { mutableStateOf(false) }

    // Brand accent for a specific-account filter, reusing the same GlowTone
    // mapping every row already uses for its own origin glow — one source of
    // truth for "what color is this account", not a second hardcoded map.
    val activeAccountGlow = remember(state.filters.account, state.accounts) {
        TransactionFilterEngine.activeAccount(state.filters.account, state.accounts)
            ?.let { account -> GlowTones.forAccountOrigin(account.name, account.bankName) }
            ?.let { tone -> GlowPalette.color(tone) }
    }

    // Fluid header: scrolling the feed up compresses/crossfades the search
    // field and filter chips into a compact bar; scrolling back down at the
    // top of the list re-expands them. All the math lives in FilterBarCollapse
    // (pure Kotlin); this connection only forwards deltas and mirrors the
    // fraction into Compose state.
    val collapse = remember { FilterBarCollapse() }
    var collapseFraction by remember { mutableFloatStateOf(0f) }
    val feedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val consumedY = collapse.onPreScroll(available.y)
                collapseFraction = collapse.fraction
                return Offset(0f, consumedY)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val consumedY = collapse.onPostScroll(available.y)
                collapseFraction = collapse.fraction
                return Offset(0f, consumedY)
            }
        }
    }

    // Standard Material 3 collapse behavior for the Premium hero card, kept
    // separate from the filter header's own hand-rolled FilterBarCollapse
    // above. Attached as the OUTER nestedScroll (on the Scaffold, below) so it
    // gets first claim on upward scroll: the hero collapses away completely
    // before any leftover delta reaches feedScrollConnection to compact the
    // filter header, and expands back in the reverse order on scroll down.
    val heroScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val heroVisible = LocalDesignMode.current == DesignMode.PREMIUM && !state.isInBulkMode
    // The hero card stops measuring (and so stops updating heightOffsetLimit)
    // whenever it's not shown -- Classic, or bulk mode temporarily hiding it.
    // Without this, a mid-collapse offset from before it disappeared would
    // silently keep eating scroll deltas meant for the list underneath.
    LaunchedEffect(heroVisible) {
        if (!heroVisible) {
            heroScrollBehavior.state.heightOffsetLimit = 0f
            heroScrollBehavior.state.heightOffset = 0f
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(heroScrollBehavior.nestedScrollConnection),
        floatingActionButton = {
            FloatingActionButton(onClick = { addOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add transaction")
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .obsidianCanvas()
                .padding(padding)
                .nestedScroll(feedScrollConnection),
        ) {
            if (state.isInBulkMode) {
                BulkModeBar(
                    selectedCount = state.selectedTransactionIds.size,
                    accounts = state.accounts,
                    knownPersons = state.knownPersons,
                    onSelectAll = { viewModel.selectAll(state.transactions.map { it.id }) },
                    onCategorize = { bulkCategoryPickerOpen = true },
                    onAssignAccount = { accountId -> viewModel.bulkAssignAccount(accountId) },
                    onAssignPerson = { person -> viewModel.bulkSetPerson(person) },
                    onExclude = { viewModel.bulkSetExcludeFromStats(true) },
                    onDelete = { viewModel.bulkDelete() },
                    onDeleteIgnoreSimilar = { viewModel.bulkDeleteAndIgnoreSimilar() },
                    onClose = { viewModel.exitBulkMode() },
                )
            } else {
                CompactFilterBar(
                    query = state.filters.query,
                    filtersActive = state.filters.active,
                    fraction = collapseFraction,
                    onExpand = {
                        collapse.expand()
                        collapseFraction = 0f
                    },
                )
                if (uncategorizedCount > 0) {
                    UncategorizedBanner(count = uncategorizedCount, onClick = onOpenUncategorizedQueue)
                }
                // Premium-only hero spend summary, directly above the filter bar.
                if (heroVisible) {
                    Box(
                        Modifier
                            .clipToBounds()
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                heroScrollBehavior.state.heightOffsetLimit = -placeable.height.toFloat()
                                val heroFraction = heroScrollBehavior.state.collapsedFraction
                                val visibleHeight = (placeable.height * (1f - heroFraction)).roundToInt()
                                layout(placeable.width, visibleHeight) {
                                    placeable.placeRelative(0, visibleHeight - placeable.height)
                                }
                            }
                            .graphicsLayer { alpha = 1f - heroScrollBehavior.state.collapsedFraction },
                    ) {
                        HeroSpendSummaryCard(state = spendSummary)
                    }
                }
                Box(
                    Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            collapse.updateRange(placeable.height.toFloat())
                            val visibleHeight = (placeable.height * (1f - collapseFraction)).roundToInt()
                            layout(placeable.width, visibleHeight) {
                                placeable.placeRelative(0, visibleHeight - placeable.height)
                            }
                        }
                        .graphicsLayer { alpha = 1f - collapseFraction },
                ) {
                    Column {
                    when (state.filterBarLayout) {
                        FilterBarLayout.SINGLE_ROW -> TransactionFilterBar(
                            state = state,
                            onQuery = viewModel::setQuery,
                            onDateRange = viewModel::setDateRange,
                            onDirectionFilter = viewModel::setDirectionFilter,
                            onAccountFilter = viewModel::setAccountFilter,
                            onPersonFilter = viewModel::setPersonFilter,
                            onTriageOnly = viewModel::setTriageOnly,
                            onAmountRange = viewModel::setAmountRange,
                            onSourceFilter = viewModel::setSourceFilter,
                            onAnalyticsStatus = viewModel::setAnalyticsStatus,
                            onClear = viewModel::clearFilters,
                            onKindFilter = viewModel::setKindFilter,
                        )
                        FilterBarLayout.CLASSIC -> ActivityFilterBar(
                            state = state,
                            onQuery = viewModel::setQuery,
                            onDateRange = viewModel::setDateRange,
                            onDirectionFilter = viewModel::setDirectionFilter,
                            onAccountFilter = viewModel::setAccountFilter,
                            onPersonFilter = viewModel::setPersonFilter,
                            onTriageOnly = viewModel::setTriageOnly,
                            onAmountRange = viewModel::setAmountRange,
                            onSourceFilter = viewModel::setSourceFilter,
                            onAnalyticsStatus = viewModel::setAnalyticsStatus,
                            onClear = viewModel::clearFilters,
                            onKindFilter = viewModel::setKindFilter,
                        )
                    }
                    // The category filter has no picker in either bar, so it
                    // is always shown here while active, with a way out.
                    state.filters.categoryIds?.let { ids ->
                        CategoryFilterRow(
                            label = categoryFilterLabel(ids, state.categories),
                            onClear = { viewModel.setCategoryFilter(null) },
                        )
                    }
                    }
                }
            }

            if (state.transactions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.hasAnyTransactions) {
                            "No transactions match your filters."
                        } else {
                            "No transactions yet.\nCaptured bank messages appear here automatically — " +
                                "or tap + to add one the app missed."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Floating day pills (spec 5.3): group the already date-sorted
                // feed by calendar day and float a sticky label over each
                // section. groupBy preserves first-encounter key order, which
                // matches the feed's order as long as it stays date-sorted.
                val today = remember { LocalDate.now() }
                val grouped = remember(state.transactions) {
                    state.transactions.groupBy { it.timestampMillis.toLocalDate() }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // Bottom clearance so the "+" FAB never floats over the last
                    // row's edit/delete actions (56dp FAB + 16dp margin + 16dp).
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                ) {
                    grouped.forEach { (day, dayTransactions) ->
                        stickyHeader(key = "day-$day") {
                            DayPill(day = day, today = today)
                        }
                        items(dayTransactions, key = { it.id }) { tx ->
                            Column {
                                SwipeToActionBox(
                                    enabled = !state.isInBulkMode,
                                    onFlag = { viewModel.toggleExcludeFromStats(tx) },
                                    onCategorize = { categoryPickerFor = tx },
                                ) {
                                    TransactionCard(
                                        tx = tx,
                                        accounts = state.accounts,
                                        knownPersons = state.knownPersons,
                                        categories = state.categories,
                                        bankName = viewModel.bankNameFor(tx.sender, state.senderLabels),
                                        accountFilterTint = activeAccountGlow,
                                        isInBulkMode = state.isInBulkMode,
                                        isSelected = tx.id in state.selectedTransactionIds,
                                        onAssignAccount = { accountId -> viewModel.assignAccount(tx, accountId) },
                                        onAssignPerson = { person -> viewModel.setPerson(tx.id, person) },
                                        onOpenCategoryPicker = { categoryPickerFor = tx },
                                        onRenameSender = { label -> tx.sender?.let { viewModel.setSenderLabel(it, label) } },
                                        onResetSender = { tx.sender?.let { viewModel.clearSenderLabel(it) } },
                                        onEdit = { editing = tx },
                                        onDelete = { deleteTarget = tx },
                                        onCardClick = { selectedTransaction = tx },
                                        onLongPress = { viewModel.enterBulkMode(tx.id) },
                                        onToggleSelected = { viewModel.toggleSelected(tx.id) },
                                    )
                                }
                                // White base, not DividerDefaults: the theme's divider
                                // gray at 10% alpha would vanish on the obsidian canvas.
                                HorizontalDivider(
                                    modifier = Modifier
                                        .padding(vertical = 8.dp)
                                        .alpha(0.1f),
                                    thickness = 1.dp,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (addOpen) {
        TransactionEditorDialog(
            existing = null,
            accounts = state.accounts,
            knownPersons = state.knownPersons,
            categories = state.categories,
            defaultCurrency = state.defaultCurrency,
            aiEnabled = viewModel.aiParsingConfigured,
            onAiParse = viewModel::parseWithAi,
            onCreateCategory = viewModel::createCategory,
            onDismiss = { addOpen = false },
            onSave = { amount, currency, direction, merchant, accountId, person, categoryId, timestamp, excludeFromStats, kind ->
                viewModel.addManual(amount, currency, direction, merchant, accountId, person, categoryId, timestamp, excludeFromStats, kind)
                addOpen = false
            },
            onSaveTransfer = { draft ->
                viewModel.addTransfer(draft)
                addOpen = false
            },
        )
    }

    editing?.let { tx ->
        TransactionEditorDialog(
            existing = tx,
            accounts = state.accounts,
            knownPersons = state.knownPersons,
            categories = state.categories,
            defaultCurrency = state.defaultCurrency,
            onCreateCategory = viewModel::createCategory,
            onDismiss = { editing = null },
            onSave = { amount, currency, direction, merchant, accountId, person, categoryId, timestamp, excludeFromStats, kind ->
                viewModel.updateTransaction(tx, amount, currency, direction, merchant, accountId, person, categoryId, timestamp, excludeFromStats, kind)
                editing = null
            },
        )
    }

    categoryPickerFor?.let { tx ->
        CategoryPickerBottomSheet(
            categories = state.categories,
            currency = state.defaultCurrency,
            onSelect = { category ->
                viewModel.setCategoryId(tx, category.id)
                categoryPickerFor = null
            },
            onCreateNew = { name, colorHex, icon, cap, parentId ->
                viewModel.createAndAssignCategory(tx, name, colorHex, icon, cap, parentId)
                categoryPickerFor = null
            },
            onDismiss = { categoryPickerFor = null },
        )
    }

    if (bulkCategoryPickerOpen) {
        CategoryPickerBottomSheet(
            categories = state.categories,
            currency = state.defaultCurrency,
            onSelect = { category ->
                viewModel.bulkSetCategoryId(category.id)
                bulkCategoryPickerOpen = false
            },
            onCreateNew = { name, colorHex, icon, cap, parentId ->
                viewModel.bulkCreateAndAssignCategory(name, colorHex, icon, cap, parentId)
                bulkCategoryPickerOpen = false
            },
            onDismiss = { bulkCategoryPickerOpen = false },
        )
    }

    deleteTarget?.let { tx ->
        DeleteWithIgnoreDialog(
            title = "Delete this transaction?",
            rawMessage = tx.rawMessage,
            merchant = tx.merchant,
            onDelete = { deleteTarget = null; viewModel.delete(tx.id) },
            onDeleteIgnoreSimilar = { deleteTarget = null; viewModel.deleteAndIgnoreSimilar(tx) },
            onDeleteIgnoreKeyword = { kw -> deleteTarget = null; viewModel.deleteAndIgnoreKeyword(tx, kw) },
            onDismiss = { deleteTarget = null },
        )
    }

    selectedTransaction?.let { tx ->
        TransactionDetailSheet(
            transaction = tx,
            accountName = state.accounts.firstOrNull { it.id == tx.accountId }?.name,
            bankName = viewModel.bankNameFor(tx.sender, state.senderLabels),
            categories = state.categories,
            onEditClick = {
                selectedTransaction = null
                editing = tx
            },
            onDeleteClick = {
                deleteTarget = tx
                selectedTransaction = null
            },
            onDismiss = { selectedTransaction = null },
            onMerchantClick = { merchant ->
                selectedTransaction = null
                onOpenMerchant(merchant)
            },
            onKindChange = { kind, remember ->
                viewModel.setKind(tx, kind, remember)
                selectedTransaction = null
            },
        )
    }
}

/**
 * Floating sticky-header day pill (spec 5.3): a small translucent pill that
 * hovers over its section while scrolling, labeling the calendar day below it.
 */
@Composable
private fun DayPill(day: LocalDate, today: LocalDate) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(ObsidianTokens.pillRadius))
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(ObsidianTokens.pillRadius))
                .border(1.dp, ObsidianTokens.hairline, RoundedCornerShape(ObsidianTokens.pillRadius))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                dayPillLabel(day, today),
                color = ObsidianTokens.textMeta,
                fontFamily = AppFonts.inter,
                fontWeight = FontWeight.Medium,
                fontSize = ObsidianTokens.metaFontSize,
                letterSpacing = ObsidianTokens.pillTracking,
            )
        }
    }
}

/**
 * Tactile micro-interaction: wraps a transaction row in a two-way swipe.
 * Swiping start-to-end toggles the analytics-exclusion "flag"; swiping
 * end-to-start opens a quick category picker. Neither direction actually
 * dismisses the row -- [confirmValueChange] always returns false so the card
 * snaps back after firing its action, mirroring a Gmail-style "swipe to
 * archive without removing from view" interaction rather than a real delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToActionBox(
    enabled: Boolean,
    onFlag: () -> Unit,
    onCategorize: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val haptic = LocalHapticFeedback.current
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onFlag()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onCategorize()
                }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { SwipeActionBackground(dismissState.dismissDirection) },
    ) {
        content()
    }
}

@Composable
private fun SwipeActionBackground(direction: SwipeToDismissBoxValue) {
    val (background, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(ObsidianTokens.debit.copy(alpha = 0.28f), Icons.Filled.Flag, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(ObsidianTokens.credit.copy(alpha = 0.28f), Icons.Filled.Label, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(background, RoundedCornerShape(ObsidianTokens.rowRadius))
            .padding(horizontal = 22.dp),
        contentAlignment = alignment,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = Color.White) }
    }
}

/**
 * Contextual bar shown in place of the filter header while the Transactions
 * screen is in bulk-edit mode: selection count, Select All, and the bulk
 * actions (Categorize / Account / Person, plus Exclude / Delete tucked into
 * a "More" overflow), plus a close button to drop back to the normal filter
 * header.
 */
@Composable
private fun BulkModeBar(
    selectedCount: Int,
    accounts: List<Account>,
    knownPersons: List<String>,
    onSelectAll: () -> Unit,
    onCategorize: () -> Unit,
    onAssignAccount: (Long?) -> Unit,
    onAssignPerson: (String?) -> Unit,
    onExclude: () -> Unit,
    onDelete: () -> Unit,
    onDeleteIgnoreSimilar: () -> Unit,
    onClose: () -> Unit,
) {
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var newPersonOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    var deleteConfirmOpen by remember { mutableStateOf(false) }
    val hasSelection = selectedCount > 0
    Surface(color = ObsidianTokens.canvas, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Exit bulk mode")
            }
            Text(
                "$selectedCount selected",
                style = MaterialTheme.typography.labelLarge,
                color = ObsidianTokens.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.DoneAll, contentDescription = "Select all")
            }
            IconButton(onClick = onCategorize, enabled = hasSelection) {
                Icon(Icons.Filled.CategoryIcon, contentDescription = "Categorize selected")
            }
            Box {
                IconButton(onClick = { accountMenuOpen = true }, enabled = hasSelection) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = "Assign account to selected")
                }
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Unassigned") },
                        onClick = { accountMenuOpen = false; onAssignAccount(null) },
                    )
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = { accountMenuOpen = false; onAssignAccount(account.id) },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { personMenuOpen = true }, enabled = hasSelection) {
                    Icon(Icons.Filled.Person, contentDescription = "Assign person to selected")
                }
                DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Nobody") },
                        onClick = { personMenuOpen = false; onAssignPerson(null) },
                    )
                    knownPersons.forEach { person ->
                        DropdownMenuItem(
                            text = { Text(person) },
                            onClick = { personMenuOpen = false; onAssignPerson(person) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("New person…") },
                        onClick = { personMenuOpen = false; newPersonOpen = true },
                    )
                }
            }
            Box {
                IconButton(onClick = { moreMenuOpen = true }, enabled = hasSelection) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More bulk actions")
                }
                DropdownMenu(expanded = moreMenuOpen, onDismissRequest = { moreMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Exclude from analytics") },
                        onClick = { moreMenuOpen = false; onExclude() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { moreMenuOpen = false; deleteConfirmOpen = true },
                    )
                }
            }
        }
    }
    if (newPersonOpen) {
        NewPersonDialog(
            onDismiss = { newPersonOpen = false },
            onSave = { person -> newPersonOpen = false; onAssignPerson(person) },
        )
    }
    if (deleteConfirmOpen) {
        DeleteWithIgnoreDialog(
            title = "Delete $selectedCount transaction${if (selectedCount == 1) "" else "s"}?",
            // Bulk can't know which messages are captured, so the option is
            // always offered; manual entries simply have no text to mute.
            rawMessage = "bulk",
            merchant = null,
            onDelete = { deleteConfirmOpen = false; onDelete() },
            onDeleteIgnoreSimilar = { deleteConfirmOpen = false; onDeleteIgnoreSimilar() },
            onDeleteIgnoreKeyword = null,
            onDismiss = { deleteConfirmOpen = false },
        )
    }
}

/**
 * The compact top app bar the filter header crossfades into on scroll: a thin
 * tappable strip echoing the active query/filters. Its height and alpha track
 * the collapse [fraction], so it grows in exactly as the full bar compresses
 * out; tapping it snaps the full bar back open.
 */
@Composable
private fun CompactFilterBar(
    query: String,
    filtersActive: Boolean,
    fraction: Float,
    onExpand: () -> Unit,
) {
    if (fraction <= 0f) return
    Surface(
        color = ObsidianTokens.canvas,
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val visibleHeight = (placeable.height * fraction).roundToInt()
                layout(placeable.width, visibleHeight) {
                    placeable.placeRelative(0, visibleHeight - placeable.height)
                }
            }
            .graphicsLayer { alpha = fraction },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .clickable(onClick = onExpand)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                when {
                    query.isNotBlank() -> "“$query”"
                    filtersActive -> "Filters active"
                    else -> "Search & filters"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = "Expand filters",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Glass filter chip (spec 5.3): idle White@4% fill / hairline label, active
 * White@12% fill / bright label. A thin wrapper around Material's FilterChip,
 * used by the transaction editor dialog; the filter header uses the denser
 * [FilterBarChip].
 */
@Composable
internal fun ObsidianChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        leadingIcon = leadingIcon,
        shape = RoundedCornerShape(ObsidianTokens.pillRadius),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.White.copy(alpha = 0.04f),
            labelColor = ObsidianTokens.textMeta,
            iconColor = ObsidianTokens.textMeta,
            selectedContainerColor = Color.White.copy(alpha = 0.12f),
            selectedLabelColor = ObsidianTokens.textPrimary,
            selectedLeadingIconColor = ObsidianTokens.textPrimary,
        ),
    )
}

/**
 * Dense primary filter chip for the Activity filter header: 28dp tall, 8dp
 * corners, 12sp label. Same glass treatment as [ObsidianChip] (idle White@4% /
 * active White@12%) but hand-rolled so height and padding aren't inflated by
 * Material's chip defaults.
 */
/** "Dining" / "Food + 1 more" -- the parent first when a sub-category tree was selected. */
internal fun categoryFilterLabel(ids: Set<Long>, categories: List<com.xemmy.mmbank.domain.model.Category>): String {
    val chosen = categories.filter { it.id in ids }
    if (chosen.isEmpty()) return "Selected categories"
    val root = chosen.firstOrNull { c -> c.parentCategoryId == null || c.parentCategoryId !in ids } ?: chosen.first()
    val others = chosen.size - 1
    return if (others == 0) root.name else "${root.name} + $others more"
}

@Composable
private fun CategoryFilterRow(label: String, onClear: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterBarChip(
            selected = true,
            onClick = onClear,
            label = "Category: $label  ✕",
            icon = Icons.Filled.CategoryIcon,
        )
    }
}

@Composable
internal fun FilterBarChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val background = if (selected) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f)
    val border = if (selected) Color.White.copy(alpha = 0.28f) else ObsidianTokens.hairline
    val labelColor = if (selected) ObsidianTokens.textPrimary else ObsidianTokens.textMeta
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(shape)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = labelColor)
        }
        Text(
            label,
            fontSize = 12.sp,
            color = labelColor,
            fontFamily = AppFonts.inter,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Subtle secondary action for the filter header's bottom row (Inbox Zero,
 * Amount, Source, Analytics): no fill and a thinner border when idle, so it
 * reads a clear step below the primary [FilterBarChip]s.
 */
@Composable
internal fun SecondaryActionChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val shape = RoundedCornerShape(8.dp)
    val labelColor = if (selected) ObsidianTokens.textPrimary else ObsidianTokens.textMeta
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (selected) Color.White.copy(alpha = 0.10f) else Color.Transparent, shape)
            .border(0.5.dp, if (selected) Color.White.copy(alpha = 0.24f) else ObsidianTokens.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = labelColor)
        }
        Text(
            label,
            fontSize = 11.sp,
            color = labelColor,
            fontFamily = AppFonts.inter,
            maxLines = 1,
        )
    }
}

/**
 * 40dp-tall glass search field. A BasicTextField with a hand-built decoration
 * box, because OutlinedTextField's 56dp minimum and 16dp vertical padding
 * can't be reduced without clipping.
 */
@Composable
internal fun CompactSearchField(
    query: String,
    onQuery: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = ObsidianTokens.textPrimary),
        cursorBrush = SolidColor(ObsidianTokens.textPrimary),
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(
                1.dp,
                if (focused) Color.White.copy(alpha = 0.28f) else ObsidianTokens.hairline,
                shape,
            )
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = ObsidianTokens.textMeta,
                )
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search merchant, bank, amount…",
                            style = MaterialTheme.typography.bodySmall,
                            color = ObsidianTokens.textMeta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onQuery("") },
                        tint = ObsidianTokens.textMeta,
                    )
                }
            }
        },
    )
}

/** Glass field colors (spec 5.3): surfaceGlass fill, hairline/focus border — shared by every OutlinedTextField in the glass dialogs. */
@Composable
internal fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = ObsidianTokens.surfaceGlass,
    focusedContainerColor = ObsidianTokens.surfaceGlass,
    unfocusedBorderColor = ObsidianTokens.hairline,
    focusedBorderColor = Color.White.copy(alpha = 0.28f),
)

/**
 * Search box + date/direction/account/person/triage/amount/source/analytics
 * filter chips for the Activity list. Filtering is purely in-memory
 * (personal-scale data), so it's instant and needs no new queries. Primary
 * chips share one FlowRow and wrap only on narrow phones; the quieter
 * secondary row scrolls horizontally.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActivityFilterBar(
    state: TransactionsUiState,
    onQuery: (String) -> Unit,
    onDateRange: (DateRangeFilter) -> Unit,
    onDirectionFilter: (TransactionDirection?) -> Unit,
    onAccountFilter: (AccountFilter) -> Unit,
    onPersonFilter: (PersonFilter) -> Unit,
    onTriageOnly: (Boolean) -> Unit,
    onAmountRange: (java.math.BigDecimal?, java.math.BigDecimal?) -> Unit,
    onSourceFilter: (TransactionSource?) -> Unit,
    onAnalyticsStatus: (AnalyticsStatusFilter?) -> Unit,
    onClear: () -> Unit,
    onKindFilter: (TransactionKind?) -> Unit = {},
) {
    val f = state.filters
    var kindMenuOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var analyticsMenuOpen by remember { mutableStateOf(false) }
    var customRangeOpen by remember { mutableStateOf(false) }
    var amountOpen by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Compact glass search bar (spec 5.3): surfaceGlass fill, 14dp radius,
        // 40dp tall.
        CompactSearchField(
            query = f.query,
            onQuery = onQuery,
            modifier = Modifier.fillMaxWidth(),
        )

        // Primary filters — date range presets (incl. the data-driven statement
        // cycle and a twin-calendar custom range) plus direction/account/person
        // — share one FlowRow, wrapping onto extra lines only when the screen
        // is too narrow to fit them.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val presets = listOf(
                DateRangeFilter.All to "All",
                DateRangeFilter.Today to "Today",
                DateRangeFilter.Past7Days to "Past 7 days",
                DateRangeFilter.Past30Days to "Past 30 days",
                DateRangeFilter.YearToDate to "Year-to-date",
            )
            presets.forEach { (range, label) ->
                FilterBarChip(
                    selected = f.dateRange == range,
                    onClick = { onDateRange(range) },
                    label = label,
                )
            }
            // Label the statement chip with the cycle when one applies, so the
            // rolling-30-day fallback (no account / no cycle day) is visible.
            val cycleDay = TransactionFilterEngine
                .activeAccount(f.account, state.accounts)?.statementCycleDay
            FilterBarChip(
                selected = f.dateRange == DateRangeFilter.StatementCycle,
                onClick = { onDateRange(DateRangeFilter.StatementCycle) },
                label = if (cycleDay != null) "Statement (day $cycleDay)" else "Statement",
            )
            val custom = f.dateRange as? DateRangeFilter.Custom
            FilterBarChip(
                selected = custom != null,
                onClick = { customRangeOpen = true },
                label = custom?.let { "${it.start.format(chipDateFormat)} – ${it.end.format(chipDateFormat)}" }
                    ?: "Custom…",
                icon = Icons.Filled.DateRange,
            )

            FilterBarChip(
                selected = f.direction == null,
                onClick = { onDirectionFilter(null) },
                label = "All",
            )
            FilterBarChip(
                selected = f.direction == TransactionDirection.DEBIT,
                onClick = { onDirectionFilter(TransactionDirection.DEBIT) },
                label = "Spent",
            )
            FilterBarChip(
                selected = f.direction == TransactionDirection.CREDIT,
                onClick = { onDirectionFilter(TransactionDirection.CREDIT) },
                label = "Received",
            )

            // Account filter chip + menu.
            Box {
                val accountLabel = when (val a = f.account) {
                    AccountFilter.Any -> "Account"
                    AccountFilter.Unassigned -> "Unassigned"
                    is AccountFilter.Specific ->
                        state.accounts.firstOrNull { it.id == a.id }?.name ?: "Account"
                }
                FilterBarChip(
                    selected = f.account != AccountFilter.Any,
                    onClick = { accountMenuOpen = true },
                    label = accountLabel,
                    icon = Icons.Filled.AccountBalanceWallet,
                )
                DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any account") },
                        onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Any) },
                    )
                    DropdownMenuItem(
                        text = { Text("Unassigned") },
                        onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Unassigned) },
                    )
                    state.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = { accountMenuOpen = false; onAccountFilter(AccountFilter.Specific(account.id)) },
                        )
                    }
                }
            }

            // Person filter chip + menu.
            Box {
                val personLabel = when (val p = f.person) {
                    PersonFilter.Any -> "Person"
                    PersonFilter.Unassigned -> "Unassigned"
                    is PersonFilter.Specific -> p.name
                }
                FilterBarChip(
                    selected = f.person != PersonFilter.Any,
                    onClick = { personMenuOpen = true },
                    label = personLabel,
                    icon = Icons.Filled.Person,
                )
                DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any person") },
                        onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Any) },
                    )
                    DropdownMenuItem(
                        text = { Text("Unassigned") },
                        onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Unassigned) },
                    )
                    state.knownPersons.forEach { name ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            onClick = { personMenuOpen = false; onPersonFilter(PersonFilter.Specific(name)) },
                        )
                    }
                }
            }

        }

        // Secondary filters — triage + amount + source + analytics status.
        // Deliberately quieter than the FilterBarChips above (no fill, thinner
        // border when idle) so the two tiers read as a hierarchy.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "Inbox Zero": rows still needing review — uncategorized captures
            // and system-generated balance adjustments.
            SecondaryActionChip(
                selected = f.triageOnly,
                onClick = { onTriageOnly(!f.triageOnly) },
                label = "Inbox Zero",
                icon = Icons.Filled.Inbox,
            )

            // Amount threshold / range.
            val amountLabel = when {
                f.minAmount != null && f.maxAmount != null -> "${f.minAmount}–${f.maxAmount}"
                f.minAmount != null -> "≥ ${f.minAmount}"
                f.maxAmount != null -> "≤ ${f.maxAmount}"
                else -> "Amount"
            }
            SecondaryActionChip(
                selected = f.minAmount != null || f.maxAmount != null,
                onClick = { amountOpen = true },
                label = amountLabel,
            )

            // Capture source.
            Box {
                SecondaryActionChip(
                    selected = f.source != null,
                    onClick = { sourceMenuOpen = true },
                    label = f.source?.let(::sourceLabel) ?: "Source",
                )
                DropdownMenu(expanded = sourceMenuOpen, onDismissRequest = { sourceMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any source") },
                        onClick = { sourceMenuOpen = false; onSourceFilter(null) },
                    )
                    TransactionSource.entries.forEach { source ->
                        DropdownMenuItem(
                            text = { Text(sourceLabel(source)) },
                            onClick = { sourceMenuOpen = false; onSourceFilter(source) },
                        )
                    }
                }
            }

            // What it is: spend, refund, card payment, transfer...
            Box {
                SecondaryActionChip(
                    selected = f.kind != null,
                    onClick = { kindMenuOpen = true },
                    label = f.kind?.label ?: "Kind",
                )
                DropdownMenu(expanded = kindMenuOpen, onDismissRequest = { kindMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Any kind") },
                        onClick = { kindMenuOpen = false; onKindFilter(null) },
                    )
                    TransactionKind.entries.forEach { kind ->
                        DropdownMenuItem(
                            text = { Text(kind.label) },
                            onClick = { kindMenuOpen = false; onKindFilter(kind) },
                        )
                    }
                }
            }

            // Analytics status (the Phase 1 excludeFromStats flag).
            Box {
                SecondaryActionChip(
                    selected = f.analyticsStatus != null,
                    onClick = { analyticsMenuOpen = true },
                    label = f.analyticsStatus?.label ?: "Analytics",
                )
                DropdownMenu(expanded = analyticsMenuOpen, onDismissRequest = { analyticsMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("All rows") },
                        onClick = { analyticsMenuOpen = false; onAnalyticsStatus(null) },
                    )
                    AnalyticsStatusFilter.entries.forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.label) },
                            onClick = { analyticsMenuOpen = false; onAnalyticsStatus(status) },
                        )
                    }
                }
            }

            if (f.active) {
                // Plain text action, not a TextButton: Material's 48dp minimum
                // touch target would make this row taller than its chips.
                Text(
                    "Clear",
                    fontSize = 11.sp,
                    color = ObsidianTokens.textPrimary,
                    fontFamily = AppFonts.inter,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    if (customRangeOpen) {
        CustomDateRangeDialog(
            initial = f.dateRange as? DateRangeFilter.Custom,
            onDismiss = { customRangeOpen = false },
            onSelect = { start, end ->
                onDateRange(DateRangeFilter.Custom(start, end))
                customRangeOpen = false
            },
        )
    }

    if (amountOpen) {
        AmountRangeDialog(
            currentMin = f.minAmount,
            currentMax = f.maxAmount,
            onDismiss = { amountOpen = false },
            onApply = { min, max ->
                onAmountRange(min, max)
                amountOpen = false
            },
        )
    }
}

/** Short human label for a capture source in the filter UI. */
internal fun sourceLabel(source: TransactionSource): String = when (source) {
    TransactionSource.MANUAL -> "Manual"
    TransactionSource.SMS -> "SMS"
    TransactionSource.PUSH_NOTIFICATION -> "Notification"
    TransactionSource.BINANCE_SYNC -> "Binance sync"
    TransactionSource.PASTED -> "Pasted"
}

internal val chipDateFormat = java.time.format.DateTimeFormatter.ofPattern("d MMM")

/** Twin-calendar picker for an arbitrary inclusive Start→End window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomDateRangeDialog(
    initial: DateRangeFilter.Custom?,
    onDismiss: () -> Unit,
    onSelect: (java.time.LocalDate, java.time.LocalDate) -> Unit,
) {
    // The Material picker works in UTC day-millis, so conversions stay in UTC.
    fun java.time.LocalDate.toUtcMillis() =
        atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    fun Long.toUtcDate() =
        java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    val pickerState = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.start?.toUtcMillis(),
        initialSelectedEndDateMillis = initial?.end?.toUtcMillis(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = pickerState.selectedStartDateMillis != null &&
                    pickerState.selectedEndDateMillis != null,
                onClick = {
                    val start = pickerState.selectedStartDateMillis
                    val end = pickerState.selectedEndDateMillis
                    if (start != null && end != null) onSelect(start.toUtcDate(), end.toUtcDate())
                },
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            showModeToggle = false,
        )
    }
}

/** Min/max amount bounds; either side may be left blank for an open threshold. */
@Composable
internal fun AmountRangeDialog(
    currentMin: java.math.BigDecimal?,
    currentMax: java.math.BigDecimal?,
    onDismiss: () -> Unit,
    onApply: (java.math.BigDecimal?, java.math.BigDecimal?) -> Unit,
) {
    var minText by remember { mutableStateOf(currentMin?.toPlainString().orEmpty()) }
    var maxText by remember { mutableStateOf(currentMax?.toPlainString().orEmpty()) }
    val min = minText.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val max = maxText.trim().replace(",", "").takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
    val minValid = minText.isBlank() || min != null
    val maxValid = maxText.isBlank() || max != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter by amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Leave a field blank for an open bound (e.g. only “Min” = everything above it).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = it },
                        label = { Text("Min") },
                        singleLine = true,
                        isError = !minValid,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = it },
                        label = { Text("Max") },
                        singleLine = true,
                        isError = !maxValid,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = minValid && maxValid,
                onClick = { onApply(min, max) },
            ) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = { onApply(null, null) }) { Text("Clear") }
        },
    )
}

/** Sign-based tone of a row's amount, deciding which color the figure renders in. */
internal enum class AmountTone { NEGATIVE, POSITIVE, NEUTRAL }

/**
 * Amounts store magnitude and [TransactionDirection] carries the sign, so a
 * DEBIT reads as negative money and a CREDIT as positive; a zero amount is
 * neutral either way and keeps the default text color.
 */
internal fun amountTone(amount: java.math.BigDecimal, direction: TransactionDirection): AmountTone = when {
    amount.signum() == 0 -> AmountTone.NEUTRAL
    direction == TransactionDirection.CREDIT -> AmountTone.POSITIVE
    else -> AmountTone.NEGATIVE
}

/** Soft copper for money going out, desaturated mint for money coming in. */
internal val amountNegativeColor = Color(0xFFE07A5F)
internal val amountPositiveColor = Color(0xFF4ECDC4)

/** Shared treatment for the card's secondary meta lines and chip labels. */
private val metaTextColor = Color.Gray

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransactionCard(
    tx: Transaction,
    accounts: List<Account>,
    knownPersons: List<String>,
    categories: List<Category>,
    bankName: String?,
    /** Brand accent from the active account filter, or null for the default hairline. */
    accountFilterTint: Color? = null,
    /** True while the screen is in multi-select bulk-edit mode. */
    isInBulkMode: Boolean = false,
    /** True when this row is checked while [isInBulkMode]. */
    isSelected: Boolean = false,
    onAssignAccount: (Long?) -> Unit,
    onAssignPerson: (String?) -> Unit,
    onOpenCategoryPicker: () -> Unit,
    onRenameSender: (String) -> Unit,
    onResetSender: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCardClick: () -> Unit,
    /** Long-press enters bulk mode, selecting this row as the first pick. */
    onLongPress: () -> Unit = {},
    /** Tap while already in bulk mode toggles this row's selection instead of opening detail. */
    onToggleSelected: () -> Unit = {},
) {
    val isCredit = tx.direction == TransactionDirection.CREDIT
    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }
    var newPersonOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val accountName = accounts.firstOrNull { it.id == tx.accountId }?.name

    // Audit-friendly presentation: rows excluded from analytics stay in the
    // feed (never filtered here) but read as muted — dimmed card plus a
    // slashed-eye marker beside the amount.
    val cardAlpha = if (tx.excludeFromStats) 0.55f else 1f
    // Edge glow (spec 5.3): the row's brand origin, or a neutral slate for
    // system/manual accounts with none registered.
    val originTone = GlowTones.forAccountOrigin(accountName, bankName, tx.sender) ?: GlowTone.SYSTEM_SLATE
    val avatarName = tx.merchant ?: accountName ?: bankName ?: tx.sender ?: tx.source.name
    val shape = RoundedCornerShape(ObsidianTokens.rowRadius)
    Box(
        Modifier
            .fillMaxWidth()
            // Filter/expansion-driven size changes glide instead of snapping.
            .animateContentSize()
            .alpha(cardAlpha)
            .clip(shape)
            .background(ObsidianTokens.surfaceGlass, shape)
            .border(
                1.dp,
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    accountFilterTint != null -> accountFilterTint.copy(alpha = 0.35f)
                    else -> ObsidianTokens.hairline
                },
                shape,
            )
            .rowEdgeGlow(originTone)
            .combinedClickable(
                onClick = { if (isInBulkMode) onToggleSelected() else onCardClick() },
                onLongClick = onLongPress,
            )
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isInBulkMode) {
                    Icon(
                        if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                        contentDescription = if (isSelected) "Selected" else "Not selected",
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else ObsidianTokens.textMeta,
                        modifier = Modifier.size(34.dp).padding(4.dp),
                    )
                } else {
                    RowAvatar(name = avatarName, tone = originTone)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    // Primary line: merchant if we have one, else the friendly bank name.
                    // Excluded rows also get a strikethrough here, on top of the
                    // existing card-wide dimming and trailing icon (additive, not
                    // a replacement for either).
                    Text(
                        tx.merchant ?: bankName ?: tx.sender ?: tx.source.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (tx.excludeFromStats) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        color = ObsidianTokens.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Bank + account line, tappable to rename the bank/sender.
                    val bankLine = buildString {
                        append(bankName ?: tx.sender ?: tx.source.name)
                        tx.accountNumberSuffix?.takeIf { it.isNotBlank() }?.let {
                            append(" · ••")
                            append(it)
                        }
                        // Say what it is whenever it isn't a plain spend/receive,
                        // so a card payment or refund reads as one at a glance.
                        if (tx.kind != TransactionKind.defaultFor(tx.direction)) {
                            append(" · ")
                            append(tx.kind.label)
                        }
                    }
                    Text(
                        bankLine,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = metaTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = tx.sender != null) { renameOpen = true },
                    )
                }
                Spacer(
                    Modifier
                        .weight(1f)
                        .widthIn(min = 12.dp)
                        .padding(horizontal = 6.dp)
                        .dottedLeader(),
                )
                AmountText(
                    display = (if (isCredit) "+" else "-") + tx.amount.asMoney(tx.currencyCode),
                    // A transfer, card payment or correction is neither money
                    // spent nor received: shown neutral, not red/green.
                    color = if (LedgerMath.effect(tx) == LedgerMath.Effect.NONE && !tx.excludeFromStats) {
                        ObsidianTokens.textPrimary
                    } else {
                        when (amountTone(tx.amount, tx.direction)) {
                            AmountTone.NEGATIVE -> amountNegativeColor
                            AmountTone.POSITIVE -> amountPositiveColor
                            AmountTone.NEUTRAL -> ObsidianTokens.textPrimary
                        }
                    },
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        // Tabular figures so decimal points align down the list.
                        fontFeatureSettings = "tnum",
                    ),
                )
                if (tx.excludeFromStats) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Excluded from analytics",
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp)
                            .alpha(0.7f),
                        tint = ObsidianTokens.textMeta,
                    )
                }
            }

            tx.rawMessage?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = metaTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                listOfNotNull(tx.timestampMillis.asDateTime(), tx.matchedRuleName).joinToString(" · "),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = metaTextColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )

            // One compact meta row: account · person · category chips scroll
            // horizontally so long names never blow up the card, with edit &
            // delete pinned on the right.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        MetaChip(
                            text = accountName ?: "Account",
                            active = tx.accountId != null,
                            icon = Icons.Filled.AccountBalanceWallet,
                            onClick = { accountMenuOpen = true },
                        )
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Unassigned") }, onClick = { accountMenuOpen = false; onAssignAccount(null) })
                            accounts.forEach { account ->
                                DropdownMenuItem(text = { Text(account.name) }, onClick = { accountMenuOpen = false; onAssignAccount(account.id) })
                            }
                        }
                    }
                    Box {
                        MetaChip(
                            text = tx.person ?: "Person",
                            active = tx.person != null,
                            icon = Icons.Filled.Person,
                            onClick = { personMenuOpen = true },
                        )
                        DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                            DropdownMenuItem(text = { Text("Nobody") }, onClick = { personMenuOpen = false; onAssignPerson(null) })
                            knownPersons.forEach { person ->
                                DropdownMenuItem(text = { Text(person) }, onClick = { personMenuOpen = false; onAssignPerson(person) })
                            }
                            DropdownMenuItem(text = { Text("New person…") }, onClick = { personMenuOpen = false; newPersonOpen = true })
                        }
                    }
                    val assignedCategory = remember(tx.categoryId, categories) {
                        categories.firstOrNull { it.id == tx.categoryId }
                    }
                    MetaChip(
                        text = assignedCategory?.name ?: "Category",
                        active = assignedCategory != null,
                        emoji = assignedCategory?.icon ?: "🏷️",
                        onClick = onOpenCategoryPicker,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit transaction",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete transaction",
                        modifier = Modifier.size(17.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (newPersonOpen) {
        NewPersonDialog(
            onDismiss = { newPersonOpen = false },
            onSave = { person ->
                onAssignPerson(person)
                newPersonOpen = false
            },
        )
    }

    if (renameOpen && tx.sender != null) {
        RenameSenderDialog(
            sender = tx.sender,
            current = bankName ?: tx.sender,
            onDismiss = { renameOpen = false },
            onSave = { label ->
                onRenameSender(label)
                renameOpen = false
            },
            onReset = {
                onResetSender()
                renameOpen = false
            },
        )
    }
}

/**
 * Dense tappable chip for the per-transaction account/person/category selectors.
 * Smaller than a Material AssistChip (labelSmall text, tighter padding) and tinted when
 * a value is set, so a card's meta row stays compact instead of looking cluttered.
 * Long values ellipsize within a capped width rather than widening the card.
 */
@Composable
private fun MetaChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
) {
    // Glass pill (spec 5.3): idle White@4%/hairline border/meta label; active
    // White@12%/White@28% border/full-bright label.
    val shape = RoundedCornerShape(ObsidianTokens.pillRadius)
    val background = if (active) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f)
    val border = if (active) Color.White.copy(alpha = 0.28f) else ObsidianTokens.hairline
    // Assigned values stay full-bright so set/unset chips remain distinguishable.
    val labelColor = if (active) ObsidianTokens.textPrimary else metaTextColor
    Row(
        modifier = Modifier
            .clip(shape)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .widthIn(max = 150.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = labelColor)
            emoji != null -> Text(emoji, style = MaterialTheme.typography.labelSmall)
        }
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewPersonDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assign person") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }) { Text("Assign") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Add or edit a transaction. With [existing] null it hand-enters one the capture
 * pipeline missed; with [existing] set it edits that row's fields in place. Either
 * way the result behaves identically to a captured row in balances and filters.
 */
@Composable
private fun TransactionEditorDialog(
    existing: Transaction?,
    accounts: List<Account>,
    knownPersons: List<String>,
    categories: List<Category>,
    defaultCurrency: String,
    aiEnabled: Boolean = false,
    onAiParse: (suspend (String) -> AiEditorFill?)? = null,
    onCreateCategory: suspend (
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: java.math.BigDecimal?,
        parentCategoryId: Long?,
    ) -> Category,
    onDismiss: () -> Unit,
    onSave: (
        amount: java.math.BigDecimal,
        currency: String,
        direction: TransactionDirection,
        merchant: String?,
        accountId: Long?,
        person: String?,
        categoryId: Long?,
        timestampMillis: Long,
        excludeFromStats: Boolean,
        kind: TransactionKind,
    ) -> Unit,
    /** Add mode only: saves a transfer/card payment as two linked legs. */
    onSaveTransfer: ((TransferLegs.Draft) -> Unit)? = null,
) {
    val zone = java.time.ZoneId.systemDefault()
    val isEdit = existing != null
    var amount by remember { mutableStateOf(existing?.amount?.stripTrailingZeros()?.toPlainString() ?: "") }
    var currency by remember { mutableStateOf(existing?.currencyCode ?: defaultCurrency) }
    var kind by remember { mutableStateOf(existing?.kind ?: TransactionKind.SPEND) }
    // Only consulted for a kind that goes either way (an edited transfer leg
    // or correction keeps the side it was on); everything else follows the kind.
    val direction = kind.fixedDirection ?: existing?.direction ?: TransactionDirection.DEBIT
    // Adding a transfer/card payment means two accounts and two legs.
    val transferMode = !isEdit && onSaveTransfer != null && kind.isInternalMove
    var fromAccountId by remember { mutableStateOf<Long?>(null) }
    var toAccountId by remember { mutableStateOf<Long?>(null) }
    var toAmount by remember { mutableStateOf("") }
    var fromMenuOpen by remember { mutableStateOf(false) }
    var toMenuOpen by remember { mutableStateOf(false) }
    var merchant by remember { mutableStateOf(existing?.merchant.orEmpty()) }
    var accountId by remember { mutableStateOf(existing?.accountId) }
    var person by remember { mutableStateOf(existing?.person) }
    // null = follow the merchant-derived suggestion; a value = explicit choice.
    // Editing starts from the row's saved category so it never silently changes.
    var categoryIdOverride by remember { mutableStateOf(existing?.categoryId) }
    var creatingCategory by remember { mutableStateOf(false) }
    val categoryScope = rememberCoroutineScope()
    var excludeFromStats by remember { mutableStateOf(existing?.excludeFromStats ?: false) }
    var dateIso by remember {
        mutableStateOf(
            java.time.Instant.ofEpochMilli(existing?.timestampMillis ?: System.currentTimeMillis())
                .atZone(zone).toLocalDate().toString()
        )
    }

    var accountMenuOpen by remember { mutableStateOf(false) }
    var personMenuOpen by remember { mutableStateOf(false) }

    val amountValue = amount.replace(",", "").toBigDecimalOrNull()
    val date = runCatching { java.time.LocalDate.parse(dateIso.trim()) }.getOrNull()
    val fromAccount = accounts.firstOrNull { it.id == fromAccountId }
    val toAccount = accounts.firstOrNull { it.id == toAccountId }
    // A second amount only when the money changes currency on the way.
    val crossCurrency = transferMode && fromAccount != null && toAccount != null &&
        !fromAccount.currencyCode.equals(toAccount.currencyCode, ignoreCase = true)
    val toAmountValue = toAmount.replace(",", "").toBigDecimalOrNull()
    fun endpoint(account: Account?) = account?.let {
        TransferLegs.Endpoint(it.id, it.name, it.type, it.currencyCode)
    }
    val transferDraft = if (transferMode && amountValue != null && date != null) {
        TransferLegs.Draft(
            from = endpoint(fromAccount),
            to = endpoint(toAccount),
            amount = amountValue,
            toAmount = if (crossCurrency) toAmountValue else null,
            timestampMillis = 0,
            note = merchant,
            person = person,
        )
    } else {
        null
    }
    val transferError = transferDraft?.let(TransferLegs::validate)
        ?: if (crossCurrency && toAmountValue == null) "Enter the amount received" else null
    val valid = amountValue != null && amountValue.signum() > 0 &&
        currency.trim().length in CURRENCY_CODE_MIN_LENGTH..CURRENCY_CODE_MAX_LENGTH && date != null &&
        (!transferMode || (transferDraft != null && transferError == null))
    val accountName = accounts.firstOrNull { it.id == accountId }?.name
    // Same merchant-keyword guess the capture pipeline uses, bridged onto its
    // same-named dynamic category (see CategoryMigrationMapping) so "Auto"
    // previews a real, selectable category rather than a bare enum label.
    val suggestedCategory = remember(merchant, categories) {
        val enumGuess = MerchantCategorizer.categorize(merchant.takeIf { it.isNotBlank() })
        val name = CategoryMigrationMapping.ENUM_TO_CATEGORY_NAME[enumGuess]
        categories.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
    val effectiveCategoryId = categoryIdOverride ?: suggestedCategory?.id

    AlertDialog(
        onDismissRequest = onDismiss,
        // Glass overlay: translucent container + hairline border, with a real
        // backdrop blur on API 31+ (falls back to a more opaque surface below).
        modifier = Modifier.glassSurface(AlertDialogDefaults.shape),
        containerColor = Color.Transparent,
        title = { Text(if (isEdit) "Edit transaction" else "Add transaction", style = MaterialTheme.typography.titleLarge) },
        text = {
            GlassDialogBackdrop()
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Manual "Parse with AI" (add mode only): paste a bank message
                // the built-in rules missed and let the local (loopback-only) AI
                // fill amount/currency/merchant/direction. Only shown when the
                // feature is configured; failures leave the form untouched.
                if (!isEdit && aiEnabled && onAiParse != null) {
                    val aiParse = onAiParse
                    val aiScope = rememberCoroutineScope()
                    var rawMessage by remember { mutableStateOf("") }
                    var parsing by remember { mutableStateOf(false) }
                    var parseError by remember { mutableStateOf<String?>(null) }
                    OutlinedTextField(
                        value = rawMessage,
                        onValueChange = { rawMessage = it },
                        label = { MetaLabel("Paste bank message") },
                        minLines = 2,
                        colors = glassFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            enabled = rawMessage.isNotBlank() && !parsing,
                            onClick = {
                                parsing = true
                                parseError = null
                                aiScope.launch {
                                    val fill = aiParse(rawMessage)
                                    if (fill != null) {
                                        amount = fill.amount.stripTrailingZeros().toPlainString()
                                        currency = fill.currency
                                        merchant = fill.merchant.orEmpty()
                                        kind = TransactionKind.defaultFor(fill.direction)
                                    } else {
                                        parseError = "Couldn't read a transaction. Check the AI server in Settings."
                                    }
                                    parsing = false
                                }
                            },
                        ) { Text(if (parsing) "Parsing…" else "Parse with AI") }
                        if (parsing) {
                            AiParsingIndicator(modifier = Modifier.size(18.dp))
                        }
                    }
                    parseError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    HorizontalDivider(color = ObsidianTokens.hairlineDialog)
                }
                // What it is: decides the sign in balances AND how it counts in
                // Spent/Received (a refund lowers spending, a transfer or card
                // payment counts in neither). See LedgerMath.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    editorKindsFor(existing?.kind).forEach { entry ->
                        ObsidianChip(
                            selected = kind == entry,
                            onClick = { kind = entry },
                            label = { Text(entry.label) },
                        )
                    }
                }
                Text(
                    kindHint(kind, transferMode),
                    style = MaterialTheme.typography.bodySmall,
                    color = ObsidianTokens.textMeta,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { MetaLabel("Amount") },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = AppFonts.plexMono,
                            fontSize = ObsidianTokens.amountFontSize,
                        ),
                        singleLine = true,
                        isError = amount.isNotBlank() && amountValue == null,
                        colors = glassFieldColors(),
                        modifier = Modifier.weight(0.6f),
                    )
                    OutlinedTextField(
                        value = currency,
                        onValueChange = { currency = it.uppercase().take(CURRENCY_CODE_MAX_LENGTH) },
                        label = { MetaLabel("Currency") },
                        singleLine = true,
                        colors = glassFieldColors(),
                        modifier = Modifier.weight(0.4f),
                    )
                }
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { MetaLabel("Merchant / description (optional)") },
                    singleLine = true,
                    colors = glassFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dateIso,
                    onValueChange = { dateIso = it },
                    label = { MetaLabel("Date (YYYY-MM-DD)") },
                    singleLine = true,
                    isError = date == null,
                    colors = glassFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )

                if (transferMode) {
                    TransferAccountPicker(
                        label = "From",
                        accounts = accounts,
                        selectedId = fromAccountId,
                        open = fromMenuOpen,
                        onOpenChange = { fromMenuOpen = it },
                        onSelect = { picked ->
                            fromAccountId = picked?.id
                            picked?.let { currency = it.currencyCode }
                        },
                    )
                    TransferAccountPicker(
                        label = "To",
                        accounts = accounts,
                        selectedId = toAccountId,
                        open = toMenuOpen,
                        onOpenChange = { toMenuOpen = it },
                        onSelect = { picked ->
                            toAccountId = picked?.id
                            // Paying onto a card reads as a card payment.
                            if (picked?.type == com.xemmy.mmbank.domain.model.AccountType.CREDIT_CARD) {
                                kind = TransactionKind.CARD_PAYMENT
                            }
                            if (fromAccountId == null) picked?.let { currency = it.currencyCode }
                        },
                    )
                    if (crossCurrency) {
                        OutlinedTextField(
                            value = toAmount,
                            onValueChange = { toAmount = it },
                            label = { MetaLabel("Amount received (${toAccount?.currencyCode})") },
                            singleLine = true,
                            isError = toAmount.isNotBlank() && toAmountValue == null,
                            colors = glassFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    transferError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // Account picker — same target the capture pipeline assigns to.
                    // A glowing brand dot previews the row's edge glow before saving.
                    Box {
                        val accountTone = GlowTones.forAccountOrigin(
                            accountName,
                            accounts.firstOrNull { it.id == accountId }?.bankName,
                        ) ?: GlowTone.SYSTEM_SLATE
                        OutlinedButton(onClick = { accountMenuOpen = true }) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(GlowPalette.color(accountTone)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Account: " + (accountName ?: "Unassigned"))
                        }
                        DropdownMenu(expanded = accountMenuOpen, onDismissRequest = { accountMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Unassigned") },
                                onClick = { accountMenuOpen = false; accountId = null },
                            )
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = { accountMenuOpen = false; accountId = account.id },
                                )
                            }
                        }
                    }
                }

                Box {
                    OutlinedButton(onClick = { personMenuOpen = true }) {
                        Text("Person: " + (person ?: "Nobody"))
                    }
                    DropdownMenu(expanded = personMenuOpen, onDismissRequest = { personMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Nobody") },
                            onClick = { personMenuOpen = false; person = null },
                        )
                        knownPersons.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { personMenuOpen = false; person = name },
                            )
                        }
                    }
                }

                // Category chip row — defaults to the merchant-derived guess
                // ("Auto"), overridable, plus a trailing "+ New" chip opening
                // the shared create form. Mirrors the auto-categorization done
                // on capture. Active chip reads teal (spec 5.3).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MetaLabel("Category")
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ObsidianChip(
                            selected = categoryIdOverride == null,
                            onClick = { categoryIdOverride = null },
                            label = {
                                Text(
                                    suggestedCategory?.let { "Auto · ${it.icon} ${it.name}" } ?: "Auto · Uncategorized",
                                )
                            },
                        )
                        categories.forEach { category ->
                            ObsidianChip(
                                selected = categoryIdOverride == category.id,
                                onClick = { categoryIdOverride = category.id },
                                label = { Text("${category.icon} ${category.name}") },
                            )
                        }
                        ObsidianChip(
                            selected = false,
                            onClick = { creatingCategory = true },
                            label = { Text("+ New") },
                        )
                    }
                }

                androidx.compose.material3.HorizontalDivider(color = ObsidianTokens.hairline)

                // Analytics purity control: internal transfers, cash refunds and
                // the like stay in the ledger but shouldn't skew the stats.
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Exclude from Analytics", style = MaterialTheme.typography.bodyLarge)
                        MetaLabel("Kept in ledger, hidden from stats & charts", maxLines = 2)
                    }
                    Switch(
                        checked = excludeFromStats,
                        onCheckedChange = { excludeFromStats = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = ObsidianTokens.credit),
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                enabled = valid,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = ObsidianTokens.textPrimary,
                    contentColor = ObsidianTokens.canvas,
                ),
                onClick = {
                    // Button is only enabled when both parse, so this is safe.
                    val parsedDate = date
                    val value = amountValue
                    if (parsedDate != null && value != null) {
                        // Keep the original time-of-day when editing (so unchanged
                        // dates keep their exact timestamp and intra-day order);
                        // use the current time for a fresh manual entry.
                        val timeOfDay = existing
                            ?.let { java.time.Instant.ofEpochMilli(it.timestampMillis).atZone(zone).toLocalTime() }
                            ?: java.time.LocalTime.now()
                        val timestamp = parsedDate.atTime(timeOfDay).atZone(zone).toInstant().toEpochMilli()
                        if (transferMode && transferDraft != null && onSaveTransfer != null) {
                            onSaveTransfer(transferDraft.copy(timestampMillis = timestamp))
                        } else {
                            onSave(
                                value,
                                currency.trim().uppercase(),
                                direction,
                                merchant.trim().takeIf { it.isNotBlank() },
                                accountId,
                                person,
                                effectiveCategoryId,
                                timestamp,
                                excludeFromStats,
                                kind,
                            )
                        }
                    }
                },
            ) { Text(if (isEdit) "Save changes" else if (transferMode) "Add ${kind.label.lowercase()}" else "Add transaction") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (creatingCategory) {
        CategoryEditorDialog(
            initial = null,
            currency = currency.trim().ifBlank { defaultCurrency },
            allCategories = categories,
            onDismiss = { creatingCategory = false },
            onSave = { name, icon, colorHex, cap, parentId ->
                categoryScope.launch {
                    val created = onCreateCategory(name, colorHex, icon, cap, parentId)
                    categoryIdOverride = created.id
                    creatingCategory = false
                }
            },
        )
    }
}

/** The kinds offered when adding/editing by hand, plus [current] when it's one of the rarer ones. */
internal fun editorKindsFor(current: TransactionKind?): List<TransactionKind> {
    val base = listOf(
        TransactionKind.SPEND,
        TransactionKind.INCOME,
        TransactionKind.REFUND,
        TransactionKind.TRANSFER,
        TransactionKind.CARD_PAYMENT,
        TransactionKind.ATM,
    )
    return if (current == null || current in base) base else base + current
}

/** One line under the kind chips saying how the choice counts. */
internal fun kindHint(kind: TransactionKind, transferMode: Boolean): String = when (kind) {
    TransactionKind.SPEND -> "Counts as spending"
    TransactionKind.ATM -> "Cash taken out — counts as spending"
    TransactionKind.INCOME -> "Counts as money received"
    TransactionKind.REFUND -> "Money back — lowers spending, not income"
    TransactionKind.TRANSFER ->
        if (transferMode) "Between your accounts — saved as two linked sides, not spending"
        else "Between your accounts — not spending or income"
    TransactionKind.CARD_PAYMENT ->
        if (transferMode) "Paying a card off — saved as two linked sides; purchases already counted"
        else "Paying a card off — purchases on the card already counted"
    TransactionKind.CASHBACK -> "Rewards — shown on Cashback, not in totals"
    TransactionKind.ADJUSTMENT -> "Balance correction — not in totals"
}

@Composable
private fun TransferAccountPicker(
    label: String,
    accounts: List<Account>,
    selectedId: Long?,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSelect: (Account?) -> Unit,
) {
    val selected = accounts.firstOrNull { it.id == selectedId }
    Box {
        OutlinedButton(onClick = { onOpenChange(true) }, modifier = Modifier.fillMaxWidth()) {
            Text("$label: " + (selected?.let { "${it.name} · ${it.currencyCode}" } ?: "Outside / not tracked"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            DropdownMenuItem(
                text = { Text("Outside / not tracked") },
                onClick = { onOpenChange(false); onSelect(null) },
            )
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Text("${account.name} · ${account.currencyCode}") },
                    onClick = { onOpenChange(false); onSelect(account) },
                )
            }
        }
    }
}

@Composable
private fun RenameSenderDialog(
    sender: String,
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
) {
    var label by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename bank") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Show a friendly name for “$sender” everywhere it appears.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Display name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onSave(label.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset to default") } },
    )
}

/**
 * Delete confirmation that doubles as "this message was wrong": besides a
 * plain delete it can also mute future look-alikes ([onDeleteIgnoreSimilar])
 * or any message containing a phrase ([onDeleteIgnoreKeyword]). The ignore
 * options only show for captured messages, i.e. when [rawMessage] is present;
 * pass null for [onDeleteIgnoreKeyword] (bulk) to hide the keyword option.
 */
@Composable
internal fun DeleteWithIgnoreDialog(
    title: String,
    rawMessage: String?,
    merchant: String?,
    onDelete: () -> Unit,
    onDeleteIgnoreSimilar: () -> Unit,
    onDeleteIgnoreKeyword: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var keywordMode by remember { mutableStateOf(false) }
    var keyword by remember(rawMessage, merchant) {
        mutableStateOf(IgnoreKeywordSuggester.suggest(rawMessage, merchant))
    }
    val canIgnore = !rawMessage.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This can't be undone.")
                if (canIgnore) {
                    Text(
                        "Captured wrongly? You can also stop similar messages from being captured again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (keywordMode && onDeleteIgnoreKeyword != null) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("Ignore messages containing") },
                        supportingText = { Text("Must appear in this message (3+ characters)") },
                        singleLine = true,
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (canIgnore) {
                    if (keywordMode && onDeleteIgnoreKeyword != null) {
                        TextButton(
                            enabled = IgnoreKeywordSuggester.isValid(keyword, rawMessage),
                            onClick = { onDeleteIgnoreKeyword(keyword.trim()) },
                        ) { Text("Delete & ignore \"${keyword.trim()}\"") }
                    } else {
                        TextButton(onClick = onDeleteIgnoreSimilar) { Text("Delete & ignore similar") }
                        if (onDeleteIgnoreKeyword != null) {
                            TextButton(onClick = { keywordMode = true }) { Text("Delete & ignore containing…") }
                        }
                    }
                }
                TextButton(onClick = onDelete) { Text("Delete only") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
