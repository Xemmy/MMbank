package com.xemmy.mmbank.data.backup

import com.xemmy.mmbank.data.local.entity.AccountEntity
import com.xemmy.mmbank.data.local.entity.CategoryEntity
import com.xemmy.mmbank.data.local.entity.IgnoredMessagePatternEntity
import com.xemmy.mmbank.data.local.entity.MerchantCategoryMappingEntity
import com.xemmy.mmbank.data.local.entity.ParsingRuleEntity
import com.xemmy.mmbank.data.local.entity.TransactionEntity
import com.xemmy.mmbank.domain.model.AccountType
import com.xemmy.mmbank.domain.model.SpendingCategory
import com.xemmy.mmbank.domain.model.TransactionCategory
import com.xemmy.mmbank.domain.model.TransactionDirection
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
}
