package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.TransactionDirection

/**
 * Says *why* a message that looks financial produced no transaction (backlog
 * B.3 #13), so a miss reads as "THB isn't a currency I know yet" instead of a
 * bare "no match". Pure and best-effort: it re-runs the same cheap checks the
 * parser's guards use on the text, and never decides anything -- the parser's
 * verdict stands; this only describes it.
 *
 * Reasons, most specific first:
 *  - [Reason.NOT_A_PAYMENT]: declined / blocked / one-time-code wording, which
 *    built-in rules skip on purpose.
 *  - [Reason.UNKNOWN_CURRENCY]: a `ABC 1,500.00` shape whose code isn't one
 *    the extractor knows.
 *  - [Reason.NO_AMOUNT]: no amount sits next to a currency.
 *  - [Reason.NO_RULE_MATCHES]: a clear amount, but no rule's wording fits --
 *    the case worth turning into a rule.
 */
object UnmatchedExplainer {

    enum class Reason { NOT_A_PAYMENT, UNKNOWN_CURRENCY, NO_AMOUNT, NO_RULE_MATCHES }

    data class Explanation(val reason: Reason, val headline: String)

    private val KNOWN_CURRENCY = Regex("^(?:${GenericExtractors.CUR})$", RegexOption.IGNORE_CASE)

    /** `THB 1,500.00` / `THB1500` -- an upper-case 3-letter token glued to a number. */
    private val CODE_THEN_NUMBER = Regex(
        "(?<![A-Za-z])([A-Z]{3})\\s?(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)(?![\\d.]*[A-Za-z])",
    )

    /** Null when there is nothing to explain (blank text). */
    fun explain(title: String?, body: String): Explanation? {
        val text = TextNormalizer.normalize(listOfNotNull(title, body).filter { it.isNotBlank() }.joinToString(" "))
        if (text.isBlank()) return null

        if (ParseGuards.isNonTransaction(text)) {
            return Explanation(
                Reason.NOT_A_PAYMENT,
                "reads like a declined payment, block notice or one-time code, which built-in rules skip on purpose",
            )
        }

        val amount = GenericExtractors.extractAmount(text)
        if (amount == null) {
            val unknown = CODE_THEN_NUMBER.findAll(text).firstOrNull { !KNOWN_CURRENCY.matches(it.groupValues[1]) }
            if (unknown != null) {
                return Explanation(
                    Reason.UNKNOWN_CURRENCY,
                    "found \"${unknown.value.trim()}\" but ${unknown.groupValues[1]} is not a currency MMbank reads yet",
                )
            }
            return Explanation(Reason.NO_AMOUNT, "no amount next to a currency was found")
        }

        val shown = (amount.currencyCode?.let { "$it " } ?: "") + amount.amount.toPlainString()
        val cue = when (GenericExtractors.detectDirectionOverride(text)) {
            TransactionDirection.DEBIT -> ", and it reads like money going out"
            TransactionDirection.CREDIT -> ", and it reads like money coming in"
            null -> ""
        }
        return Explanation(
            Reason.NO_RULE_MATCHES,
            "found $shown but no rule's wording matches this message$cue -- create a rule from it",
        )
    }
}
