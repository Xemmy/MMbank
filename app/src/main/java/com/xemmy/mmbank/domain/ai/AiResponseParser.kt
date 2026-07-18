package com.xemmy.mmbank.domain.ai

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes and validates Gemma 4 E2B's Gate-2 JSON output. LLM text output is
 * untrusted: fenced code blocks are stripped, unknown keys are ignored, and
 * any shape/type mismatch fails closed to null instead of throwing, so a
 * single bad generation can never crash the ingestion pipeline.
 */
object AiResponseParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val codeFence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    fun parse(raw: String): AiParseResult? = runCatching {
        val candidate = stripCodeFence(raw).trim()
        if (candidate.isEmpty()) return null
        val obj = json.parseToJsonElement(candidate).jsonObject

        val intentRaw = stringField(obj, "transaction_intent") ?: return null
        val intent = intentRaw.trim().lowercase().toBooleanStrictOrNull() ?: return null
        if (!intent) return AiParseResult(transactionIntent = false, amount = null, currency = null, merchant = null)

        val amount = stringField(obj, "amount")?.toBigDecimalOrNull()
        val currency = stringField(obj, "currency")
        val merchant = stringField(obj, "merchant")

        if (amount == null || amount <= BigDecimal.ZERO || currency.isNullOrBlank()) return null

        AiParseResult(transactionIntent = true, amount = amount, currency = currency.uppercase(), merchant = merchant)
    }.getOrNull()

    private fun stringField(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: return null
        if (element is JsonNull) return null
        return element.jsonPrimitive.content
    }

    private fun stripCodeFence(text: String): String =
        codeFence.find(text)?.groupValues?.get(1) ?: text
}
