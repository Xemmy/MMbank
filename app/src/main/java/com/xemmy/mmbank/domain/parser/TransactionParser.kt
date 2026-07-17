package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsedTransaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.util.LogRedactor

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
 *
 * 3. **Card-ending routing** — a last resort for keyword-less, signature-less
 *    merchant notifications ("AED27.60 with Tabby Cash Card ••4543"): a card
 *    ending trusted by [CardEndingRegistry] binds the message to that card's
 *    direction, with amount/merchant recovered by [GenericExtractors] and the
 *    ending copied onto [ParsedTransaction.accountNumberSuffix] so the existing
 *    suffix→account lookup assigns the account. Runs *after* the regex loop so
 *    it never preempts a custom rule's account pinning, and — like signature
 *    routing — never fabricates an amount.
 */
class TransactionParser(
    rules: List<CompiledRule>,
    private val fallbackCurrency: String = DEFAULT_CURRENCY,
    /** Sink for non-fatal diagnostics (signature-collision ties). Redacted: rule names can echo message fragments. */
    private val onWarning: (String) -> Unit = { println("TransactionParser: ${LogRedactor.redactSensitiveData(it)}") },
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

            // A keyword rule can match on the wrong side of a mixed message: the
            // Binance Pay "You have received a payment of 710 USDT" notification
            // trips the DEBIT rule because "payment of" is a debit keyword, even
            // though "received" makes it money IN. When an explicit incoming-money
            // word (received/incoming/deposited) is the earliest directional cue,
            // promote a DEBIT-ruled match to CREDIT. This is deliberately one-way:
            // the DEBIT side of the override ("paid") fires on unrelated clauses
            // like "...processed after next statement is paid" in cashback credits,
            // so we never demote a CREDIT rule here — only rescue a mislabeled credit.
            val direction =
                if (rule.direction == TransactionDirection.DEBIT &&
                    GenericExtractors.detectDirectionOverride(text) == TransactionDirection.CREDIT
                ) {
                    TransactionDirection.CREDIT
                } else {
                    rule.direction
                }

            return ParsedTransaction(
                amount = amount,
                currencyCode = currency,
                direction = direction,
                merchant = merchant,
                matchedRuleName = rule.name,
                accountNumberSuffix = accountNumberSuffix,
                category = rule.category,
                assignAccountId = rule.assignAccountId,
            )
        }

        return routeByCardEnding(text)
    }

    /**
     * Phase 3. Returns null unless the text carries a card ending
     * [CardEndingRegistry] trusts *and* a plausible amount can be recovered —
     * an unknown ending or a missing amount leaves the message unparsed rather
     * than guessing.
     */
    private fun routeByCardEnding(text: String): ParsedTransaction? {
        val ending = CardEndingExtractor.extract(text) ?: return null
        val route = CardEndingRegistry.lookup(ending) ?: return null

        val extracted = GenericExtractors.extractAmount(text) ?: return null

        return ParsedTransaction(
            amount = extracted.amount,
            currencyCode = extracted.currencyCode
                ?: route.defaultCurrency
                ?: fallbackCurrency,
            direction = route.direction,
            merchant = GenericExtractors.extractMerchant(text),
            matchedRuleName = route.label,
            accountNumberSuffix = ending,
            category = route.category,
        )
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

        val title = message.title?.let(TextNormalizer::normalize)?.takeIf { it.isNotBlank() }
        // A signature rule's direction is a single static default (e.g.
        // "Binance Mastercard" -> DEBIT); explicit wording in the message
        // overrides it so a "received"/"incoming"/"deposited" notification
        // still credits the account even under a spend-flavored signature.
        val direction = GenericExtractors.detectDirectionOverride(text) ?: winner.rule.direction
        return ParsedTransaction(
            amount = extracted.amount,
            currencyCode = extracted.currencyCode
                ?: winner.rule.defaultCurrency
                ?: fallbackCurrency,
            direction = direction,
            merchant = GenericExtractors.extractMerchant(text, title = title),
            matchedRuleName = winner.rule.name,
            accountNumberSuffix = GenericExtractors.extractAccountSuffix(text),
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
