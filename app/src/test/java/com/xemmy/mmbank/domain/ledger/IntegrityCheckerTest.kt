package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.ledger.IntegrityChecker.LinkProblem
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Severity
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Status
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Type
import com.xemmy.mmbank.domain.ledger.IntegrityChecker.Why
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityCheckerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private val current = Account(
        id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED",
        openingBalance = BigDecimal("1000"),
    )
    private val card = Account(
        id = 2, name = "Card", type = AccountType.CREDIT_CARD, currencyCode = "AED",
        openingBalance = BigDecimal.ZERO, creditLimit = BigDecimal("10000"), dueDayOfMonth = 25,
    )

    private var nextId = 1L

    private fun tx(
        account: Long? = 1,
        amount: String,
        direction: TransactionDirection = TransactionDirection.DEBIT,
        at: Long = 50 * day,
        reported: String? = null,
        reportedKind: ReportedBalanceKind = ReportedBalanceKind.ACCOUNT_BALANCE,
        currency: String = "AED",
        kind: TransactionKind = TransactionKind.defaultFor(direction),
        kindUserSet: Boolean = false,
        linked: Long? = null,
        id: Long = nextId++,
    ) = Transaction(
        id = id,
        accountId = account,
        amount = BigDecimal(amount),
        currencyCode = currency,
        direction = direction,
        source = TransactionSource.SMS,
        timestampMillis = at,
        kind = kind,
        kindUserSet = kindUserSet,
        linkedTransactionId = linked,
        reportedBalance = reported?.let(::BigDecimal),
        reportedBalanceKind = reported?.let { reportedKind },
    )

    private fun check(accounts: List<Account>, txs: List<Transaction>, cutoff: Long? = null) =
        IntegrityChecker.check(accounts, txs, now, cutoff)

    private fun Iterable<IntegrityChecker.Issue>.ofType(type: Type) = filter { it.type == type }

    // ---- balance drift -------------------------------------------------

    @Test
    fun `books that match the bank raise nothing`() {
        // 1000 opening - 200 spent = 800, the bank agrees.
        val report = check(listOf(current), listOf(tx(amount = "200", reported = "800")))
        assertTrue(report.issues.isEmpty())
        val rec = report.reconciliations.getValue(1)
        assertEquals(Status.MATCHES, rec.status)
        assertEquals(0, BigDecimal("800").compareTo(rec.bankBalance))
    }

    @Test
    fun `a missing deposit shows as the bank having more`() {
        // The app never saw a 500 deposit: it computes 800, the bank says 1300.
        val report = check(listOf(current), listOf(tx(amount = "200", reported = "1300")))
        val issue = report.issues.single()
        assertEquals(Type.BALANCE_DRIFT, issue.type)
        assertEquals(Severity.PROBLEM, issue.severity)
        assertEquals(0, BigDecimal("500").compareTo(issue.amount))
        assertEquals(0, BigDecimal("1300").compareTo(issue.bankBalance))
        assertEquals(0, BigDecimal("800").compareTo(issue.computedBalance))
        assertEquals(50 * day, issue.asOfMillis)
    }

    @Test
    fun `a missing purchase shows as the bank having less`() {
        val report = check(listOf(current), listOf(tx(amount = "200", reported = "650")))
        assertEquals(0, BigDecimal("-150").compareTo(report.issues.single().amount))
    }

    @Test
    fun `the comparison is as of the message, so later transactions cannot cause a false alarm`() {
        val txs = listOf(
            tx(amount = "200", at = 50 * day, reported = "800"),
            tx(amount = "300", at = 60 * day), // happened after the snapshot
        )
        assertTrue(check(listOf(current), txs).issues.isEmpty())
    }

    @Test
    fun `only the latest bank statement is used`() {
        val txs = listOf(
            tx(amount = "100", at = 40 * day, reported = "123456"), // stale and wrong, superseded
            tx(amount = "200", at = 50 * day, reported = "700"),
        )
        val rec = check(listOf(current), txs).reconciliations.getValue(1)
        assertEquals(Status.MATCHES, rec.status)
    }

    @Test
    fun `a balance adjustment dated at the snapshot clears the drift`() {
        val missing = listOf(tx(amount = "200", reported = "1300"))
        assertEquals(1, check(listOf(current), missing).issues.size)
        val adjustment = tx(
            amount = "500", direction = TransactionDirection.CREDIT, at = 50 * day,
            kind = TransactionKind.ADJUSTMENT, kindUserSet = true,
        )
        assertTrue(check(listOf(current), missing + adjustment).issues.isEmpty())
    }

    @Test
    fun `rounding noise under a cent is not a discrepancy`() {
        assertTrue(check(listOf(current), listOf(tx(amount = "200", reported = "800.005"))).issues.isEmpty())
    }

    @Test
    fun `an account with no bank statements is simply unchecked`() {
        val report = check(listOf(current), listOf(tx(amount = "200")))
        assertEquals(Status.NO_BANK_BALANCE, report.reconciliations.getValue(1).status)
        assertTrue(report.issues.isEmpty())
    }

    @Test
    fun `an account holding another currency cannot be reconciled`() {
        val txs = listOf(tx(amount = "200", reported = "9999"), tx(amount = "10", currency = "USD"))
        val report = check(listOf(current), txs)
        val rec = report.reconciliations.getValue(1)
        assertEquals(Status.CANNOT_CHECK, rec.status)
        assertEquals(Why.MIXED_CURRENCY, rec.why)
        assertTrue(report.issues.ofType(Type.BALANCE_DRIFT).isEmpty())
        assertEquals(1, report.issues.ofType(Type.CURRENCY_MISMATCH).single().count)
    }

    @Test
    fun `a statement older than the retention cutoff is skipped`() {
        val report = check(listOf(current), listOf(tx(amount = "200", at = 10 * day, reported = "9999")), cutoff = 30 * day)
        assertEquals(Why.OLDER_THAN_RETENTION, report.reconciliations.getValue(1).why)
        assertTrue(report.issues.isEmpty())
    }

    // ---- credit cards --------------------------------------------------

    @Test
    fun `a card compares what is owed, from the limit less the available credit`() {
        // 300 spent on the card, so 300 owed and 9700 available: the bank agrees.
        val ok = check(listOf(card), listOf(tx(account = 2, amount = "300", reported = "9700", reportedKind = ReportedBalanceKind.AVAILABLE_CREDIT)))
        assertEquals(Status.MATCHES, ok.reconciliations.getValue(2).status)
        assertTrue(ok.issues.isEmpty())

        // The bank says 9400 available => 600 owed, the app only knows 300.
        val off = check(listOf(card), listOf(tx(account = 2, amount = "300", reported = "9400", reportedKind = ReportedBalanceKind.AVAILABLE_CREDIT)))
        val issue = off.issues.ofType(Type.BALANCE_DRIFT).single()
        assertEquals(0, BigDecimal("-300").compareTo(issue.amount))
        assertEquals(0, BigDecimal("-600").compareTo(issue.bankBalance))
        assertEquals(0, BigDecimal("-300").compareTo(issue.computedBalance))
    }

    @Test
    fun `a card in credit owes nothing however far in credit`() {
        val txs = listOf(
            tx(account = 2, amount = "500", direction = TransactionDirection.CREDIT, at = 40 * day),
            tx(account = 2, amount = "10", at = 50 * day, reported = "10000", reportedKind = ReportedBalanceKind.AVAILABLE_CREDIT),
        )
        assertTrue(check(listOf(card), txs).issues.isEmpty())
    }

    @Test
    fun `a card without a limit cannot be checked and says what to set`() {
        val noLimit = card.copy(creditLimit = null)
        val report = check(listOf(noLimit), listOf(tx(account = 2, amount = "300", reported = "9700", reportedKind = ReportedBalanceKind.AVAILABLE_CREDIT)))
        assertEquals(Why.NEEDS_CREDIT_LIMIT, report.reconciliations.getValue(2).why)
        val setup = report.issues.ofType(Type.CARD_SETUP).single()
        assertEquals(Severity.INFO, setup.severity)
        assertEquals(listOf(IntegrityChecker.SetupItem.CREDIT_LIMIT), setup.setupMissing)
        assertTrue(setup.blocksBankCheck)
    }

    @Test
    fun `a plain balance on a card is ambiguous and ignored`() {
        val report = check(listOf(card), listOf(tx(account = 2, amount = "300", reported = "123")))
        assertEquals(Why.AMBIGUOUS_BALANCE, report.reconciliations.getValue(2).why)
        assertTrue(report.issues.ofType(Type.BALANCE_DRIFT).isEmpty())
    }

    @Test
    fun `a card missing a due day gets a setup hint, not a bank check problem`() {
        val report = check(listOf(card.copy(dueDayOfMonth = null)), emptyList())
        val setup = report.issues.single()
        assertEquals(listOf(IntegrityChecker.SetupItem.DUE_DAY), setup.setupMissing)
        assertEquals(false, setup.blocksBankCheck)
    }

    @Test
    fun `a debit card's available balance counts as the account balance`() {
        val txs = listOf(tx(amount = "200", reported = "800", reportedKind = ReportedBalanceKind.AVAILABLE_CREDIT))
        assertEquals(Status.MATCHES, check(listOf(current), txs).reconciliations.getValue(1).status)
    }

    // ---- unpaired and broken links --------------------------------------

    @Test
    fun `a card payment with no other side after the window is flagged`() {
        val old = tx(amount = "3600", at = 90 * day, kind = TransactionKind.CARD_PAYMENT)
        val issue = check(listOf(current), listOf(old)).issues.single()
        assertEquals(Type.UNPAIRED_MOVE, issue.type)
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals(TransactionKind.CARD_PAYMENT, issue.transactionKind)
    }

    @Test
    fun `a recent one is still allowed to find its partner`() {
        val fresh = tx(amount = "3600", at = now - day, kind = TransactionKind.CARD_PAYMENT)
        assertTrue(check(listOf(current), listOf(fresh)).issues.isEmpty())
    }

    @Test
    fun `a move the user classified themselves is not second-guessed`() {
        val mine = tx(amount = "3600", at = 10 * day, kind = TransactionKind.TRANSFER, kindUserSet = true)
        assertTrue(check(listOf(current), listOf(mine)).issues.isEmpty())
    }

    @Test
    fun `a healthy linked pair raises nothing, including across currencies`() {
        val a = tx(account = 1, amount = "367.30", kind = TransactionKind.TRANSFER, linked = 902, id = 901)
        val b = tx(account = 3, amount = "100", direction = TransactionDirection.CREDIT, currency = "USD", kind = TransactionKind.TRANSFER, linked = 901, id = 902)
        val usd = Account(id = 3, name = "USD", type = AccountType.CHECKING, currencyCode = "USD")
        assertTrue(check(listOf(current, usd), listOf(a, b)).issues.isEmpty())
    }

    @Test
    fun `broken links are reported once per pair with the reason`() {
        fun link(a: Transaction, b: Transaction) = check(listOf(current, card), listOf(a, b)).issues.ofType(Type.BROKEN_LINK)

        val missingPartner = tx(account = 1, amount = "10", linked = 999, id = 801)
        assertEquals(LinkProblem.PARTNER_MISSING, check(listOf(current), listOf(missingPartner)).issues.single().linkProblem)

        val a = tx(account = 1, amount = "10", linked = 812, id = 811)
        val notMutual = tx(account = 2, amount = "10", direction = TransactionDirection.CREDIT, linked = null, id = 812)
        assertEquals(LinkProblem.NOT_MUTUAL, link(a, notMutual).single().linkProblem)

        val c = tx(account = 1, amount = "10", linked = 822, id = 821)
        val d = tx(account = 2, amount = "11", direction = TransactionDirection.CREDIT, linked = 821, id = 822)
        val amountIssues = link(c, d)
        assertEquals(1, amountIssues.size)
        assertEquals(LinkProblem.AMOUNT_DIFFERS, amountIssues.single().linkProblem)
        assertEquals(setOf(821L, 822L), amountIssues.single().transactionIds.toSet())

        val e = tx(account = 1, amount = "10", linked = 832, id = 831)
        val f = tx(account = 1, amount = "10", direction = TransactionDirection.CREDIT, linked = 831, id = 832)
        assertEquals(LinkProblem.SAME_ACCOUNT, link(e, f).single().linkProblem)
    }

    // ---- unassigned, currency, ordering -----------------------------------

    @Test
    fun `rows in no account are counted once`() {
        val report = check(listOf(current), listOf(tx(account = null, amount = "5"), tx(account = null, amount = "6")))
        val issue = report.issues.single()
        assertEquals(Type.UNASSIGNED, issue.type)
        assertEquals(2, issue.count)
    }

    @Test
    fun `rows on an archived or unknown account are not treated as unassigned`() {
        assertTrue(check(listOf(current), listOf(tx(account = 77, amount = "5"))).issues.isEmpty())
    }

    @Test
    fun `problems come before warnings before hints`() {
        val txs = listOf(
            tx(account = null, amount = "5"),                       // warning
            tx(account = 1, amount = "200", reported = "1300"),     // problem
        )
        val severities = check(listOf(current, card.copy(dueDayOfMonth = null)), txs).issues.map { it.severity }
        assertEquals(listOf(Severity.PROBLEM, Severity.WARNING, Severity.INFO), severities)
        assertEquals(1, check(listOf(current), txs).actionableCount.coerceAtMost(1))
    }

    @Test
    fun `an empty ledger is clean`() {
        val report = check(listOf(current), emptyList())
        assertTrue(report.issues.isEmpty())
        assertNull(report.reconciliations.getValue(1).drift)
    }

    @Test
    fun `rows set aside as history only are not reported as unassigned`() {
        val history = tx(account = null, amount = "5").copy(historyOnly = true)
        assertTrue(check(listOf(current), listOf(history)).issues.isEmpty())

        val report = check(listOf(current), listOf(history, tx(account = null, amount = "6")))
        assertEquals(1, report.issues.single().count)
    }
}
