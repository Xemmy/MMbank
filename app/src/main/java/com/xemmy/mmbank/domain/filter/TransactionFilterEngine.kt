package com.xemmy.mmbank.domain.filter

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Rule name the Accounts screen stamps on reconciliation rows
 * (see buildBalanceAdjustment in AccountsScreen.kt). The triage filter keys
 * on it; a cross-file test asserts the two stay in sync.
 */
const val BALANCE_ADJUSTMENT_RULE_NAME = "Balance adjustment"

/** Date dimension of the Activity filter. */
sealed interface DateRangeFilter {
    data object All : DateRangeFilter
    data object Today : DateRangeFilter
    data object Past7Days : DateRangeFilter
    data object Past30Days : DateRangeFilter
    data object YearToDate : DateRangeFilter

    /**
     * Statement cycle of the actively filtered account, driven by
     * [Account.statementCycleDay]. Falls back to a rolling 30 days when no
     * single account is selected or the account has no cycle day.
     */
    data object StatementCycle : DateRangeFilter

    /** Arbitrary inclusive range from the twin-calendar picker. */
    data class Custom(val start: LocalDate, val end: LocalDate) : DateRangeFilter
}

/** Account dimension of the Activity filter: any / only unassigned / one account. */
sealed interface AccountFilter {
    data object Any : AccountFilter
    data object Unassigned : AccountFilter
    data class Specific(val id: Long) : AccountFilter
}

/** Person dimension of the Activity filter: any / only unassigned / one person. */
sealed interface PersonFilter {
    data object Any : PersonFilter
    data object Unassigned : PersonFilter
    data class Specific(val name: String) : PersonFilter
}

/** Analytics dimension: only rows counted in stats, or only excluded ones. */
enum class AnalyticsStatusFilter(val label: String) {
    INCLUDED_ONLY("Included only"),
    EXCLUDED_ONLY("Excluded only"),
}

/** All Activity-tab filters, folded into one value so the ViewModel combine stays small. */
data class ActivityFilters(
    val dateRange: DateRangeFilter = DateRangeFilter.All,
    val query: String = "",
    val direction: TransactionDirection? = null,
    val account: AccountFilter = AccountFilter.Any,
    val person: PersonFilter = PersonFilter.Any,
    /** "Inbox Zero" triage: only rows still needing review. */
    val triageOnly: Boolean = false,
    /** Inclusive amount bounds; either side may be open. */
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val source: TransactionSource? = null,
    val analyticsStatus: AnalyticsStatusFilter? = null,
) {
    /** True when anything beyond the default (show-everything) is set. */
    val active: Boolean
        get() = this != ActivityFilters()
}

/** Inclusive calendar-day window a date filter resolves to. */
data class DateWindow(val start: LocalDate, val end: LocalDate) {
    fun contains(timestampMillis: Long, zone: ZoneId): Boolean {
        val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val endExclusiveMillis = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return timestampMillis in startMillis until endExclusiveMillis
    }
}

/**
 * Pure-Kotlin, Compose-free filtering core behind the Activity tab.
 * [TransactionsViewModel][com.xemmy.mmbank.ui.screens.transactions.TransactionsViewModel]
 * delegates here; keeping it free of Android types is what makes the full
 * filter matrix testable on the JVM.
 */
object TransactionFilterEngine {

    /** Applies every active filter in [filters] to [transactions], preserving order. */
    fun filter(
        transactions: List<Transaction>,
        filters: ActivityFilters,
        accounts: List<Account> = emptyList(),
        /** Resolves a raw sender to its display bank name for query matching. */
        bankNameOf: (String?) -> String? = { null },
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
    ): List<Transaction> {
        val window = resolveDateWindow(filters.dateRange, activeAccount(filters.account, accounts), today)
        return transactions.filter { matches(it, filters, window, bankNameOf, zone) }
    }

    /** The single selected account, if the filter names one that still exists. */
    fun activeAccount(filter: AccountFilter, accounts: List<Account>): Account? =
        (filter as? AccountFilter.Specific)?.let { spec -> accounts.firstOrNull { it.id == spec.id } }

