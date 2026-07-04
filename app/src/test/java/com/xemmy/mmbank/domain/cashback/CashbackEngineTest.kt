package com.xemmy.mmbank.domain.cashback

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CashbackEngineTest {

    private fun spend(vararg pairs: Pair<String, String>): Map<String, BigDecimal> =
        pairs.associate { it.first to BigDecimal(it.second) }

    @Test
    fun `routes each category to the highest-rate card`() {
        val plan = CashbackEngine.compute(
            CashbackCatalog.cards,
            CashbackCatalog.categories,
            spend(
                CashbackCatalog.DINING_DELIVERY to "1000",
                CashbackCatalog.FUEL_SALIK to "1000",
                CashbackCatalog.GROCERIES_LULU to "1000",
            ),
        )
        val byCat = plan.routings.associateBy { it.category.key }
        // Dining: ADCB 365 at 6%.
        assertEquals("ADCB 365", byCat.getValue(CashbackCatalog.DINING_DELIVERY).card?.name)
        assertEquals(BigDecimal("60.00"), byCat.getValue(CashbackCatalog.DINING_DELIVERY).cashback)
        // Fuel: FAB at 5% (beats ADCB's 3%).
        assertEquals("FAB Cashback", byCat.getValue(CashbackCatalog.FUEL_SALIK).card?.name)
        // LuLu groceries: ADCB LuLu Platinum at 5%.
        assertEquals("ADCB LuLu Platinum", byCat.getValue(CashbackCatalog.GROCERIES_LULU).card?.name)
    }

    @Test
    fun `flags a card below its minimum monthly spend`() {
        // Only 1000 of grocery routed to ADCB LuLu (needs 3500) -> below-min alert.
        val plan = CashbackEngine.compute(
            CashbackCatalog.cards,
            CashbackCatalog.categories,
            spend(CashbackCatalog.GROCERIES_LULU to "1000"),
        )
        val lulu = plan.cardSummaries.first { it.card.id == "adcb_lulu" }
        assertTrue(lulu.belowMinSpend)
        assertTrue(plan.alerts.any { !it.ok && it.message.contains("ADCB LuLu") })
    }

    @Test
    fun `applies the monthly cap`() {
        // FAB caps at 300/mo: 5% of 10000 = 500 raw, capped to 300.
        val plan = CashbackEngine.compute(
            CashbackCatalog.cards,
            CashbackCatalog.categories,
            spend(CashbackCatalog.FUEL_SALIK to "10000"),
        )
        val fab = plan.cardSummaries.first { it.card.id == "fab_cashback" }
        assertEquals(BigDecimal("500.00"), fab.rawCashback)
        assertTrue(fab.cappedCashback.compareTo(BigDecimal("300")) == 0)
        assertTrue(fab.capReached)
    }

    @Test
    fun `net subtracts spread annual fees`() {
        // Tabby+ only: fashion 600 @5% = 30 gross, fee 588/12 = 49 -> net negative.
        val plan = CashbackEngine.compute(
            CashbackCatalog.cards,
            CashbackCatalog.categories,
            spend(CashbackCatalog.ONLINE_FASHION to "600"),
        )
        assertEquals(BigDecimal("30.00"), plan.grossMonthly)
        assertEquals(BigDecimal("49.00"), plan.monthlyFees)
        assertEquals(BigDecimal("-19.00"), plan.netMonthly)
        assertTrue(plan.alerts.any { !it.ok && it.message.contains("Tabby+") })
    }

    @Test
    fun `empty spend yields zero`() {
        val plan = CashbackEngine.compute(CashbackCatalog.cards, CashbackCatalog.categories, emptyMap())
        assertEquals(BigDecimal.ZERO, plan.grossMonthly)
        assertEquals(BigDecimal.ZERO, plan.monthlyFees)
        assertTrue(plan.cardSummaries.isEmpty())
    }
}
