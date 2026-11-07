package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.model.Category
import java.time.LocalDate

/** How the Copilot should handle one question. */
sealed interface CopilotDecision {
    /** Understood on the phone: answer with [CopilotAnswerEngine]. */
    data class Local(val query: CopilotQuery) : CopilotDecision

    /** Not understood, but an AI provider is set up: ask it, with numbers for [range]. */
    data class AskAi(val range: CopilotRange) : CopilotDecision

    /** Not understood and no AI: explain what can be asked. */
    data object Help : CopilotDecision
}

/**
 * Routes a Copilot question. On-phone answers always win, even with AI set
 * up: they're exact, instant and private. Only questions the parser can't
 * read go to the model -- with aggregates for the range the question named,
 * not always "this month".
 */
object CopilotResponder {

    fun decide(
        question: String,
        categories: List<Category>,
        merchants: Collection<String>,
        today: LocalDate,
        aiConfigured: Boolean,
    ): CopilotDecision {
        CopilotQueryParser.parse(question, categories, merchants, today)?.let { return CopilotDecision.Local(it) }
        if (!aiConfigured) return CopilotDecision.Help
        return CopilotDecision.AskAi(
            CopilotQueryParser.parseRange(question, today) ?: CopilotQueryParser.defaultRange(today),
        )
    }

    fun helpText(aiConfigured: Boolean): String = buildString {
        append("I didn't catch that. On this phone I can answer things like: ")
        append("how much you spent (on a category, at a merchant), what you received, ")
        append("your top categories or merchants, your biggest expense, how a period compares with the one before, ")
        append("your savings rate, how many purchases, or your daily average, ")
        append("for today, this or last week, month or year, a month like \"October\", or \"the last 30 days\".")
        if (!aiConfigured) append(" Set up AI in Settings > Local AI parsing for open-ended questions.")
    }
}