    /**
     * How many independent secondary-filter categories (person, triage,
     * amount, source, analytics status) are active — drives the "More
     * filters" badge count in the single-row filter bar.
     */
    fun activeSecondaryFilterCount(filters: ActivityFilters): Int {
        var count = 0
        if (filters.person != PersonFilter.Any) count++
        if (filters.triageOnly) count++
        if (filters.minAmount != null || filters.maxAmount != null) count++
        if (filters.source != null) count++
        if (filters.analyticsStatus != null) count++
        return count
    }

    /** Resolves a date filter to a concrete window; null = unbounded (All). */
    fun resolveDateWindow(
        range: DateRangeFilter,
        activeAccount: Account?,
        today: LocalDate,
    ): DateWindow? = when (range) {
        DateRangeFilter.All -> null
        DateRangeFilter.Today -> DateWindow(today, today)
        DateRangeFilter.Past7Days -> DateWindow(today.minusDays(6), today)
        DateRangeFilter.Past30Days -> rolling30(today)
        DateRangeFilter.YearToDate -> DateWindow(today.withDayOfYear(1), today)
        DateRangeFilter.StatementCycle ->
            activeAccount?.statementCycleDay?.let { statementCycleWindow(it, today) } ?: rolling30(today)
        is DateRangeFilter.Custom ->
            if (range.start <= range.end) DateWindow(range.start, range.end) else DateWindow(range.end, range.start)
    }

    /**
     * `[cycleDay of previous month, cycleDay − 1 of current month]`, e.g. cycle
     * day 10 → 10th of last month through 9th of this month. Cycle days past a
     * short month's length clamp to that month's last day; the end is always
     * "day before the next cycle starts" so no day ever belongs to two windows.
     */
    fun statementCycleWindow(cycleDay: Int, today: LocalDate): DateWindow {
        val currentMonth = YearMonth.from(today)
        val start = clampedDay(currentMonth.minusMonths(1), cycleDay)
        val end = clampedDay(currentMonth, cycleDay).minusDays(1)
        return DateWindow(start, end)
    }

    private fun rolling30(today: LocalDate) = DateWindow(today.minusDays(29), today)

    private fun clampedDay(month: YearMonth, day: Int): LocalDate =
        month.atDay(day.coerceIn(1, month.lengthOfMonth()))

    /**
     * "Inbox Zero" triage: rows still needing a human look — uncategorized
     * captures plus system-generated balance corrections.
     */
    fun needsTriage(tx: Transaction): Boolean =
        tx.spendingCategory == SpendingCategory.UNCATEGORIZED ||
            tx.matchedRuleName == BALANCE_ADJUSTMENT_RULE_NAME

    private fun matches(
        tx: Transaction,
        f: ActivityFilters,
        window: DateWindow?,
        bankNameOf: (String?) -> String?,
        zone: ZoneId,
    ): Boolean {
        if (window != null && !window.contains(tx.timestampMillis, zone)) return false
        if (f.direction != null && tx.direction != f.direction) return false
        when (val a = f.account) {
            AccountFilter.Any -> Unit
            AccountFilter.Unassigned -> if (tx.accountId != null) return false
            is AccountFilter.Specific -> if (tx.accountId != a.id) return false
        }
        when (val p = f.person) {
            PersonFilter.Any -> Unit
            PersonFilter.Unassigned -> if (!tx.person.isNullOrBlank()) return false
            is PersonFilter.Specific -> if (!tx.person.equals(p.name, ignoreCase = true)) return false
        }
        if (f.triageOnly && !needsTriage(tx)) return false
        if (f.minAmount != null && tx.amount < f.minAmount) return false
        if (f.maxAmount != null && tx.amount > f.maxAmount) return false
        if (f.source != null && tx.source != f.source) return false
        when (f.analyticsStatus) {
            AnalyticsStatusFilter.INCLUDED_ONLY -> if (tx.excludeFromStats) return false
            AnalyticsStatusFilter.EXCLUDED_ONLY -> if (!tx.excludeFromStats) return false
            null -> Unit
        }
        val q = f.query.trim()
        if (q.isNotEmpty()) {
            val haystack = listOfNotNull(
                tx.merchant,
                tx.rawMessage,
                tx.sender,
                bankNameOf(tx.sender),
                tx.amount.toPlainString(),
                tx.currencyCode,
                tx.spendingCategory.label,
            )
            if (haystack.none { it.contains(q, ignoreCase = true) }) return false
        }
        return true
    }
}
