package com.xemmy.mmbank.data.local.entity

import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
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

    private fun transaction(
        excludeFromStats: Boolean,
        isDemo: Boolean = false,
        linkedTransactionId: Long? = null,
        categoryId: Long? = null,
        needsCategoryApproval: Boolean = false,
        suggestedCategoryName: String? = null,
        suggestedCategoryIcon: String? = null,
        suggestedCategoryColorHex: String? = null,
    ) = Transaction(
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
        linkedTransactionId = linkedTransactionId,
        isDemo = isDemo,
        categoryId = categoryId,
        needsCategoryApproval = needsCategoryApproval,
        suggestedCategoryName = suggestedCategoryName,
        suggestedCategoryIcon = suggestedCategoryIcon,
        suggestedCategoryColorHex = suggestedCategoryColorHex,
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
    fun `transaction round-trip preserves a non-null linkedTransactionId`() {
        val original = transaction(excludeFromStats = true, linkedTransactionId = 99)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(99L, roundTripped.linkedTransactionId)
    }

    @Test
    fun `transaction round-trip preserves a null linkedTransactionId`() {
        val original = transaction(excludeFromStats = false, linkedTransactionId = null)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.linkedTransactionId)
    }

    @Test
    fun `transaction entity defaults linkedTransactionId to null`() {
        // Mirrors the v12 migration: rows predating it load unlinked.
        val entity = TransactionEntity(
            amount = BigDecimal.ONE,
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = 0,
            createdAt = 0,
        )
        assertNull(entity.linkedTransactionId)
        assertNull(entity.toDomain().linkedTransactionId)
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
    fun `parsing rule round-trip preserves isDemo in both states`() {
        fun rule(isDemo: Boolean) = ParsingRule(
            id = 5,
            name = "Demo Bank card spend",
            pattern = "You spent (?<amount>\\d+)",
            senderPattern = "demobank",
            direction = TransactionDirection.DEBIT,
            defaultCurrency = "USD",
            priority = 110,
            enabled = true,
            sampleText = "You spent 42",
            createdAt = 1_700_000_000_000,
            assignAccountId = 9,
            isDemo = isDemo,
        )
        val demo = rule(isDemo = true)
        assertEquals(demo, demo.toEntity().toDomain())
        assertTrue(demo.toEntity().isDemo)
        val real = rule(isDemo = false)
        assertEquals(real, real.toEntity().toDomain())
        assertFalse(real.toEntity().isDemo)
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

    @Test
    fun `transaction round-trip preserves an assigned dynamic category id`() {
        val original = transaction(excludeFromStats = false, categoryId = 3)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(3L, roundTripped.categoryId)
        assertFalse(roundTripped.needsCategoryApproval)
    }

    @Test
    fun `transaction round-trip preserves a pending AI category suggestion`() {
        val original = transaction(
            excludeFromStats = false,
            needsCategoryApproval = true,
            suggestedCategoryName = "Pets",
            suggestedCategoryIcon = "🐾",
            suggestedCategoryColorHex = "#8D6E63",
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertTrue(roundTripped.needsCategoryApproval)
        assertEquals("Pets", roundTripped.suggestedCategoryName)
        assertEquals("🐾", roundTripped.suggestedCategoryIcon)
        assertEquals("#8D6E63", roundTripped.suggestedCategoryColorHex)
    }

    @Test
    fun `transaction entity defaults dynamic category fields to unset`() {
        // Mirrors the v13 migration: rows predating it load with no dynamic category.
        val entity = TransactionEntity(
            amount = BigDecimal.ONE,
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = 0,
            createdAt = 0,
        )
        assertNull(entity.categoryId)
        assertFalse(entity.needsCategoryApproval)
        assertNull(entity.suggestedCategoryName)
        assertNull(entity.toDomain().categoryId)
        assertFalse(entity.toDomain().needsCategoryApproval)
    }

    @Test
    fun `category round-trip preserves a non-null monthlyCap`() {
        val original = Category(
            id = 4,
            name = "Groceries",
            icon = "🛒",
            colorHex = "#4CAF50",
            isCustom = false,
            monthlyCap = BigDecimal("1500.00"),
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(BigDecimal("1500.00"), roundTripped.monthlyCap)
    }

    @Test
    fun `category round-trip preserves a null monthlyCap and isCustom = true`() {
        val original = Category(
            id = 12,
            name = "Pets",
            icon = "🐾",
            colorHex = "#8D6E63",
            isCustom = true,
            monthlyCap = null,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.monthlyCap)
        assertTrue(roundTripped.isCustom)
    }

    @Test
    fun `merchant category mapping round-trip preserves a confirmed exact-match mapping`() {
        val original = MerchantCategoryMapping(
            id = 1,
            merchantPattern = "Carrefour",
            categoryId = 4,
            isExactMatch = true,
            pending = false,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.suggestedName)
    }

    @Test
    fun `merchant category mapping round-trip preserves a pending AI suggestion`() {
        val original = MerchantCategoryMapping(
            id = 2,
            merchantPattern = "Pet Zone",
            categoryId = null,
            isExactMatch = true,
            pending = true,
            suggestedName = "Pets",
            suggestedIcon = "🐾",
            suggestedColorHex = "#8D6E63",
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.categoryId)
        assertTrue(roundTripped.pending)
    }

    @Test
    fun `subscription round-trip preserves cadence and dismissed state`() {
        val original = Subscription(
            id = 6,
            merchant = "Netflix",
            averageAmount = BigDecimal("39.99"),
            currencyCode = "AED",
            cadence = SubscriptionCadence.MONTHLY,
            nextDueDateMillis = 1_700_000_000_000,
            lastSeenMillis = 1_697_408_000_000,
            occurrenceCount = 4,
            dismissed = false,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(SubscriptionCadence.MONTHLY, roundTripped.cadence)
        assertFalse(roundTripped.dismissed)
    }

    @Test
    fun `subscription round-trip preserves dismissed = true and weekly cadence`() {
        val original = Subscription(
            id = 7,
            merchant = "Weekly Cleaner",
            averageAmount = BigDecimal("50.00"),
            currencyCode = "USD",
            cadence = SubscriptionCadence.WEEKLY,
            nextDueDateMillis = 1_700_000_000_000,
            lastSeenMillis = 1_699_395_200_000,
            occurrenceCount = 6,
            dismissed = true,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertTrue(roundTripped.dismissed)
    }
}
