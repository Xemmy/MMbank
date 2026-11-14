package com.xemmy.mmbank.domain.intelligence.copilot

import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CopilotSuggestionsTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 7)

    private val categories = listOf(
        Category(id = 2, name = "Dining", icon = "", colorHex = "#000000"),
        Category(id = 3, name = "Groceries", icon = "", colorHex = "#000000"),
        Category(id = 9, name = "Uncategorized", icon = "", colorHex = "#000000"),
    )

    private fun spend(amount: String, day: LocalDate, merchant: String, categoryId: Long?) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
        categoryId = categoryId,
    )

    private val toBase: (Transaction) -> Double? = { it.amount.toDouble() }

    private fun build(rows: List<Transaction>, max: Int = 6) =
        CopilotSuggestions.build(rows, categories, toBase, today, zone, max)

    @Test
    fun `chips name the user's own top category and merchant`() {
        val rows = listOf(
            spend("200", LocalDate.of(2026, 11, 2), "Careem", 9),
            spend("180", LocalDate.of(2026, 11, 3), "Zuma", 2),
            spend("50", LocalDate.of(2026, 11, 4), "Carrefour", 3),
        )
        val chips = build(rows)
        assertTrue(chips.toString(), "How much on Dining this month?" in chips)
        assertTrue(chips.toString(), "How much at Careem this month?" in chips)
        // Uncategorized is never suggested as "your top category".
        assertFalse(chips.any { it.contains("Uncategorized") })
    }

    @Test
    fun `every chip gets an on-phone answer`() {
        val rows = listOf(spend("80", LocalDate.of(2026, 11, 2), "Careem", 2))
        val merchants = rows.mapNotNull { it.merchant }
        build(rows, max = 10).forEach { chip ->
            assertNotNull(chip, CopilotQueryParser.parse(chip, categories, merchants, today))
        }
    }

    @Test
    fun `early in a month the chips look at last month`() {
        val rows = listOf(spend("80", LocalDate.of(2026, 10, 20), "Careem", 2))
        val chips = build(rows)
        assertTrue(chips.toString(), "How much on Dining last month?" in chips)
    }

    @Test
    fun `with no history only generic chips, capped at max`() {
        val chips = build(emptyList(), max = 3)
        assertEquals(
            listOf("How much did I spend this month?", "What's my biggest expense this month?", "Compare this month with last month"),
            chips,
        )
    }
}
