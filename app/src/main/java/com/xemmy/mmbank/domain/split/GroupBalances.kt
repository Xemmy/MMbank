package com.xemmy.mmbank.domain.split

import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.GroupExpense
import java.math.BigDecimal

/**
 * Nets a group's expenses down to one balance per participant (null = "me"):
 * positive means the group owes them money, negative means they owe the
 * group. For each expense, the payer is credited the full amount and every
 * split is debited its own [ExpenseSplit.owedAmount] -- a payer who is also
 * a participant nets to (amount paid - their own share), exactly matching
 * how everyday shared-expense math works: pay for dinner, get back what
 * everyone else owes, keep only your own share as a wash.
 *
 * [SplitType.SETTLEMENT][com.xemmy.mmbank.domain.model.SplitType] needs no
 * special case here -- it's just a [GroupExpense] whose one split is the
 * full amount owed to the person being paid, so it nets out through the same
 * math as any other expense.
 *
 * Pure and top-level so it's JVM-testable.
 */
object GroupBalances {

    fun compute(expenses: List<GroupExpense>, splitsByExpenseId: Map<Long, List<ExpenseSplit>>): Map<Long?, BigDecimal> {
        val balances = mutableMapOf<Long?, BigDecimal>()
        fun add(participantId: Long?, delta: BigDecimal) {
            balances[participantId] = (balances[participantId] ?: BigDecimal.ZERO) + delta
        }
        for (expense in expenses) {
            add(expense.paidByFriendId, expense.amount)
            for (split in splitsByExpenseId[expense.id].orEmpty()) {
                add(split.friendId, -split.owedAmount)
            }
        }
        return balances
    }
}
