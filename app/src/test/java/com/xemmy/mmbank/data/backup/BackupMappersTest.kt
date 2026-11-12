package com.xemmy.mmbank.data.backup

import com.xemmy.mmbank.data.local.entity.MerchantAccountMappingEntity
import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.IgnoredKeywordEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.MerchantKindMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionKind
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip coverage for the backup DTO mappers.
 *
 * Every fixture sets *every* field to a distinctive non-default value, then
 * asserts the whole entity comes back equal. Data-class equality is what makes
 * this exhaustive: a field the mapper forgets can't survive the round trip, so
 * adding a column to an entity without carrying it through the backup fails
 * here rather than silently resetting itself on a user's next restore.
 */
class BackupMappersTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val fullTransaction = TransactionEntity(
        id = 42,
        accountId = 7,
        amount = BigDecimal("1234.56"),
        currencyCode = "AED",
        direction = TransactionDirection.DEBIT,
        merchant = "Carrefour",
        source = TransactionSource.SMS,
        sender = "ADCB",
        rawMessage = "Purchase of AED 1234.56 at Carrefour",
        matchedRuleName = "ADCB Debit",
        timestamp = 1_700_000_000_000L,
        createdAt = 1_700_000_001_000L,
        accountNumberSuffix = "4321",
        category = TransactionCategory.CASHBACK_EARNED,
        person = "Sam",
        spendingCategory = SpendingCategory.GROCERIES,
        excludeFromStats = true,
        linkedTransactionId = 99,
        isDemo = false,
        categoryId = 5,
        needsCategoryApproval = true,
        suggestedCategoryName = "Groceries",
        suggestedCategoryIcon = "🛒",
        suggestedCategoryColorHex = "#00FF00",
        extractedMerchant = "CARREFOUR HYPER",
        aiConfidence = 87,
        kind = TransactionKind.CARD_PAYMENT,
        kindUserSet = true,
        reportedBalance = BigDecimal("46120.85"),
        reportedBalanceKind = com.xemmy.mmbank.domain.model.ReportedBalanceKind.AVAILABLE_CREDIT,
        categorySource = com.xemmy.mmbank.domain.model.AssignmentSource.LEARNED,
        personSource = com.xemmy.mmbank.domain.model.AssignmentSource.USER,
    )

    private val fullAccount = AccountEntity(
        id = 7,
        name = "ADCB Current",
        type = AccountType.CHECKING,
        currencyCode = "AED",
        openingBalance = BigDecimal("2500.00"),
        archived = true,
        notes = "primary",
        createdAt = 1_600_000_000_000L,
        accountNumber = "1234567890",
        bankName = "ADCB",
        accountTitle = "S HOLDER",
        iban = "AE070331234567890123456",
        swift = "ADCBAEAA",
        outstandingBalance = BigDecimal("450.25"),
        nextInstallmentAmount = BigDecimal("150.00"),
        dueDayOfMonth = 15,
        profitRatePa = 3.75,
        maturityDateMillis = 1_800_000_000_000L,
        loanNotes = "24 months",
        statementCycleDay = 10,
        isDemo = false,
        hiddenFromDashboard = true,
        creditLimit = BigDecimal("20000.00"),
        cardEndings = "5555, 6666",
        accentColor = 0xFFCC3366.toInt(),
    )

    @Test
    fun `a fully populated transaction survives the backup round trip`() {
        assertEquals(fullTransaction, fullTransaction.toBackup().toEntity())
    }

    @Test
    fun `a fully populated account survives the backup round trip`() {
        assertEquals(fullAccount, fullAccount.toBackup().toEntity())
    }

    @Test
    fun `the analytics exclusion flag survives -- it used to be silently dropped`() {
        // A restored ledger that re-admits every balance correction and
        // reconciled transfer into analytics reports different totals than the
        // ledger that was backed up. This is the regression that motivated v3.
        assertTrue(fullTransaction.toBackup().excludeFromStats)
        assertTrue(fullTransaction.toBackup().toEntity().excludeFromStats)
    }

    @Test
    fun `transfer links and statement cycle day survive`() {
        assertEquals(99L, fullTransaction.toBackup().toEntity().linkedTransactionId)
        assertEquals(10, fullAccount.toBackup().toEntity().statementCycleDay)
    }

    @Test
    fun `a fully populated rule survives the backup round trip`() {
        val rule = ParsingRuleEntity(
            id = 3,
            name = "ADCB Debit",
            pattern = "purchase of (?<amount>[\\d.,]+)",
            senderPattern = "ADCB",
            direction = TransactionDirection.DEBIT,
            defaultCurrency = "AED",
            priority = 250,
            enabled = false,
            sampleText = "Purchase of AED 10.00",
            createdAt = 1_650_000_000_000L,
            assignAccountId = 7,
            isDemo = false,
            assignKind = TransactionKind.TRANSFER,
        )
        assertEquals(rule, rule.toBackup().toEntity())
    }

    @Test
    fun `a fully populated category survives the backup round trip`() {
        val category = CategoryEntity(
            id = 5,
            name = "Groceries",
            icon = "🛒",
            colorHex = "#00FF00",
            isCustom = true,
            monthlyCap = BigDecimal("1500.00"),
            parentCategoryId = 2,
        )
        assertEquals(category, category.toBackup().toEntity())
    }

    @Test
    fun `a fully populated merchant mapping survives the backup round trip`() {
        val mapping = MerchantCategoryMappingEntity(
            id = 11,
            merchantPattern = "carrefour",
            categoryId = 5,
            isExactMatch = false,
            pending = true,
            suggestedName = "Groceries",
            suggestedIcon = "🛒",
            suggestedColorHex = "#00FF00",
        )
        assertEquals(mapping, mapping.toBackup().toEntity())
    }

    @Test
    fun `amount scale is preserved exactly, not normalized away`() {
        val trailingZeros = fullTransaction.copy(amount = BigDecimal("10.50"))
        assertEquals(BigDecimal("10.50"), trailingZeros.toBackup().toEntity().amount)
    }

    @Test
    fun `demo rows are never marked demo on the way back in`() {
        // The export selects real rows only and an import restores real rows;
        // isDemo is deliberately not a backup field.
        assertFalse(fullTransaction.toBackup().toEntity().isDemo)
        assertFalse(fullAccount.toBackup().toEntity().isDemo)
    }

    @Test
    fun `an unknown spending category falls back instead of throwing`() {
        val fromNewerBuild = fullTransaction.toBackup().copy(spendingCategory = "CATEGORY_FROM_THE_FUTURE")
        assertEquals(SpendingCategory.UNCATEGORIZED, fromNewerBuild.toEntity().spendingCategory)
    }

    @Test
    fun `a format v2 payload without the v3 fields still imports, at entity defaults`() {
        // Older backups genuinely don't carry these; they must decode rather
        // than fail, taking the same defaults a fresh row would.
        val v2Transaction = """
            {"id":1,"accountId":null,"amount":"10.00","currencyCode":"AED","direction":"DEBIT",
             "merchant":null,"source":"MANUAL","sender":null,"rawMessage":null,"matchedRuleName":null,
             "timestamp":1,"createdAt":1,"accountNumberSuffix":null,"category":"NORMAL","person":null,
             "spendingCategory":"UNCATEGORIZED","categoryId":null}
        """.trimIndent()
        val decoded = json.decodeFromString(BackupTransaction.serializer(), v2Transaction).toEntity()
        assertFalse(decoded.excludeFromStats)
        assertNull(decoded.linkedTransactionId)
        assertFalse(decoded.needsCategoryApproval)
        assertNull(decoded.extractedMerchant)
        assertNull(decoded.aiConfidence)

        val v2Account = """
            {"id":1,"name":"A","type":"CHECKING","currencyCode":"AED","openingBalance":"0",
             "archived":false,"notes":null,"createdAt":1,"accountNumber":null,"bankName":null,
             "accountTitle":null,"iban":null,"swift":null,"outstandingBalance":null,
             "nextInstallmentAmount":null,"dueDayOfMonth":null,"profitRatePa":null,
             "maturityDateMillis":null,"loanNotes":null}
        """.trimIndent()
        val decodedAccount = json.decodeFromString(BackupAccount.serializer(), v2Account).toEntity()
        assertNull(decodedAccount.statementCycleDay)
        assertFalse(decodedAccount.hiddenFromDashboard)
    }

    @Test
    fun `a pre-v6 transaction gets its kind exactly as the database upgrade would`() {
        // A reconciled card-payment leg from before kinds existed: excluded,
        // linked, on a credit card. Restoring it must land on CARD_PAYMENT with
        // the automatic exclusion dropped -- the same row upgraded in place.
        val v5Linked = """
            {"id":1,"accountId":3,"amount":"3600","currencyCode":"AED","direction":"DEBIT",
             "merchant":null,"source":"SMS","sender":null,"rawMessage":null,"matchedRuleName":"builtin.en.debit.af.cur-amt",
             "timestamp":1,"createdAt":1,"accountNumberSuffix":null,"category":"NORMAL","person":null,
             "spendingCategory":"TRANSFERS","categoryId":null,"excludeFromStats":true,"linkedTransactionId":2}
        """.trimIndent()
        val linked = json.decodeFromString(BackupTransaction.serializer(), v5Linked).toEntity(onCreditCard = true)
        assertEquals(TransactionKind.CARD_PAYMENT, linked.kind)
        assertFalse(linked.excludeFromStats)

        // A credit the user hid by hand keeps its flag and reads as income.
        val v5Hidden = """
            {"id":4,"accountId":null,"amount":"50","currencyCode":"AED","direction":"CREDIT",
             "merchant":"Gift","source":"MANUAL","sender":null,"rawMessage":null,"matchedRuleName":null,
             "timestamp":1,"createdAt":1,"accountNumberSuffix":null,"excludeFromStats":true}
        """.trimIndent()
        val hidden = json.decodeFromString(BackupTransaction.serializer(), v5Hidden).toEntity()
        assertEquals(TransactionKind.INCOME, hidden.kind)
        assertTrue(hidden.excludeFromStats)
        assertFalse(hidden.kindUserSet)
    }

    @Test
    fun `a learned merchant kind rule survives the backup round trip`() {
        val rule = MerchantKindMappingEntity(
            id = 3,
            merchantPattern = "ADCB CC PAYMENT",
            kind = TransactionKind.CARD_PAYMENT,
            createdAt = 1_700_000_000_000,
        )
        assertEquals(rule, rule.toBackup().toEntity())
        val encoded = json.encodeToString(BackupMerchantKindMapping.serializer(), rule.toBackup())
        assertEquals(rule, json.decodeFromString(BackupMerchantKindMapping.serializer(), encoded).toEntity())
    }

    @Test
    fun `a learned kind rule from a newer build is skipped, not crashed on`() {
        assertNull(BackupMerchantKindMapping(1, "X", "KIND_FROM_THE_FUTURE", 0).toEntity())
    }

    @Test
    fun `a pre-v7 payload decodes with no learned kind rules`() {
        val payload = json.decodeFromString(
            BackupPayload.serializer(),
            """{"formatVersion":6,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"",
               "accounts":[],"transactions":[],"rules":[],"senderLabels":[]}""",
        )
        assertTrue(payload.merchantKindMappings.isEmpty())
    }

    @Test
    fun `a pre-v9 transaction has no reported balance`() {
        val old = """
            {"id":1,"accountId":null,"amount":"10.00","currencyCode":"AED","direction":"DEBIT",
             "merchant":null,"source":"MANUAL","sender":null,"rawMessage":null,"matchedRuleName":null,
             "timestamp":1,"createdAt":1,"accountNumberSuffix":null}
        """.trimIndent()
        val decoded = json.decodeFromString(BackupTransaction.serializer(), old).toEntity()
        assertNull(decoded.reportedBalance)
        assertNull(decoded.reportedBalanceKind)
    }

    @Test
    fun `a pre-v12 transaction restores its sources the way the v34 upgrade marks them`() {
        fun old(categoryId: String, person: String) = """
            {"id":1,"accountId":null,"amount":"10.00","currencyCode":"AED","direction":"DEBIT",
             "merchant":null,"source":"MANUAL","sender":null,"rawMessage":null,"matchedRuleName":null,
             "timestamp":1,"createdAt":1,"accountNumberSuffix":null,"categoryId":$categoryId,"person":$person}
        """.trimIndent()
        val withBoth = json.decodeFromString(BackupTransaction.serializer(), old("5", "\"Sam\"")).toEntity()
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.USER, withBoth.categorySource)
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.USER, withBoth.personSource)
        val withNeither = json.decodeFromString(BackupTransaction.serializer(), old("null", "null")).toEntity()
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.DEFAULT, withNeither.categorySource)
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.DEFAULT, withNeither.personSource)
    }

    @Test
    fun `an unknown source name from a newer build is treated like an absent one`() {
        val restored = fullTransaction.toBackup().copy(categorySource = "FROM_THE_FUTURE", personSource = "FROM_THE_FUTURE").toEntity()
        // fullTransaction has a category and a person, so the absent-source rule gives USER.
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.USER, restored.categorySource)
        assertEquals(com.xemmy.mmbank.domain.model.AssignmentSource.USER, restored.personSource)
    }

    @Test
    fun `an unknown reported-balance kind or an unreadable amount is dropped, not fatal`() {
        val odd = fullTransaction.toBackup().copy(reportedBalance = "not-a-number", reportedBalanceKind = "FROM_THE_FUTURE")
        val restored = odd.toEntity()
        assertNull(restored.reportedBalance)
        assertNull(restored.reportedBalanceKind)
    }

    @Test
    fun `an unknown kind from a newer build falls back to the direction's default`() {
        val fromNewerBuild = fullTransaction.toBackup().copy(kind = "KIND_FROM_THE_FUTURE")
        assertEquals(TransactionKind.SPEND, fromNewerBuild.toEntity().kind)
    }

    @Test
    fun `the new fields survive JSON serialization, not just the mappers`() {
        val encoded = json.encodeToString(BackupTransaction.serializer(), fullTransaction.toBackup())
        assertEquals(fullTransaction, json.decodeFromString(BackupTransaction.serializer(), encoded).toEntity())

        val encodedAccount = json.encodeToString(BackupAccount.serializer(), fullAccount.toBackup())
        assertEquals(fullAccount, json.decodeFromString(BackupAccount.serializer(), encodedAccount).toEntity())
    }

    @Test
    fun `an ignored message pattern survives the backup round-trip`() {
        val pattern = IgnoredMessagePatternEntity(
            id = 5,
            fingerprint = "enjoy # off at noon this weekend",
            sample = "Enjoy 50% off at Noon this weekend!",
            createdAt = 1_700_000_000_000,
        )
        assertEquals(pattern, pattern.toBackup().toEntity())

        val encoded = json.encodeToString(BackupIgnoredMessagePattern.serializer(), pattern.toBackup())
        assertEquals(
            pattern,
            json.decodeFromString(BackupIgnoredMessagePattern.serializer(), encoded).toEntity(),
        )
    }

    @Test
    fun `an ignored keyword survives the backup round-trip`() {
        val keyword = IgnoredKeywordEntity(id = 2, keyword = "promo", createdAt = 1_700_000_000_000)
        assertEquals(keyword, keyword.toBackup().toEntity())
        val encoded = json.encodeToString(BackupIgnoredKeyword.serializer(), keyword.toBackup())
        assertEquals(keyword, json.decodeFromString(BackupIgnoredKeyword.serializer(), encoded).toEntity())
    }

    @Test
    fun `a pre-v10 payload decodes with no ignored keywords`() {
        val decoded = json.decodeFromString<BackupPayload>(
            """{"formatVersion":9,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"0",
               "accounts":[],"transactions":[],"rules":[],"senderLabels":[]}"""
        )
        assertEquals(emptyList<BackupIgnoredKeyword>(), decoded.ignoredKeywords)
    }

    @Test
    fun `a pre-v4 payload decodes with an empty ignore list rather than failing`() {
        // Restoring an older backup must not blow up on the missing field --
        // and BackupManager.import leaves the device's own list alone for it.
        val v3 = """
            {"formatVersion":3,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"0",
             "personsRaw":"","accounts":[],"transactions":[],"rules":[],"senderLabels":[]}
        """.trimIndent()
        val decoded = json.decodeFromString(BackupPayload.serializer(), v3)
        assertEquals(emptyList<BackupIgnoredMessagePattern>(), decoded.ignoredMessagePatterns)
        assertEquals(3, decoded.formatVersion)
    }

    @Test
    fun `a learned merchant account mapping survives the backup round trip`() {
        val mapping = MerchantAccountMappingEntity(
            id = 9,
            merchantPattern = "Golden Fork",
            accountId = 4,
            createdAt = 1_700_000_000_000,
        )
        val encoded = json.encodeToString(BackupMerchantAccountMapping.serializer(), mapping.toBackup())
        assertEquals(
            mapping,
            json.decodeFromString(BackupMerchantAccountMapping.serializer(), encoded).toEntity(),
        )
    }

    @Test
    fun `a pre-v5 payload decodes with no learned account mappings rather than failing`() {
        // Same gate as the ignore list one version earlier: restoring an older
        // backup must not erase what this device has learned.
        val v4 = """
            {"formatVersion":4,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"0",
             "personsRaw":"","accounts":[],"transactions":[],"rules":[],"senderLabels":[]}
        """.trimIndent()
        val decoded = json.decodeFromString(BackupPayload.serializer(), v4)
        assertEquals(emptyList<BackupMerchantAccountMapping>(), decoded.merchantAccountMappings)
        assertEquals(4, decoded.formatVersion)
    }
}
