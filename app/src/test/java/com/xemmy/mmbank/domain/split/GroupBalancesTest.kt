package com.xemmy.mmbank.domain.split

import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.SplitType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GroupBalancesTest {

    private fun expense(id: Long, amount: String, paidBy: Long?) = GroupExpense(
        id = id,
        groupId = 1L,
        description = "Dinner",
        amount = BigDecimal(amount),
        currencyCode = "AED",
        paidByFriendId = paidBy,
        splitType = SplitType.EQUAL,
    )

    private fun split(expenseId: Long, friendId: Long?, owed: String) =
        ExpenseSplit(expenseId = expenseId, friendId = friendId, owedAmount = BigDecimal(owed))

    @Test
    fun `a payer who is also a participant nets to amount paid minus their own share`() {
        // "Me" (null) pays 90 for a dinner split equally three ways with friend 2 and friend 3.
        val expenses = listOf(expense(1, "90.00", paidBy = null))
        val splits = mapOf(
            1L to listOf(split(1, null, "30.00"), split(1, 2L, "30.00"), split(1, 3L, "30.00")),
        )
        val balances = GroupBalances.compute(expenses, splits)
        assertEquals(0, balances.getValue(null).compareTo(BigDecimal("60.00"))) // 90 paid - 30 own share
        assertEquals(0, balances.getValue(2L).compareTo(BigDecimal("-30.00")))
        assertEquals(0, balances.getValue(3L).compareTo(BigDecimal("-30.00")))
    }

    @Test
    fun `a payer who did not take a share of their own expense is fully owed it back`() {
        // Friend 2 pays for something split only between "me" and friend 3.
        val expenses = listOf(expense(1, "50.00", paidBy = 2L))
        val splits = mapOf(
            1L to listOf(split(1, null, "25.00"), split(1, 3L, "25.00")),
        )
        val balances = GroupBalances.compute(expenses, splits)
        assertEquals(0, balances.getValue(2L).compareTo(BigDecimal("50.00")))
        assertEquals(0, balances.getValue(null).compareTo(BigDecimal("-25.00")))
        assertEquals(0, balances.getValue(3L).compareTo(BigDecimal("-25.00")))
    }

    @Test
    fun `multiple expenses accumulate into running balances`() {
        val expenses = listOf(
            expense(1, "20.00", paidBy = null),
            expense(2, "10.00", paidBy = 2L),
        )
        val splits = mapOf(
            1L to listOf(split(1, null, "10.00"), split(1, 2L, "10.00")),
            2L to listOf(split(2, null, "5.00"), split(2, 2L, "5.00")),
        )
        val balances = GroupBalances.compute(expenses, splits)
        // me: +20 (paid) -10 (own share of #1) -5 (own share of #2) = 5
        assertEquals(0, balances.getValue(null).compareTo(BigDecimal("5.00")))
        // friend 2: -10 (owed on #1) +10 (paid #2) -5 (own share of #2) = -5
        assertEquals(0, balances.getValue(2L).compareTo(BigDecimal("-5.00")))
    }

    @Test
    fun `a settlement expense nets exactly like any other expense`() {
        // Friend 2 settles up by paying "me" 15 directly.
        val expenses = listOf(
            GroupExpense(
                id = 1,
                groupId = 1,
                description = "Settlement",
                amount = BigDecimal("15.00"),
                currencyCode = "AED",
                paidByFriendId = 2L,
                splitType = SplitType.SETTLEMENT,
            ),
        )
        val splits = mapOf(1L to listOf(split(1, null, "15.00")))
        val balances = GroupBalances.compute(expenses, splits)
        assertEquals(0, balances.getValue(2L).compareTo(BigDecimal("15.00")))
        assertEquals(0, balances.getValue(null).compareTo(BigDecimal("-15.00")))
    }

    @Test
    fun `no expenses means no balances`() {
        assertEquals(emptyMap<Long?, BigDecimal>(), GroupBalances.compute(emptyList(), emptyMap()))
    }

    @Test
    fun `an expense with no recorded splits yet only credits the payer`() {
        val expenses = listOf(expense(1, "40.00", paidBy = 2L))
        val balances = GroupBalances.compute(expenses, emptyMap())
        assertEquals(0, balances.getValue(2L).compareTo(BigDecimal("40.00")))
        assertNull(balances[null])
    }
}
