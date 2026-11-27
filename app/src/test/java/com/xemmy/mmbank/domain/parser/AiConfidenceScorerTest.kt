package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.SpendingCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AiConfidenceScorerTest {

    @Test
    fun `a confirmed merchant mapping wins regardless of other signals`() {
        val score = AiConfidenceScorer.score(
            hasConfirmedCategory = true,
            hasPendingSuggestion = true,
            spendingCategory = SpendingCategory.GROCERIES,
        )
        assertEquals(AiConfidenceScorer.CONFIRMED_MAPPING, score)
    }

    @Test
    fun `a pending AI suggestion outranks a keyword-only match`() {
        val score = AiConfidenceScorer.score(
            hasConfirmedCategory = false,
            hasPendingSuggestion = true,
            spendingCategory = SpendingCategory.DINING,
        )
        assertEquals(AiConfidenceScorer.PENDING_SUGGESTION, score)
    }

    @Test
    fun `a keyword hit with no confirmed or pending category scores medium-high`() {
        val score = AiConfidenceScorer.score(
            hasConfirmedCategory = false,
            hasPendingSuggestion = false,
            spendingCategory = SpendingCategory.FUEL,
        )
        assertEquals(AiConfidenceScorer.KEYWORD_MATCH, score)
    }

    @Test
    fun `no signal at all scores the lowest bucket`() {
        val score = AiConfidenceScorer.score(
            hasConfirmedCategory = false,
            hasPendingSuggestion = false,
            spendingCategory = SpendingCategory.UNCATEGORIZED,
        )
        assertEquals(AiConfidenceScorer.NO_SIGNAL, score)
    }
}
