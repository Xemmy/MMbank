package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsedTransaction

/**
 * The parsing engine. Two phases:
 *
 * 1. **Signature routing** — rules that registered plain-text [CompiledRule.signatures]
 *    are checked by case-insensitive containment. A hit binds the message to that
 *    rule's direction/account immediately, with amount/merchant recovered by
 *    [GenericExtractors]. Collisions resolve by the existing rule-priority
 *    convention (higher number wins), then longest matched signature (more
 *    specific beats more generic), then registration order plus a warning.
 *    If no plausible amount can be extracted the signature rule does NOT
 *    consume the message — never fabricate a guessed or zero amount — and
 *    processing falls through to phase 2. A missing merchant is non-fatal.
 *
 * 2. **Regex matching** — the classic loop: rules are tried highest-priority
 *    first; the first rule whose pattern yields a valid positive amount wins.
 */
class TransactionParser(
    rules: List<CompiledRule>,
    private val fallbackCurrency: String = DEFAULT_CURRENCY,
    /** Sink for non-fatal diagnostics (signature-collision ties). */
    private val onWarning: (String) -> Unit = { println("TransactionParser: $it") },
) {

    private val orderedRules = rules.sortedByDescending { it.priority }

    /** Signature rules in registration order, for the final collision tiebreak. */
    private val signatureRules: List<IndexedValue<CompiledRule>> =
        rules.withIndex().filter { it.value.signatures.isNotEmpty() }

    fun parse(message: CapturedMessage): ParsedTransaction? {
        val text = TextNormalizer.normalize(
            listOfNotNull(message.title, message.body)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
        if (text.isBlank()) return null

        routeBySignature(message, text)?.let { return it }

        for (rule in orderedRules) {
            val pattern = rule.pattern ?: continue // signature-only rule
            if (rule.senderPattern != null) {
                val sender = message.sender ?: continue
                if (!rule.senderPattern.containsMatchIn(sender)) continue
            }
            val match = pattern.find(text) ?: continue

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

    private data class SignatureHit(
        val rule: CompiledRule,
        /** The longest of the rule's signatures found in the text. */
        val signature: String,
        val registrationIndex: Int,
    )

    /**
     * Phase 1. Returns null when no signature matches, or when the winning
     * rule cannot recover an amount (the message then falls through to the
     * regex phase — including the winner's own regex, if it has one).
     */
    private fun routeBySignature(message: CapturedMessage, text: String): ParsedTransaction? {
        val hits = signatureRules.mapNotNull { (index, rule) ->
            if (rule.senderPattern != null) {
                val sender = message.sender ?: return@mapNotNull null
                if (!rule.senderPattern.containsMatchIn(sender)) return@mapNotNull null
            }
            rule.signatures
                .filter { it.isNotBlank() && text.contains(it, ignoreCase = true) }
                .maxByOrNull { it.length }
                ?.let { SignatureHit(rule, it, index) }
        }
        if (hits.isEmpty()) return null

        val ordered = hits.sortedWith(
            compareByDescending<SignatureHit> { it.rule.priority }
                .thenByDescending { it.signature.length }
                .thenBy { it.registrationIndex }
        )
        val winner = ordered.first()
        val runnerUp = ordered.getOrNull(1)
        if (runnerUp != null &&
            runnerUp.rule.priority == winner.rule.priority &&
            runnerUp.signature.length == winner.signature.length
        ) {
            onWarning(
                "Signature collision: '${winner.rule.name}' and '${runnerUp.rule.name}' matched " +
                    "with equal priority and signature length; keeping first-registered '${winner.rule.name}'."
            )
        }

        // Every signature rule shares the same generic extractor, so if the
        // winner cannot recover an amount neither can the rest: bail once.
        val extracted = GenericExtractors.extractAmount(text) ?: return null

        return ParsedTransaction(
            amount = extracted.amount,
            currencyCode = extracted.currencyCode
                ?: winner.rule.defaultCurrency
                ?: fallbackCurrency,
            direction = winner.rule.direction,
            merchant = GenericExtractors.extractMerchant(text),
            matchedRuleName = winner.rule.name,
            category = winner.rule.category,
            assignAccountId = winner.rule.assignAccountId,
        )
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
