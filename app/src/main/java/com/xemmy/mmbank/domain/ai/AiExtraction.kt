package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal

/**
 * Validated result of one on-device model pass over a captured message.
 * The model's raw contract is the strict keyset
 * `{"transaction_intent": Boolean, "amount": Float, "currency": String, "merchant": String}`;
 * [AiResponseParser] maps it here, or to null when the output is unusable.
 */
sealed interface AiExtraction {

    /** The model decided the message does not describe a money movement. */
    data object NotATransaction : AiExtraction

    data class Transaction(
        val amount: BigDecimal,
        /** ISO-ish code normalized by CurrencyNormalizer; null when the model didn't know. */
        val currency: String?,
        val merchant: String?,
    ) : AiExtraction
}
