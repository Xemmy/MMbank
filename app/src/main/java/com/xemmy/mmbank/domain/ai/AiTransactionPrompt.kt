package com.xemmy.mmbank.domain.ai

/**
 * Builds the constrained-JSON prompt sent to the local Gemma 4 E2B engine for
 * Gate 2 (see IngestCapturedMessageUseCase / AiParsingWorker): forced to emit
 * exactly the {transaction_intent, amount, currency, merchant} keyset the
 * local-AI spec requires, nothing else.
 */
object AiTransactionPrompt {
    fun build(rawMessage: String): String = buildString {
        appendLine("You extract financial transaction data from a raw bank/payment notification.")
        appendLine("Respond with ONLY a single JSON object, no other text, matching exactly this shape:")
        appendLine("""{"transaction_intent": boolean, "amount": number, "currency": string, "merchant": string}""")
        appendLine(
            "If the message is not a financial transaction, set transaction_intent to false and the " +
                "other fields to null. Never invent an amount or currency that is not present in the message."
        )
        appendLine()
        appendLine("Message:")
        append(rawMessage)
    }
}
