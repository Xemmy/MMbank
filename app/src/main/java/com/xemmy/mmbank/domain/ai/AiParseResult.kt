package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal

/**
 * Validated Gate-2 output: Gemma 4 E2B is prompted to emit exactly this
 * keyset (see [AiTransactionPrompt]); [AiResponseParser] guarantees amount
 * and currency are non-null whenever [transactionIntent] is true.
 */
data class AiParseResult(
    val transactionIntent: Boolean,
    val amount: BigDecimal?,
    val currency: String?,
    val merchant: String?,
    /**
     * One of the category names the caller passed to [AiResponseParser.parse]
     * (matched case-insensitively, canonicalized to the caller's own casing).
     * Null when the model didn't name an existing category -- either it had
     * none to choose from, or it judged none fit (see [suggestedCategoryName]).
     */
    val categoryName: String? = null,
    /** Proposed brand-new category name when no existing one fit; mutually exclusive with [categoryName]. */
    val suggestedCategoryName: String? = null,
    val suggestedCategoryIcon: String? = null,
    val suggestedCategoryColorHex: String? = null,
)
