package com.xemmy.mmbank.data.backup

import com.xemmy.mmbank.data.local.entity.AccountChangeEntity
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AccountChangeBackupTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val entity = AccountChangeEntity(
        id = 4,
        accountId = 2,
        accountName = "Tabby Card",
        timestamp = 1_700_000_000_000,
        type = AccountChangeType.TRANSACTION_MOVED_IN,
        source = AccountChangeSource.USER,
        field = "account",
        oldValue = "No account",
        newValue = "Tabby Card",
        balanceDelta = BigDecimal("-12.40"),
        currencyCode = "AED",
        transactionId = 31,
        reason = "r",
        note = "n",
    )

    @Test
    fun `a log entry round trips through its backup form`() {
        assertEquals(entity, entity.toBackup().toEntity())
    }

    @Test
    fun `a missing balance delta stays missing`() {
        val noDelta = entity.copy(balanceDelta = null)
        assertNull(noDelta.toBackup().balanceDelta)
        assertEquals(noDelta, noDelta.toBackup().toEntity())
    }

    @Test
    fun `the decimal survives exactly`() {
        val back = entity.copy(balanceDelta = BigDecimal("0.10")).toBackup().toEntity()
        assertEquals(BigDecimal("0.10"), back.balanceDelta)
    }

    @Test
    fun `demo flag is never carried`() {
        assertFalse(entity.copy(isDemo = true).toBackup().toEntity().isDemo)
    }

    @Test
    fun `an unknown type in a newer backup degrades instead of failing`() {
        val b = entity.toBackup().copy(type = "SOMETHING_NEW", source = "SOMEONE_NEW")
        val e = b.toEntity()
        assertEquals(AccountChangeType.DETAILS_EDITED, e.type)
        assertEquals(AccountChangeSource.USER, e.source)
    }

    @Test
    fun `an older backup decodes with an empty log`() {
        val decoded = json.decodeFromString<BackupPayload>(
            """{"formatVersion":13,"exportedAtMillis":1,"defaultCurrency":"AED","cashbackSpendRaw":"0",
            "accounts":[],"transactions":[],"rules":[],"senderLabels":[]}""",
        )
        assertEquals(emptyList<BackupAccountChange>(), decoded.accountChanges)
    }

    @Test
    fun `the log survives json`() {
        val payload = BackupPayload(
            formatVersion = 14,
            exportedAtMillis = 1,
            defaultCurrency = "AED",
            cashbackSpendRaw = "0",
            accounts = emptyList(),
            transactions = emptyList(),
            rules = emptyList(),
            senderLabels = emptyList(),
            accountChanges = listOf(entity.toBackup()),
        )
        val back = json.decodeFromString<BackupPayload>(json.encodeToString(payload))
        assertEquals(listOf(entity), back.accountChanges.map { it.toEntity() })
    }
}
