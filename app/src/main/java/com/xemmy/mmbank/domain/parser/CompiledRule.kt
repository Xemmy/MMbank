package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection

/** A parsing rule with its regexes compiled, ready for matching. */
data class CompiledRule(
    /** Room id for custom rules; null for built-ins. */
    val ruleId: Long?,
    val name: String,
    val pattern: Regex,
    val senderPattern: Regex?,
    val direction: TransactionDirection,
    val defaultCurrency: String?,
    val priority: Int,
    /** Internal-only tag; custom user rules always compile to NORMAL. */
    val category: TransactionCategory = TransactionCategory.NORMAL,
    /** Account matched transactions are assigned to; null = detect from the message. */
    val assignAccountId: Long? = null,
)

object RuleCompiler {

    const val AMOUNT_GROUP = "amount"
    const val CURRENCY_GROUP = "currency"
    const val MERCHANT_GROUP = "merchant"
    const val ACCOUNT_NUMBER_GROUP = "accountNumberSuffix"

    /**
     * Compiles a user-defined rule. Returns null when the pattern is invalid or
     * missing the mandatory `(?<amount>...)` group, so a bad custom rule can
     * never crash the capture services.
     */
    fun compile(rule: ParsingRule): CompiledRule? {
        if (!rule.pattern.contains("(?<$AMOUNT_GROUP>")) return null
        val regex = runCatching { Regex(rule.pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        val sender = rule.senderPattern
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() }
        return CompiledRule(
            ruleId = rule.id,
            name = rule.name,
            pattern = regex,
            senderPattern = sender,
            direction = rule.direction,
            defaultCurrency = rule.defaultCurrency?.takeIf { it.isNotBlank() }?.uppercase(),
            priority = rule.priority,
            assignAccountId = rule.assignAccountId,
        )
    }

    /** Human-readable validation for the rule editor UI. */
    fun validate(pattern: String): String? {
        if (pattern.isBlank()) return "Pattern is empty"
        if (!pattern.contains("(?<$AMOUNT_GROUP>")) return "Pattern must contain a named group (?<amount>...)"
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE); null }
            .getOrElse { "Invalid regex: ${it.message}" }
    }
}
