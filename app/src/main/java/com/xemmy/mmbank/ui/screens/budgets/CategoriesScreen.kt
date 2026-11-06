package com.xemmy.mmbank.ui.screens.budgets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    /** Transaction count per category id -- drives whether deleting one needs a reassign picker. */
    val transactionCounts: Map<Long, Int> = emptyMap(),
) {
    /** Transactions filed directly on [categoryId] -- the rows a delete would actually move. */
    fun directCount(categoryId: Long): Int = transactionCounts[categoryId] ?: 0

    /**
     * Transactions sitting under [categoryId]'s sub-categories, at any depth.
     * These aren't moved by a delete, but they must still be surfaced: a parent
     * with no direct rows can have children full of them, and staying silent
     * about that is the loss the reassign prompt exists to prevent.
     */
    fun descendantCount(categoryId: Long): Int =
        CategoryHierarchy.descendantIds(categories, categoryId)
            .sumOf { transactionCounts[it] ?: 0 }
}

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
        val transactionCounts = transactions.mapNotNull { it.categoryId }
            .groupingBy { it }
            .eachCount()
        CategoriesUiState(
            statuses = CategoryBudgetCalculator.statuses(categories, rolledUpSpend),
            currency = currency,
            categories = categories,
            transactionCounts = transactionCounts,
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

    /**
     * Deletes [category], first moving its transactions onto [reassignTo] (or
     * clearing them, when null), then re-homing the learned merchant rules that
     * pointed at it -- otherwise the next transaction from those merchants would
     * be filed straight back onto the id that just went away.
     */
    fun delete(category: Category, reassignTo: Long?) {
        viewModelScope.launch {
            runCatching {
                container.transactionRepository.reassignCategory(category.id, reassignTo)
                container.categoryRepository.repointMappings(category.id, reassignTo)
                container.categoryRepository.delete(category.id)
            }.onFailure { container.recordError("CategoriesViewModel.delete", it) }
        }
    }
}

/**
 * The category hierarchy list (parent rows followed by their indented
 * children, each showing its own monthly-cap progress when it has one) plus
 * its create/edit/delete dialogs — no Scaffold chrome of its own, so it can
 * be embedded directly into a host screen's tab (see
 * [com.xemmy.mmbank.ui.screens.settings.CategoriesAndPeopleScreen]).
 * [creatingNew] is hoisted so a FAB living in that host's Scaffold can open
 * the create dialog; edit/delete stay local since they're triggered from
 * within this list.
 *
 * The cap has been storable since dynamic categories shipped but was never
 * readable anywhere — this is where it becomes visible, and where the
 * post-insert alert in [com.xemmy.mmbank.di.AppContainer] gets its thresholds.
 */
@Composable
fun CategoriesTabContent(
    creatingNew: Boolean,
    onCreatingNewChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Category?>(null) }
    var confirmingDelete by remember { mutableStateOf<Category?>(null) }

    val statusByCategoryId = remember(state.statuses) { state.statuses.associateBy { it.category.id } }
    val tree = remember(state.categories) { CategoryHierarchy.buildTree(state.categories) }

    LazyColumn(
        modifier = modifier.fillMaxSize().obsidianCanvas(),
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

    if (creatingNew) {
        CategoryEditorDialog(
            initial = null,
            currency = state.currency,
            allCategories = state.categories,
            onDismiss = { onCreatingNewChange(false) },
            onSave = { name, icon, colorHex, cap, parentId ->
                viewModel.create(name, icon, colorHex, cap, parentId)
                onCreatingNewChange(false)
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
        val directCount = state.directCount(category.id)
        val descendantCount = state.descendantCount(category.id)
        if (directCount == 0 && descendantCount == 0) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = null },
                title = { Text("Delete this category?") },
                text = { Text("“${category.name}” will be removed.") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.delete(category, reassignTo = null)
                        confirmingDelete = null
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { confirmingDelete = null }) { Text("Cancel") } },
            )
        } else {
            CategoryReassignDialog(
                category = category,
                directCount = directCount,
                descendantCount = descendantCount,
                otherCategories = CategoryHierarchy.reassignTargets(state.categories, category.id),
                onDismiss = { confirmingDelete = null },
                onConfirm = { reassignTo ->
                    viewModel.delete(category, reassignTo)
                    confirmingDelete = null
                },
            )
        }
    }
}

/**
 * Shown instead of the plain confirm dialog when the category being deleted
 * still has transactions on it -- picks where those transactions land
 * (another category, or back to uncategorized) instead of silently
 * orphaning them.
 *
 * [directCount] and [descendantCount] are deliberately kept apart: only the
 * direct rows move, so promising to move a sub-category's transactions too
 * would be a lie. The sub-category total is still shown, because a parent with
 * zero direct rows is exactly the case where a silent delete does the most
 * damage.
 */
@Composable
private fun CategoryReassignDialog(
    category: Category,
    directCount: Int,
    descendantCount: Int,
    otherCategories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Long?) -> Unit,
) {
    var selected by remember { mutableStateOf<Long?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete “${category.name}”?") },
        text = {
            Column {
                Text(
                    if (directCount > 0) {
                        "$directCount transaction${if (directCount == 1) "" else "s"} " +
                            "${if (directCount == 1) "is" else "are"} filed directly under this " +
                            "category. Choose where to move ${if (directCount == 1) "it" else "them"}:"
                    } else {
                        "Nothing is filed directly under this category. Choose where its " +
                            "learned merchant rules should point from now on:"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (descendantCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$descendantCount " +
                            "${if (descendantCount == 1) "transaction sits" else "transactions sit"} " +
                            "under its sub-categories. Those stay where they are — the " +
                            "sub-categories move up to the top level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    item {
                        ReassignOptionRow(
                            label = "Leave uncategorized",
                            selected = selected == null,
                            onClick = { selected = null },
                        )
                    }
                    items(otherCategories, key = { it.id }) { other ->
                        ReassignOptionRow(
                            label = "${other.icon} ${other.name}",
                            selected = selected == other.id,
                            onClick = { selected = other.id },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ReassignOptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
