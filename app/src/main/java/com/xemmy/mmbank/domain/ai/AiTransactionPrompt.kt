package com.xemmy.mmbank.domain.ai

/**
 * Builds the constrained-JSON prompt sent to the local Gemma 4 E2B engine for
 * Gate 2 (see IngestCapturedMessageUseCase / AiParsingWorker): forced to emit
 * exactly the {transaction_intent, amount, currency, merchant} keyset the
 * local-AI spec requires, nothing else.
 */
object AiTransactionPrompt {
    /**
     * [categoryNames] is the app's current spending-category list (built-ins +
     * any custom ones the user has created). Empty by default -- the extra
     * category_name / suggested_category keys are only requested when the
     * caller actually has categories to offer, so a caller that doesn't care
     * about categorization keeps the original, smaller prompt/response shape.
     */
    fun build(rawMessage: String, categoryNames: List<String> = emptyList()): String = buildString {
        appendLine("You extract financial transaction data from a raw bank/payment notification.")
        appendLine("Respond with ONLY a single JSON object, no other text, matching exactly this shape:")
        if (categoryNames.isEmpty()) {
            appendLine("""{"transaction_intent": boolean, "amount": number, "currency": string, "merchant": string}""")
        } else {
            appendLine(
                """{"transaction_intent": boolean, "amount": number, "currency": string, "merchant": string, """ +
                    """"category_name": string|null, "suggested_category": {"name": string, "icon": string, "color": string}|null}"""
            )
            appendLine("Existing categories: ${categoryNames.joinToString(", ")}")
            appendLine(
                "If the merchant clearly fits one of the existing categories, set category_name to that exact " +
                    "name and leave suggested_category null. If none of them fit, leave category_name null and " +
                    "set suggested_category to a new short category name, a single representative emoji as icon, " +
                    "and a 6-digit hex color (e.g. \"#4CAF50\") for color. Never invent a category_name that " +
                    "isn't in the existing list."
            )
        }
        appendLine(
            "If the message is not a financial transaction, set transaction_intent to false and the " +
                "other fields to null. Never invent an amount or currency that is not present in the message."
        )
        appendLine()
        appendLine("Message:")
        append(rawMessage)
    }
}
