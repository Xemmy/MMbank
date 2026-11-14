package com.xemmy.mmbank.domain.filter

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import com.xemmy.mmbank.ui.screens.accounts.buildBalanceAdjustment
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The full Phase 4 filter matrix for [TransactionFilterEngine]: statement-cycle
 * date math (including short-month clamping and year boundaries), relative and
 * custom ranges, and the triage / amount / source / analytics-status predicates,
 * individually and combined.
 */
class TransactionFilterEngineTest {

    private val zone = ZoneId.of("UTC")

    private val tabby = Account(
        id = 1, name = "Tabby", type = AccountType.CREDIT_CARD,
        currencyCode = "AED", statementCycleDay = 10,
    )
    private val noCycleCard = Account(
        id = 2, name = "Plain card", type = AccountType.CREDIT_CARD,
        currencyCode = "AED", statementCycleDay = null,
    )
    private val accounts = listOf(tabby, noCycleCard)

    private var nextId = 1L
    private fun tx(
        date: LocalDate = LocalDate.of(2026, 7, 5),
        amount: String = "100.00",
        direction: TransactionDirection = TransactionDirection.DEBIT,
        accountId: Long? = null,
        category: SpendingCategory = SpendingCategory.GROCERIES,
        categoryId: Long? = null,
        source: TransactionSource = TransactionSource.SMS,
        excluded: Boolean = false,
        merchant: String? = null,
        matchedRuleName: String? = "Some rule",
        person: String? = null,
        timeOfDayHour: Int = 12,
    ) = Transaction(
        id = nextId++,
        accountId = accountId,
        amount = BigDecimal(amount),
        currencyCode = "AED",
        direction = direction,
        merchant = merchant,
        source = source,
        timestampMillis = date.atTime(timeOfDayHour, 0).atZone(zone).toInstant().toEpochMilli(),
        matchedRuleName = matchedRuleName,
        spendingCategory = category,
        categoryId = categoryId,
        person = person,
        excludeFromStats = excluded,
    )

    private fun filter(
        transactions: List<Transaction>,
        filters: ActivityFilters,
        today: LocalDate = LocalDate.of(2026, 7, 15),
    ) = TransactionFilterEngine.filter(
        transactions = transactions,
        filters = filters,
        accounts = accounts,
        zone = zone,
        today = today,
    )

    // ------------------------------------------------------------------
    // Statement-cycle window math
    // ------------------------------------------------------------------

    @Test
    fun `statement cycle - normal month, day 10 spans 10th prev to 9th current`() {
        val window = TransactionFilterEngine.statementCycleWindow(10, LocalDate.of(2026, 7, 15))
        assertEquals(LocalDate.of(2026, 6, 10), window.start)
        assertEquals(LocalDate.of(2026, 7, 9), window.end)
    }

    @Test
    fun `statement cycle - day 31 clamps to short previous month`() {
        // Previous month is February (28 days in 2026): the start clamps to the 28th.
        val window = TransactionFilterEngine.statementCycleWindow(31, LocalDate.of(2026, 3, 5))
        assertEquals(LocalDate.of(2026, 2, 28), window.start)
        assertEquals(LocalDate.of(2026, 3, 30), window.end)
    }

    @Test
    fun `statement cycle - day 31 clamps in short current month`() {
        // Current month is February: the next cycle starts on the clamped 28th,
        // so this window ends the day before.
        val window = TransactionFilterEngine.statementCycleWindow(31, LocalDate.of(2026, 2, 15))
        assertEquals(LocalDate.of(2026, 1, 31), window.start)
        assertEquals(LocalDate.of(2026, 2, 27), window.end)
    }

    @Test
    fun `statement cycle - leap-year February keeps the 29th`() {
        val window = TransactionFilterEngine.statementCycleWindow(30, LocalDate.of(2028, 2, 15))
        assertEquals(LocalDate.of(2028, 1, 30), window.start)
        assertEquals(LocalDate.of(2028, 2, 28), window.end) // next cycle starts Feb 29
    }

