package com.xemmy.mmbank.domain.demo

import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import java.math.BigDecimal
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validity of the disposable demo batch. Pure JVM: the clock and RNG are
 * injected, so output is deterministic and no Room/Android is involved. These
 * pin the invariants Demo mode relies on — everything tagged demo, referentially
 * intact, and a realistic spread.
 */
class DemoDataGeneratorTest {

    private val now = 1_700_000_000_000L

    /** Fake "inserted ids" — the temp key doubles as the row id for the test. */
    private fun idMap(batch: DemoDataGenerator.Batch) =
        batch.accounts.associate { it.key to it.key.toLong() }

    @Test
    fun `all accounts are demo and span several types including a card`() {
        val batch = DemoDataGenerator.generate(random = Random(1), now = now)
        assertTrue(batch.accounts.all { it.entity.isDemo })
        val types = batch.accounts.map { it.entity.type }.toSet()
        assertTrue("expected a credit card", AccountType.CREDIT_CARD in types)
        assertTrue("expected a variety of account types", types.size >= 4)
    }

    @Test
    fun `all transactions are demo, positive, and in the batch currency`() {
        val batch = DemoDataGenerator.generate(currency = "EUR", random = Random(2), now = now)
        val txns = batch.transactions(idMap(batch))
        assertTrue(txns.isNotEmpty())
        assertTrue(txns.all { it.isDemo })
        assertTrue(txns.all { it.amount > BigDecimal.ZERO })
        assertTrue(txns.all { it.currencyCode == "EUR" })
    }

    @Test
    fun `every transaction references an account in the batch`() {
        val batch = DemoDataGenerator.generate(random = Random(3), now = now)
        val ids = idMap(batch)
        val validIds = ids.values.toSet()
        assertTrue(batch.transactions(ids).all { it.accountId in validIds })
    }

    @Test
    fun `both directions and earned cashback are represented`() {
        val batch = DemoDataGenerator.generate(random = Random(4), now = now)
        val txns = batch.transactions(idMap(batch))
        assertTrue(txns.any { it.direction == TransactionDirection.CREDIT })
        assertTrue(txns.any { it.direction == TransactionDirection.DEBIT })
        assertTrue(txns.any { it.category == TransactionCategory.CASHBACK_EARNED })
    }

    @Test
    fun `timestamps fall within a recent window ending at now`() {
        val batch = DemoDataGenerator.generate(random = Random(5), now = now)
        val window = 200L * 86_400_000L
        assertTrue(batch.transactions(idMap(batch)).all { it.timestamp in (now - window)..now })
    }

    @Test
    fun `same seed produces identical output`() {
        fun run() = DemoDataGenerator.generate(random = Random(42), now = now)
            .let { b -> b.transactions(idMap(b)).map { it.amount to it.timestamp } }
        assertEquals(run(), run())
    }

    @Test
    fun `debit transactions are shared among the demo people`() {
        val batch = DemoDataGenerator.generate(random = Random(6), now = now)
        val txns = batch.transactions(idMap(batch))
        val people = txns.mapNotNull { it.person }.toSet()
        assertTrue("demo debits should carry demo people", people.isNotEmpty())
        assertTrue("only demo people are used", DemoDataGenerator.DEMO_PEOPLE.containsAll(people))
        assertTrue(
            "credits stay unassigned",
            txns.filter { it.direction == TransactionDirection.CREDIT }.all { it.person == null },
        )
    }

    @Test
    fun `demo rules are all flagged demo and cover both directions`() {
        val rules = DemoDataGenerator.generate(random = Random(7), now = now).rules
        assertTrue(rules.isNotEmpty())
        assertTrue(rules.all { it.isDemo })
        assertTrue(rules.any { it.direction == TransactionDirection.DEBIT })
        assertTrue(rules.any { it.direction == TransactionDirection.CREDIT })
    }
}
