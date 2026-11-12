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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.em
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
import com.xemmy.mmbank.domain.usecase.BulkTransactionPatch
import com.xemmy.mmbank.domain.usecase.FieldUpdate
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.designsystem.MoneyTone
import com.xemmy.mmbank.ui.designsystem.moneyColor
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import com.xemmy.mmbank.ui.components.AiParsingIndicator
import com.xemmy.mmbank.ui.components.CategoryEditorDialog
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.theme.GlassDialogBackdrop
import com.xemmy.mmbank.ui.theme.GlowPalette
import com.xemmy.mmbank.ui.theme.GlowTone
import com.xemmy.mmbank.ui.theme.GlowTones
import com.xemmy.mmbank.ui.theme.MetaLabel
import com.xemmy.mmbank.ui.theme.MMTheme
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

/** Entry point into the To review inbox ([com.xemmy.mmbank.ui.screens.review.ReviewTransactionsScreen]) -- shown only while there's something to review. */
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
    val heroVisible = !state.isInBulkMode
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

    // Back leaves multi-select instead of leaving the screen.
    androidx.activity.compose.BackHandler(enabled = state.isInBulkMode) { viewModel.exitBulkMode() }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
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
                    TransactionFilterBar(
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
                val today = rememberCurrentDate()
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
        val anomaly by produceState<SpendAnomalyDetector.Anomaly?>(null, tx) { value = viewModel.anomalyFor(tx) }
        TransactionDetailSheet(
            anomaly = anomaly,
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
    val colors = MMTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.surfaceRaised, CircleShape)
                .border(1.dp, colors.borderSubtle, CircleShape)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                dayPillLabel(day, today),
                color = colors.textSecondary,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.18.em),
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
    // The swipe state is remembered from the first composition, so it would keep
    // calling that composition's lambdas (a stale row). Always call the latest.
    val currentOnFlag by rememberUpdatedState(onFlag)
    val currentOnCategorize by rememberUpdatedState(onCategorize)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnFlag()
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentOnCategorize()
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
    val colors = MMTheme.colors
    val (background, icon, alignment) = when (direction) {
        SwipeToDismissBoxValue.StartToEnd ->
            Triple(colors.danger.copy(alpha = 0.28f), Icons.Filled.Flag, Alignment.CenterStart)
        SwipeToDismissBoxValue.EndToStart ->
            Triple(colors.moneyIn.copy(alpha = 0.28f), Icons.Filled.Label, Alignment.CenterEnd)
        SwipeToDismissBoxValue.Settled -> Triple(Color.Transparent, null, Alignment.Center)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(background, MaterialTheme.shapes.medium)
            .padding(horizontal = 22.dp),
        contentAlignment = alignment,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = colors.textPrimary) }
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
    Surface(color = MMTheme.colors.canvas, modifier = Modifier.fillMaxWidth()) {
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
                color = MMTheme.colors.textPrimary,
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
        color = MMTheme.colors.canvas,
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
 * Dense primary filter chip for the Activity filter header: 28dp tall, 8dp
 * corners, 12sp label. Token-coloured (idle transparent / selected raised
 * surface) but hand-rolled, rather than MMFilterChip, so height and padding
 * aren't inflated by the 48dp touch target.
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
    val colors = MMTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    val background = if (selected) colors.surfaceRaised else Color.Transparent
    val border = if (selected) colors.borderStrong else colors.borderSubtle
    val labelColor = if (selected) colors.textPrimary else colors.textSecondary
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
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = labelColor,
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
    val colors = MMTheme.colors
    val shape = MaterialTheme.shapes.extraSmall
    val labelColor = if (selected) colors.textPrimary else colors.textSecondary
    Row(
        modifier = Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (selected) colors.surfaceRaised else Color.Transparent, shape)
            .border(0.5.dp, if (selected) colors.borderStrong else colors.borderSubtle, shape)
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
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
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
    val colors = MMTheme.colors
    val shape = MaterialTheme.shapes.small
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.textPrimary),
        modifier = modifier
            .height(40.dp)
            .clip(shape)
            .background(colors.surface, shape)
            .border(
                1.dp,
                if (focused) colors.borderStrong else colors.borderSubtle,
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
                    tint = colors.textSecondary,
                )
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Search merchant, bank, amount…",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
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
                        tint = colors.textSecondary,
                    )
                }
            }
        },
    )
}

/** Field colors: surface fill, subtle/accent border — shared by every OutlinedTextField in the glass dialogs. */
@Composable
internal fun glassFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = MMTheme.colors.surface,
    focusedContainerColor = MMTheme.colors.surface,
    unfocusedBorderColor = MMTheme.colors.borderSubtle,
    focusedBorderColor = MMTheme.colors.accent,
)

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
    val min = com.xemmy.mmbank.util.AmountInput.parse(minText)
    val max = com.xemmy.mmbank.util.AmountInput.parse(maxText)
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

/** Which design-system money tone an [AmountTone] renders in: out / in / neutral. */
internal fun AmountTone.toMoneyTone(): MoneyTone = when (this) {
    AmountTone.NEGATIVE -> MoneyTone.OUT
    AmountTone.POSITIVE -> MoneyTone.IN
    AmountTone.NEUTRAL -> MoneyTone.NEUTRAL
}

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
    val shape = MaterialTheme.shapes.medium
    val colors = MMTheme.colors
    // Shared treatment for the card's secondary meta lines.
    val metaTextColor = colors.textSecondary
    Box(
        Modifier
            .fillMaxWidth()
            // Filter/expansion-driven size changes glide instead of snapping.
            .animateContentSize()
            .alpha(cardAlpha)
            .clip(shape)
            .background(colors.surface, shape)
            .border(
                1.dp,
                when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    accountFilterTint != null -> accountFilterTint.copy(alpha = 0.35f)
                    else -> colors.borderSubtle
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
                        tint = if (isSelected) MaterialTheme.colorScheme.primary else colors.textSecondary,
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
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (tx.excludeFromStats) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        color = colors.textPrimary,
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
                        style = MaterialTheme.typography.bodySmall,
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
                    display = (if (isCredit) "+" else "-") + moneyLabel(tx.amount.toDouble(), tx.currencyCode),
                    // A transfer, card payment or correction is neither money
                    // spent nor received: shown neutral, not as in/out.
                    color = moneyColor(
                        if (LedgerMath.effect(tx) == LedgerMath.Effect.NONE && !tx.excludeFromStats) {
                            MoneyTone.NEUTRAL
                        } else {
                            amountTone(tx.amount, tx.direction).toMoneyTone()
                        },
                    ),
                    // AmountText supplies the tabular mono numerals.
                    style = MaterialTheme.typography.titleMedium,
                )
                if (tx.excludeFromStats) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Excluded from analytics",
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(12.dp)
                            .alpha(0.7f),
                        tint = colors.textSecondary,
                    )
                }
            }

            tx.rawMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = metaTextColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                listOfNotNull(tx.timestampMillis.asDateTime(), tx.matchedRuleName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
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
    // Pill: idle transparent/subtle border/secondary label; active raised
    // surface/strong border/primary label.
    val colors = MMTheme.colors
    val shape = CircleShape
    val background = if (active) colors.surfaceRaised else Color.Transparent
    val border = if (active) colors.borderStrong else colors.borderSubtle
    // Assigned values stay full-bright so set/unset chips remain distinguishable.
    val labelColor = if (active) colors.textPrimary else colors.textSecondary
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
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
