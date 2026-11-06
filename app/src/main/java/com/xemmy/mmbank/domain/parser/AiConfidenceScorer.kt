package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.SpendingCategory

/**
 * Scores how confident the capture pipeline is in a transaction's automated
 * categorization, at insert time (see [com.xemmy.mmbank.data.repository.TransactionRepositoryImpl.recordParsed]).
 * Persisted as [com.xemmy.mmbank.domain.model.Transaction.aiConfidence] and
 * driving the Review screen's color-coded confidence chip.
 */
object AiConfidenceScorer {
    /** A confirmed [com.xemmy.mmbank.domain.model.MerchantCategoryMapping] hit -- the strongest signal available. */
    const val CONFIRMED_MAPPING = 95

    /** [MerchantCategorizer]'s fixed keyword buckets matched -- a decent but unconfirmed guess. */
    const val KEYWORD_MATCH = 70

    /** An unconfirmed AI category suggestion is staged, awaiting human approval. */
    const val PENDING_SUGGESTION = 55

    /** No signal at all -- plain "Uncategorized" with nothing to go on. */
    const val NO_SIGNAL = 25

    fun score(
        hasConfirmedCategory: Boolean,
        hasPendingSuggestion: Boolean,
        spendingCategory: SpendingCategory,
    ): Int = when {
        hasConfirmedCategory -> CONFIRMED_MAPPING
        hasPendingSuggestion -> PENDING_SUGGESTION
        spendingCategory != SpendingCategory.UNCATEGORIZED -> KEYWORD_MATCH
        else -> NO_SIGNAL
    }
}
