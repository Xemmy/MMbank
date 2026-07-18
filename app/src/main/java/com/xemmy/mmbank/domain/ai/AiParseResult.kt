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
)
