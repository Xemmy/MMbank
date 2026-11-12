package com.xemmy.mmbank.ui.screens.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.ui.AppViewModelProvider
import com.xemmy.mmbank.ui.components.CategoryPickerBottomSheet
import com.xemmy.mmbank.ui.components.categoryColor
import com.xemmy.mmbank.ui.designsystem.moneyLabel
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UncategorizedQueueUiState(
    val transactions: List<Transaction> = emptyList(),
    val categories: List<Category> = emptyList(),
    val defaultCurrency: String = "USD",
)

class UncategorizedQueueViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<UncategorizedQueueUiState> = combine(
        container.transactionRepository.observeUncategorized(),
        container.categoryRepository.observeAll(),
    ) { transactions, categories ->
        UncategorizedQueueUiState(transactions, categories, container.preferences.defaultCurrency)
    }
        .catch { e ->
            container.recordError("UncategorizedQueueViewModel", e)
            emit(UncategorizedQueueUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UncategorizedQueueUiState())

    /** Accepts a pending AI suggestion: creates the custom category, binds the merchant mapping, assigns it. */
    fun acceptSuggestion(transaction: Transaction) {
        val name = transaction.suggestedCategoryName ?: return
        viewModelScope.launch {
            runCatching {
                val categoryId = container.categoryRepository.save(
                    Category(
                        name = name,
                        icon = transaction.suggestedCategoryIcon ?: "🏷️",
                        colorHex = transaction.suggestedCategoryColorHex ?: "#9E9E9E",
                        isCustom = true,
                    )
                )
                transaction.merchant?.takeIf { it.isNotBlank() }
                    ?.let { container.categoryRepository.learnMapping(it, categoryId) }
                container.transactionRepository.setCategory(transaction.id, categoryId)
            }.onFailure { container.recordError("UncategorizedQueueViewModel.acceptSuggestion", it) }
        }
    }

    fun dismissSuggestion(transaction: Transaction) {
        viewModelScope.launch {
            runCatching { container.transactionRepository.dismissCategorySuggestion(transaction.id) }
                .onFailure { container.recordError("UncategorizedQueueViewModel.dismissSuggestion", it) }
        }
    }

    fun assignCategory(transaction: Transaction, category: Category) {
        viewModelScope.launch {
            runCatching { container.transactionRepository.setCategory(transaction.id, category.id) }
                .onFailure { container.recordError("UncategorizedQueueViewModel.assignCategory", it) }
        }
    }

    fun createAndAssignCategory(
        transaction: Transaction,
        name: String,
        colorHex: String,
        icon: String,
        monthlyCap: BigDecimal?,
        parentCategoryId: Long?,
    ) {
        viewModelScope.launch {
            runCatching {
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
            }.onFailure { container.recordError("UncategorizedQueueViewModel.createAndAssignCategory", it) }
        }
    }
}

/**
 * Fast review queue for transactions still missing a dynamic [Category]:
 * plain uncategorized rows (tap to pick one) and rows carrying a pending AI
 * category suggestion (one-tap Accept, which creates the category and learns
 * the merchant mapping for next time).
 */
@Composable
fun UncategorizedQueueScreen(
    onBack: () -> Unit,
    viewModel: UncategorizedQueueViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pickerFor by remember { mutableStateOf<Transaction?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Uncategorized (${state.transactions.size})", style = MaterialTheme.typography.titleLarge)
        }

        if (state.transactions.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "All caught up -- nothing left to categorize.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(state.transactions, key = { it.id }) { transaction ->
                    UncategorizedRow(
                        transaction = transaction,
                        onAccept = { viewModel.acceptSuggestion(transaction) },
                        onDismissSuggestion = { viewModel.dismissSuggestion(transaction) },
                        onPickCategory = { pickerFor = transaction },
                    )
                }
            }
        }
    }

    pickerFor?.let { transaction ->
        CategoryPickerBottomSheet(
            categories = state.categories,
            currency = state.defaultCurrency,
            onSelect = { category ->
                viewModel.assignCategory(transaction, category)
                pickerFor = null
            },
            onCreateNew = { name, colorHex, icon, cap, parentId ->
                viewModel.createAndAssignCategory(transaction, name, colorHex, icon, cap, parentId)
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }
}

@Composable
private fun UncategorizedRow(
    transaction: Transaction,
    onAccept: () -> Unit,
    onDismissSuggestion: () -> Unit,
    onPickCategory: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    transaction.merchant ?: transaction.sender ?: "Unknown",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    (if (transaction.direction == TransactionDirection.CREDIT) "+" else "-") +
                        moneyLabel(transaction.amount.toDouble(), transaction.currencyCode),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (transaction.needsCategoryApproval && transaction.suggestedCategoryName != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier.padding(end = 2.dp),
                    ) {
                        Text(transaction.suggestedCategoryIcon ?: "🏷️")
                    }
                    Button(onClick = onAccept, modifier = Modifier.weight(1f)) {
                        Text("Accept ${transaction.suggestedCategoryName}")
                    }
                    IconButton(onClick = onDismissSuggestion) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss suggestion")
                    }
                }
                // Purely decorative reference to the suggested color -- keeps the
                // swatch visible even before the category exists for real.
                Box(
                    Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        "Suggested",
                        style = MaterialTheme.typography.labelSmall,
                        color = categoryColor(transaction.suggestedCategoryColorHex),
                    )
                }
            } else {
                OutlinedButton(onClick = onPickCategory, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Choose category")
                }
            }
        }
    }
}
