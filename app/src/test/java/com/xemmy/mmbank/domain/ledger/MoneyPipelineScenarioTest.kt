package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator
import com.xemmy.mmbank.domain.account.AccountNumberMatcher
import com.xemmy.mmbank.domain.account.CreditCardStatusCalculator
import com.xemmy.mmbank.domain.intelligence.NetWorthAggregator
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.CapturedMessage
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.domain.parser.BuiltInRules
import com.xemmy.mmbank.domain.parser.TransactionParser
import com.xemmy.mmbank.domain.report.MonthlyReportBuilder
import com.xemmy.mmbank.domain.usecase.TransferMatcher
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One realistic month, end to end: real-format bank messages go through the
 * real parser, account matcher, kind detector and transfer matcher (glued the
 * way TransactionRepositoryImpl.recordParsed glues them), plus a manual
 * transfer through [TransferLegs] -- and then the numbers a user actually
 * reads are checked to the fils: Spent, Received, every balance, net worth,
 * the card's statement status and the monthly report.
 *
 * The month:
 *  - salary 15,000 into Current;
 *  - two card purchases (68.00, 275.18) and a 500 ATM withdrawal;
 *  - last month's 3,600 card bill paid from Current, the card side posting a
 *    day later;
 *  - 2,000 moved to Savings by hand;
 *  - a 5.58 Tabby partial refund and 2.23 Tabby cashback.
 *
 * Before Phase 16 the card bill's bank side (a day apart from the card
 * side, so never paired) counted as spending on top of the purchases, the
 * savings move counted as spending and income, and cashback as income. The
 * right answer is Spent 837.60 / Received 15,000.
 */
class MoneyPipelineScenarioTest {

    private val zone = ZoneId.of("Asia/Dubai")

    private val current = Account(
        id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED",
        openingBalance = BigDecimal("10000.00"), accountNumber = "0123963",
    )
    private val card = Account(
        id = 2, name = "ADCB Card", type = AccountType.CREDIT_CARD, currencyCode = "AED",
        // Last month's statement: 3,600 owed going into October.
        openingBalance = BigDecimal("-3600.00"), accountNumber = "876",
        creditLimit = BigDecimal("20000"), statementCycleDay = 1, dueDayOfMonth = 25,
    )
    private val savings = Account(
        id = 3, name = "Savings", type = AccountType.SAVINGS, currencyCode = "AED",
        accountNumber = "617",
    )
    private val accounts = listOf(current, card, savings)
    private val types = accounts.associate { it.id to it.type }

    private val parser = TransactionParser(BuiltInRules.all, fallbackCurrency = "AED")
    private val ledger = mutableListOf<Transaction>()
    private var nextId = 1L

    private fun at(day: Int, hour: Int = 10) =
        LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()

    /** What recordParsed does: parse, file under an account, classify, then try to pair. */
    private fun capture(body: String, day: Int, sender: String = "ADCB", title: String? = null): Transaction {
        val message = CapturedMessage(
            source = if (title != null) TransactionSource.PUSH_NOTIFICATION else TransactionSource.SMS,
            sender = sender,
            title = title,
            body = body,
            timestampMillis = at(day),
        )
        val parsed = parser.parse(message)
        assertNotNull("didn't parse: $body", parsed)
        parsed!!
        val accountId = parsed.assignAccountId ?: parsed.accountNumberSuffix?.let { suffix ->
            AccountNumberMatcher.match(
                suffix,
                parsed.currencyCode,
                accounts.map { AccountNumberMatcher.Candidate(it.id, it.accountNumber, it.cardEndings, it.currencyCode) },
            )
        }
        val kind = TransactionKindDetector.detect(
            TransactionKindDetector.Input(
                direction = parsed.direction,
                matchedRuleName = parsed.matchedRuleName,
                rawMessage = body,
                merchant = parsed.merchant,
                accountType = accountId?.let { types[it] },
                category = parsed.category,
                ruleKind = parsed.kindOverride,
            ),
        )
        val tx = Transaction(
            id = nextId++,
            accountId = accountId,
            amount = parsed.amount,
            currencyCode = parsed.currencyCode,
            direction = parsed.direction,
            merchant = parsed.merchant,
            source = message.source,
            rawMessage = body,
            matchedRuleName = parsed.matchedRuleName,
            timestampMillis = message.timestampMillis,
            category = parsed.category,
            kind = kind,
        )
        ledger += tx
        link(tx)
        return ledger.first { it.id == tx.id }
    }

