package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.TransactionDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleHistoryTesterTest {

    private fun rule(pattern: String) = ParsingRule(
        name = "ai.pending.1",
        pattern = pattern,
        direction = TransactionDirection.DEBIT,
    )

    @Test
    fun `counts only the historical messages the pattern actually matches`() {
        val candidate = rule("You paid (?<amount>\\d+(?:\\.\\d+)?) (?<currency>[A-Z]{3}) at (?<merchant>[^.]+)\\.")
        val history = listOf(
            "You paid 46.12 AED at CoffeeShop.",
            "You paid 12.00 AED at Bakery.",
            "Your statement is ready.",
        )
        assertEquals(2, RuleHistoryTester.countMatches(candidate, history, fallbackCurrency = "USD"))
    }

    @Test
    fun `zero matches when nothing in history fits the shape`() {
        val candidate = rule("You paid (?<amount>\\d+(?:\\.\\d+)?) (?<currency>[A-Z]{3}) at (?<merchant>[^.]+)\\.")
        val history = listOf("A completely unrelated message", "Another one")
        assertEquals(0, RuleHistoryTester.countMatches(candidate, history, fallbackCurrency = "USD"))
    }

    @Test
    fun `an uncompilable pattern -- missing the mandatory amount group -- counts zero rather than throwing`() {
        val candidate = rule("no named groups here")
        val history = listOf("You paid 46.12 AED at CoffeeShop.")
        assertEquals(0, RuleHistoryTester.countMatches(candidate, history, fallbackCurrency = "USD"))
    }

    @Test
    fun `empty history counts zero`() {
        val candidate = rule("You paid (?<amount>\\d+(?:\\.\\d+)?) (?<currency>[A-Z]{3})\\.")
        assertEquals(0, RuleHistoryTester.countMatches(candidate, emptyList(), fallbackCurrency = "USD"))
    }
}
