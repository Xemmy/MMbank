package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.ReportedBalanceKind
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.usecase.TransferMatcher
import java.math.BigDecimal

/**
 * Checks the books against what can be checked, and says what's wrong in terms
 * a person can act on.
 *
 * MMbank is a single-entry ledger: nothing structural proves it's complete, so
 * a missed SMS or a wrong amount leaves the computed balance quietly drifting
 * from the bank's. The bank, though, states its own balance in most messages
 * (kept as [Transaction.reportedBalance]); this reconciles the two.
 *
 * **Balance drift.** For each account, the latest bank-stated balance is
 * compared with the balance the app computes *as of that message* -- opening
 * balance plus every row up to its time. Looking at the same instant is what
 * makes the comparison fair: later transactions can't cause a false alarm, and
 * a fix dated at that instant (see [Reconciliation.asOfMillis]) clears it.
 *  - Current / savings / cash: the stated balance is the account balance.
 *  - Credit card: the stated figure is credit still *available*, so what's owed
 *    is `creditLimit - available`, compared with the app's owed amount. Without
 *    a limit it can't be checked (an info issue says so).
 *
 * Guards against false alarms: accounts holding other-currency rows (balances
 * add at face value, so they can't reconcile), and snapshots older than the
 * retention cutoff (pruned rows are folded into the opening balance, which
 * only holds for the present).
 *
 * The rest are structural: transfers/card payments with no other side, links
 * that don't add up, rows in no account, rows in another currency than their
 * account, and card setup that blocks checks or reminders.
 *
 * Pure -- everything is passed in -- so the whole matrix is unit tested.
 */
object IntegrityChecker {

    /** Below this a difference is rounding noise, not a discrepancy. */
    private val TOLERANCE = BigDecimal("0.01")

    /** How long a transfer / card payment may wait for its other side before it's flagged. */
    const val UNPAIRED_AFTER_MILLIS: Long = TransferMatcher.CARD_PAYMENT_WINDOW_MILLIS

    enum class Severity { PROBLEM, WARNING, INFO }

    enum class Type { BALANCE_DRIFT, UNPAIRED_MOVE, BROKEN_LINK, UNASSIGNED, CURRENCY_MISMATCH, CARD_SETUP }

    enum class LinkProblem { PARTNER_MISSING, NOT_MUTUAL, AMOUNT_DIFFERS, SAME_ACCOUNT }

    enum class SetupItem { CREDIT_LIMIT, DUE_DAY }

    enum class Status { MATCHES, DIFFERS, NO_BANK_BALANCE, CANNOT_CHECK }

    enum class Why { MIXED_CURRENCY, OLDER_THAN_RETENTION, NEEDS_CREDIT_LIMIT, AMBIGUOUS_BALANCE }

    /**
     * One account's reconciliation. Balances are in the app's signed
     * convention (a card you owe on is negative); [drift] is bank minus app,
     * so positive means the bank shows more.
     */
    data class Reconciliation(
        val accountId: Long,
        val status: Status,
        val why: Why? = null,
        val bankBalance: BigDecimal? = null,
        /** The app's raw computed balance at [asOfMillis] -- the value to hand to a balance adjustment. */
        val computedBalance: BigDecimal? = null,
        val drift: BigDecimal? = null,
        val asOfMillis: Long? = null,
    )

    data class Issue(
        val type: Type,
        val severity: Severity,
        val accountId: Long? = null,
        val currencyCode: String? = null,
        val transactionIds: List<Long> = emptyList(),
        /** Rows affected (unassigned, wrong-currency). */
        val count: Int = 0,
        /** Drift (bank minus app) for [Type.BALANCE_DRIFT]; the row's amount for an unpaired move. */
        val amount: BigDecimal? = null,
        val bankBalance: BigDecimal? = null,
        val computedBalance: BigDecimal? = null,
        val asOfMillis: Long? = null,
        val transactionKind: TransactionKind? = null,
        val linkProblem: LinkProblem? = null,
        val setupMissing: List<SetupItem> = emptyList(),
        /** Setup issue only: a bank balance is waiting to be checked once this is filled in. */
        val blocksBankCheck: Boolean = false,
    )

