package com.xemmy.mmbank.data.local.entity

import com.xemmy.mmbank.domain.model.AccountChange
import com.xemmy.mmbank.domain.model.AccountChangeSource
import com.xemmy.mmbank.domain.model.AccountChangeType
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md: every field of the domain model, entity and mappers round-trips. */
class AccountChangeMappersTest {

    private val full = AccountChange(
        id = 9,
        accountId = 3,
        accountName = "ADCB Current",
        timestampMillis = 1_700_000_000_123,
        type = AccountChangeType.TRANSACTION_EDITED,
        source = AccountChangeSource.CAPTURE_SUGGESTION,
        field = "amount,date",
        oldValue = "-100 AED",
        newValue = "-150 AED",
        balanceDelta = BigDecimal("-50.25"),
        currencyCode = "AED",
        transactionId = 77,
        reason = "Missed transaction",
        note = "Carrefour",
        isDemo = true,
    )

    @Test
    fun `domain to entity to domain keeps every field`() {
        assertEquals(full, full.toEntity().toDomain())
    }

    @Test
    fun `entity to domain to entity keeps every field`() {
        val entity = full.toEntity()
        assertEquals(entity, entity.toDomain().toEntity())
    }

    @Test
    fun `the entity carries each field under its own column`() {
        val e = full.toEntity()
        assertEquals(9L, e.id)
        assertEquals(3L, e.accountId)
        assertEquals("ADCB Current", e.accountName)
        assertEquals(1_700_000_000_123, e.timestamp)
        assertEquals(AccountChangeType.TRANSACTION_EDITED, e.type)
        assertEquals(AccountChangeSource.CAPTURE_SUGGESTION, e.source)
        assertEquals("amount,date", e.field)
        assertEquals("-100 AED", e.oldValue)
        assertEquals("-150 AED", e.newValue)
        assertEquals(BigDecimal("-50.25"), e.balanceDelta)
        assertEquals("AED", e.currencyCode)
        assertEquals(77L, e.transactionId)
        assertEquals("Missed transaction", e.reason)
        assertEquals("Carrefour", e.note)
        assertTrue(e.isDemo)
    }

    @Test
    fun `optional fields default to unset`() {
        val e = AccountChangeEntity(
            accountId = 1,
            accountName = "x",
            timestamp = 5,
            type = AccountChangeType.ARCHIVED,
            source = AccountChangeSource.USER,
        )
        assertEquals(0L, e.id)
        assertNull(e.field)
        assertNull(e.oldValue)
        assertNull(e.newValue)
        assertNull(e.balanceDelta)
        assertNull(e.currencyCode)
        assertNull(e.transactionId)
        assertNull(e.reason)
        assertNull(e.note)
        assertFalse(e.isDemo)
        assertEquals(e, e.toDomain().toEntity())
    }

    @Test
    fun `unknown persisted names parse leniently`() {
        assertEquals(AccountChangeType.DETAILS_EDITED, AccountChangeType.parse("FROM_THE_FUTURE"))
        assertEquals(AccountChangeType.DETAILS_EDITED, AccountChangeType.parse(null))
        assertEquals(AccountChangeSource.USER, AccountChangeSource.parse("???"))
        assertEquals(AccountChangeType.RETENTION_FOLDED, AccountChangeType.parse("RETENTION_FOLDED"))
    }
}