    @Test
    fun `statement cycle - january window starts in december of the previous year`() {
        val window = TransactionFilterEngine.statementCycleWindow(10, LocalDate.of(2026, 1, 5))
        assertEquals(LocalDate.of(2025, 12, 10), window.start)
        assertEquals(LocalDate.of(2026, 1, 9), window.end)
    }

    @Test
    fun `statement preset - account without a cycle day falls back to rolling 30 days`() {
        val today = LocalDate.of(2026, 7, 15)
        val window = TransactionFilterEngine.resolveDateWindow(
            DateRangeFilter.StatementCycle, noCycleCard, today,
        )
        assertEquals(DateWindow(LocalDate.of(2026, 6, 16), today), window)
    }

    @Test
    fun `statement preset - no account filter falls back to rolling 30 days`() {
        val today = LocalDate.of(2026, 7, 15)
        val window = TransactionFilterEngine.resolveDateWindow(DateRangeFilter.StatementCycle, null, today)
        assertEquals(DateWindow(LocalDate.of(2026, 6, 16), today), window)
    }

    @Test
    fun `statement preset - end to end, boundary days in, neighbours out`() {
        val startDay = tx(LocalDate.of(2026, 6, 10), accountId = 1, timeOfDayHour = 0)
        val endDay = tx(LocalDate.of(2026, 7, 9), accountId = 1, timeOfDayHour = 23)
        val beforeStart = tx(LocalDate.of(2026, 6, 9), accountId = 1)
        val nextCycle = tx(LocalDate.of(2026, 7, 10), accountId = 1)
        val filters = ActivityFilters(
            dateRange = DateRangeFilter.StatementCycle,
            account = AccountFilter.Specific(1),
        )
        val visible = filter(listOf(startDay, endDay, beforeStart, nextCycle), filters)
        assertEquals(listOf(startDay.id, endDay.id), visible.map { it.id })
    }

    // ------------------------------------------------------------------
    // Relative presets & custom ranges
    // ------------------------------------------------------------------

    @Test
    fun `today, past 7, past 30 and year-to-date resolve to the right windows`() {
        val today = LocalDate.of(2026, 7, 15)
        assertEquals(
            DateWindow(today, today),
            TransactionFilterEngine.resolveDateWindow(DateRangeFilter.Today, null, today),
        )
        assertEquals(
            DateWindow(LocalDate.of(2026, 7, 9), today),
            TransactionFilterEngine.resolveDateWindow(DateRangeFilter.Past7Days, null, today),
        )
        assertEquals(
            DateWindow(LocalDate.of(2026, 6, 16), today),
            TransactionFilterEngine.resolveDateWindow(DateRangeFilter.Past30Days, null, today),
        )
        assertEquals(
            DateWindow(LocalDate.of(2026, 1, 1), today),
            TransactionFilterEngine.resolveDateWindow(DateRangeFilter.YearToDate, null, today),
        )
        assertEquals(
            null,
            TransactionFilterEngine.resolveDateWindow(DateRangeFilter.All, null, today),
        )
    }

    @Test
    fun `custom range filters inclusively and normalizes a reversed selection`() {
        val inside = tx(LocalDate.of(2026, 5, 20))
        val onStart = tx(LocalDate.of(2026, 5, 10))
        val onEnd = tx(LocalDate.of(2026, 5, 25))
        val outside = tx(LocalDate.of(2026, 5, 26))
        val range = DateRangeFilter.Custom(LocalDate.of(2026, 5, 25), LocalDate.of(2026, 5, 10))
        val visible = filter(listOf(inside, onStart, onEnd, outside), ActivityFilters(dateRange = range))
        assertEquals(setOf(inside.id, onStart.id, onEnd.id), visible.map { it.id }.toSet())
    }

