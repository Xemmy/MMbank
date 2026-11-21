package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.model.MerchantAlias
import com.xemmy.mmbank.domain.model.Transaction
import com.xemmy.mmbank.domain.model.TransactionDirection
import com.xemmy.mmbank.domain.model.TransactionSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantAliasesTest {
    private val aliases = listOf(MerchantAlias("apple.com/bill", "Apple"))

    @Test fun `a matching name is renamed, ignoring case and padding`() {
        assertEquals("Apple", MerchantAliases.apply("  APPLE.COM/BILL ", aliases))
    }

    @Test fun `a different name is left alone`() {
        assertEquals("Netflix", MerchantAliases.apply("Netflix", aliases))
    }

    @Test fun `null, blank and an empty alias list pass through`() {
        assertNull(MerchantAliases.apply(null, aliases))
        assertEquals("  ", MerchantAliases.apply("  ", aliases))
        assertEquals("X", MerchantAliases.apply("X", emptyList()))
    }

    @Test fun `the key trims and lower-cases`() {
        assertEquals("apple.com/bill", MerchantAliases.keyOf("  Apple.com/BILL "))
    }

    private val utc = ZoneOffset.UTC
    private fun millis(date: LocalDate) = date.atTime(12, 0).atZone(utc).toInstant().toEpochMilli()
    private fun tx(id: Long, merchant: String, date: LocalDate) = Transaction(
        id = id, amount = BigDecimal("19.99"), currencyCode = "AED", direction = TransactionDirection.DEBIT,
        merchant = merchant, source = TransactionSource.SMS, timestampMillis = millis(date),
    )

    @Test fun `rename then rescan - renamed charges join one detected series`() {
        val now = millis(LocalDate.of(2026, 5, 1))
        val before = listOf(
            tx(1, "SOMESHOP LLC 111", LocalDate.of(2026, 2, 1)),
            tx(2, "Somewhat Shop", LocalDate.of(2026, 3, 1)),
            tx(3, "SomeShop", LocalDate.of(2026, 4, 1)),
        )
        // After the user renames every spelling to one name, the three charges form a single series.
        val after = before.map { it.copy(merchant = "Some Shop") }
        val plan = SubscriptionRescan.plan(emptyList(), emptyList(), after, now, utc)
        assertEquals(1, plan.created)
        assertEquals("Some Shop", plan.upserts.single().merchant)
        assertTrue(plan.deleteIds.isEmpty())
    }
}
