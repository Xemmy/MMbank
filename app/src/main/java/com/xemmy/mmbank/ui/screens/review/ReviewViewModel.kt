package com.xemmy.mmbank.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xemmy.mmbank.di.AppContainer
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.parser.CategorySuggestion
import com.xemmy.mmbank.domain.parser.CategorySuggestionEngine
import java.math.BigDecimal
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Color bucket for the Review card's confidence chip: Green/Yellow/Red at 80/50. */
enum class ConfidenceLevel { HIGH, MEDIUM, LOW }

/** One queue row: the transaction, its ranked one-tap category picks, and its confidence bucket. */
data class ReviewQueueItem(
    val transaction: Transaction,
    val suggestions: List<CategorySuggestion>,
    val confidenceLevel: ConfidenceLevel,
)

data class ReviewUiState(
    val items: List<ReviewQueueItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    /** For the review card's account badge; resolved by id in the UI layer. */
    val accounts: List<Account> = emptyList(),
    /** Count behind [BatchApprovalBanner]'s "Approve X" button. */
    val batchApprovableCount: Int = 0,
)

/** Threshold (Green in the confidence chip, "Auto-Categorized" in the batch banner). */
internal const val HIGH_CONFIDENCE_THRESHOLD = 80

/** Floor of the Yellow band; anything below is Red. */
internal const val MEDIUM_CONFIDENCE_THRESHOLD = 50

/** Un-scored ([Transaction.aiConfidence] null, e.g. a manual entry) reads as the lowest bucket. */
internal fun confidenceLevelOf(score: Int?): ConfidenceLevel {
    val value = score ?: 0
    return when {
        value >= HIGH_CONFIDENCE_THRESHOLD -> ConfidenceLevel.HIGH
        value >= MEDIUM_CONFIDENCE_THRESHOLD -> ConfidenceLevel.MEDIUM
        else -> ConfidenceLevel.LOW
    }
}

/** Pairs each uncategorized transaction with its ranked suggestions and confidence bucket. Pure and top-level for JVM testing. */
internal fun buildReviewQueue(transactions: List<Transaction>, categories: List<Category>): List<ReviewQueueItem> =
    transactions.map { transaction ->
        ReviewQueueItem(
            transaction = transaction,
            suggestions = CategorySuggestionEngine.suggest(transaction, categories),
            confidenceLevel = confidenceLevelOf(transaction.aiConfidence),
        )
    }

/**
 * Transactions [BatchApprovalBanner]'s "Approve X" resolves in one pass: a
 * pending AI category suggestion scored at or above [threshold]. A plain
 * "Uncategorized" row with no suggestion at all can never auto-resolve, no
 * matter how it's scored.
 */
internal fun highConfidenceApprovable(
    transactions: List<Transaction>,
    threshold: Int = HIGH_CONFIDENCE_THRESHOLD,
): List<Transaction> = transactions.filter {
    it.needsCategoryApproval && !it.suggestedCategoryName.isNullOrBlank() && (it.aiConfidence ?: 0) >= threshold
}

/**
 * Backs the High-Detail Transaction Review screen: every real,
 * still-[Category]-less transaction, each with up to 3 one-tap suggestions
 * (see [CategorySuggestionEngine]) and a confidence bucket for the review
 * card's chip. Popping an item off the queue needs no local bookkeeping --
 * assigning a category removes it from [com.xemmy.mmbank.domain.repository.TransactionRepository.observeUncategorized]'s
 * own reactive stream, same as the existing Uncategorized queue.
 */
class ReviewViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<ReviewUiState> = combine(
        container.transactionRepository.observeUncategorized(),
        container.categoryRepository.observeAll(),
        container.accountRepository.observeActive(),
    ) { transactions, categories, accounts ->
        ReviewUiState(
            items = buildReviewQueue(transactions, categories),
            categories = categories,
            accounts = accounts,
            batchApprovableCount = highConfidenceApprovable(transactions).size,
        )
    }
        .catch { e ->
            container.recordError("ReviewViewModel", e)
            emit(ReviewUiState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewUiState())

    /** One-tap chip pick from [CategorySuggestionRow]; creates + learns the category first when [suggestion] is still pending. */
    fun assignSuggestion(transaction: Transaction, suggestion: CategorySuggestion) {
        viewModelScope.launch {
            runCatching {
                val categoryId = suggestion.categoryId
                    ?: createAndLearnCategory(transaction, suggestion.name, suggestion.icon, suggestion.colorHex)
                container.transactionRepository.setCategory(transaction.id, categoryId)
            }.onFailure { container.recordError("ReviewViewModel.assignSuggestion", it) }
        }
    }

    /** "More Categories…" bottom-sheet pick. */
    fun assignCategory(transaction: Transaction, category: Category) {
        viewModelScope.launch {
            runCatching { container.transactionRepository.setCategory(transaction.id, category.id) }
                .onFailure { container.recordError("ReviewViewModel.assignCategory", it) }
        }
    }

    /** "Create New Category" from the searchable category sheet. */
    fun createAndAssignCategory(transaction: Transaction, name: String, colorHex: String, icon: String, monthlyCap: BigDecimal?) {
        viewModelScope.launch {
            runCatching {
                val categoryId = container.categoryRepository.save(
                    Category(name = name, icon = icon, colorHex = colorHex, isCustom = true, monthlyCap = monthlyCap)
                )
                container.transactionRepository.setCategory(transaction.id, categoryId)
            }.onFailure { container.recordError("ReviewViewModel.createAndAssignCategory", it) }
        }
    }

    fun dismissSuggestion(transaction: Transaction) {
        viewModelScope.launch {
            runCatching { container.transactionRepository.dismissCategorySuggestion(transaction.id) }
                .onFailure { container.recordError("ReviewViewModel.dismissSuggestion", it) }
        }
    }

    /** [BatchApprovalBanner]'s "Approve X" action: accepts every still-pending high-confidence suggestion in one pass. */
    fun approveAllHighConfidence() {
        viewModelScope.launch {
            runCatching {
                val approvable = highConfidenceApprovable(container.transactionRepository.observeUncategorized().first())
                approvable.forEach { transaction ->
                    val name = transaction.suggestedCategoryName ?: return@forEach
                    val categoryId = createAndLearnCategory(
                        transaction, name,
                        transaction.suggestedCategoryIcon ?: "🏷️",
                        transaction.suggestedCategoryColorHex ?: "#9E9E9E",
                    )
                    container.transactionRepository.setCategory(transaction.id, categoryId)
                }
            }.onFailure { container.recordError("ReviewViewModel.approveAllHighConfidence", it) }
        }
    }

    private suspend fun createAndLearnCategory(transaction: Transaction, name: String, icon: String, colorHex: String): Long {
        val categoryId = container.categoryRepository.save(Category(name = name, icon = icon, colorHex = colorHex, isCustom = true))
        transaction.merchant?.takeIf { it.isNotBlank() }?.let { container.categoryRepository.learnMapping(it, categoryId) }
        return categoryId
    }
}