    // ------------------------------------------------------------------
    // Amount threshold / range
    // ------------------------------------------------------------------

    @Test
    fun `amount - min-only keeps rows at or above the bound`() {
        val small = tx(amount = "49.99")
        val exact = tx(amount = "50.00")
        val big = tx(amount = "51.00")
        val visible = filter(listOf(small, exact, big), ActivityFilters(minAmount = BigDecimal("50")))
        assertEquals(setOf(exact.id, big.id), visible.map { it.id }.toSet())
    }

    @Test
    fun `amount - max-only keeps rows at or below the bound`() {
        val small = tx(amount = "49.99")
        val exact = tx(amount = "50.00")
        val big = tx(amount = "50.01")
        val visible = filter(listOf(small, exact, big), ActivityFilters(maxAmount = BigDecimal("50")))
        assertEquals(setOf(small.id, exact.id), visible.map { it.id }.toSet())
    }

    @Test
    fun `amount - bounded range keeps only rows inside min and max`() {
        val below = tx(amount = "9.99")
        val low = tx(amount = "10.00")
        val high = tx(amount = "20.00")
        val above = tx(amount = "20.01")
        val visible = filter(
            listOf(below, low, high, above),
            ActivityFilters(minAmount = BigDecimal("10"), maxAmount = BigDecimal("20")),
        )
        assertEquals(setOf(low.id, high.id), visible.map { it.id }.toSet())
    }

    // ------------------------------------------------------------------
    // Source & analytics status
    // ------------------------------------------------------------------

    @Test
    fun `kind filter isolates card payments, refunds and the rest`() {
        val spend = tx()
        val cardPayment = tx().copy(kind = com.xemmy.mmbank.domain.model.TransactionKind.CARD_PAYMENT)
        val refund = tx(direction = TransactionDirection.CREDIT)
            .copy(kind = com.xemmy.mmbank.domain.model.TransactionKind.REFUND)
        val all = listOf(spend, cardPayment, refund)
        assertEquals(
            listOf(cardPayment.id),
            filter(all, ActivityFilters(kind = com.xemmy.mmbank.domain.model.TransactionKind.CARD_PAYMENT)).map { it.id },
        )
        assertEquals(
            listOf(refund.id),
            filter(all, ActivityFilters(kind = com.xemmy.mmbank.domain.model.TransactionKind.REFUND)).map { it.id },
        )
        assertEquals(3, filter(all, ActivityFilters()).size)
        assertTrue(ActivityFilters(kind = com.xemmy.mmbank.domain.model.TransactionKind.SPEND).active)
    }

    @Test
    fun `source filter isolates each capture source`() {
        val manual = tx(source = TransactionSource.MANUAL)
        val sms = tx(source = TransactionSource.SMS)
        val push = tx(source = TransactionSource.PUSH_NOTIFICATION)
        val all = listOf(manual, sms, push)
        assertEquals(
            listOf(manual.id),
            filter(all, ActivityFilters(source = TransactionSource.MANUAL)).map { it.id },
        )
        assertEquals(
            listOf(sms.id),
            filter(all, ActivityFilters(source = TransactionSource.SMS)).map { it.id },
        )
        assertEquals(
            listOf(push.id),
            filter(all, ActivityFilters(source = TransactionSource.PUSH_NOTIFICATION)).map { it.id },
        )
        assertEquals(3, filter(all, ActivityFilters(source = null)).size)
    }

    @Test
    fun `analytics status - included only vs excluded only`() {
        val counted = tx(excluded = false)
        val flagged = tx(excluded = true)
        val all = listOf(counted, flagged)
        assertEquals(
            listOf(counted.id),
            filter(all, ActivityFilters(analyticsStatus = AnalyticsStatusFilter.INCLUDED_ONLY)).map { it.id },
        )
        assertEquals(
            listOf(flagged.id),
            filter(all, ActivityFilters(analyticsStatus = AnalyticsStatusFilter.EXCLUDED_ONLY)).map { it.id },
        )
        assertEquals(2, filter(all, ActivityFilters(analyticsStatus = null)).size)
    }

