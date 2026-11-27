package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.repository.TransactionRepository

/**
 * Distinguishes "leave this field alone" from "explicitly set it to null" for
 * patch fields whose target value is itself nullable (accountId, person). A
 * plain nullable type can't express both states, so those two fields use
 * this wrapper; spendingCategory/excludeFromStats keep plain nullable types
 * since null unambiguously means "no-op" for them.
 */
sealed interface FieldUpdate<out T> {
    data object NoChange : FieldUpdate<Nothing>
    data class Set<T>(val value: T) : FieldUpdate<T>
}

/**
 * Type-safe patch applied to every transaction in a bulk selection: a field
 * left null/NoChange is untouched, a set field is written to every selected
 * row. Kept explicit rather than a generic Map<String, Any> so the call site
 * and the Room queries it dispatches to stay statically checked.
 */
data class BulkTransactionPatch(
    val spendingCategory: SpendingCategory? = null,
    val excludeFromStats: Boolean? = null,
    val accountId: FieldUpdate<Long?> = FieldUpdate.NoChange,
    val person: FieldUpdate<String?> = FieldUpdate.NoChange,
    val categoryId: FieldUpdate<Long?> = FieldUpdate.NoChange,
)

/**
 * Multi-select edit/delete for the Transactions screen's bulk mode. One Room
 * UPDATE per set [BulkTransactionPatch] field, or a single DELETE via
 * [delete] — simpler and more Room-idiomatic than one dynamic query.
 */
class BulkUpdateTransactionsUseCase(
    private val transactionRepository: TransactionRepository,
) {
    suspend operator fun invoke(ids: List<Long>, patch: BulkTransactionPatch): Int {
        if (ids.isEmpty()) return 0
        var affected = 0
        patch.spendingCategory?.let { affected = transactionRepository.bulkSetCategory(ids, it) }
        patch.excludeFromStats?.let { affected = transactionRepository.bulkSetExcludeFromStats(ids, it) }
        (patch.accountId as? FieldUpdate.Set)?.let { affected = transactionRepository.bulkAssignAccount(ids, it.value) }
        (patch.person as? FieldUpdate.Set)?.let { affected = transactionRepository.bulkSetPerson(ids, it.value) }
        (patch.categoryId as? FieldUpdate.Set)?.let { affected = transactionRepository.bulkSetCategoryId(ids, it.value) }
        return affected
    }

    suspend fun delete(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return transactionRepository.deleteMany(ids)
    }
}
