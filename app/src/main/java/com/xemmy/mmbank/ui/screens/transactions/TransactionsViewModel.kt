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
import com.xemmy.mmbank.ui.util.rememberCurrentDate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.xemmy.mmbank.domain.people.PersonLists
import com.xemmy.mmbank.domain.ledger.LedgerMath
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.filter.AccountFilter
import com.xemmy.mmbank.domain.filter.ActivityDrillDown
import com.xemmy.mmbank.domain.filter.ActivityFilters
import com.xemmy.mmbank.domain.filter.AnalyticsStatusFilter
import com.xemmy.mmbank.domain.filter.DateRangeFilter
import com.xemmy.mmbank.domain.filter.DateWindow
import com.xemmy.mmbank.domain.filter.PersonFilter
import com.xemmy.mmbank.domain.filter.TransactionFilterEngine
import com.xemmy.mmbank.domain.intelligence.SpendAnomalyDetector
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.BankNames
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.CategoryMigrationMapping
import com.xemmy.mmbank.domain.model.ExchangeRate
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
import com.xemmy.mmbank.domain.paylater.PayLaterAttribution
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
import kotlinx.coroutines.flow.flowOn
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
    /** People offered when *assigning* a person: [knownPersons] minus those marked inactive (see [PersonLists]). */
    val assignablePersons: List<String> = emptyList(),
    /** True when the filter (not an empty ledger) hid everything. */
    val hasAnyTransactions: Boolean = false,
    /** Currency a new manual entry defaults to. */
    val defaultCurrency: String = "USD",
    /** True once a long-press has entered multi-select bulk-edit mode. */
    val isInBulkMode: Boolean = false,
    /** Ids currently checked while in bulk mode. */
    val selectedTransactionIds: Set<Long> = emptySet(),
    /** Single-row (Premium) vs Classic wrapped-chip filter bar; see Settings > Design. */
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
    /**
     * Net spend under the same filters over the equal-length window just
     * before this one; null when the date filter is unbounded ("All").
     */
    val previousSpent: Double? = null,
    /** Length of that window in days. */
    val comparisonDays: Int? = null,
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
    /** The same filters applied to the window just before; null when unbounded. */
    previousTransactions: List<Transaction>? = null,
    comparisonDays: Int? = null,
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
        previousSpent = previousTransactions?.let { LedgerMath.netSpend(it, value = ::inBase) },
        comparisonDays = comparisonDays,
    )
}

/**
 * The window of equal length just before [window] -- what the hero card
 * compares the filtered feed against.
 */
internal fun priorWindow(window: DateWindow): DateWindow {
    val days = java.time.temporal.ChronoUnit.DAYS.between(window.start, window.end) + 1
    return DateWindow(window.start.minusDays(days), window.start.minusDays(1))
}

class TransactionsViewModel(private val container: AppContainer) : ViewModel() {

    private val filters = MutableStateFlow(ActivityFilters())
    private val bulkMode = MutableStateFlow(BulkModeState())

