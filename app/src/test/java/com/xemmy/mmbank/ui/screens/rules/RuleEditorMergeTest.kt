package com.xemmy.mmbank.ui.screens.rules

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.parser.RuleCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RuleEditorMergeTest {
    private val stored = ParsingRule(
        id = 7, name = "ai.rule", pattern = "(?<amount>\\d+)", direction = TransactionDirection.DEBIT,
        enabled = false, createdAt = 123L, isDemo = true,
    )

    @Test
    fun savingKeepsADisabledRuleDisabled() {
        val saved = RuleEditorMerge.apply(
            stored, 7, "renamed", stored.pattern, null, TransactionDirection.CREDIT, null, 50, null, null, null,
        )
        assertFalse(saved.enabled)
        assertEquals(123L, saved.createdAt)
        assertEquals(true, saved.isDemo)
        assertEquals("renamed", saved.name)
        assertEquals(TransactionDirection.CREDIT, saved.direction)
    }

    @Test
    fun newRuleStartsEnabled() {
        val saved = RuleEditorMerge.apply(
            null, 0, "n", "(?<amount>\\d+)", null, TransactionDirection.DEBIT, null, 100, null, null, null,
        )
        assertEquals(true, saved.enabled)
    }

    @Test
    fun invalidSenderRegexDisablesTheRuleInsteadOfMakingItGlobal() {
        assertNull(RuleCompiler.compile(stored.copy(senderPattern = "([unclosed")))
        assertNotNull(RuleCompiler.compile(stored.copy(senderPattern = "ADCB")))
        assertNotNull(RuleCompiler.compile(stored.copy(senderPattern = " ")))
    }
}
