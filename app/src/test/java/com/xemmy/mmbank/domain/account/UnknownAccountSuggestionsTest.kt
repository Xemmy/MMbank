package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnknownAccountSuggestionsTest {

    private fun tx(
        id: Long,
        suffix: String?,
        accountId: Long? = null,
        currency: String = "AED",
        at: Long = id * 1000,
        sender: String? = "ADCB",
        balance: String? = null,
        kind: ReportedBalanceKind? = null,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        amount: String = "10",
    ) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal(amount), currencyCode = currency,
        direction = direction, source = TransactionSource.SMS, sender = sender, timestampMillis = at,
        accountNumberSuffix = suffix, reportedBalance = balance?.let(::BigDecimal), reportedBalanceKind = kind,
    )

    private fun account(id: Long, number: String? = null, endings: String? = null, currency: String = "AED") =
        Account(id = id, name = "A$id", type = AccountType.CHECKING, currencyCode = currency, accountNumber = number, cardEndings = endings)

    @Test
    fun `groups unassigned rows by unknown ending and currency`() {
        val out = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543"), tx(2, "*4543"), tx(3, "4543", currency = "USD"), tx(4, "9999")),
            accounts = emptyList(),
        )
        assertEquals(3, out.size)
        val aed = out.first { it.digits == "4543" && it.currencyCode == "AED" }
        assertEquals(listOf(1L, 2L), aed.transactionIds)
        assertEquals(2, aed.count)
        // Biggest group first.
        assertEquals(aed, out.first())
    }

    @Test
    fun `an ending that matches an account is not unknown`() {
        val out = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543"), tx(2, "810001"), tx(3, "7777")),
            accounts = listOf(account(1, number = "0004543"), account(2, endings = "810001")),
        )
        assertEquals(listOf("7777"), out.map { it.digits })
    }

    @Test
    fun `an archived account still claims its ending`() {
        val out = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543")),
            accounts = listOf(account(1, number = "4543").copy(archived = true)),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `assigned rows and endings that are too short are ignored`() {
        val out = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543", accountId = 5), tx(2, "45"), tx(3, null), tx(4, "x")),
            accounts = emptyList(),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `available credit means a credit card, otherwise current`() {
        val card = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543", balance = "5000", kind = ReportedBalanceKind.AVAILABLE_CREDIT)), emptyList(),
        ).single()
        assertEquals(AccountType.CREDIT_CARD, card.suggestedType)
        assertEquals("card ••4543", card.label)

        val current = UnknownAccountSuggestions.suggest(
            listOf(tx(1, "4543", balance = "5000", kind = ReportedBalanceKind.ACCOUNT_BALANCE)), emptyList(),
        ).single()
        assertEquals(AccountType.CHECKING, current.suggestedType)
        assertEquals("account ••4543", current.label)

        assertNull(UnknownAccountSuggestions.suggest(listOf(tx(1, "4543")), emptyList()).single().latestReportedBalance)
    }

    @Test
    fun `reports the newest printed balance and the most common sender`() {
        val s = UnknownAccountSuggestions.suggest(
            listOf(
                tx(1, "4543", sender = "ADCB", balance = "100", kind = ReportedBalanceKind.ACCOUNT_BALANCE, at = 1000),
                tx(2, "4543", sender = "ADCB", balance = "250", kind = ReportedBalanceKind.ACCOUNT_BALANCE, at = 3000),
                tx(3, "4543", sender = "Other", at = 9000),
            ),
            emptyList(),
        ).single()
        assertEquals(0, BigDecimal("250").compareTo(s.latestReportedBalance))
        assertEquals(3000L, s.latestReportedAtMillis)
        assertEquals(9000L, s.latestAtMillis)
        assertEquals(listOf("ADCB", "Other"), s.senders)
    }

    @Test
    fun `new account carries the ending and an opening balance that reconciles`() {
        val rows = listOf(
            tx(1, "4543", at = 1000, amount = "100", direction = TransactionDirection.DEBIT),
            tx(2, "4543", at = 2000, amount = "40", direction = TransactionDirection.CREDIT, balance = "500", kind = ReportedBalanceKind.ACCOUNT_BALANCE),
        )
        val s = UnknownAccountSuggestions.suggest(rows, emptyList()).single()
        val account = UnknownAccountSuggestions.newAccountFor(s, rows, " Main ", AccountType.CHECKING)
        assertEquals("Main", account.name)
        assertEquals("4543", account.accountNumber)
        assertEquals("AED", account.currencyCode)
        // 500 printed after -100 +40 => opening 560.
        assertEquals(0, BigDecimal("560").compareTo(account.openingBalance))
    }

    @Test
    fun `no printed balance means a zero opening balance`() {
        val rows = listOf(tx(1, "4543"))
        val s = UnknownAccountSuggestions.suggest(rows, emptyList()).single()
        assertEquals(0, BigDecimal.ZERO.compareTo(UnknownAccountSuggestions.newAccountFor(s, rows, "X", AccountType.CHECKING).openingBalance))
    }

    @Test
    fun `linking adds the ending without touching anything else`() {
        val s = UnknownAccountSuggestions.suggest(listOf(tx(1, "4543")), emptyList()).single()
        val existing = account(7, number = "1111111", endings = "2222")
        val linked = UnknownAccountSuggestions.linkTo(existing, s)
        assertEquals(existing.copy(cardEndings = linked.cardEndings), linked)
        assertTrue(linked.cardEndings!!.contains("2222") && linked.cardEndings!!.contains("4543"))
        // The ending is now known, so the suggestion disappears.
        assertTrue(UnknownAccountSuggestions.suggest(listOf(tx(1, "4543")), listOf(linked)).isEmpty())
    }
}
