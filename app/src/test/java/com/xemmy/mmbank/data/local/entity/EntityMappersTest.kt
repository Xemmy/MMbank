package com.xemmy.mmbank.data.local.entity

import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.domain.model.MerchantAccountMapping
import com.xemmy.mmbank.domain.model.Account
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.Bill
import com.xemmy.mmbank.domain.model.BillRecurrence
import com.xemmy.mmbank.domain.model.Category
import com.xemmy.mmbank.domain.model.ExpenseSplit
import com.xemmy.mmbank.domain.model.Friend
import com.xemmy.mmbank.domain.model.GroupExpense
import com.xemmy.mmbank.domain.model.IgnoredKeyword
import com.xemmy.mmbank.domain.model.IgnoredMessagePattern
import com.xemmy.mmbank.domain.model.MerchantCategoryMapping
import com.xemmy.mmbank.domain.model.ParsingRule
import com.xemmy.mmbank.domain.model.SavingsGoal
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.SplitGroup
import com.xemmy.mmbank.domain.model.SplitType
import com.xemmy.mmbank.domain.model.Subscription
import com.xemmy.mmbank.domain.model.SubscriptionCadence
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
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
        extractedMerchant: String? = null,
        aiConfidence: Int? = null,
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
        extractedMerchant = extractedMerchant,
        aiConfidence = aiConfidence,
    )

    private fun account(statementCycleDay: Int?, isDemo: Boolean = false, hiddenFromDashboard: Boolean = false) = Account(
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
        hiddenFromDashboard = hiddenFromDashboard,
        creditLimit = BigDecimal("15000.00"),
        cardEndings = "4321, 8765",
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
    fun `account round-trip preserves hiddenFromDashboard in both states`() {
        val hidden = account(statementCycleDay = 10, hiddenFromDashboard = true).toEntity().toDomain()
        assertTrue(hidden.hiddenFromDashboard)
        val visible = account(statementCycleDay = 10, hiddenFromDashboard = false).toEntity().toDomain()
        assertFalse(visible.hiddenFromDashboard)
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
    fun `transaction round-trip preserves extractedMerchant and aiConfidence`() {
        val original = transaction(
            excludeFromStats = false,
            extractedMerchant = "CARREFOUR-MAF MKT",
            aiConfidence = 70,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals("CARREFOUR-MAF MKT", roundTripped.extractedMerchant)
        assertEquals(70, roundTripped.aiConfidence)
    }

    @Test
    fun `transaction entity defaults extractedMerchant and aiConfidence to null`() {
        // Mirrors the v17 migration: rows predating it load unscored.
        val entity = TransactionEntity(
            amount = BigDecimal.ONE,
            currencyCode = "AED",
            direction = TransactionDirection.DEBIT,
            source = TransactionSource.MANUAL,
            timestamp = 0,
            createdAt = 0,
        )
        assertNull(entity.extractedMerchant)
        assertNull(entity.aiConfidence)
        assertNull(entity.toDomain().extractedMerchant)
        assertNull(entity.toDomain().aiConfidence)
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
    fun `category round-trip preserves a parentCategoryId`() {
        val original = Category(
            id = 5,
            name = "Dining",
            icon = "🍽️",
            colorHex = "#FF7043",
            parentCategoryId = 4,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(4L, roundTripped.parentCategoryId)
    }

    @Test
    fun `category round-trip preserves a null parentCategoryId for a top-level category`() {
        val original = Category(id = 4, name = "Groceries", icon = "🛒", colorHex = "#4CAF50")
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.parentCategoryId)
    }

    @Test
    fun `merchant account mapping round-trips in both directions`() {
        val original = MerchantAccountMapping(
            id = 3,
            merchantPattern = "Golden Fork",
            accountId = 12,
            createdAt = 1_700_000_000_000,
        )
        assertEquals(original, original.toEntity().toDomain())

        val entity = MerchantAccountMappingEntity(
            id = 4,
            merchantPattern = "ADNOC",
            accountId = 5,
            createdAt = 1_700_000_000_001,
        )
        assertEquals(entity, entity.toDomain().toEntity())
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
        assertNull("a steady price must round-trip as null, not zero", roundTripped.previousAmount)
    }

    @Test
    fun `subscription round-trip preserves previousAmount when the price changed`() {
        val original = Subscription(
            id = 7,
            merchant = "Netflix",
            averageAmount = BigDecimal("56.00"),
            currencyCode = "AED",
            cadence = SubscriptionCadence.MONTHLY,
            nextDueDateMillis = 1_700_000_000_000,
            lastSeenMillis = 1_697_408_000_000,
            occurrenceCount = 4,
            dismissed = false,
            previousAmount = BigDecimal("39.99"),
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(0, roundTripped.previousAmount!!.compareTo(BigDecimal("39.99")))
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

    @Test
    fun `bill round-trip preserves a recurring cadence`() {
        val original = Bill(
            id = 10,
            name = "Rent",
            amount = BigDecimal("2500.00"),
            currencyCode = "AED",
            recurrence = BillRecurrence.MONTHLY,
            nextDueDateMillis = 1_700_000_000_000,
            createdAt = 1_690_000_000_000,
            dueDayAnchor = 31,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(31, roundTripped.dueDayAnchor)
        assertEquals(BillRecurrence.MONTHLY, roundTripped.recurrence)
    }

    @Test
    fun `bill round-trip preserves a null recurrence for a one-off bill`() {
        val original = Bill(
            id = 11,
            name = "Tuition installment",
            amount = BigDecimal("1200.00"),
            currencyCode = "USD",
            recurrence = null,
            nextDueDateMillis = 1_700_000_000_000,
            createdAt = 1_690_000_000_000,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.recurrence)
    }

    @Test
    fun `savings goal round-trip preserves a non-null target date`() {
        val original = SavingsGoal(
            id = 8,
            title = "Emergency Fund",
            targetAmount = BigDecimal("10000.00"),
            currentAmount = BigDecimal("2500.00"),
            targetDateMillis = 1_800_000_000_000,
            icon = "🛟",
            createdAt = 1_690_000_000_000,
            archived = false,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(1_800_000_000_000, roundTripped.targetDateMillis)
        assertFalse(roundTripped.archived)
    }

    @Test
    fun `savings goal round-trip preserves a null target date and archived = true`() {
        val original = SavingsGoal(
            id = 9,
            title = "Old Goal",
            targetAmount = BigDecimal("500.00"),
            currentAmount = BigDecimal("500.00"),
            targetDateMillis = null,
            icon = "🎯",
            createdAt = 1_690_000_000_000,
            archived = true,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.targetDateMillis)
        assertTrue(roundTripped.archived)
    }

    @Test
    fun `friend round-trip preserves name and archived state`() {
        val original = Friend(id = 3, name = "Alex", createdAt = 1_700_000_000_000, archived = true)
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertTrue(roundTripped.archived)
    }

    @Test
    fun `split group round-trip preserves its member id list, supplied separately from the entity`() {
        val original = SplitGroup(
            id = 7,
            name = "Trip to Muscat",
            icon = "🏝️",
            memberFriendIds = listOf(3L, 5L, 9L),
            createdAt = 1_700_000_000_000,
            archived = false,
        )
        // SplitGroupEntity carries no member list of its own -- it comes from a
        // separate join-table query, so toDomain() takes it as a parameter.
        val roundTripped = original.toEntity().toDomain(original.memberFriendIds)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `split group round-trip with no members yields an empty list, not null`() {
        val original = SplitGroup(id = 1, name = "Roommates", memberFriendIds = emptyList(), createdAt = 1_700_000_000_000)
        val roundTripped = original.toEntity().toDomain(emptyList())
        assertEquals(original, roundTripped)
        assertTrue(roundTripped.memberFriendIds.isEmpty())
    }

    @Test
    fun `group expense round-trip preserves a friend payer and split type`() {
        val original = GroupExpense(
            id = 4,
            groupId = 7,
            description = "Dinner",
            amount = BigDecimal("90.00"),
            currencyCode = "AED",
            paidByFriendId = 3L,
            splitType = SplitType.EQUAL,
            createdAt = 1_700_000_000_000,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertEquals(3L, roundTripped.paidByFriendId)
    }

    @Test
    fun `group expense round-trip preserves a null payer, meaning me`() {
        val original = GroupExpense(
            id = 5,
            groupId = 7,
            description = "Groceries",
            amount = BigDecimal("40.00"),
            currencyCode = "AED",
            paidByFriendId = null,
            splitType = SplitType.SETTLEMENT,
            createdAt = 1_700_000_000_000,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.paidByFriendId)
    }

    @Test
    fun `expense split round-trip preserves a friend's owed amount`() {
        val original = ExpenseSplit(id = 1, expenseId = 4, friendId = 3L, owedAmount = BigDecimal("30.00"))
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
    }

    @Test
    fun `expense split round-trip preserves a null friendId, meaning me`() {
        val original = ExpenseSplit(id = 2, expenseId = 4, friendId = null, owedAmount = BigDecimal("30.00"))
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        assertNull(roundTripped.friendId)
    }

    @Test
    fun `ignored message pattern round-trip preserves the sample and its fingerprint`() {
        val original = IgnoredMessagePattern(
            id = 3,
            fingerprint = "enjoy # off at noon this weekend",
            sample = "Enjoy 50% off at Noon this weekend!",
            createdAt = 1_700_000_000_000,
        )
        val roundTripped = original.toEntity().toDomain()
        assertEquals(original, roundTripped)
        // The verbatim sample is what the list shows and what fuzzy matching
        // re-reads, so it must survive untouched -- punctuation included.
        assertEquals("Enjoy 50% off at Noon this weekend!", roundTripped.sample)
    }

    @Test
    fun `ignored keyword round-trip preserves every field`() {
        val original = IgnoredKeyword(id = 4, keyword = "promo", createdAt = 1_700_000_000_000)
        assertEquals(original, original.toEntity().toDomain())
    }

    @Test
    fun `transaction kind and kindUserSet survive both directions`() {
        TransactionKind.entries.forEach { kind ->
            val original = transaction(excludeFromStats = false).copy(kind = kind, kindUserSet = true)
            assertEquals(original, original.toEntity().toDomain())
            val entity = original.toEntity()
            assertEquals(kind, entity.kind)
            assertTrue(entity.kindUserSet)
            assertEquals(entity, entity.toDomain().toEntity())
        }
    }

    @Test
    fun `a transaction built without a kind defaults to the plain kind for its direction`() {
        val debit = transaction(excludeFromStats = false)
        assertEquals(TransactionKind.SPEND, debit.kind)
        assertFalse(debit.kindUserSet)
        val credit = debit.copy(direction = TransactionDirection.CREDIT)
        // copy() keeps the old kind -- callers that flip the direction must
        // coerce (see TransactionKindDetector.coerce); a fresh row defaults.
        assertEquals(TransactionKind.SPEND, credit.kind)
        assertEquals(
            TransactionKind.INCOME,
            Transaction(amount = BigDecimal.ONE, currencyCode = "AED", direction = TransactionDirection.CREDIT,
                source = TransactionSource.MANUAL, timestampMillis = 0).kind,
        )
    }

    @Test
    fun `reported bank balance round-trips both directions`() {
        com.xemmy.mmbank.domain.model.ReportedBalanceKind.entries.forEach { kind ->
            val original = transaction(excludeFromStats = false)
                .copy(reportedBalance = BigDecimal("46120.85"), reportedBalanceKind = kind)
            assertEquals(original, original.toEntity().toDomain())
            val entity = original.toEntity()
            assertEquals(BigDecimal("46120.85"), entity.reportedBalance)
            assertEquals(kind, entity.reportedBalanceKind)
            assertEquals(entity, entity.toDomain().toEntity())
        }
        val none = transaction(excludeFromStats = false)
        assertNull(none.toEntity().reportedBalance)
        assertNull(none.toEntity().reportedBalanceKind)
    }

    @Test
    fun `parsing rule round-trip preserves assignKind`() {
        val rule = ParsingRule(
            name = "ADCB card payment",
            pattern = "card payment (?<amount>\\d+)",
            direction = TransactionDirection.DEBIT,
            assignKind = TransactionKind.CARD_PAYMENT,
        )
        assertEquals(rule, rule.toEntity().toDomain())
        assertEquals(TransactionKind.CARD_PAYMENT, rule.toEntity().assignKind)
        assertNull(rule.copy(assignKind = null).toEntity().assignKind)
    }

    @Test
    fun `merchant kind mapping round-trips both directions`() {
        val mapping = com.xemmy.mmbank.domain.model.MerchantKindMapping(
            id = 4,
            merchantPattern = "ADCB CC PAYMENT",
            kind = TransactionKind.CARD_PAYMENT,
            createdAt = 1_700_000_000_000,
        )
        assertEquals(mapping, mapping.toEntity().toDomain())
        val entity = mapping.toEntity()
        assertEquals(entity, entity.toDomain().toEntity())
    }
}
