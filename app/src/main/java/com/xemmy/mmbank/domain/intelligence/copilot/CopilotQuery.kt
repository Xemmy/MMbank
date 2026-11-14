package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.filter.DateWindow

/** What a Copilot question asks for. Answered on the phone by [CopilotAnswerEngine]. */
enum class CopilotIntent {
    /** "How much did I spend (on X / at Y)?" */
    SPEND,

    /** "How much did I receive / earn?" */
    INCOME,

    /** "What do I spend the most on?" */
    TOP_CATEGORIES,

    /** "Where do I spend the most?" */
    TOP_MERCHANTS,

    /** "What was my biggest expense?" */
    BIGGEST_EXPENSE,

    /** "Compare with last month" -- the window against the one just before it. */
    COMPARE,

    /** "What's my savings rate?" */
    SAVINGS_RATE,

    /** "How many purchases (at Y)?" */
    TRANSACTION_COUNT,

    /** "How much do I spend a day?" */
    AVERAGE_PER_DAY,
}

/** What a question narrows to: one of the user's own categories, or a merchant. */
sealed interface CopilotSubject {
    /** Display name used in answers. */
    val label: String

    /**
     * [categoryIds] is the category plus every sub-category beneath it, so
     * "Food" also counts what was filed under "Food > Coffee".
     */
    data class CategorySubject(
        val categoryId: Long,
        override val label: String,
        val categoryIds: Set<Long>,
    ) : CopilotSubject

    /**
     * [phrase] is what matched in the question ("careem"); [key] is
     * [com.xemmy.mmbank.domain.parser.MerchantNormalizer.key] of the matched
     * merchant. A transaction belongs to the subject when its merchant has
     * the same key or contains the phrase.
     */
    data class MerchantSubject(
        override val label: String,
        val phrase: String,
        val key: String,
    ) : CopilotSubject
}

/** A parsed question: what, over which days, about what. */
data class CopilotQuery(
    val intent: CopilotIntent,
    val window: DateWindow,
    /** Human label for [window], e.g. "this month", "October 2026", "the last 7 days". */
    val rangeLabel: String,
    val subject: CopilotSubject? = null,
)

/** A time range found in a question. */
data class CopilotRange(val window: DateWindow, val label: String)
