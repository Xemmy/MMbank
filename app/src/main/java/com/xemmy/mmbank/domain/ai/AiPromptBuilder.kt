package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage

/**
 * Builds the constrained-JSON extraction prompt for one captured message.
 * Inputs are truncated so prompt plus reply stay inside the engine's strict
 * 1024-token context cap (LocalAiManager.MAX_TOKENS) — roughly 4 chars per
 * token leaves comfortable headroom for the instruction block and the reply.
 */
object AiPromptBuilder {

    const val MAX_BODY_CHARS = 1200
    const val MAX_TITLE_CHARS = 200

    fun build(message: CapturedMessage): String {
        val title = message.title?.take(MAX_TITLE_CHARS).orEmpty()
        val body = message.body.take(MAX_BODY_CHARS)
        val sender = message.sender.orEmpty()
        return """
            |You are a strict JSON extractor for bank transaction messages.
            |Reply with ONLY one JSON object, no markdown and no explanations, using exactly these keys:
            |{"transaction_intent": true, "amount": 0.0, "currency": "", "merchant": ""}
            |- "transaction_intent": true only if the message reports one completed money movement (purchase, payment, transfer, deposit, refund, fee).
            |- "amount": the moved amount as a number. Never the available balance or credit limit.
            |- "currency": the amount's currency code like "AED" or "USD". Empty string if unknown.
            |- "merchant": the store or counterparty name exactly as written in the message. Empty string if none.
            |
            |Sender: $sender
            |Title: $title
            |Message: $body
        """.trimMargin()
    }
}
