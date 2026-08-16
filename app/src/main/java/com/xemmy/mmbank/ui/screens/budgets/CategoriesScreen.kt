package com.xemmy.mmbank.ui.screens.budgets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator
import com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.BudgetState
import com.xemmy.mmbank.domain.budget.CategoryHierarchy
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.CategoryEditorDialog
import com.xemmy.mmbank.ui.theme.ObsidianCard
import com.xemmy.mmbank.ui.theme.ObsidianTokens
import com.xemmy.mmbank.ui.theme.obsidianCanvas
import com.xemmy.mmbank.ui.util.asMoney
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoriesUiState(
    /** Capped categories, closest to (or furthest past) their cap first -- rolled up to include child spend. */
    val statuses: List<CategoryBudgetCalculator.CategoryBudgetStatus> = emptyList(),
    val currency: String = "USD",
    /** Every category: drives the hierarchy tree, the parent picker, and the empty-state check. */
    val categories: List<Category> = emptyList(),
)

class CategoriesViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = combine(
        container.categoryRepository.observeAll(),
        container.transactionRepository.observeTransactions(),
    ) { categories, transactions ->
        val currency = container.preferences.defaultCurrency
        val zone = ZoneId.systemDefault()
        val monthStart = LocalDate.now(zone).withDayOfMonth(1)
        val startMillis = monthStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = monthStart.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val directSpend = CategoryBudgetCalculator.monthToDateByCategory(
            transactions = transactions,
            currencyCode = currency,
            startMillis = startMillis,
            endMillisExclusive = endMillis,
        )
        val rolledUpSpend = CategoryBudgetCalculator.rollUpSpend(categories, directSpend)
        CategoriesUiState(
            statuses = CategoryBudgetCalculator.statuses(categories, rolledUpSpend),
            currency = currency,
            categories = categories,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    /** Creates a brand-new category from the FAB's [CategoryEditorDialog]. */
    fun create(name: String, icon: String, colorHex: String, monthlyCap: BigDecimal?, parentCategoryId: Long?) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                container.categoryRepository.save(
                    Category(
                        name = clean,
                        icon = icon.trim().ifBlank { "🏷️" },
                        colorHex = colorHex.trim().ifBlank { "#9E9E9E" },
                        isCustom = true,
                        monthlyCap = monthlyCap?.takeIf { it.signum() > 0 },
                        parentCategoryId = parentCategoryId,
                    )
                )
            }.onFailure { container.recordError("CategoriesViewModel.create", it) }
        }
    }

    /**
     * Saves an edited category — name, icon, color, monthly cap and parent.
     * A null [monthlyCap] clears the budget; a null [parentCategoryId] makes
     * it top-level.
     */
    fun save(
        category: Category,
        name: String,
        icon: String,
        colorHex: String,
        monthlyCap: BigDecimal?,
        parentCategoryId: Long?,
    ) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                container.categoryRepository.save(
                    category.copy(
                        name = clean,
                        icon = icon.trim().ifBlank { category.icon },
                        colorHex = colorHex.trim().ifBlank { category.colorHex },
                        monthlyCap = monthlyCap?.takeIf { it.signum() > 0 },
                        parentCategoryId = parentCategoryId,
                    )
                )
            }.onFailure { container.recordError("CategoriesViewModel.save", it) }
        }
    }

    fun delete(category: Category) {
        viewModelScope.launch {
            runCatching { container.categoryRepository.delete(category.id) }
                .onFailure { container.recordError("CategoriesViewModel.delete", it) }
        }
    }
}

