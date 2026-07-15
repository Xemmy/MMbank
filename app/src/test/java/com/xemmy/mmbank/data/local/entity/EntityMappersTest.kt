package com.xemmy.mmbank.data.local.entity

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Domain <-> entity mapper round-trips. Every field added to an entity must
 * survive `toDomain()` / `toEntity()` in both directions (project convention),
 * so these tests pin the whole object, with focused assertions on the fields
 * the v9 schema added (Transaction.excludeFromStats, Account.statementCycleDay)
 * and the v10 schema added (isDemo on both).
 */
class EntityMappersTest {

    private fun transaction(excludeFromStats: Boolean, isDemo: Boolean = false) = Transaction(
        id = 42,
        accountId = 7,
        amount = BigDecimal("229.59"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        merchant = "Carrefour",
        source = TransactionSource.PUSH_NOTIFICATION,
        sender = "com.tabby.customer",
        rawMessage = "Transaction of AED 229.59 At Carrefour was successful.",
        matchedRuleName = "builtin.en.debit.tabby.purchase",
        timestampMillis = 1_700_000_000_000,
        createdAt = 1_700_000_000_500,
        accountNumberSuffix = "617",
        category = TransactionCategory.NORMAL,
        person = "Me",
        spendingCategory = SpendingCategory.GROCERIES,
        excludeFromStats = excludeFromStats,
        isDemo = isDemo,
    )

    private fun account(statementCycleDay: Int?, isDemo: Boolean = false) = Account(
        id = 3,
        name = "Tabby Card",
        type = AccountType.CREDIT_CARD,
        currencyCode = "AED",
        openingBalance = BigDecimal("0.00"),
        archived = false,
        notes = "BNPL card",
        createdAt = 1_690_000_000_000,
        accountNumber = "963",
        bankName = "Tabby",
        accountTitle = "Personal",
        iban = "AE070331234567890123456",
        swift = "TABBAEAD",
        outstandingBalance = BigDecimal("120.50"),
        nextInstallmentAmount = BigDecimal("40.17"),
        dueDayOfMonth = 15,
        profitRatePa = 0.0,
        maturityDateMillis = 1_800_000_000_000,
        loanNotes = "no fees",
        statementCycleDay = statementCycleDay,
        isDemo = isDemo,
    )

    @Test
    fun `transaction round-trip preserves excludeFromStats = true`() {
        val original = transaction(excludeFromStats = true)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertTrue(roundTripped.excludeFromStats)
    }

    @Test
    fun `transaction round-trip preserves excludeFromStats = false`() {
        val original = transaction(excludeFromStats = false)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertFalse(roundTripped.excludeFromStats)
    }

    @Test
    fun `entity-first transaction round-trip preserves excludeFromStats`() {
        val entity = transaction(excludeFromStats = true).toEntity()
        assertTrue(entity.excludeFromStats)
        assertEquals(entity, entity.toDomain().toEntity())
    }

    @Test
    fun `transaction entity defaults excludeFromStats to false`() {
        // Mirrors the migration's NOT NULL DEFAULT 0: rows predating v9 load unflagged.
        val entity = TransactionEntity(
            amount = BigDecimal.ONE,
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = 0,
            createdAt = 0,
        )
        assertFalse(entity.excludeFromStats)
        assertFalse(entity.toDomain().excludeFromStats)
    }

    @Test
    fun `account round-trip preserves non-null statementCycleDay`() {
        val original = account(statementCycleDay = 10)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(10, roundTripped.statementCycleDay)
    }

    @Test
    fun `account round-trip preserves null statementCycleDay`() {
        val original = account(statementCycleDay = null)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.statementCycleDay)
    }

    @Test
    fun `entity-first account round-trip preserves statementCycleDay`() {
        val entity = account(statementCycleDay = 10).toEntity()
        assertEquals(10, entity.statementCycleDay)
        assertEquals(entity, entity.toDomain().toEntity())
    }

    @Test
    fun `transaction round-trip preserves isDemo in both states`() {
        val demo = transaction(excludeFromStats = false, isDemo = true).toEntity().toDomain()
        assertTrue(demo.isDemo)
        val real = transaction(excludeFromStats = false, isDemo = false).toEntity().toDomain()
        assertFalse(real.isDemo)
    }

    @Test
    fun `account round-trip preserves isDemo in both states`() {
        val demo = account(statementCycleDay = 10, isDemo = true).toEntity().toDomain()
        assertTrue(demo.isDemo)
        val real = account(statementCycleDay = 10, isDemo = false).toEntity().toDomain()
        assertFalse(real.isDemo)
    }

    @Test
    fun `entities default isDemo to false`() {
        // Mirrors the v10 migration's NOT NULL DEFAULT 0: rows predating v10 load real.
        val txn = TransactionEntity(
            amount = BigDecimal.ONE,
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = 0,
            createdAt = 0,
        )
        assertFalse(txn.isDemo)
        assertFalse(txn.toDomain().isDemo)

        val acct = AccountEntity(
            name = "Cash",
            type = AccountType.CASH,
            currencyCode = "AED",
            openingBalance = BigDecimal.ZERO,
            createdAt = 0,
        )
        assertFalse(acct.isDemo)
        assertFalse(acct.toDomain().isDemo)
    }
}