    // ------------------------------------------------------------------
    // "Inbox Zero" triage
    // ------------------------------------------------------------------

    @Test
    fun `triage keeps uncategorized rows and balance adjustments, drops the rest`() {
        val uncategorized = tx(categoryId = null)
        val adjustment = tx(
            categoryId = 1L,
            matchedRuleName = BALANCE_ADJUSTMENT_RULE_NAME,
        )
        val done = tx(categoryId = 1L)
        val visible = filter(listOf(uncategorized, adjustment, done), ActivityFilters(triageOnly = true))
        assertEquals(setOf(uncategorized.id, adjustment.id), visible.map { it.id }.toSet())
    }

    @Test
    fun `triage recognises the real balance-adjustment rows the Accounts screen builds`() {
        // Cross-file consistency: buildBalanceAdjustment stamps the rule name the
        // triage predicate keys on. If either side drifts, this fails.
        val adjustment = buildBalanceAdjustment(
            account = tabby,
            computedBalance = BigDecimal("100.00"),
            targetBalance = BigDecimal("250.00"),
        )
        assertTrue(TransactionFilterEngine.needsTriage(adjustment!!))
    }

    // ------------------------------------------------------------------
    // Retained behavior + combinations
    // ------------------------------------------------------------------

    @Test
    fun `query matches the resolved bank name via the resolver`() {
        val fromBank = tx().copy(sender = "ADCBAlert")
        val other = tx(merchant = "Carrefour")
        val visible = TransactionFilterEngine.filter(
            transactions = listOf(fromBank, other),
            filters = ActivityFilters(query = "Abu Dhabi Commercial"),
            accounts = accounts,
            bankNameOf = { sender -> if (sender == "ADCBAlert") "Abu Dhabi Commercial Bank" else null },
            zone = zone,
            today = LocalDate.of(2026, 7, 15),
        )
        assertEquals(listOf(fromBank.id), visible.map { it.id })
    }

    @Test
    fun `direction, account and person filters still apply`() {
        val mine = tx(direction = TransactionDirection.DEBIT, accountId = 1, person = "Amir")
        val wrongDirection = tx(direction = TransactionDirection.CREDIT, accountId = 1, person = "Amir")
        val wrongAccount = tx(direction = TransactionDirection.DEBIT, accountId = 2, person = "Amir")
        val wrongPerson = tx(direction = TransactionDirection.DEBIT, accountId = 1, person = "Sara")
        val filters = ActivityFilters(
            direction = TransactionDirection.DEBIT,
            account = AccountFilter.Specific(1),
            person = PersonFilter.Specific("amir"), // case-insensitive
        )
        val visible = filter(listOf(mine, wrongDirection, wrongAccount, wrongPerson), filters)
        assertEquals(listOf(mine.id), visible.map { it.id })
    }

    @Test
    fun `combined - statement window with amount, source and analytics predicates`() {
        val match = tx(
            LocalDate.of(2026, 6, 20), amount = "150.00", accountId = 1,
            source = TransactionSource.SMS, excluded = false,
        )
        val tooSmall = tx(
            LocalDate.of(2026, 6, 20), amount = "20.00", accountId = 1,
            source = TransactionSource.SMS,
        )
        val wrongSource = tx(
            LocalDate.of(2026, 6, 20), amount = "150.00", accountId = 1,
            source = TransactionSource.MANUAL,
        )
        val flagged = tx(
            LocalDate.of(2026, 6, 20), amount = "150.00", accountId = 1,
            source = TransactionSource.SMS, excluded = true,
        )
        val outsideWindow = tx(
            LocalDate.of(2026, 5, 1), amount = "150.00", accountId = 1,
            source = TransactionSource.SMS,
        )
        val filters = ActivityFilters(
            dateRange = DateRangeFilter.StatementCycle,
            account = AccountFilter.Specific(1),
            minAmount = BigDecimal("100"),
            source = TransactionSource.SMS,
            analyticsStatus = AnalyticsStatusFilter.INCLUDED_ONLY,
        )
        val visible = filter(listOf(match, tooSmall, wrongSource, flagged, outsideWindow), filters)
        assertEquals(listOf(match.id), visible.map { it.id })
    }