/**
 * The single home for category management: every category in one hierarchy
 * (parent rows followed by their indented children), each showing its own
 * monthly-cap progress when it has one, with create/edit/delete all here.
 *
 * The cap has been storable since dynamic categories shipped but was never
 * readable anywhere — this screen is where it becomes visible, and where the
 * post-insert alert in [com.xemmy.mmbank.di.AppContainer] gets its thresholds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoriesScreen(
    onBack: () -> Unit,
    viewModel: CategoriesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var creatingNew by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf<Category?>(null) }

    val statusByCategoryId = remember(state.statuses) { state.statuses.associateBy { it.category.id } }
    val tree = remember(state.categories) { CategoryHierarchy.buildTree(state.categories) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categories") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creatingNew = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New category")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().obsidianCanvas().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.categories.isEmpty()) {
                item {
                    Text(
                        "No categories yet. Tap + to add one, or set a monthly cap on one " +
                            "and you'll be alerted at 80% and again if you pass it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            tree.forEach { node ->
                item(key = "cat_${node.category.id}") {
                    CategoryRow(
                        category = node.category,
                        status = statusByCategoryId[node.category.id],
                        currency = state.currency,
                        onEdit = { editing = node.category },
                        onDelete = { confirmingDelete = node.category },
                    )
                }
                items(node.children, key = { "cat_${it.id}" }) { child ->
                    CategoryRow(
                        category = child,
                        status = statusByCategoryId[child.id],
                        currency = state.currency,
                        modifier = Modifier.padding(start = 20.dp),
                        onEdit = { editing = child },
                        onDelete = { confirmingDelete = child },
                    )
                }
            }
        }
    }

    if (creatingNew) {
        CategoryEditorDialog(
            initial = null,
            currency = state.currency,
            allCategories = state.categories,
            onDismiss = { creatingNew = false },
            onSave = { name, icon, colorHex, cap, parentId ->
                viewModel.create(name, icon, colorHex, cap, parentId)
                creatingNew = false
            },
        )
    }

    editing?.let { category ->
        CategoryEditorDialog(
            initial = category,
            currency = state.currency,
            allCategories = state.categories,
            onDismiss = { editing = null },
            onSave = { name, icon, colorHex, cap, parentId ->
                viewModel.save(category, name, icon, colorHex, cap, parentId)
                editing = null
            },
        )
    }

    confirmingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { confirmingDelete = null },
            title = { Text("Delete this category?") },
            text = {
                Text(
                    "“${category.name}” will be removed. Transactions filed under it stay, " +
                        "but become uncategorized again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(category)
                    confirmingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Compact "closest to the cap" card for the Insights tab. Renders nothing at
 * all when no category carries a cap, so the tab stays clean until budgets are
 * actually in use.
 *
 * Fed by its own [CategoriesViewModel] rather than by
 * [com.xemmy.mmbank.ui.screens.dashboard.DashboardViewModel]: that one's
 * `combine` is already at Kotlin's 5-argument limit and folds state through a
 * bundle class to fit, so adding budgets there would mean widening that
 * workaround for a card that reads independent data.
 */
@Composable
fun CategoryBudgetSummaryCard(
    onOpenBudgets: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (state.statuses.isEmpty()) return

    ObsidianCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Category budgets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = ObsidianTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenBudgets) { Text("Manage") }
            }
            state.statuses.take(3).forEach { status ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${status.category.icon} ${status.category.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = ObsidianTokens.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${status.spent.asMoney(state.currency)} / ${status.cap.asMoney(state.currency)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (status.state == BudgetState.UNDER) {
                                ObsidianTokens.textMeta
                            } else {
                                barColor(status.state)
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { status.fraction.toFloat().coerceIn(0f, 1f) },
                        color = barColor(status.state),
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
            }
        }
    }
}

/** Bar color by state: normal, approaching the cap, and past it. */
@Composable
private fun barColor(state: BudgetState): Color = when (state) {
    BudgetState.UNDER -> MaterialTheme.colorScheme.primary
    BudgetState.NEAR -> ObsidianTokens.debit
    BudgetState.OVER -> MaterialTheme.colorScheme.error
}

/**
 * One row in the hierarchy: shows budget progress when [status] is non-null
 * (this category carries a cap), otherwise a plain "no cap" row. [modifier]
 * carries the caller's indentation for a child row -- a top-level row passes
 * the default.
 */
@Composable
private fun CategoryRow(
    category: Category,
    status: CategoryBudgetCalculator.CategoryBudgetStatus?,
    currency: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ObsidianCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(category.icon, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(10.dp))
                Text(
                    category.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ObsidianTokens.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RowActions(onEdit = onEdit, onDelete = onDelete)
            }
            if (status != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { status.fraction.toFloat().coerceIn(0f, 1f) },
                    color = barColor(status.state),
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(status.spent.asMoney(currency))
                        append(" of ")
                        append(status.cap.asMoney(currency))
                        append(
                            when (status.state) {
                                BudgetState.OVER -> " · over budget"
                                else -> " · ${status.remaining.asMoney(currency)} left"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.state == BudgetState.UNDER) {
                        ObsidianTokens.textMeta
                    } else {
                        barColor(status.state)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowActions(onEdit: () -> Unit, onDelete: () -> Unit) {
    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = "Edit category",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "Delete category",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
