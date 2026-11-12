package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountBalanceCalculator.LedgerEntry
import com.xemmy.mmbank.domain.model.TransactionDirection.CREDIT
import com.xemmy.mmbank.domain.model.TransactionDirection.DEBIT
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerFxTest {
    // AED base; 1 AED = 0.2723 USD (so 1 USD = ~3.6725 AED), 1 AED = 0.25 EUR.
    private val rates = mapOf("USD" to BigDecimal("0.2723"), "EUR" to BigDecimal("0.25"))
    private fun fx(accounts: Map<Long, String> = mapOf(1L to "AED", 2L to "USD")) = LedgerFx("AED", rates, accounts)
    private fun e(acct: Long?, amt: String, cur: String, dir: com.xemmy.mmbank.domain.model.TransactionDirection = DEBIT) =
        LedgerEntry(acct, BigDecimal(amt), cur, dir)

    @Test fun sameCurrencyPassesThroughUntouched() {
        val entry = e(1, "100", "AED")
        assertEquals(listOf(entry), fx().apply(listOf(entry)))
    }

    @Test fun usdChargeOnAedAccountIsConvertedAndFlagged() {
        val out = fx().apply(listOf(e(1, "100", "USD"))).single()
        assertEquals("AED", out.currencyCode)
        assertTrue(out.approximate)
        // 100 USD / 0.2723 = 367.2420...
        assertEquals(0, BigDecimal("367.242").compareTo(out.amount.setScale(3, java.math.RoundingMode.HALF_UP)))
    }

    @Test fun crossRateBetweenTwoNonBaseCurrencies() {
        // 25 EUR -> USD: 25 * 0.2723 / 0.25 = 27.23
        val out = fx(mapOf(5L to "USD")).apply(listOf(e(5, "25", "EUR"))).single()
        assertEquals(0, BigDecimal("27.23").compareTo(out.amount.setScale(2, java.math.RoundingMode.HALF_UP)))
        assertEquals("USD", out.currencyCode)
    }

    @Test fun unknownRateKeepsFaceValueAndFlagsIt() {
        val entry = e(1, "10", "JPY")
        val out = fx().apply(listOf(entry)).single()
        assertEquals(BigDecimal("10"), out.amount)
        assertEquals("JPY", out.currencyCode)
        assertTrue(out.approximate)
    }

    @Test fun unassignedAndUnknownAccountEntriesPassThrough() {
        val a = e(null, "5", "USD")
        val b = e(99, "5", "USD")
        assertEquals(listOf(a, b), fx().apply(listOf(a, b)))
        assertFalse(a.approximate)
    }

    @Test fun directionIsPreservedSoCreditsStillAdd() {
        val out = fx().apply(listOf(e(1, "100", "USD", CREDIT))).single()
        assertEquals(CREDIT, out.direction)
    }

    @Test fun zeroOrNegativeRatesAreIgnored() {
        val bad = LedgerFx("AED", mapOf("USD" to BigDecimal.ZERO), mapOf(1L to "AED"))
        assertNull(bad.factor("USD", "AED"))
    }

    @Test fun balanceReflectsTheConversion() {
        val account = com.xemmy.mmbank.domain.model.Account(
            id = 1, name = "Main", type = com.xemmy.mmbank.domain.model.AccountType.CHECKING,
            currencyCode = "AED", openingBalance = BigDecimal("1000"),
        )
        val converted = fx().apply(listOf(e(1, "100", "USD")))
        val balance = AccountBalanceCalculator.balanceFor(account, converted)
        assertEquals(0, BigDecimal("632.758").compareTo(balance.setScale(3, java.math.RoundingMode.HALF_UP)))
    }
}
