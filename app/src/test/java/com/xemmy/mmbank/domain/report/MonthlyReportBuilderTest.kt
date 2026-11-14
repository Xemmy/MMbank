package com.xemmy.mmbank.domain.report

import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlyReportBuilderTest {

    private fun tx(
        amount: String,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        merchant: String? = "Merchant",
        categoryId: Long? = null,
        currencyCode: String = "AED",
        excludeFromStats: Boolean = false,
    ) = Transaction(
        amount = BigDecimal(amount),
        currencyCode = currencyCode,
        direction = direction,
        merchant = merchant,
        source = TransactionSource.SMS,
        timestampMillis = 1_700_000_000_000L,
        excludeFromStats = excludeFromStats,
        categoryId = categoryId,
    )

    private fun build(
        month: List<Transaction>,
        previous: List<Transaction> = emptyList(),
        categoryNames: Map<Long, String> = emptyMap(),
        accountBalances: List<NetWorthAggregator.AccountBalance> = emptyList(),
        subscriptions: List<Subscription> = emptyList(),
        goals: List<SavingsGoal> = emptyList(),
    ) = MonthlyReportBuilder.build(
        monthLabel = "August 2026",
        currency = "AED",
        monthTransactions = month,
        previousMonthTransactions = previous,
        categoryNames = categoryNames,
        accountBalances = accountBalances,
        subscriptions = subscriptions,
        goals = goals,
    )

    private fun subscription(
        merchant: String,
        amount: String,
        cadence: SubscriptionCadence = SubscriptionCadence.MONTHLY,
        dismissed: Boolean = false,
        currencyCode: String = "AED",
    ) = Subscription(
        merchant = merchant,
        averageAmount = BigDecimal(amount),
        currencyCode = currencyCode,
        cadence = cadence,
        nextDueDateMillis = 1_700_000_000_000L,
        lastSeenMillis = 1_697_000_000_000L,
        occurrenceCount = 3,
        dismissed = dismissed,
    )

    @Test
    fun `headline figures are income, spend and net`() {
        val report = build(
            listOf(
                tx("1000.00", direction = TransactionDirection.CREDIT),
                tx("300.00"),
                tx("200.00"),
            )
        )
        assertEquals(0, report.income.compareTo(BigDecimal("1000.00")))
        assertEquals(0, report.spent.compareTo(BigDecimal("500.00")))
        assertEquals(0, report.net.compareTo(BigDecimal("500.00")))
        assertEquals(3, report.transactionCount)
    }

    @Test
    fun `analytics-excluded rows stay out of the statement`() {
        val report = build(listOf(tx("100.00"), tx("9999.00", excludeFromStats = true)))
        assertEquals(0, report.spent.compareTo(BigDecimal("100.00")))
        assertEquals(1, report.transactionCount)
    }

    @Test
    fun `a foreign currency is left out rather than summed in`() {
        val report = build(listOf(tx("100.00"), tx("500.00", currencyCode = "USD")))
        assertEquals(0, report.spent.compareTo(BigDecimal("100.00")))
    }

    @Test
    fun `deltas compare against the previous month`() {
        val report = build(
            month = listOf(tx("500.00"), tx("1000.00", direction = TransactionDirection.CREDIT)),
            previous = listOf(tx("300.00"), tx("800.00", direction = TransactionDirection.CREDIT)),
        )
        assertEquals(0, report.spentDelta!!.compareTo(BigDecimal("200.00")))
        assertEquals(0, report.incomeDelta!!.compareTo(BigDecimal("200.00")))
    }

    @Test
    fun `no previous month means no delta rather than a misleading zero`() {
        val report = build(listOf(tx("500.00")))
        assertNull(report.spentDelta)
        assertNull(report.incomeDelta)
    }

    @Test
    fun `an empty month produces a report rather than crashing`() {
        val report = build(emptyList())
        assertEquals(0, report.income.compareTo(BigDecimal.ZERO))
        assertEquals(0, report.spent.compareTo(BigDecimal.ZERO))
        assertEquals(0, report.transactionCount)
        assertTrue(report.topCategories.isEmpty())
        assertTrue(report.topMerchants.isEmpty())
        assertNull(report.netWorth)
    }

    @Test
    fun `categories are named, ranked and given a share of spend`() {
        val report = build(
            month = listOf(
                tx("300.00", categoryId = 1L),
                tx("100.00", categoryId = 2L),
            ),
            categoryNames = mapOf(1L to "Groceries", 2L to "Dining"),
        )
        assertEquals(listOf("Groceries", "Dining"), report.topCategories.map { it.name })
        assertEquals(0.75, report.topCategories.first().shareOfSpend, 0.001)
    }

    @Test
    fun `uncategorized spend is shown, not silently dropped`() {
        val report = build(
            month = listOf(tx("300.00", categoryId = 1L), tx("100.00", categoryId = null)),
            categoryNames = mapOf(1L to "Groceries"),
        )
        assertTrue(report.topCategories.any { it.name == "Uncategorized" })
        val total = report.topCategories.fold(BigDecimal.ZERO) { acc, line -> acc + line.amount }
        assertEquals("category rows must add up to the headline", 0, total.compareTo(report.spent))
    }

    @Test
    fun `merchants are ranked by spend and carry a transaction count`() {
        val report = build(
            listOf(
                tx("50.00", merchant = "Carrefour"),
                tx("70.00", merchant = "Carrefour"),
                tx("90.00", merchant = "Talabat"),
            )
        )
        assertEquals("Carrefour", report.topMerchants.first().name)
        assertEquals(2, report.topMerchants.first().transactionCount)
    }

    @Test
    fun `net worth wires up the aggregator when balances are supplied`() {
        val report = build(
            month = emptyList(),
            accountBalances = listOf(
                NetWorthAggregator.AccountBalance(1L, AccountType.CHECKING, BigDecimal("5000")),
                NetWorthAggregator.AccountBalance(2L, AccountType.CREDIT_CARD, BigDecimal("-1200")),
            ),
        )
        val netWorth = report.netWorth!!
        assertEquals(0, netWorth.debitTotal.compareTo(BigDecimal("5000")))
        assertEquals(0, netWorth.creditTotal.compareTo(BigDecimal("1200")))
        assertEquals(0, netWorth.netWorth.compareTo(BigDecimal("3800")))
    }

    @Test
    fun `a weekly subscription is normalized to a monthly equivalent`() {
        val report = build(
            month = emptyList(),
            subscriptions = listOf(subscription("Gym", "20.00", SubscriptionCadence.WEEKLY)),
        )
        // 20 * 30/7 = 85.71
        assertEquals(0, report.subscriptionMonthlyTotal.compareTo(BigDecimal("85.71")))
    }

    @Test
    fun `dismissed subscriptions are excluded from the list and the total`() {
        val report = build(
            month = emptyList(),
            subscriptions = listOf(
                subscription("Netflix", "40.00"),
                subscription("Cancelled", "999.00", dismissed = true),
            ),
        )
        assertEquals(listOf("Netflix"), report.subscriptions.map { it.merchant })
        assertEquals(0, report.subscriptionMonthlyTotal.compareTo(BigDecimal("40.00")))
    }

    @Test
    fun `a subscription in another currency is not added to the monthly total`() {
        val report = build(
            month = emptyList(),
            subscriptions = listOf(subscription("Foreign", "100.00", currencyCode = "USD")),
        )
        assertEquals(0, report.subscriptionMonthlyTotal.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a subscription in another currency is left out of the list too, not just the total`() {
        // Listing one above a total that silently omits it reads as an
        // arithmetic error; the report is scoped to a single currency
        // throughout, exactly as off-currency transactions are.
        val report = build(
            month = emptyList(),
            subscriptions = listOf(
                subscription("Local", "40.00"),
                subscription("Foreign", "100.00", currencyCode = "USD"),
            ),
        )
        assertEquals(listOf("Local"), report.subscriptions.map { it.merchant })
        assertEquals(0, report.subscriptionMonthlyTotal.compareTo(BigDecimal("40.00")))
    }

    @Test
    fun `the listed subscriptions are exactly the ones behind the monthly total`() {
        val report = build(
            month = emptyList(),
            subscriptions = listOf(
                subscription("Netflix", "40.00"),
                subscription("Foreign", "100.00", currencyCode = "USD"),
                subscription("Cancelled", "999.00", dismissed = true),
            ),
        )
        val listedTotal = report.subscriptions.fold(BigDecimal.ZERO) { acc, s -> acc + s.averageAmount }
        assertEquals(0, report.subscriptionMonthlyTotal.compareTo(listedTotal))
    }

    @Test
    fun `archived goals are left out`() {
        val report = build(
            month = emptyList(),
            goals = listOf(
                SavingsGoal(id = 1, title = "Emergency", targetAmount = BigDecimal("10000")),
                SavingsGoal(id = 2, title = "Old", targetAmount = BigDecimal("500"), archived = true),
            ),
        )
        assertEquals(listOf("Emergency"), report.goals.map { it.title })
    }

    @Test
    fun `net worth leaves out accounts in other currencies`() {
        val report = MonthlyReportBuilder.build(
            monthLabel = "Jan",
            currency = "AED",
            monthTransactions = emptyList(),
            accountBalances = listOf(
                NetWorthAggregator.AccountBalance(1L, AccountType.CHECKING, BigDecimal("5000"), "AED"),
                NetWorthAggregator.AccountBalance(2L, AccountType.CHECKING, BigDecimal("900"), "USD"),
            ),
        )
        org.junit.Assert.assertEquals(0, BigDecimal("5000").compareTo(report.netWorth!!.netWorth))
    }
}
