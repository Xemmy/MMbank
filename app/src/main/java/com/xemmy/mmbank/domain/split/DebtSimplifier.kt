package com.xemmy.mmbank.domain.split

import java.math.BigDecimal

/**
 * Reduces a group's net balances to a minimal-ish set of settle-up payments:
 * repeatedly pays the largest creditor from the largest debtor until every
 * balance is within a cent of zero. This greedy largest-vs-largest approach
 * is the standard practical approximation for debt simplification (the
 * true minimum-transaction-count problem is NP-hard in general) and is what
 * Splitwise itself uses -- it's never worse, and usually far better, than
 * naively settling every debtor/creditor pair individually.
 *
 * Pure and top-level so it's JVM-testable; the caller supplies each group's
 * net balances (see [GroupBalances]).
 */
object DebtSimplifier {

    private val EPSILON = BigDecimal("0.01")

    /** One suggested payment: [from] pays [to] [amount] to help settle the group. */
    data class Settlement<T>(val from: T, val to: T, val amount: BigDecimal)

    /**
     * [netBalances] should already sum to (approximately) zero -- every debt
     * within a group is owed to someone else in it. A balance within
     * [EPSILON] of zero is treated as already settled and never appears as
     * either a creditor or a debtor.
     */
    fun <T> simplify(netBalances: Map<T, BigDecimal>): List<Settlement<T>> {
        val balances = netBalances.toMutableMap()
        val settlements = mutableListOf<Settlement<T>>()

        while (true) {
            val creditor = balances.maxByOrNull { it.value } ?: break
            val debtor = balances.minByOrNull { it.value } ?: break
            if (creditor.value <= EPSILON || debtor.value >= EPSILON.negate()) break

            val amount = creditor.value.min(debtor.value.negate())
            settlements += Settlement(from = debtor.key, to = creditor.key, amount = amount)
            balances[creditor.key] = creditor.value - amount
            balances[debtor.key] = debtor.value + amount
        }
        return settlements
    }
}
