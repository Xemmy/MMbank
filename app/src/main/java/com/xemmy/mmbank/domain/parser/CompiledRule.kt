package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind

/** A parsing rule with its regexes compiled, ready for matching. */
data class CompiledRule(
    /** Room id for custom rules; null for built-ins. */
    val ruleId: Long?,
    val name: String,
    /** Regex capture pattern; null for signature-only rules. */
    val pattern: Regex?,
    val senderPattern: Regex?,
    val direction: TransactionDirection,
    val defaultCurrency: String?,
    val priority: Int,
    /** Internal-only tag; custom user rules always compile to NORMAL. */
    val category: TransactionCategory = TransactionCategory.NORMAL,
    /** Account matched transactions are assigned to; null = detect from the message. */
    val assignAccountId: Long? = null,
    /** Kind matched transactions get; null = automatic (see [ParsingRule.assignKind]). */
    val assignKind: TransactionKind? = null,
    /**
     * Plain-text phrases checked with case-insensitive containment against the
     * normalized message. When one matches, the message binds to this rule's
     * direction/account immediately — no regex needed — and the amount/merchant
     * come from [GenericExtractors]. See [TransactionParser] for the collision
     * and extraction-failure rules.
     */
    val signatures: List<String> = emptyList(),
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
        // An invalid sender regex must disable the rule, not silently widen it
        // to every sender.
        val senderText = rule.senderPattern?.takeIf { it.isNotBlank() }
        val sender = senderText?.let {
            runCatching { Regex(it, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null
        }
        return CompiledRule(
            ruleId = rule.id,
            name = rule.name,
            pattern = regex,
            senderPattern = sender,
            direction = rule.direction,
            defaultCurrency = rule.defaultCurrency?.takeIf { it.isNotBlank() }?.uppercase(),
            priority = rule.priority,
            assignAccountId = rule.assignAccountId,
            assignKind = rule.assignKind,
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