    data class Report(
        val issues: List<Issue>,
        val reconciliations: Map<Long, Reconciliation>,
    ) {
        /** Issues worth interrupting for: everything except info hints. */
        val actionableCount: Int get() = issues.count { it.severity != Severity.INFO }
    }

    /**
     * @param accounts the accounts to check (active ones); rows on any other account are left alone
     * @param transactions the ledger in the same view (real or demo) as [accounts]
     * @param retentionCutoffMillis when retention pruning is on, the oldest instant still reconcilable
     */
    fun check(
        accounts: List<Account>,
        transactions: List<Transaction>,
        nowMillis: Long,
        retentionCutoffMillis: Long? = null,
    ): Report {
        val byId = transactions.associateBy { it.id }
        val byAccount = transactions.filter { it.accountId != null }.groupBy { it.accountId!! }
        val accountsById = accounts.associateBy { it.id }
        val issues = mutableListOf<Issue>()
        val reconciliations = LinkedHashMap<Long, Reconciliation>()

        for (account in accounts) {
            val own = byAccount[account.id].orEmpty()
            val rec = reconcile(account, own, retentionCutoffMillis)
            reconciliations[account.id] = rec
            if (rec.status == Status.DIFFERS) {
                issues += Issue(
                    type = Type.BALANCE_DRIFT,
                    severity = Severity.PROBLEM,
                    accountId = account.id,
                    currencyCode = account.currencyCode,
                    amount = rec.drift,
                    bankBalance = rec.bankBalance,
                    computedBalance = rec.computedBalance,
                    asOfMillis = rec.asOfMillis,
                )
            }

            val wrongCurrency = own.filter { !it.currencyCode.equals(account.currencyCode, ignoreCase = true) }
            if (wrongCurrency.isNotEmpty()) {
                issues += Issue(
                    type = Type.CURRENCY_MISMATCH,
                    severity = Severity.WARNING,
                    accountId = account.id,
                    currencyCode = account.currencyCode,
                    transactionIds = wrongCurrency.map { it.id },
                    count = wrongCurrency.size,
                )
            }

            if (account.type == AccountType.CREDIT_CARD) {
                val missing = buildList {
                    if (account.creditLimit == null || account.creditLimit.signum() <= 0) add(SetupItem.CREDIT_LIMIT)
                    if (account.dueDayOfMonth == null) add(SetupItem.DUE_DAY)
                }
                if (missing.isNotEmpty()) {
                    issues += Issue(
                        type = Type.CARD_SETUP,
                        severity = Severity.INFO,
                        accountId = account.id,
                        currencyCode = account.currencyCode,
                        setupMissing = missing,
                        blocksBankCheck = rec.why == Why.NEEDS_CREDIT_LIMIT,
                    )
                }
            }
        }

        val unassigned = transactions.filter { it.accountId == null }
        if (unassigned.isNotEmpty()) {
            issues += Issue(
                type = Type.UNASSIGNED,
                severity = Severity.WARNING,
                transactionIds = unassigned.map { it.id },
                count = unassigned.size,
            )
        }

        // A transfer / card payment the app classified by itself that never found its other side.
        // One the user classified (kindUserSet) is theirs to vouch for, so it isn't second-guessed.
        for (tx in transactions) {
            if (!tx.kind.isInternalMove || tx.linkedTransactionId != null || tx.kindUserSet) continue
            if (tx.accountId != null && tx.accountId !in accountsById) continue
            if (tx.timestampMillis >= nowMillis - UNPAIRED_AFTER_MILLIS) continue
            issues += Issue(
                type = Type.UNPAIRED_MOVE,
                severity = Severity.WARNING,
                accountId = tx.accountId,
                currencyCode = tx.currencyCode,
                transactionIds = listOf(tx.id),
                amount = tx.amount,
                asOfMillis = tx.timestampMillis,
                transactionKind = tx.kind,
            )
        }

        for (tx in transactions) {
            val linkedId = tx.linkedTransactionId ?: continue
            val partner = byId[linkedId]
            val problem = when {
                partner == null -> LinkProblem.PARTNER_MISSING
                partner.linkedTransactionId != tx.id -> LinkProblem.NOT_MUTUAL
                tx.accountId != null && tx.accountId == partner.accountId -> LinkProblem.SAME_ACCOUNT
                // Different currencies are a legitimate cross-currency transfer; only same-currency legs must match.
                tx.currencyCode.equals(partner.currencyCode, ignoreCase = true) &&
                    tx.amount.compareTo(partner.amount) != 0 -> LinkProblem.AMOUNT_DIFFERS
                else -> null
            } ?: continue
            // Report each pair once.
            if (partner != null && partner.linkedTransactionId == tx.id && partner.id < tx.id) continue
            issues += Issue(
                type = Type.BROKEN_LINK,
                severity = Severity.PROBLEM,
                accountId = tx.accountId,
                currencyCode = tx.currencyCode,
                transactionIds = listOfNotNull(tx.id, partner?.id),
                amount = tx.amount,
                asOfMillis = tx.timestampMillis,
                transactionKind = tx.kind,
                linkProblem = problem,
            )
        }

        val sorted = issues.sortedWith(
            compareBy<Issue>({ it.severity.ordinal }, { it.type.ordinal }).thenByDescending { it.asOfMillis ?: 0L },
        )
        return Report(sorted, reconciliations)
    }

