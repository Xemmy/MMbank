package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.parser.CurrencyNormalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Extracts the constrained-JSON transaction keyset from raw model output.
 *
 * Small local models routinely wrap the object in markdown fences or a line
 * of prose despite instructions, so this scans for every balanced `{...}`
 * candidate in the text and accepts the first one that carries a usable
 * keyset. An amount is required (positive, number or numeric string) unless
 * `transaction_intent` is false; currency and merchant are best-effort.
 */
object AiResponseParser {

    const val KEY_INTENT = "transaction_intent"
    const val KEY_AMOUNT = "amount"
    const val KEY_CURRENCY = "currency"
    const val KEY_MERCHANT = "merchant"

    private const val MAX_MERCHANT_CHARS = 60

    /** Placeholder strings models emit for "no merchant"; never store them. */
    private val JUNK_MERCHANTS = setOf("null", "none", "unknown", "n/a", "na", "empty")

    private val json = Json { isLenient = true }

    fun parse(raw: String): AiExtraction? {
        var searchFrom = 0
        while (true) {
            val start = raw.indexOf('{', searchFrom)
            if (start < 0) return null
            balancedObjectAt(raw, start)?.let { candidate ->
                parseObject(candidate)?.let { return it }
            }
            searchFrom = start + 1
        }
    }

    private fun parseObject(payload: String): AiExtraction? {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }
            .getOrNull() ?: return null

        val intent = (obj[KEY_INTENT] as? JsonPrimitive)?.booleanOrNull ?: return null
        if (!intent) return AiExtraction.NotATransaction

        val amount = (obj[KEY_AMOUNT] as? JsonPrimitive)?.contentOrNull
            ?.replace(",", "")?.trim()?.toBigDecimalOrNull() ?: return null
        // A fabricated zero/negative amount is worse than an unparsed message —
        // same plausibility bar as the deterministic extractors.
        if (amount.signum() <= 0) return null

        val currency = (obj[KEY_CURRENCY] as? JsonPrimitive)?.contentOrNull
            ?.let(CurrencyNormalizer::normalize)

        val merchant = (obj[KEY_MERCHANT] as? JsonPrimitive)?.contentOrNull
            ?.trim()?.take(MAX_MERCHANT_CHARS)?.trim()
            ?.takeIf { it.length >= 2 && it.lowercase() !in JUNK_MERCHANTS }

        return AiExtraction.Transaction(amount, currency, merchant)
    }

    /** The balanced `{...}` starting at [start], or null when it never closes. */
    private fun balancedObjectAt(raw: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        return null
    }
}
