package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.HistoryOnlyPlanner.Scope
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryOnlyPlannerTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day

    private fun account(id: Long, createdAt: Long) = Account(
        id = id, name = "A$id", type = AccountType.CHECKING, currencyCode = "AED", createdAt = createdAt,
    )

    private fun tx(
        id: Long,
        at: Long,
        accountId: Long? = null,
        kind: TransactionKind = TransactionKind.SPEND,
        historyOnly: Boolean = false,
    ) = Transaction(
        id = id, accountId = accountId, amount = BigDecimal.TEN, currencyCode = "AED",
        direction = TransactionDirection.DEBIT, source = TransactionSource.SMS, timestampMillis = at,
        kind = kind, historyOnly = historyOnly,
    )

    private fun option(options: List<HistoryOnlyPlanner.Option>, scope: Scope) = options.first { it.scope == scope }

    @Test
    fun `the last adjustment per account is the newest adjustment row`() {
        val out = HistoryOnlyPlanner.lastAdjustments(
            listOf(
                tx(1, at = 10, accountId = 1, kind = TransactionKind.ADJUSTMENT),
                tx(2, at = 30, accountId = 1, kind = TransactionKind.ADJUSTMENT),
                tx(3, at = 99, accountId = 1),
                tx(4, at = 50, accountId = 2, kind = TransactionKind.ADJUSTMENT),
                tx(5, at = 70, kind = TransactionKind.ADJUSTMENT),
            ),
        )
        assertEquals(mapOf(1L to 30L, 2L to 50L), out)
    }

    @Test
    fun `tracking starts at the earliest account cutoff`() {
        val accounts = listOf(account(1, createdAt = 100 * day), account(2, createdAt = 200 * day))
        // Account 1 was corrected on day 300, so its cutoff is later than account 2's creation on day 200.
        val txs = listOf(tx(1, at = 300 * day, accountId = 1, kind = TransactionKind.ADJUSTMENT))
        assertEquals(200 * day, HistoryOnlyPlanner.trackingStart(accounts, txs))
    }

    @Test
    fun `with no accounts there is no tracking start and no such option`() {
        assertNull(HistoryOnlyPlanner.trackingStart(emptyList(), emptyList()))
        val options = HistoryOnlyPlanner.options(listOf(tx(1, at = day)), emptyList(), now)
        assertEquals(listOf(Scope.OLDER_THAN_30_DAYS, Scope.ALL), options.map { it.scope })
    }

    @Test
    fun `each option counts only rows that still need an account`() {
        val accounts = listOf(account(1, createdAt = 200 * day))
        val txs = listOf(
            tx(1, at = 100 * day),                       // before tracking
            tx(2, at = 250 * day),                       // after tracking, older than 30 days
            tx(3, at = 390 * day),                       // recent
            tx(4, at = 100 * day, accountId = 1),        // filed: never offered
            tx(5, at = 100 * day, historyOnly = true),   // already set aside: never offered
        )
        val options = HistoryOnlyPlanner.options(txs, accounts, now)

        assertEquals(listOf(Scope.BEFORE_TRACKING, Scope.OLDER_THAN_30_DAYS, Scope.ALL), options.map { it.scope })
        assertEquals(listOf(1L), option(options, Scope.BEFORE_TRACKING).transactionIds)
        assertEquals(200 * day, option(options, Scope.BEFORE_TRACKING).beforeMillis)
        assertEquals(listOf(1L, 2L), option(options, Scope.OLDER_THAN_30_DAYS).transactionIds)
        assertEquals(listOf(1L, 2L, 3L), option(options, Scope.ALL).transactionIds)
        assertNull(option(options, Scope.ALL).beforeMillis)
    }

    @Test
    fun `a row dated exactly at the tracking start is not before it`() {
        val options = HistoryOnlyPlanner.options(listOf(tx(1, at = 200 * day)), listOf(account(1, createdAt = 200 * day)), now)
        assertEquals(0, option(options, Scope.BEFORE_TRACKING).count)
    }

    @Test
    fun `the default is the narrowest option that marks something`() {
        val accounts = listOf(account(1, createdAt = 50 * day))
        // Nothing predates tracking, so the default moves on to "older than 30 days".
        val options = HistoryOnlyPlanner.options(listOf(tx(1, at = 100 * day)), accounts, now)
        assertEquals(Scope.OLDER_THAN_30_DAYS, HistoryOnlyPlanner.default(options)?.scope)
    }

    @Test
    fun `with nothing to mark the default is still the first option`() {
        val options = HistoryOnlyPlanner.options(emptyList(), listOf(account(1, createdAt = 50 * day)), now)
        assertEquals(Scope.BEFORE_TRACKING, HistoryOnlyPlanner.default(options)?.scope)
        assertEquals(0, HistoryOnlyPlanner.default(options)?.count)
    }
}
