package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guarantee under test is one sentence: **a retention prune must not move a
 * balance.** Before this existed, turning the retention setting on deleted old
 * rows and every balance they fed quietly shifted by the pruned net, with
 * nothing on screen to explain it.
 *
 * Most cases below therefore assert the same invariant rather than a magic
 * number: compute the balance, prune, apply the fold, and the balance is
 * unchanged.
 */
class PrunedBalanceFolderTest {

    private fun account(id: Long = 1, opening: String = "0", currency: String = "AED") = Account(
        id = id,
        name = "Account $id",
        type = AccountType.CHECKING,
        currencyCode = currency,
        openingBalance = BigDecimal(opening),
    )

    private fun credit(amount: String, accountId: Long? = 1, currency: String = "AED") =
        LedgerEntry(accountId, BigDecimal(amount), currency, TransactionDirection.CREDIT)

    private fun debit(amount: String, accountId: Long? = 1, currency: String = "AED") =
        LedgerEntry(accountId, BigDecimal(amount), currency, TransactionDirection.DEBIT)

    /** Balance after pruning [pruned] from [all] and applying the fold. */
    private fun balanceAfterPrune(
        account: Account,
        all: List<LedgerEntry>,
        pruned: List<LedgerEntry>,
    ): BigDecimal {
        val adjustments = PrunedBalanceFolder.fold(listOf(account), pruned)
        val adjusted = adjustments.firstOrNull { it.accountId == account.id }
            ?.let { account.copy(openingBalance = it.newOpeningBalance) }
            ?: account
        // Remove exactly the pruned rows, preserving duplicates.
        val remaining = all.toMutableList().apply { pruned.forEach { remove(it) } }
        return AccountBalanceCalculator.balanceFor(adjusted, remaining)
    }

    @Test
    fun `the pruned net is folded into openingBalance so the balance is unchanged`() {
        val account = account(opening = "1000")
        val old = listOf(debit("200"), credit("50"))
        val recent = listOf(debit("75"))
        val all = old + recent

        val before = AccountBalanceCalculator.balanceFor(account, all)
        val after = balanceAfterPrune(account, all, old)

        assertEquals(0, before.compareTo(after))
        assertEquals(0, BigDecimal("775").compareTo(after))
    }

    @Test
    fun `pruning debits lowers the opening balance and pruning credits raises it`() {
        val account = account(opening = "1000")

        val debitFold = PrunedBalanceFolder.fold(listOf(account), listOf(debit("200")))
        assertEquals(0, BigDecimal("800").compareTo(debitFold.single().newOpeningBalance))
        assertEquals(0, BigDecimal("-200").compareTo(debitFold.single().foldedNet))

        val creditFold = PrunedBalanceFolder.fold(listOf(account), listOf(credit("200")))
        assertEquals(0, BigDecimal("1200").compareTo(creditFold.single().newOpeningBalance))
        assertEquals(0, BigDecimal("200").compareTo(creditFold.single().foldedNet))
    }

    @Test
    fun `the adjustment reports the previous opening balance for auditing`() {
        val adjustment = PrunedBalanceFolder
            .fold(listOf(account(opening = "42.50")), listOf(debit("2.50")))
            .single()
        assertEquals(0, BigDecimal("42.50").compareTo(adjustment.previousOpeningBalance))
        assertEquals(0, BigDecimal("40.00").compareTo(adjustment.newOpeningBalance))
    }

    @Test
    fun `each account is folded only by its own pruned rows`() {
        val a = account(id = 1, opening = "1000")
        val b = account(id = 2, opening = "500")
        val pruned = listOf(debit("100", accountId = 1), credit("300", accountId = 2))

        val adjustments = PrunedBalanceFolder.fold(listOf(a, b), pruned).associateBy { it.accountId }
        assertEquals(0, BigDecimal("900").compareTo(adjustments.getValue(1).newOpeningBalance))
        assertEquals(0, BigDecimal("800").compareTo(adjustments.getValue(2).newOpeningBalance))
    }

    @Test
    fun `an account with no pruned rows produces no adjustment at all`() {
        // So the pruner writes only the rows it genuinely needs to, rather than
        // rewriting every account's opening balance to its current value.
        val adjustments = PrunedBalanceFolder.fold(
            listOf(account(id = 1, opening = "1000"), account(id = 2, opening = "500")),
            listOf(debit("100", accountId = 1)),
        )
        assertEquals(listOf(1L), adjustments.map { it.accountId })
    }

    @Test
    fun `unassigned pruned rows fold into no account`() {
        // A row belonging to no account was in no balance, so deleting it moves
        // nothing -- that is the "money in no account" gap, not this one.
        val adjustments = PrunedBalanceFolder.fold(
            listOf(account(id = 1, opening = "1000")),
            listOf(debit("100", accountId = null)),
        )
        assertTrue(adjustments.isEmpty())
    }

    @Test
    fun `an empty prune produces no adjustments`() {
        assertTrue(PrunedBalanceFolder.fold(listOf(account()), emptyList()).isEmpty())
        assertTrue(PrunedBalanceFolder.fold(emptyList(), listOf(debit("1"))).isEmpty())
    }

    @Test
    fun `folding is exact for amounts Double would round`() {
        val account = account(opening = "0")
        val all = listOf(credit("0.1"), credit("0.2"), credit("0.7"))
        val pruned = listOf(credit("0.1"), credit("0.2"))

        val before = AccountBalanceCalculator.balanceFor(account, all)
        val after = balanceAfterPrune(account, all, pruned)
        assertEquals(0, before.compareTo(after))
        assertEquals(0, BigDecimal("1.0").compareTo(after))
    }

    @Test
    fun `a foreign-currency pruned row folds at face value, matching the balance`() {
        // The fold has to use the same arithmetic as the balance it protects.
        // Balances sum across currencies at face value (deliberately -- see
        // AccountBalanceCalculatorTest), so the fold must too; converting only
        // one side is what would reintroduce drift.
        val account = account(opening = "1000", currency = "AED")
        val all = listOf(debit("100", currency = "USD"), debit("10", currency = "AED"))
        val pruned = listOf(debit("100", currency = "USD"))

        val before = AccountBalanceCalculator.balanceFor(account, all)
        val after = balanceAfterPrune(account, all, pruned)
        assertEquals(0, before.compareTo(after))
    }

    @Test
    fun `pruning every row leaves the balance standing at the folded opening`() {
        val account = account(opening = "250")
        val all = listOf(credit("100"), debit("30"))

        val before = AccountBalanceCalculator.balanceFor(account, all)
        val after = balanceAfterPrune(account, all, all)
        assertEquals(0, before.compareTo(after))
        assertEquals(0, BigDecimal("320").compareTo(after))
    }
}
