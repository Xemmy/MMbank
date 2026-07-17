package com.xemmy.mmbank.domain.usecase

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.repository.TransactionRepository

/**
 * Type-safe patch applied to every transaction in a bulk selection: a field
 * left null is untouched, a non-null field is written to every selected row.
 * Kept explicit rather than a generic Map<String, Any> so the call site and
 * the Room queries it dispatches to stay statically checked.
 */
data class BulkTransactionPatch(
    val spendingCategory: SpendingCategory? = null,
    val excludeFromStats: Boolean? = null,
)

/**
 * Multi-select edit/delete for the Transactions screen's bulk mode. One Room
 * UPDATE per non-null [BulkTransactionPatch] field, or a single DELETE via
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
        return affected
    }

    suspend fun delete(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return transactionRepository.deleteMany(ids)
    }
}