    /** TransactionRepositoryImpl.tryLinkTransfer, over the in-memory ledger. */
    private fun link(tx: Transaction) {
        val candidates = ledger.filter {
            it.id != tx.id && it.linkedTransactionId == null &&
                it.amount.compareTo(tx.amount) == 0 && it.currencyCode == tx.currencyCode &&
                it.direction != tx.direction &&
                abs(it.timestampMillis - tx.timestampMillis) <= TransferMatcher.MAX_WINDOW_MILLIS
        }
        val match = TransferMatcher.bestMatch(tx, candidates, types) ?: return
        val kind = TransferMatcher.pairKind(tx, match, types)
        ledger.replaceAll {
            when (it.id) {
                tx.id -> it.copy(linkedTransactionId = match.id, kind = if (it.kindUserSet) it.kind else kind)
                match.id -> it.copy(linkedTransactionId = tx.id, kind = if (it.kindUserSet) it.kind else kind)
                else -> it
            }
        }
    }

    private fun runMonth() {
        capture("Dear Customer, AED 15000.00 was credited to your account *963. Your available account balance is AED 25000.00", day = 1)
        capture(
            "Trx. of AED68.00 on your card ending *876 at MCDONALDS-54176, UAE is Approved. " +
                "Avl. card bal is 17296.72. Trx Date: 03/10/26 13:35",
            day = 3,
        )
        capture(
            "Trx. of AED275.18 on your card ending *876 at ADNOC SEIH SIDARA 723, UAE is Approved. " +
                "Avl. card bal is 17021.54. Trx Date: 04/10/26 13:23",
            day = 4,
        )
        capture("Dear Customer, AED 3600.00 was debited from your account *963. Your available account balance is AED 21400.00", day = 12)
        capture("Dear Customer, your payment of AED3600.00 on 13/10/2026 for card ending with *876 has been credited. Thank you", day = 13)
        // Moved to savings by hand, through the Add -> Transfer form.
        val (out, inn) = TransferLegs.build(
            TransferLegs.Draft(
                from = TransferLegs.Endpoint(1, "Current", AccountType.CHECKING, "AED"),
                to = TransferLegs.Endpoint(3, "Savings", AccountType.SAVINGS, "AED"),
                amount = BigDecimal("2000.00"),
                timestampMillis = at(15),
            ),
            label = "Manual transfer",
        )
        val outLeg = out!!.copy(id = nextId++)
        val inLeg = inn!!.copy(id = nextId++)
        ledger += outLeg.copy(linkedTransactionId = inLeg.id)
        ledger += inLeg.copy(linkedTransactionId = outLeg.id)
        capture(
            "Your CAREEM RIDE purchase was decreased by AED 5.58",
            day = 16, sender = "com.tabby.customer", title = "Transaction adjusted",
        )
        capture("Dear Customer, AED 500.00 was debited from your account *963 for ATM cash withdrawal.", day = 17)
        capture(
            "You earned AED 2.23! You got cashback with Tabby+. Your cashback will be processed after next statement is paid.",
            day = 18, sender = "com.tabby.customer",
        )
    }

    private fun kindsByMerchantOrAmount(): Map<String, TransactionKind> =
        ledger.associate { "${it.amount.stripTrailingZeros().toPlainString()}@${it.accountId}:${it.direction}" to it.kind }

