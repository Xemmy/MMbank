package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes and validates Gate-2's JSON output (local Gemma or an online LLM).
 * LLM text output is untrusted: fenced code blocks are stripped, a bare JSON
 * object is recovered even behind a conversational preamble/postamble with no
 * fence, unknown keys are ignored, and any shape/type mismatch fails closed to
 * null instead of throwing, so a single bad generation can never crash the
 * ingestion pipeline. A missing/blank currency falls back to
 * [fallbackCurrency] (the caller passes the app's default currency) rather
 * than failing the whole parse; a missing merchant falls back to
 * [FALLBACK_MERCHANT].
 */
object AiResponseParser {
    /** Used when the model omits `merchant` (or emits null) on a positive intent. */
    const val FALLBACK_MERCHANT = "Uncategorized Merchant"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val codeFence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /** Widest {...} span in the raw text -- the fallback when there's no code fence to strip. */
    private val bareJsonObject = Regex("\\{[\\s\\S]*\\}")

    fun parse(raw: String, fallbackCurrency: String? = null): AiParseResult? = runCatching {
        val candidate = extractJsonCandidate(raw)
        if (candidate.isEmpty()) return null
        val obj = json.parseToJsonElement(candidate).jsonObject

        val intentRaw = stringField(obj, "transaction_intent") ?: return null
        val intent = intentRaw.trim().lowercase().toBooleanStrictOrNull() ?: return null
        if (!intent) return AiParseResult(transactionIntent = false, amount = null, currency = null, merchant = null)

        val amount = stringField(obj, "amount")?.toBigDecimalOrNull()
        val currency = (stringField(obj, "currency") ?: fallbackCurrency)?.trim()
        val merchant = stringField(obj, "merchant") ?: FALLBACK_MERCHANT

        if (amount == null || amount <= BigDecimal.ZERO || currency.isNullOrBlank()) return null

        AiParseResult(transactionIntent = true, amount = amount, currency = currency.uppercase(), merchant = merchant)
    }.getOrNull()

    private fun stringField(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content
    }

    /**
     * Fenced code block first; failing that, a bare object spanning the whole
     * trimmed text is used as-is. Otherwise -- a conversational preamble
     * and/or postamble around the object with no fence -- falls back to the
     * widest {...} span found anywhere in the raw text.
     */
    private fun extractJsonCandidate(raw: String): String {
        codeFence.find(raw)?.groupValues?.get(1)?.trim()?.let { return it }
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        return bareJsonObject.find(raw)?.value ?: trimmed
    }
}