    private fun reconcile(account: Account, own: List<Transaction>, retentionCutoffMillis: Long?): Reconciliation {
        fun unknown(status: Status, why: Why? = null, asOf: Long? = null) =
            Reconciliation(account.id, status, why, asOfMillis = asOf)

        val isCard = account.type == AccountType.CREDIT_CARD
        // A card's printed figure is credit still available; a current account's "card balance" is its balance.
        val accepted = if (isCard) {
            setOf(ReportedBalanceKind.AVAILABLE_CREDIT)
        } else {
            setOf(ReportedBalanceKind.ACCOUNT_BALANCE, ReportedBalanceKind.AVAILABLE_CREDIT)
        }
        val latest = own
            .filter {
                it.reportedBalance != null && it.reportedBalanceKind in accepted &&
                    it.currencyCode.equals(account.currencyCode, ignoreCase = true)
            }
            .maxWithOrNull(compareBy<Transaction>({ it.timestampMillis }, { it.id }))
            ?: return if (isCard && own.any { it.reportedBalance != null }) {
                unknown(Status.CANNOT_CHECK, Why.AMBIGUOUS_BALANCE)
            } else {
                unknown(Status.NO_BANK_BALANCE)
            }
        val at = latest.timestampMillis

        if (own.any { !it.currencyCode.equals(account.currencyCode, ignoreCase = true) }) {
            return unknown(Status.CANNOT_CHECK, Why.MIXED_CURRENCY, at)
        }
        if (retentionCutoffMillis != null && at < retentionCutoffMillis) {
            return unknown(Status.CANNOT_CHECK, Why.OLDER_THAN_RETENTION, at)
        }

        val computed = own
            .filter { it.timestampMillis <= at }
            .fold(account.openingBalance) { acc, tx ->
                if (tx.direction == TransactionDirection.CREDIT) acc + tx.amount else acc - tx.amount
            }
        val reported = latest.reportedBalance!!

        val bank: BigDecimal
        val effective: BigDecimal
        if (isCard) {
            val limit = account.creditLimit?.takeIf { it.signum() > 0 }
                ?: return unknown(Status.CANNOT_CHECK, Why.NEEDS_CREDIT_LIMIT, at)
            // What's owed is the limit less what's still available, never negative; compared against
            // what the app thinks is owed. A card in credit owes nothing, however far in credit.
            bank = (reported - limit).min(BigDecimal.ZERO)
            effective = computed.min(BigDecimal.ZERO)
        } else {
            bank = reported
            effective = computed
        }
        val drift = bank - effective
        return Reconciliation(
            accountId = account.id,
            status = if (drift.abs() < TOLERANCE) Status.MATCHES else Status.DIFFERS,
            bankBalance = bank,
            computedBalance = computed,
            drift = drift,
            asOfMillis = at,
        )
    }
}