    @Test
    fun `every message is classified as what it is`() {
        runMonth()
        val kinds = kindsByMerchantOrAmount()
        assertEquals(TransactionKind.INCOME, kinds["15000@1:CREDIT"])
        assertEquals(TransactionKind.SPEND, kinds["68@2:DEBIT"])
        assertEquals(TransactionKind.SPEND, kinds["275.18@2:DEBIT"])
        assertEquals(TransactionKind.CARD_PAYMENT, kinds["3600@1:DEBIT"])
        assertEquals(TransactionKind.CARD_PAYMENT, kinds["3600@2:CREDIT"])
        assertEquals(TransactionKind.TRANSFER, kinds["2000@1:DEBIT"])
        assertEquals(TransactionKind.TRANSFER, kinds["2000@3:CREDIT"])
        assertEquals(TransactionKind.REFUND, kinds["5.58@null:CREDIT"])
        assertEquals(TransactionKind.ATM, kinds["500@1:DEBIT"])
        assertEquals(TransactionKind.CASHBACK, kinds["2.23@null:CREDIT"])
        // The two sides of the card payment found each other a day apart.
        val bankLeg = ledger.first { it.amount.compareTo(BigDecimal("3600")) == 0 && it.accountId == 1L }
        val cardLeg = ledger.first { it.amount.compareTo(BigDecimal("3600")) == 0 && it.accountId == 2L }
        assertEquals(cardLeg.id, bankLeg.linkedTransactionId)
        assertEquals(bankLeg.id, cardLeg.linkedTransactionId)
    }

    @Test
    fun `spent and received count each movement exactly once`() {
        runMonth()
        assertEquals(0, BigDecimal("837.60").compareTo(LedgerMath.netSpendExact(ledger)))
        assertEquals(0, BigDecimal("15000").compareTo(LedgerMath.incomeExact(ledger)))
        // The Dashboard's Double path agrees with the exact one.
        assertEquals(837.60, LedgerMath.netSpend(ledger) { it.amount.toDouble() }, 1e-9)
    }

    @Test
    fun `every balance and net worth are right`() {
        runMonth()
        val entries = ledger.map { AccountBalanceCalculator.LedgerEntry(it.accountId, it.amount, it.currencyCode, it.direction) }
        val net = AccountBalanceCalculator.netByAccount(entries)
        val balances = accounts.associate { it.id to AccountBalanceCalculator.balanceOf(it, net) }
        // 10,000 + 15,000 salary - 3,600 card bill - 2,000 to savings - 500 ATM
        assertEquals(0, BigDecimal("18900").compareTo(balances[1L]))
        // -3,600 owed - 68 - 275.18 + 3,600 paid
        assertEquals(0, BigDecimal("-343.18").compareTo(balances[2L]))
        assertEquals(0, BigDecimal("2000").compareTo(balances[3L]))
        // The Tabby rows carried no account number: visible as unassigned, not lost.
        assertEquals(0, BigDecimal("7.81").compareTo(AccountBalanceCalculator.unassignedTotals(entries)["AED"]))

        val worth = NetWorthAggregator.aggregate(
            accounts.map { NetWorthAggregator.AccountBalance(it.id, it.type, balances.getValue(it.id)) },
        )
        assertEquals(0, BigDecimal("20556.82").compareTo(worth.netWorth))
    }

    @Test
    fun `the card shows last month's bill as paid and this cycle's spending`() {
        runMonth()
        val entries = ledger.map { AccountBalanceCalculator.LedgerEntry(it.accountId, it.amount, it.currencyCode, it.direction) }
        val balance = AccountBalanceCalculator.balanceFor(card, entries)
        val status = CreditCardStatusCalculator.status(card, balance, ledger, LocalDate.of(2026, 10, 20), zone)
        assertEquals(0, BigDecimal("343.18").compareTo(status.outstanding))
        assertEquals(0, BigDecimal("19656.82").compareTo(status.available))
        assertEquals(0, BigDecimal("3600").compareTo(status.statementBalance))
        assertEquals(0, BigDecimal("3600").compareTo(status.paidSinceStatement))
        assertEquals(0, BigDecimal.ZERO.compareTo(status.amountDue))
        assertEquals(0, BigDecimal("343.18").compareTo(status.spentThisCycle))
        assertEquals(LocalDate.of(2026, 10, 25), status.dueDate)
        assertEquals(CreditCardStatusCalculator.Status.PAID, status.status)
    }

    @Test
    fun `the monthly report agrees with the dashboard`() {
        runMonth()
        val report = MonthlyReportBuilder.build(monthLabel = "October 2026", currency = "AED", monthTransactions = ledger)
        assertEquals(0, BigDecimal("837.60").compareTo(report.spent))
        assertEquals(0, BigDecimal("15000").compareTo(report.income))
        assertNull(report.topMerchants.firstOrNull { it.name.contains("3600") })
    }
}