    val uiState: StateFlow<TransactionsUiState> = combine(
        container.transactionRepository.observeTransactions(),
        container.accountRepository.observeActive(),
        container.senderLabelRepository.observeLabels(),
        filters,
        // Categories and label links ride together so this stays within combine's five typed slots.
        combine(container.categoryRepository.observeAll(), container.labelRepository.observeLinks()) { c, l -> c to l },
    ) { transactions, accounts, senderLabels, f, categoriesAndLinks ->
        val (categories, labelLinks) = categoriesAndLinks
        val labelsByTx = com.xemmy.mmbank.domain.label.LabelRules.byTransaction(labelLinks)
        // All predicate logic lives in the pure-Kotlin engine so it's JVM-testable.
        val visible = TransactionFilterEngine.filter(
            transactions = transactions,
            filters = f,
            accounts = accounts,
            bankNameOf = { sender -> bankNameFor(sender, senderLabels) },
            labelsOf = { id -> labelsByTx[id].orEmpty() },
        )
        // The menu is fed ONLY by the saved list (Settings > Preferences >
        // People) so removing a person there really removes it here. Names
        // dedupe case-insensitively to avoid "Ahmed" vs "ahmed" doubles.
        val persons = PersonLists.filterable(container.preferences.persons, container.preferences.defaultPerson)
        // Assignment menus leave out people marked inactive in Settings; the filter above keeps everyone.
        val assignable = PersonLists.assignable(
            container.preferences.persons,
            container.preferences.inactivePersons,
            container.preferences.defaultPerson,
        )
        TransactionsUiState(
            transactions = visible,
            accounts = accounts,
            senderLabels = senderLabels,
            filters = f,
            knownPersons = persons,
            assignablePersons = assignable,
            hasAnyTransactions = transactions.isNotEmpty(),
            defaultCurrency = container.preferences.defaultCurrency,
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
        container.transactionRepository.observeTransactions(),
        container.labelRepository.observeLinks(),
    ) { state, rates, all, labelLinks ->
        // A bounded date filter gets a "vs the days before" comparison: the
        // same filters, shifted back one window.
        val window = TransactionFilterEngine.resolveDateWindow(
            state.filters.dateRange,
            TransactionFilterEngine.activeAccount(state.filters.account, state.accounts),
            java.time.LocalDate.now(),
        )
        val previous = window?.let(::priorWindow)
        val labelsByTx = com.xemmy.mmbank.domain.label.LabelRules.byTransaction(labelLinks)
        val previousRows = previous?.let { p ->
            TransactionFilterEngine.filter(
                transactions = all,
                filters = state.filters.copy(dateRange = DateRangeFilter.Custom(p.start, p.end)),
                accounts = state.accounts,
                bankNameOf = { sender -> bankNameFor(sender, state.senderLabels) },
                // The same label filter as the feed: without it every earlier row failed the
                // label test and the "vs the days before" comparison vanished.
                labelsOf = { id -> labelsByTx[id].orEmpty() },
            )
        }
        computeSpendSummary(
            transactions = state.transactions,
            rates = rates,
            baseCurrency = container.preferences.defaultCurrency,
            monthlyBudget = container.preferences.monthlySpendBudget,
            previousTransactions = previousRows,
            comparisonDays = window?.let { (java.time.temporal.ChronoUnit.DAYS.between(it.start, it.end) + 1).toInt() },
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .recoverAndRetry("TransactionsViewModel.spendSummary", container) {
        SpendSummaryState()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpendSummaryState())

    val hideBalances: StateFlow<Boolean> = container.hideBalances

    /** Per-day totals of exactly the rows the filtered feed shows, in the base currency (Activity's day headers). */
    val dayTotals: StateFlow<Map<java.time.LocalDate, com.xemmy.mmbank.domain.report.DayTotal>> = combine(
        uiState,
        container.ratesRepository.observeRates(),
    ) { state, rates ->
        val base = container.preferences.defaultCurrency
        val ratesFromBase = rates
            .filter { it.baseCode.equals(base, ignoreCase = true) }
            .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
        com.xemmy.mmbank.domain.report.DayTotals.byDay(state.transactions, java.time.ZoneId.systemDefault()) { tx ->
            CurrencyMath.toBase(tx.amount.toDouble(), tx.currencyCode, base, ratesFromBase)
        }
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .recoverAndRetry("TransactionsViewModel.dayTotals", container) { emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** How many real transactions still need a dynamic category -- drives the Uncategorized queue entry point. */
    val uncategorizedCount: StateFlow<Int> = container.transactionRepository.observeUncategorized()
        .map { it.size }
        .recoverAndRetry("TransactionsViewModel.uncategorizedCount", container) { 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Every label, and which transactions carry them (the editor's chips and the Activity label filter). */
    val labels: StateFlow<List<com.xemmy.mmbank.domain.model.Label>> = container.labelRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val labelLinks: StateFlow<List<com.xemmy.mmbank.domain.label.LabelLink>> = container.labelRepository.observeLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _exporting = MutableStateFlow(false)
    private val _exportStatus = MutableStateFlow<String?>(null)

    /** True while an export is being built and written; the menu entries ignore taps meanwhile. */
    val exporting: StateFlow<Boolean> = _exporting

    /** One-shot result line ("Exported 42 transactions") for the screen to show, then [clearExportStatus]. */
    val exportStatus: StateFlow<String?> = _exportStatus

    fun clearExportStatus() {
        _exportStatus.value = null
    }

    /** The format last chosen in the export dialog. */
    val lastExportFormat: com.xemmy.mmbank.domain.export.ExportFormat
        get() = com.xemmy.mmbank.domain.export.ExportFormat.fromStored(container.preferences.activityExportFormat)

    /** Suggested file name for the save dialog. */
    fun exportFileName(format: com.xemmy.mmbank.domain.export.ExportFormat): String =
        format.fileName(
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")),
        )

    /**
     * Writes what Activity shows (every active filter applied, same order) -- or, with
     * [selectedOnly], just the rows ticked in bulk mode -- in [format]. [writeText] runs on IO and
     * streams the text to the file the user picked.
     */
    fun exportTransactions(
        format: com.xemmy.mmbank.domain.export.ExportFormat,
        selectedOnly: Boolean,
        writeText: suspend (String) -> Unit,
    ) {
        if (_exporting.value) return
        val state = uiState.value
        val source = if (selectedOnly) {
            state.transactions.filter { it.id in state.selectedTransactionIds }
        } else {
            state.transactions
        }
        if (source.isEmpty()) {
            _exportStatus.value = "Nothing to export"
            return
        }
        container.preferences.activityExportFormat = format.name
        _exporting.value = true
        viewModelScope.launch {
            runCatching {
                val accounts = container.accountRepository.observeActive().first() +
                    container.accountRepository.observeArchived().first()
                val accountNames = accounts.associate { it.id to it.name }
                val categories = container.categoryRepository.observeAll().first()
                val labelList = container.labelRepository.observeAll().first()
                val links = container.labelRepository.observeLinks().first()
                val rates = container.ratesRepository.observeRates().first()
                val base = container.preferences.defaultCurrency
                val ratesFromBase = rates
                    .filter { it.baseCode.equals(base, ignoreCase = true) }
                    .associate { it.quoteCode.uppercase() to it.rate.toDouble() }
                val labelNames = labelList.associate { it.id to it.name }
                val tagsByTx = com.xemmy.mmbank.domain.label.LabelRules.byTransaction(links)
                    .mapValues { (_, ids) -> ids.mapNotNull(labelNames::get).sorted() }
                val summary = if (selectedOnly) {
                    "Selected ${source.size} transaction${if (source.size == 1) "" else "s"}"
                } else {
                    com.xemmy.mmbank.domain.export.ActivityFilterSummary.describe(
                        filters = state.filters,
                        accountNames = accountNames,
                        categoryNames = categories.associate { it.id to it.name },
                        labelNames = labelNames,
                    )
                }
                val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val rows = com.xemmy.mmbank.domain.export.ExportRows.build(
                        transactions = source,
                        accountNames = accountNames,
                        categories = categories,
                        tagNamesByTransaction = tagsByTx,
                        toBase = { amount, currency ->
                            CurrencyMath.toBase(amount.toDouble(), currency, base, ratesFromBase)
                                ?.takeIf { it.isFinite() }
                                ?.let {
                                    java.math.BigDecimal.valueOf(it)
                                        .setScale(com.xemmy.mmbank.ui.util.fractionDigitsFor(base), java.math.RoundingMode.HALF_UP)
                                }
                        },
                    )
                    when (format) {
                        com.xemmy.mmbank.domain.export.ExportFormat.CSV ->
                            com.xemmy.mmbank.domain.export.TransactionCsvFormatter.format(rows)
                        com.xemmy.mmbank.domain.export.ExportFormat.JSON ->
                            com.xemmy.mmbank.domain.export.TransactionJsonFormatter.format(
                                rows = rows,
                                baseCurrency = base,
                                filterSummary = summary,
                                exportedAtMillis = System.currentTimeMillis(),
                            )
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { writeText(text) }
            }.onSuccess {
                _exportStatus.value = "Exported ${source.size} transaction${if (source.size == 1) "" else "s"} as ${format.label}"
            }.onFailure {
                container.recordError("TransactionsViewModel.exportTransactions", it)
                _exportStatus.value = "Export failed: ${it.message}"
            }
            _exporting.value = false
        }
    }

    /** One saved bill by id, for "Log payment" prefill; null when it is gone. */
    suspend fun loadBill(id: Long): com.xemmy.mmbank.domain.model.Bill? =
        container.billRepository.observeAll().first().firstOrNull { it.id == id }

    /** Saves the reminder a repeating transaction creates (see RepeatPlanner). */
    suspend fun createRepeatBill(bill: com.xemmy.mmbank.domain.model.Bill) {
        runCatching { container.billRepository.save(bill) }
            .onFailure { container.recordError("TransactionsViewModel.createRepeatBill", it) }
    }

    /** Advances (or removes) a bill whose payment was just logged from the transaction page. */
    suspend fun markBillPaid(id: Long) {
        runCatching { container.billRepository.markPaid(id) }
            .onFailure { container.recordError("TransactionsViewModel.markBillPaid", it) }
    }

    /** The label ids one saved transaction carries, read once for the editor. */
    suspend fun labelIdsOf(transactionId: Long): Set<Long> =
        container.labelRepository.observeLinks().first().filter { it.transactionId == transactionId }.map { it.labelId }.toSet()

    /** Creates (or finds) a label by name for the editor's "+ New label". */
    suspend fun createLabel(name: String): com.xemmy.mmbank.domain.model.Label? = container.labelRepository.create(name)

    /** One saved row by id, for the full-screen editor; null when it no longer exists. */
    suspend fun loadTransaction(id: Long): Transaction? =
        container.transactionRepository.observeTransactions().first().firstOrNull { it.id == id }

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

    /** Toggles one label in the Activity label filter; clearing the last one removes the filter. */
    fun toggleLabelFilter(labelId: Long) {
        val current = filters.value.labelIds.orEmpty()
        val next = if (labelId in current) current - labelId else current + labelId
        filters.value = filters.value.copy(labelIds = next.ifEmpty { null })
    }

    fun setAnalyticsStatus(status: AnalyticsStatusFilter?) {
        filters.value = filters.value.copy(analyticsStatus = status)
    }

    /**
     * Whether [tx] is far above its merchant's usual (null when not, or when
     * there's too little history). One indexed merchant query.
     */
    suspend fun anomalyFor(tx: Transaction): SpendAnomalyDetector.Anomaly? {
        val merchant = tx.merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            SpendAnomalyDetector.evaluate(tx, container.transactionRepository.spendHistoryForMerchant(merchant))
        }.getOrNull()
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

    /** "Before I started tracking": the selected rows that are in no account stop being reported as unassigned. */
    fun bulkSetHistoryOnly() = safeWrite("bulkSetHistoryOnly") {
        container.transactionRepository.setHistoryOnly(bulkMode.value.selectedIds.toList(), true)
        bulkMode.value = BulkModeState()
    }

    fun setHistoryOnly(transaction: Transaction, historyOnly: Boolean) = safeWrite("setHistoryOnly") {
        container.transactionRepository.setHistoryOnly(listOf(transaction.id), historyOnly)
    }

    fun bulkSetPerson(person: String?) = safeWrite("bulkSetPerson") {
        container.bulkUpdateTransactions(bulkMode.value.selectedIds.toList(), BulkTransactionPatch(person = FieldUpdate.Set(person)))
        bulkMode.value = BulkModeState()
    }

    fun bulkDelete() = safeWrite("bulkDelete") {
        container.bulkUpdateTransactions.delete(bulkMode.value.selectedIds.toList())
        bulkMode.value = BulkModeState()
    }

    /** Runs [block] in the view model's scope; callers that must outlive it (the full-screen editor) join the job. */
    private fun safeWrite(what: String, block: suspend () -> Unit): kotlinx.coroutines.Job {
        return viewModelScope.launch {
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
            // A Pay Later split is one purchase: its other rows follow.
            container.payLaterRepository.refileSplitOf(transactionId, categoryChanged = false, personChanged = true)
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
            learnCategory(transaction, categoryId, refileSplit(transaction))
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
        learnCategory(transaction, categoryId, refileSplit(transaction))
    }

    /**
     * A category picked on a row of a Pay Later split re-files the whole split (the purchase and
     * every payment follow); true when [transaction] is in one.
     */
    private suspend fun refileSplit(transaction: Transaction): Boolean =
        container.payLaterRepository.refileSplitOf(transaction.id, categoryChanged = true, personChanged = false)

    /**
     * Teaches merchant -> category from a manual correction. Clearing a
     * category ([categoryId] null) teaches nothing: "not this" is not a rule.
     * Nor does a row of a split ([inSplit]) or a payment to Tabby: "Tabby"
     * names who collected the money, not what was bought, and a rule would
     * file every other purchase's payments the same way
     * ([PayLaterAttribution.learnsMerchantRule]).
     */
    private suspend fun learnCategory(transaction: Transaction, categoryId: Long?, inSplit: Boolean) {
        if (categoryId == null) return
        if (!PayLaterAttribution.learnsMerchantRule(transaction, inSplit)) return
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
        note: String? = null,
        labelIds: Set<Long> = emptySet(),
    ) = safeWrite("addManual") {
        person?.let(container.preferences::addPerson)
        val newId = container.transactionRepository.recordManual(
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
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
        )
        if (labelIds.isNotEmpty() && newId > 0) container.labelRepository.setLabels(newId, labelIds)
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
        note: String? = original.note,
        labelIds: Set<Long>? = null,
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
                // Changing either in the form is the user's decision; leaving it alone decides nothing.
                categorySource = com.xemmy.mmbank.domain.model.AssignmentSource.afterEdit(
                    original.categorySource, original.categoryId, categoryId,
                ),
                // Changing it away from a refund breaks the link to the purchase it gave money back for.
                refundOfId = if (kind == TransactionKind.REFUND) original.refundOfId else null,
                personSource = com.xemmy.mmbank.domain.model.AssignmentSource.afterEdit(
                    original.personSource, original.person?.takeIf { it.isNotBlank() }, person?.takeIf { it.isNotBlank() },
                ),
                spendingCategory = resolveLegacySpendingCategory(categoryId),
                timestampMillis = timestampMillis,
                excludeFromStats = excludeFromStats,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                // Flipping Spent <-> Received must not leave a one-sided kind
                // on the wrong side (a "spend" credit would count as spending).
                kind = com.xemmy.mmbank.domain.ledger.TransactionKindDetector.coerce(original.kind, direction),
            )
        )
        // A new kind goes through the ledger so transfer links are made or
        // released consistently.
        if (kind != original.kind) container.ledgerRepository.setKind(original.id, kind, rememberForMerchant = false)
        // A Pay Later split is one purchase: a new category or person here reaches its other rows.
        val categoryChanged = original.categoryId != categoryId
        val personChanged = original.person?.trim().orEmpty() != person?.trim().orEmpty()
        if (categoryChanged || personChanged) {
            container.payLaterRepository.refileSplitOf(original.id, categoryChanged, personChanged)
        }
        labelIds?.let { container.labelRepository.setLabels(original.id, it) }
    }

    /**
     * "Not a refund": breaks the link and makes the credit plain income again,
     * as a choice the user made (so automation never turns it back).
     */
    fun unlinkRefund(transaction: Transaction) = safeWrite("unlinkRefund") {
        container.transactionRepository.update(
            transaction.copy(refundOfId = null, kind = TransactionKind.INCOME, kindUserSet = true),
        )
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
