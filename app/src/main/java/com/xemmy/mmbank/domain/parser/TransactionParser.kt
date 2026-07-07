package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsedTransaction

/**
 * The regex parsing engine. Rules are tried highest-priority first; the first
 * rule that yields a valid positive amount wins.
 */
class TransactionParser(
    rules: List<CompiledRule>,
    private val fallbackCurrency: String = DEFAULT_CURRENCY,
) {

    private val orderedRules = rules.sortedByDescending { it.priority }

    fun parse(message: CapturedMessage): ParsedTransaction? {
        val text = TextNormalizer.normalize(
            listOfNotNull(message.title, message.body)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        if (text.isBlank()) return null

        for (rule in orderedRules) {
            if (rule.senderPattern != null) {
                val sender = message.sender ?: continue
                if (!rule.senderPattern.containsMatchIn(sender)) continue
            }
            val match = rule.pattern.find(text) ?: continue

            val amountRaw = match.groupOrNull(RuleCompiler.AMOUNT_GROUP) ?: continue
            val amount = amountRaw.replace(",", "").toBigDecimalOrNull() ?: continue
            if (amount.signum() <= 0) continue

            val currency = match.groupOrNull(RuleCompiler.CURRENCY_GROUP)
                ?.let(CurrencyNormalizer::normalize)
                ?: rule.defaultCurrency
                ?: fallbackCurrency

            val merchant = match.groupOrNull(RuleCompiler.MERCHANT_GROUP)
                ?.trim()
                ?.trimEnd('.', ',', '-', ':')
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            val accountNumberSuffix = match.groupOrNull(RuleCompiler.ACCOUNT_NUMBER_GROUP)
                ?.trim()
                ?.takeIf { it.isNotBlank() }

            return ParsedTransaction(
                amount = amount,
                currencyCode = currency,
                direction = rule.direction,
                merchant = merchant,
                matchedRuleName = rule.name,
                accountNumberSuffix = accountNumberSuffix,
                category = rule.category,
                assignAccountId = rule.assignAccountId,
            )
        }
        return null
    }

    companion object {
        const val DEFAULT_CURRENCY = "USD"
    }
}

/** Safe named-group access: returns null when the group doesn't exist in the pattern. */
fun MatchResult.groupOrNull(name: String): String? = try {
    (groups as? MatchNamedGroupCollection)?.get(name)?.value
} catch (_: IllegalArgumentException) {
    null
}
