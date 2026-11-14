package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalanceHistoryTest {

    private val zone = ZoneId.of("UTC")
    private val current = Account(id = 1, name = "Current", type = AccountType.CHECKING, currencyCode = "AED", openingBalance = BigDecimal("1000"))
    private val card = Account(id = 2, name = "Card", type = AccountType.CREDIT_CARD, currencyCode = "AED")

    private fun at(date: LocalDate) = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()

    private fun tx(account: Long?, amount: String, date: LocalDate, credit: Boolean = false, excluded: Boolean = false) = Transaction(
        accountId = account,
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT,
        source = TransactionSource.SMS,
        timestampMillis = at(date),
        excludeFromStats = excluded,
        kind = if (excluded) TransactionKind.TRANSFER else TransactionKind.defaultFor(if (credit) TransactionDirection.CREDIT else TransactionDirection.DEBIT),
    )

    private val ledger = listOf(
        tx(1, "5000", LocalDate.of(2026, 9, 1), credit = true),
        tx(2, "300", LocalDate.of(2026, 9, 10)),
        // Card payment: both legs move balances even though analytics ignore them.
        tx(1, "300", LocalDate.of(2026, 10, 2), excluded = true),
        tx(2, "300", LocalDate.of(2026, 10, 2), credit = true, excluded = true),
        tx(1, "200", LocalDate.of(2026, 10, 15)),
        tx(null, "50", LocalDate.of(2026, 10, 16)),
    )

    @Test
    fun `balances at a past moment include only rows up to it`() {
        val endSep = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val sep = BalanceHistory.balancesAt(listOf(current, card), ledger, endSep)
        assertEquals(BigDecimal("6000"), sep[1])
        assertEquals(BigDecimal("-300"), sep[2])
    }

    @Test
    fun `excluded rows still move balances, unassigned rows move none`() {
        val all = BalanceHistory.balancesAt(listOf(current, card), ledger, Long.MAX_VALUE)
        assertEquals(BigDecimal("5500"), all[1])
        assertEquals(BigDecimal("0"), all[2])
    }

    @Test
    fun `before any row an account sits at its opening balance`() {
        val before = BalanceHistory.balancesAt(listOf(current, card), ledger, at(LocalDate.of(2026, 1, 1)))
        assertEquals(BigDecimal("1000"), before[1])
        assertEquals(BigDecimal.ZERO, before[2])
    }

    @Test
    fun `a row exactly at the moment is included`() {
        val row = tx(1, "10", LocalDate.of(2026, 9, 1))
        assertEquals(BigDecimal("990"), BalanceHistory.balancesAt(listOf(current), listOf(row), row.timestampMillis)[1])
    }

    @Test
    fun `the latest point equals the plain balance, future-dated rows included`() {
        val future = tx(1, "25", LocalDate.of(2027, 1, 1))
        val entries = (ledger + future).map(BalanceHistory::toLedgerEntry)
        assertEquals(
            AccountBalanceCalculator.balances(listOf(current, card), entries),
            BalanceHistory.balancesAt(listOf(current, card), ledger + future, Long.MAX_VALUE),
        )
    }

    @Test
    fun `month ends run oldest first and end at now`() {
        val ends = BalanceHistory.monthEnds(YearMonth.of(2026, 11), 3, zone)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)), ends.map { it.first })
        assertEquals(LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1, ends[0].second)
        assertEquals(Long.MAX_VALUE, ends[2].second)
    }

    @Test
    fun `history starts at the oldest row`() {
        assertEquals(YearMonth.of(2026, 9), BalanceHistory.historyStart(ledger, zone))
        assertNull(BalanceHistory.historyStart(emptyList(), zone))
    }

    // -- balancesForMonth -------------------------------------------------------

    private val noRates = emptyMap<String, BigDecimal>()
    private val nov = YearMonth.of(2026, 11)

    private fun forMonth(
        month: YearMonth,
        rows: List<Transaction> = ledger,
        rates: Map<String, BigDecimal> = noRates,
        history: List<com.xemmy.mmbank.domain.model.ExchangeRateOnDay> = emptyList(),
    ) = BalanceHistory.balancesForMonth(listOf(current, card), rows, month, nov, zone, "AED", rates, history)

    @Test
    fun `a past month is replayed to its own month-end, not today`() {
        val sep = forMonth(YearMonth.of(2026, 9))!!
        assertEquals(0, sep.getValue(1).compareTo(BigDecimal("6000")))
        assertEquals(0, sep.getValue(2).compareTo(BigDecimal("-300")))
    }

    @Test
    fun `the current month is today's balances including everything`() {
        val now = forMonth(nov)!!
        assertEquals(0, now.getValue(1).compareTo(BigDecimal("5500")))
        assertEquals(0, now.getValue(2).compareTo(BigDecimal.ZERO))
    }

    @Test
    fun `a month before the ledger starts, or an empty ledger, has no balances`() {
        assertNull(forMonth(YearMonth.of(2026, 8)))
        assertNull(forMonth(YearMonth.of(2026, 9), rows = emptyList()))
    }

    @Test
    fun `a foreign charge in a past month converts at that month's recorded rate`() {
        val rows = listOf(
            tx(1, "10", LocalDate.of(2026, 9, 1), credit = true),
            tx(1, "10", LocalDate.of(2026, 9, 15)).copy(currencyCode = "USD"),
        )
        val today = mapOf("USD" to BigDecimal("0.25"))
        val history = listOf(
            com.xemmy.mmbank.domain.model.ExchangeRateOnDay("AED", "USD", LocalDate.of(2026, 9, 1), BigDecimal("0.5")),
        )
        // 10 USD at the recorded 0.5 = 20 AED out; at today's 0.25 it would be 40.
        val withHistory = forMonth(YearMonth.of(2026, 9), rows, today, history)!!
        assertEquals(0, withHistory.getValue(1).compareTo(BigDecimal("990")))
        val withoutHistory = forMonth(YearMonth.of(2026, 9), rows, today)!!
        assertEquals(0, withoutHistory.getValue(1).compareTo(BigDecimal("970")))
    }
}
