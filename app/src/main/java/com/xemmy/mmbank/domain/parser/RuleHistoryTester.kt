package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionSource

/**
 * "Test rule" preview for a candidate [ParsingRule] (typically one staged by
 * [com.xemmy.mmbank.domain.ai.AiSelfHealingRuleGenerator]): runs it against a
 * corpus of already-recorded raw messages through the exact same
 * [TransactionParser] path the live pipeline uses, so a reviewer can see how
 * broadly the pattern reaches before approving it -- zero hits is expected
 * for a genuinely new message shape, but a large count on an otherwise
 * narrow-looking regex is a sign it may be too permissive.
 */
object RuleHistoryTester {

    fun countMatches(rule: ParsingRule, rawMessages: List<String>, fallbackCurrency: String): Int {
        val compiled = RuleCompiler.compile(rule) ?: return 0
        val parser = TransactionParser(listOf(compiled), fallbackCurrency)
        return rawMessages.count { raw -> parser.parse(testMessage(raw)) != null }
    }

    private fun testMessage(raw: String) = CapturedMessage(
        source = TransactionSource.MANUAL,
        sender = null,
        title = null,
        body = raw,
        timestampMillis = 0L,
    )
}
