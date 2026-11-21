package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancialCopilotQueryHandlerTest {

    private val zone = ZoneId.of("UTC")

    private fun millisOn(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun tx(
        amount: String,
        date: LocalDate,
        direction: TransactionDirection,
        merchant: String? = null,
        category: SpendingCategory = SpendingCategory.UNCATEGORIZED,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = merchant,
        spendingCategory = category,
        source = TransactionSource.SMS,
        timestampMillis = millisOn(date),
        excludeFromStats = excludeFromStats,
    )

    private val identityToBase: (Transaction) -> Double? = { it.amount.toDouble() }

    @Test
    fun `aggregates income and expenses within the requested range`() {
        val transactions = listOf(
            tx("5000.00", LocalDate.of(2026, 7, 1), TransactionDirection.CREDIT),
            tx("50.00", LocalDate.of(2026, 7, 5), TransactionDirection.DEBIT, merchant = "Uber", category = SpendingCategory.TRANSPORT),
            tx("30.00", LocalDate.of(2026, 7, 6), TransactionDirection.DEBIT, merchant = "Uber", category = SpendingCategory.TRANSPORT),
            tx("100.00", LocalDate.of(2026, 7, 10), TransactionDirection.DEBIT, merchant = "Carrefour", category = SpendingCategory.GROCERIES),
        )
        val context = FinancialCopilotQueryHandler.buildContext(
            transactions = transactions,
            toBase = identityToBase,
            start = LocalDate.of(2026, 7, 1),
            end = LocalDate.of(2026, 7, 31),
            currencyCode = "aed",
            rangeLabel = "This month",
            zone = zone,
        )

        assertEquals("This month", context.rangeLabel)
        assertEquals("AED", context.currencyCode)
        assertEquals(5000.0, context.totalIncome, 0.001)
        assertEquals(180.0, context.totalExpenses, 0.001)
        assertEquals(80.0, context.byMerchant["Uber"]!!, 0.001)
        assertEquals(100.0, context.byMerchant["Carrefour"]!!, 0.001)
        assertEquals(80.0, context.byCategory[SpendingCategory.TRANSPORT.label]!!, 0.001)
        assertEquals(100.0, context.byCategory[SpendingCategory.GROCERIES.label]!!, 0.001)
    }

    @Test
    fun `transactions outside the requested range are skipped`() {
        val transactions = listOf(
            tx("100.00", LocalDate.of(2026, 6, 30), TransactionDirection.DEBIT, merchant = "Late June"),
            tx("100.00", LocalDate.of(2026, 8, 1), TransactionDirection.DEBIT, merchant = "Early August"),
            tx("50.00", LocalDate.of(2026, 7, 15), TransactionDirection.DEBIT, merchant = "In Range"),
        )
        val context = FinancialCopilotQueryHandler.buildContext(
            transactions = transactions,
            toBase = identityToBase,
            start = LocalDate.of(2026, 7, 1),
            end = LocalDate.of(2026, 7, 31),
            currencyCode = "AED",
            rangeLabel = "July",
            zone = zone,
        )
        assertEquals(50.0, context.totalExpenses, 0.001)
        assertEquals(1, context.byMerchant.size)
        assertTrue(context.byMerchant.containsKey("In Range"))
    }

    @Test
    fun `excluded transactions are never counted`() {
        val transactions = listOf(
            tx("500.00", LocalDate.of(2026, 7, 5), TransactionDirection.DEBIT, merchant = "Adjustment", excludeFromStats = true),
        )
        val context = FinancialCopilotQueryHandler.buildContext(
            transactions = transactions,
            toBase = identityToBase,
            start = LocalDate.of(2026, 7, 1),
            end = LocalDate.of(2026, 7, 31),
            currencyCode = "AED",
            rangeLabel = "July",
            zone = zone,
        )
        assertEquals(0.0, context.totalExpenses, 0.001)
        assertTrue(context.byMerchant.isEmpty())
    }

    @Test
    fun `unmerchanted debits roll up under Unknown`() {
        val transactions = listOf(
            tx("20.00", LocalDate.of(2026, 7, 5), TransactionDirection.DEBIT, merchant = null),
        )
        val context = FinancialCopilotQueryHandler.buildContext(
            transactions = transactions,
            toBase = identityToBase,
            start = LocalDate.of(2026, 7, 1),
            end = LocalDate.of(2026, 7, 31),
            currencyCode = "AED",
            rangeLabel = "July",
            zone = zone,
        )
        assertEquals(20.0, context.byMerchant["Unknown"]!!, 0.001)
    }

    @Test
    fun `prompt includes the question, totals and category breakdown`() {
        val context = FinancialCopilotQueryHandler.QueryContext(
            rangeLabel = "This month",
            currencyCode = "AED",
            totalIncome = 5000.0,
            totalExpenses = 250.0,
            byCategory = linkedMapOf(SpendingCategory.TRANSPORT.label to 80.0),
            byMerchant = linkedMapOf("Uber" to 80.0),
        )
        val prompt = FinancialCopilotQueryHandler.buildPrompt("How much did I spend on Uber this month?", context)

        assertTrue(prompt.contains("How much did I spend on Uber this month?"))
        assertTrue(prompt.contains("This month"))
        assertTrue(prompt.contains("AED 5000.00"))
        assertTrue(prompt.contains("AED 250.00"))
        assertTrue(prompt.contains("Transport"))
        assertTrue(prompt.contains("Uber"))
        assertTrue(prompt.contains("AED 80.00"))
        // Never leaks raw ledger text -- only the aggregated context and the question.
        assertFalse(prompt.contains("rawMessage"))
    }

    @Test
    fun `prompt omits category and merchant sections when there is nothing to spend`() {
        val context = FinancialCopilotQueryHandler.QueryContext(
            rangeLabel = "This month",
            currencyCode = "AED",
            totalIncome = 0.0,
            totalExpenses = 0.0,
            byCategory = emptyMap(),
            byMerchant = emptyMap(),
        )
        val prompt = FinancialCopilotQueryHandler.buildPrompt("What did I spend?", context)
        assertFalse(prompt.contains("Spend by category"))
        assertFalse(prompt.contains("Spend by merchant"))
    }
}
