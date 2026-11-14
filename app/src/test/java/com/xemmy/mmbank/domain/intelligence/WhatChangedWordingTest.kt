package com.xemmy.mmbank.domain.intelligence

import com.xemmy.mmbank.domain.intelligence.WhatChangedDigest.Digest
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatChangedWordingTest {

    private val w = WhatChangedWording(
        money = { "AED " + String.format(Locale.US, "%,.0f", it) },
        moneyIn = { amount, code -> "$code ${amount.toPlainString()}" },
    )

    @Test
    fun `headline compares with last week`() {
        assertEquals("Spent AED 1,200 this week, 20% more than last week (AED 1,000).", w.headline(Digest(1200.0, 1000.0, 20.0, emptyList())))
        assertEquals("Spent AED 800 this week, 20% less than last week (AED 1,000).", w.headline(Digest(800.0, 1000.0, -20.0, emptyList())))
        assertEquals("Spent AED 1,000 this week, about the same as last week.", w.headline(Digest(1000.0, 1000.0, 0.4, emptyList())))
        assertEquals("Spent AED 50 this week.", w.headline(Digest(50.0, 0.0, null, emptyList())))
        assertEquals("No spending this week.", w.headline(Digest(0.0, 0.0, null, emptyList())))
    }

    @Test
    fun `item sentences`() {
        assertEquals("No bank messages captured for 5 days. Capture may have stopped.", w.item(WhatChangedDigest.CaptureQuiet(5)))
        assertEquals(
            "Dining is over its cap: AED 520 of AED 500.",
            w.item(WhatChangedDigest.CategoryCap("Dining", BigDecimal("520"), BigDecimal("500"), over = true, currencyCode = "AED")),
        )
        assertEquals(
            "Groceries is at 85% of its AED 1000 cap.",
            w.item(WhatChangedDigest.CategoryCap("Groceries", BigDecimal("850"), BigDecimal("1000"), over = false, currencyCode = "AED")),
        )
        assertEquals(
            "Unusual: AED 100 at Careem, 4.2× your usual.",
            w.item(WhatChangedDigest.UnusualCharge("Careem", BigDecimal("100"), 4.166, "AED")),
        )
        assertEquals("New subscription: Netflix, AED 45.", w.item(WhatChangedDigest.NewSubscription("Netflix", BigDecimal("45"), "AED")))
        assertEquals(
            "Spotify went up from AED 40 to AED 45.",
            w.item(WhatChangedDigest.PriceChange("Spotify", BigDecimal("40"), BigDecimal("45"), "AED")),
        )
        assertEquals("Groceries: AED 300 this week, up from AED 100.", w.item(WhatChangedDigest.CategorySwing("Groceries", 300.0, 100.0)))
        assertEquals("Dining: AED 60 this week, nothing last week.", w.item(WhatChangedDigest.CategorySwing("Dining", 60.0, 0.0)))
    }

    @Test
    fun `payments say when`() {
        fun bill(days: Long) = w.item(WhatChangedDigest.PaymentDue("Rent", BigDecimal("4000"), "AED", days, isCard = false))
        assertEquals("Rent (AED 4000) is due in 3 days.", bill(3))
        assertEquals("Rent (AED 4000) is due tomorrow.", bill(1))
        assertEquals("Rent (AED 4000) is due today.", bill(0))
        assertEquals("Rent (AED 4000) was due yesterday.", bill(-1))
        assertEquals("Rent (AED 4000) was due 4 days ago.", bill(-4))
        assertEquals(
            "Visa payment of AED 900 is due in 2 days.",
            w.item(WhatChangedDigest.PaymentDue("Visa", BigDecimal("900"), "AED", 2, isCard = true)),
        )
    }
}
