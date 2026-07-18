package com.xemmy.mmbank.domain.ai

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.RuleCompiler
import com.xemmy.mmbank.domain.parser.TextNormalizer
import com.xemmy.mmbank.domain.parser.groupOrNull

/**
 * The self-healing half of Gate 2: turns one model-parsed message into a
 * reusable [ParsingRule] so Gate 1 handles that message shape deterministically
 * next time (no model run, no battery).
 *
 * The message's literal text becomes the pattern skeleton; the located
 * amount/currency/merchant occurrences become named capture groups, every
 * other digit run (balances, dates, reference codes) is generalized, and the
 * candidate is validated in an isolated trial — it must re-parse the source
 * message to exactly the model's own values, or it is discarded. A discarded
 * candidate is a non-event: the transaction is already recorded either way.
 */
object RegexSynthesizer {

    const val LEARNED_RULE_PREFIX = "AI learned: "

    /** Below hand-written custom rules (100), above built-ins (0). */
    const val LEARNED_RULE_PRIORITY = 60

    private const val MAX_PATTERN_CHARS = 800
    private const val AMOUNT_GROUP_PATTERN = """(?<amount>\d[\d,]*(?:\.\d+)?)"""
    private const val CURRENCY_GROUP_PATTERN = """(?<currency>[A-Za-z]{3,4})"""

    private val NUMBER_TOKEN = Regex("""\d[\d,]*(?:\.\d+)?""")
    private val REGEX_METACHARS = setOf('\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}')

    private data class Span(val range: IntRange, val replacement: String)

    fun synthesize(
        message: CapturedMessage,
        extraction: AiExtraction.Transaction,
        direction: TransactionDirection,
    ): ParsingRule? = runCatching { build(message, extraction, direction) }.getOrNull()

    private fun build(
        message: CapturedMessage,
        extraction: AiExtraction.Transaction,
        direction: TransactionDirection,
    ): ParsingRule? {
        // Same normalization + title/body join the parser itself matches against.
        val text = TextNormalizer.normalize(
            listOfNotNull(message.title, message.body).filter { it.isNotBlank() }.joinToString(" ")
        )
        if (text.isBlank()) return null

        // The model's amount must literally occur in the text, else there is
        // nothing to anchor a capture group to.
        val amountRange = NUMBER_TOKEN.findAll(text).firstOrNull { m ->
            m.value.replace(",", "").toBigDecimalOrNull()?.compareTo(extraction.amount) == 0
        }?.range ?: return null

        val spans = mutableListOf(Span(amountRange, AMOUNT_GROUP_PATTERN))

        // A standalone occurrence of the currency code becomes a group; a
        // symbol-only message ("$46.12") keeps the code as defaultCurrency.
        extraction.currency?.takeIf { c -> c.length in 3..4 && c.all { it.isLetter() } }?.let { cur ->
            Regex("(?<![A-Za-z])${Regex.escape(cur)}(?![A-Za-z])", RegexOption.IGNORE_CASE)
                .findAll(text)
                .firstOrNull { !it.range.overlaps(amountRange) }
                ?.let { spans += Span(it.range, CURRENCY_GROUP_PATTERN) }
        }

        val merchantSpan = extraction.merchant?.takeIf { it.length >= 2 }?.let { merchant ->
            val idx = text.indexOf(merchant, ignoreCase = true)
            if (idx < 0) return@let null
            val range = idx..(idx + merchant.length - 1)
            if (spans.any { it.range.overlaps(range) }) return@let null
            // Lazy when a literal anchor follows; greedy when the merchant
            // closes the message and nothing bounds it.
            val group = if (range.last == text.lastIndex) "(?<merchant>.{2,60})" else "(?<merchant>.{2,60}?)"
            Span(range, group)
        }

        // Trial with the merchant group first, then without it: a merchant
        // that can't round-trip must not sink the amount/currency learning.
        val candidates = listOfNotNull(merchantSpan?.let { spans + it }, spans)
        for (candidate in candidates) {
            val pattern = assemble(text, candidate)
            if (pattern.length > MAX_PATTERN_CHARS) continue
            val expectMerchant = candidate.any { it.replacement.startsWith("(?<merchant>") }
            if (!validates(pattern, text, extraction, expectMerchant)) continue
            return ParsingRule(
                name = ruleName(message, extraction),
                pattern = pattern,
                senderPattern = message.sender?.takeIf { it.isNotBlank() }?.let { Regex.escape(it) },
                direction = direction,
                defaultCurrency = extraction.currency,
                priority = LEARNED_RULE_PRIORITY,
                sampleText = text,
            )
        }
        return null
    }

    private fun assemble(text: String, spans: List<Span>): String {
        val ordered = spans.sortedBy { it.range.first }
        val sb = StringBuilder()
        var pos = 0
        for (span in ordered) {
            sb.append(literalToPattern(text.substring(pos, span.range.first)))
            sb.append(span.replacement)
            pos = span.range.last + 1
        }
        sb.append(literalToPattern(text.substring(pos)))
        return sb.toString()
    }

    /**
     * Escapes a literal text segment into pattern form while generalizing the
     * parts that change between messages of the same shape: whitespace runs
     * become \s+ and digit runs (balances, dates, refs) become \d[\d,.]*.
     */
    private fun literalToPattern(segment: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            when {
                c.isWhitespace() -> {
                    sb.append("""\s+""")
                    while (i < segment.length && segment[i].isWhitespace()) i++
                }
                c.isDigit() -> {
                    sb.append("""\d[\d,.]*""")
                    while (i < segment.length && (segment[i].isDigit() || segment[i] == ',' || segment[i] == '.')) i++
                }
                else -> {
                    if (c in REGEX_METACHARS) sb.append('\\')
                    sb.append(c)
                    i++
                }
            }
        }
        return sb.toString()
    }

    /** The isolated trial: compile, re-match the source, demand exact round-trip. */
    private fun validates(
        pattern: String,
        text: String,
        extraction: AiExtraction.Transaction,
        expectMerchant: Boolean,
    ): Boolean = runCatching {
        if (RuleCompiler.validate(pattern) != null) return@runCatching false
        val match = Regex(pattern, RegexOption.IGNORE_CASE).find(text) ?: return@runCatching false
        val amount = match.groupOrNull(RuleCompiler.AMOUNT_GROUP)
            ?.replace(",", "")?.toBigDecimalOrNull() ?: return@runCatching false
        if (amount.compareTo(extraction.amount) != 0) return@runCatching false
        if (expectMerchant) {
            val merchant = match.groupOrNull(RuleCompiler.MERCHANT_GROUP)?.trim() ?: return@runCatching false
            if (!merchant.equals(extraction.merchant?.trim(), ignoreCase = true)) return@runCatching false
        }
        true
    }.getOrDefault(false)

    private fun ruleName(message: CapturedMessage, extraction: AiExtraction.Transaction): String {
        val label = extraction.merchant ?: message.sender ?: "message"
        // Short stable suffix so two learned shapes from one sender stay distinct.
        val suffix = Integer.toHexString(message.body.hashCode()).takeLast(6)
        return "$LEARNED_RULE_PREFIX$label ($suffix)"
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last
}
