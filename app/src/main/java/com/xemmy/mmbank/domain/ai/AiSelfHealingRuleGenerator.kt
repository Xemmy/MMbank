package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.parser.GenericExtractors
import java.math.BigDecimal

/**
 * Turns a validated Gate-2 [AiParseResult] plus the raw message it came from
 * into a *pending* [ParsingRule] candidate: a regex built by substituting the
 * AI's own extracted amount/currency/merchant substrings back into the
 * literal message, so future messages of the same shape route without the
 * AI. The candidate is validated in an isolated trial -- the generated regex
 * must re-match the very message it was built from and reproduce the same
 * amount/currency -- before it's returned; a message whose extracted values
 * don't literally appear in the text (so no safe substitution exists), or
 * one with no clear debit/credit signal (per the parser's shared
 * [GenericExtractors.detectDirectionOverride] vocabulary -- the AI's
 * constrained JSON keyset carries no direction field), yields null rather
 * than a guessed/unsafe pattern.
 *
 * Never auto-applied: every candidate comes back with enabled = false, so it
 * only ever reaches live parsing after a human approves it in the Rules
 * screen (see AppContainer.onAiTransactionParsed).
 */
object AiSelfHealingRuleGenerator {
    private const val AMOUNT_GROUP_PATTERN = "(?<amount>\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)"
    private const val MERCHANT_GROUP_PATTERN = "(?<merchant>[^.,;\\n*]{1,60}?)"

    fun generate(rawMessage: String, result: AiParseResult, ruleNamePrefix: String = "ai.pending"): ParsingRule? {
        if (!result.transactionIntent) return null
        val amount = result.amount ?: return null
        val currency = result.currency ?: return null
        val direction = GenericExtractors.detectDirectionOverride(rawMessage) ?: return null

        val pattern = buildPattern(rawMessage, amount, currency, result.merchant) ?: return null
        if (!validate(pattern, rawMessage, amount, currency)) return null

        return ParsingRule(
            name = "$ruleNamePrefix.${System.currentTimeMillis()}",
            pattern = pattern,
            direction = direction,
            enabled = false,
            sampleText = rawMessage,
        )
    }

    private data class Span(val start: Int, val end: Int, val groupPattern: String)

    private fun buildPattern(rawMessage: String, amount: BigDecimal, currency: String, merchant: String?): String? {
        val amountText = findAmountSubstring(rawMessage, amount) ?: return null
        val amountStart = rawMessage.indexOf(amountText)
        if (amountStart < 0) return null
        val amountSpan = Span(amountStart, amountStart + amountText.length, AMOUNT_GROUP_PATTERN)

        val currencyStart = rawMessage.indexOf(currency, ignoreCase = true)
        if (currencyStart < 0) return null
        val currencySpan = Span(
            currencyStart,
            currencyStart + currency.length,
            "(?<currency>${Regex.escape(currency)})",
        )

        val merchantSpan = merchant?.let { m ->
            val mi = rawMessage.indexOf(m, ignoreCase = true)
            if (mi < 0) null else Span(mi, mi + m.length, MERCHANT_GROUP_PATTERN)
        }

        val spans = listOfNotNull(amountSpan, currencySpan, merchantSpan).sortedBy { it.start }
        for (i in 0 until spans.size - 1) {
            // Overlapping spans (e.g. the currency code sitting inside the
            // merchant name) can't be safely substituted independently.
            if (spans[i].end > spans[i + 1].start) return null
        }

        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            sb.append(Regex.escape(rawMessage.substring(cursor, span.start)))
            sb.append(span.groupPattern)
            cursor = span.end
        }
        sb.append(Regex.escape(rawMessage.substring(cursor)))
        return sb.toString()
    }

    private fun findAmountSubstring(rawMessage: String, amount: BigDecimal): String? {
        val candidates = listOf(amount.toPlainString(), amount.stripTrailingZeros().toPlainString(), groupThousands(amount))
        return candidates.distinct().firstOrNull { rawMessage.contains(it) }
    }

    private fun groupThousands(amount: BigDecimal): String {
        val parts = amount.toPlainString().split(".")
        val grouped = parts[0].reversed().chunked(3).joinToString(",").reversed()
        return if (parts.size > 1) "$grouped.${parts[1]}" else grouped
    }

    private fun validate(pattern: String, rawMessage: String, amount: BigDecimal, currency: String): Boolean =
        runCatching {
            val match = Regex(pattern, RegexOption.IGNORE_CASE).find(rawMessage) ?: return false
            val matchedAmount = match.groups["amount"]?.value?.replace(",", "")?.toBigDecimalOrNull() ?: return false
            val matchedCurrency = match.groups["currency"]?.value ?: return false
            matchedAmount.compareTo(amount) == 0 && matchedCurrency.equals(currency, ignoreCase = true)
        }.getOrDefault(false)
}
