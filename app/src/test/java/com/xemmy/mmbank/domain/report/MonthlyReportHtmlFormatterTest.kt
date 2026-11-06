package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.FinancialHealthMetrics
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyReportHtmlFormatterTest {

    private fun report(
        monthLabel: String = "August 2026",
        income: String = "1000.00",
        spent: String = "400.00",
        incomeDelta: String? = null,
        spentDelta: String? = null,
        categories: List<ReportCategoryLine> = emptyList(),
        merchants: List<ReportMerchantLine> = emptyList(),
        subscriptions: List<Subscription> = emptyList(),
        goals: List<SavingsGoal> = emptyList(),
        netWorth: NetWorthAggregator.NetWorthBreakdown? = null,
        health: FinancialHealthMetrics? = null,
    ) = MonthlyReport(
        monthLabel = monthLabel,
        currency = "AED",
        income = BigDecimal(income),
        spent = BigDecimal(spent),
        net = BigDecimal(income) - BigDecimal(spent),
        incomeDelta = incomeDelta?.let(::BigDecimal),
        spentDelta = spentDelta?.let(::BigDecimal),
        transactionCount = 12,
        netWorth = netWorth,
        topCategories = categories,
        topMerchants = merchants,
        subscriptions = subscriptions,
        subscriptionMonthlyTotal = BigDecimal("40.00"),
        goals = goals,
        health = health,
    )

    @Test
    fun `renders a complete document`() {
        val html = MonthlyReportHtmlFormatter.format(report())
        assertTrue(html.startsWith("<!DOCTYPE html>"))
        assertTrue(html.trimEnd().endsWith("</html>"))
        assertTrue(html.contains("<title>August 2026 — MMbank</title>"))
    }

    @Test
    fun `headline figures appear in the output`() {
        val html = MonthlyReportHtmlFormatter.format(report())
        assertTrue(html.contains("1000.00 AED"))
        assertTrue(html.contains("400.00 AED"))
        assertTrue(html.contains("600.00 AED"))
    }

    @Test
    fun `a merchant name containing markup cannot break out of the document`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(merchants = listOf(ReportMerchantLine("<script>alert('x')</script>", BigDecimal("10.00"), 1)))
        )
        assertFalse("raw script tag must not survive", html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `a category name containing markup cannot break out either`() {
        // Regression: the category row builds its own <span> markup, so it
        // renders with rawLabel=true -- which originally meant the name itself
        // went in unescaped, while merchants were escaped correctly.
        val html = MonthlyReportHtmlFormatter.format(
            report(categories = listOf(ReportCategoryLine("<script>alert('x')</script>", BigDecimal("10.00"), 0.5)))
        )
        assertFalse("raw script tag must not survive in a category row", html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
    }

    @Test
    fun `a goal title containing markup cannot break out`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(
                goals = listOf(
                    SavingsGoal(
                        id = 1,
                        title = "<script>alert('x')</script>",
                        targetAmount = BigDecimal("100"),
                        currentAmount = BigDecimal("50"),
                    )
                )
            )
        )
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `a subscription merchant containing markup cannot break out`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(
                subscriptions = listOf(
                    Subscription(
                        id = 1,
                        merchant = "<script>alert('x')</script>",
                        averageAmount = BigDecimal("10.00"),
                        currencyCode = "AED",
                        cadence = SubscriptionCadence.MONTHLY,
                        nextDueDateMillis = 0,
                        lastSeenMillis = 0,
                        occurrenceCount = 3,
                    )
                )
            )
        )
        assertFalse(html.contains("<script>"))
    }

    @Test
    fun `ampersands in names are escaped`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(categories = listOf(ReportCategoryLine("Bed & Bath", BigDecimal("10.00"), 0.5)))
        )
        assertTrue(html.contains("Bed &amp; Bath"))
    }

    @Test
    fun `escape covers every character that can break html`() {
        assertEquals(
            "&amp;&lt;&gt;&quot;&#39;",
            MonthlyReportHtmlFormatter.escape("&<>\"'"),
        )
    }

    @Test
    fun `empty sections are omitted rather than rendered as empty headings`() {
        val html = MonthlyReportHtmlFormatter.format(report())
        assertFalse(html.contains("Where it went"))
        assertFalse(html.contains("Top merchants"))
        assertFalse(html.contains("Subscriptions"))
        assertFalse(html.contains("Savings goals"))
        assertFalse(html.contains("Net worth"))
        assertFalse(html.contains("Financial health"))
    }

    @Test
    fun `populated sections do render`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(
                categories = listOf(ReportCategoryLine("Groceries", BigDecimal("300.00"), 0.75)),
                merchants = listOf(ReportMerchantLine("Carrefour", BigDecimal("120.00"), 3)),
                netWorth = NetWorthAggregator.NetWorthBreakdown(
                    debitTotal = BigDecimal("5000"),
                    creditTotal = BigDecimal("1200"),
                    netWorth = BigDecimal("3800"),
                    perAccount = mapOf(1L to BigDecimal("5000")),
                ),
                health = FinancialHealthMetrics(
                    savingsRatePercent = 60.0,
                    cashRunwayMonths = 4.2,
                    netCashFlow = 600.0,
                    income = 1000.0,
                    expenses = 400.0,
                ),
            )
        )
        assertTrue(html.contains("Where it went"))
        assertTrue(html.contains("Groceries"))
        assertTrue(html.contains("75%"))
        assertTrue(html.contains("Carrefour"))
        assertTrue(html.contains("×3"))
        assertTrue(html.contains("3800.00 AED"))
        assertTrue(html.contains("60%"))
        assertTrue(html.contains("4.2 months"))
    }

    @Test
    fun `a spend increase reads as bad and an income increase as good`() {
        val spendUp = MonthlyReportHtmlFormatter.format(report(spentDelta = "50.00"))
        assertTrue(spendUp.contains("<div class=\"delta bad\">"))

        val incomeUp = MonthlyReportHtmlFormatter.format(report(incomeDelta = "50.00"))
        assertTrue(incomeUp.contains("<div class=\"delta good\">"))
    }

    @Test
    fun `a spend decrease reads as good`() {
        val html = MonthlyReportHtmlFormatter.format(report(spentDelta = "-50.00"))
        assertTrue(html.contains("<div class=\"delta good\">"))
        assertTrue(html.contains("▼"))
    }

    @Test
    fun `a zero delta is omitted entirely`() {
        val html = MonthlyReportHtmlFormatter.format(report(spentDelta = "0.00"))
        assertFalse(html.contains("vs last month"))
    }

    @Test
    fun `a subscription price rise is flagged in the table`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(
                subscriptions = listOf(
                    Subscription(
                        id = 1,
                        merchant = "Netflix",
                        averageAmount = BigDecimal("56.00"),
                        currencyCode = "AED",
                        cadence = SubscriptionCadence.MONTHLY,
                        nextDueDateMillis = 0,
                        lastSeenMillis = 0,
                        occurrenceCount = 4,
                        previousAmount = BigDecimal("39.99"),
                    )
                )
            )
        )
        assertTrue(html.contains("Netflix"))
        assertTrue(html.contains("↑ was 39.99 AED"))
    }

    @Test
    fun `goal progress is rendered as a percentage of its target`() {
        val html = MonthlyReportHtmlFormatter.format(
            report(
                goals = listOf(
                    SavingsGoal(
                        id = 1,
                        title = "Emergency fund",
                        targetAmount = BigDecimal("10000"),
                        currentAmount = BigDecimal("2500"),
                    )
                )
            )
        )
        assertTrue(html.contains("Emergency fund"))
        assertTrue(html.contains("25%"))
    }
}
