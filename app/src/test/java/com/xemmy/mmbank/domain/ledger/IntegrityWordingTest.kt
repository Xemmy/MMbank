package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Issue
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.LinkProblem
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.SetupItem
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Type
import com.xemmy.mmbank.domain.model.TransactionKind
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityWordingTest {

    private val money = { v: BigDecimal, c: String -> "$c ${v.toPlainString()}" }
    private val date = { _: Long -> "12 Oct" }

    private fun describe(issue: Issue, account: String? = "Current") =
        IntegrityWording.describe(issue, account, money, date)

    @Test
    fun `drift names the amount, both figures and which way`() {
        val more = describe(
            Issue(
                Type.BALANCE_DRIFT, IntegrityChecker.Severity.PROBLEM, accountId = 1, currencyCode = "AED",
                amount = BigDecimal("500"), bankBalance = BigDecimal("1300"), computedBalance = BigDecimal("800"), asOfMillis = 1,
            ),
        )
        assertEquals("Current differs from your bank by AED 500", more.title)
        assertTrue(more.detail.contains("AED 1300"))
        assertTrue(more.detail.contains("AED 800"))
        assertTrue(more.detail.contains("12 Oct"))
        assertTrue(more.detail.contains("Money in"))

        val less = describe(
            Issue(
                Type.BALANCE_DRIFT, IntegrityChecker.Severity.PROBLEM, accountId = 1, currencyCode = "AED",
                amount = BigDecimal("-150"), bankBalance = BigDecimal("650"), computedBalance = BigDecimal("800"), asOfMillis = 1,
            ),
        )
        assertEquals("Current differs from your bank by AED 150", less.title)
        assertTrue(less.detail.contains("Money out"))
    }

    @Test
    fun `unpaired and broken links explain themselves`() {
        val unpaired = describe(
            Issue(Type.UNPAIRED_MOVE, IntegrityChecker.Severity.WARNING, currencyCode = "AED", amount = BigDecimal("3600"),
                asOfMillis = 1, transactionKind = TransactionKind.CARD_PAYMENT),
        )
        assertEquals("Card payment of AED 3600 has no matching other side", unpaired.title)
        assertTrue(unpaired.detail.contains("change its type"))

        val broken = describe(
            Issue(Type.BROKEN_LINK, IntegrityChecker.Severity.PROBLEM, linkProblem = LinkProblem.SAME_ACCOUNT),
        )
        assertTrue(broken.detail.contains("same account"))
    }

    @Test
    fun `counts are pluralised`() {
        assertEquals(
            "1 transaction is in no account",
            describe(Issue(Type.UNASSIGNED, IntegrityChecker.Severity.WARNING, count = 1)).title,
        )
        assertEquals(
            "3 transactions are in no account",
            describe(Issue(Type.UNASSIGNED, IntegrityChecker.Severity.WARNING, count = 3)).title,
        )
        assertEquals(
            "Current: 2 transactions in another currency",
            describe(Issue(Type.CURRENCY_MISMATCH, IntegrityChecker.Severity.WARNING, count = 2)).title,
        )
    }

    @Test
    fun `card setup says what to add and why`() {
        val both = describe(
            Issue(
                Type.CARD_SETUP, IntegrityChecker.Severity.INFO, accountId = 2,
                setupMissing = listOf(SetupItem.CREDIT_LIMIT, SetupItem.DUE_DAY), blocksBankCheck = true,
            ),
            account = "Card",
        )
        assertEquals("Card: add a credit limit and a payment due day", both.title)
        assertTrue(both.detail.contains("checked once the limit is set"))
        assertTrue(both.detail.contains("reminders"))
    }
}
