package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal

/**
 * "When did the difference appear?" [IntegrityChecker] says the books differ from
 * the balance the bank last printed; this walks the account's earlier bank-printed
 * balances backwards to find the last one the books *did* match and the first one
 * they stopped matching at. The difference appeared somewhere in between, so that
 * is where to look: the transactions in the window and the changes made since.
 *
 * Each check uses the books as they are now (the current opening balance and rows),
 * the same way [IntegrityChecker] does, so an edit made later still shows up as a
 * mismatch at an older point -- which is exactly what the change log then explains.
 * Pure, so the walk and its edge cases are unit tested.
 */
object DriftWindow {

    private val TOLERANCE = BigDecimal("0.01")

    data class Window(
        val accountId: Long,
        val currencyCode: String,
        /** Event time of the last bank balance the books matched; null = they never matched. */
        val fromMillis: Long?,
        /** Event time of the first bank balance they stopped matching at. */
        val toMillis: Long,
        /** Bank minus books at [toMillis]; positive = the app shows less than the bank. */
        val drift: BigDecimal,
    ) {
        /** Whether a transaction at [millis] could have caused it: after the last match, up to the first mismatch. */
        fun containsEvent(millis: Long): Boolean = (fromMillis == null || millis > fromMillis) && millis <= toMillis
    }

    data class Summary(val transactionCount: Int, val changeCount: Int)

    /**
     * The window for [account]'s current difference, or null when there is none to
     * explain (it matches, no bank balance can be compared, mixed currencies, a card
     * without its limit, or the comparison predates retention).
     */
    fun find(
        account: Account,
        transactions: List<Transaction>,
        retentionCutoffMillis: Long? = null,
    ): Window? {
        val own = transactions.filter { it.accountId == account.id }
        if (own.any { !it.currencyCode.equals(account.currencyCode, ignoreCase = true) }) return null
        val isCard = account.type == AccountType.CREDIT_CARD
        val accepted = if (isCard) {
            setOf(ReportedBalanceKind.AVAILABLE_CREDIT)
        } else {
            setOf(ReportedBalanceKind.ACCOUNT_BALANCE, ReportedBalanceKind.AVAILABLE_CREDIT)
        }
        val anchors = own
            .filter {
                it.reportedBalance != null && it.reportedBalanceKind in accepted &&
                    (retentionCutoffMillis == null || it.timestampMillis >= retentionCutoffMillis)
            }
            .sortedWith(compareBy<Transaction>({ it.timestampMillis }, { it.id }))
        if (anchors.isEmpty()) return null
        val limit = if (isCard) account.creditLimit?.takeIf { it.signum() > 0 } ?: return null else null

        fun driftAt(anchor: Transaction): BigDecimal {
            val computed = own
                .filter { it.timestampMillis <= anchor.timestampMillis }
                .fold(account.openingBalance) { acc, tx ->
                    if (tx.direction == TransactionDirection.CREDIT) acc + tx.amount else acc - tx.amount
                }
            val reported = anchor.reportedBalance!!
            return if (limit != null) {
                // A card's printed figure is credit still available; compare what is owed, never below zero.
                (reported - limit).min(BigDecimal.ZERO) - computed.min(BigDecimal.ZERO)
            } else {
                reported - computed
            }
        }

        fun differs(drift: BigDecimal) = drift.abs() >= TOLERANCE

        val newestDrift = driftAt(anchors.last())
        if (!differs(newestDrift)) return null

        // Walk back through the unbroken run of mismatches to where it began.
        var firstBad = anchors.lastIndex
        var lastGood: Int? = null
        for (i in anchors.lastIndex - 1 downTo 0) {
            if (differs(driftAt(anchors[i]))) firstBad = i else { lastGood = i; break }
        }
        return Window(
            accountId = account.id,
            currencyCode = account.currencyCode,
            fromMillis = lastGood?.let { anchors[it].timestampMillis },
            toMillis = anchors[firstBad].timestampMillis,
            drift = driftAt(anchors[firstBad]),
        )
    }

    /**
     * What there is to look at: the transactions dated inside the window, and the
     * balance-moving changes made since the last match (an edit made before then
     * cannot have caused a difference that appeared after it).
     */
    fun summarize(window: Window, transactions: List<Transaction>, changes: List<AccountChange>): Summary = Summary(
        transactionCount = transactions.count { it.accountId == window.accountId && window.containsEvent(it.timestampMillis) },
        changeCount = changes.count { relevant(window, it) },
    )

    /** Whether [change] is worth showing for [window]. */
    fun relevant(window: Window, change: AccountChange): Boolean =
        change.accountId == window.accountId && change.type.canMoveBalance &&
            (window.fromMillis == null || change.timestampMillis >= window.fromMillis)

    /** "Difference of 120.00 appeared between 3 Nov and 5 Nov: 4 transactions, 1 change." Sign hidden with balances. */
    fun describe(
        window: Window,
        summary: Summary,
        hidden: Boolean,
        money: (BigDecimal, String) -> String,
        date: (Long) -> String,
    ): String {
        val sign = if (hidden) "" else if (window.drift.signum() < 0) "−" else "+"
        val amount = sign + money(window.drift.abs(), window.currencyCode)
        val whenText = if (window.fromMillis == null) {
            "was there from the first bank balance (${date(window.toMillis)})"
        } else {
            "appeared between ${date(window.fromMillis)} and ${date(window.toMillis)}"
        }
        val tx = if (summary.transactionCount == 1) "1 transaction" else "${summary.transactionCount} transactions"
        val ch = if (summary.changeCount == 1) "1 change" else "${summary.changeCount} changes"
        return "A difference of $amount $whenText: $tx in that time, $ch made since."
    }
}