    @Test
    fun `active flag - default config is inactive, each new dimension activates it`() {
        assertFalse(ActivityFilters().active)
        assertTrue(ActivityFilters(dateRange = DateRangeFilter.Past30Days).active)
        assertTrue(ActivityFilters(triageOnly = true).active)
        assertTrue(ActivityFilters(minAmount = BigDecimal.ONE).active)
        assertTrue(ActivityFilters(maxAmount = BigDecimal.TEN).active)
        assertTrue(ActivityFilters(source = TransactionSource.MANUAL).active)
        assertTrue(ActivityFilters(analyticsStatus = AnalyticsStatusFilter.EXCLUDED_ONLY).active)
    }

    @Test
    fun `active secondary filter count - zero when nothing beyond time-type-account is set`() {
        assertEquals(0, TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters()))
        // Date range and direction aren't "secondary" -- they get their own chips.
        assertEquals(
            0,
            TransactionFilterEngine.activeSecondaryFilterCount(
                ActivityFilters(dateRange = DateRangeFilter.Past7Days, direction = TransactionDirection.DEBIT)
            ),
        )
    }

    @Test
    fun `active secondary filter count - counts each dimension once, min and max amount as one`() {
        assertEquals(
            1,
            TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters(person = PersonFilter.Unassigned)),
        )
        assertEquals(1, TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters(triageOnly = true)))
        assertEquals(
            1,
            TransactionFilterEngine.activeSecondaryFilterCount(ActivityFilters(minAmount = BigDecimal.ONE)),
        )
        assertEquals(
            1,
            TransactionFilterEngine.activeSecondaryFilterCount(
                ActivityFilters(minAmount = BigDecimal.ONE, maxAmount = BigDecimal.TEN)
            ),
        )
        assertEquals(
            1,
            TransactionFilterEngine.activeSecondaryFilterCount(
                ActivityFilters(kind = com.xemmy.mmbank.domain.model.TransactionKind.REFUND)
            ),
        )
        assertEquals(
            6,
            TransactionFilterEngine.activeSecondaryFilterCount(
                ActivityFilters(
                    person = PersonFilter.Specific("Ahmed"),
                    triageOnly = true,
                    minAmount = BigDecimal.ONE,
                    source = TransactionSource.MANUAL,
                    kind = com.xemmy.mmbank.domain.model.TransactionKind.CARD_PAYMENT,
                    analyticsStatus = AnalyticsStatusFilter.EXCLUDED_ONLY,
                )
            ),
        )
    }

    // ---- history only ------------------------------------------------------

    @Test
    fun `the unassigned filter leaves out rows set aside as history only`() {
        val pending = tx()
        val history = tx().copy(historyOnly = true)
        val filed = tx(accountId = 1)
        val out = filter(listOf(pending, history, filed), ActivityFilters(account = AccountFilter.Unassigned))
        assertEquals(listOf(pending.id), out.map { it.id })
    }

    @Test
    fun `the history only filter shows exactly the rows set aside`() {
        val pending = tx()
        val history = tx().copy(historyOnly = true)
        val filed = tx(accountId = 1)
        val out = filter(listOf(pending, history, filed), ActivityFilters(account = AccountFilter.HistoryOnly))
        assertEquals(listOf(history.id), out.map { it.id })
    }

    @Test
    fun `with no account filter history only rows are still listed`() {
        val history = tx().copy(historyOnly = true)
        assertEquals(1, filter(listOf(history), ActivityFilters()).size)
    }
}
