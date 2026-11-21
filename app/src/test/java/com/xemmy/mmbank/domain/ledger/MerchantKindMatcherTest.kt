package com.xemmy.mmbank.domain.ledger

import com.xemmy.mmbank.domain.model.MerchantKindMapping
import com.xemmy.mmbank.domain.model.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MerchantKindMatcherTest {

    private val rules = listOf(
        MerchantKindMapping(merchantPattern = "ADCB CC PAYMENT", kind = TransactionKind.CARD_PAYMENT),
        MerchantKindMapping(merchantPattern = "Carrefour", kind = TransactionKind.SPEND),
    )

    @Test
    fun `exact match is case-insensitive`() {
        assertEquals(TransactionKind.CARD_PAYMENT, MerchantKindMatcher.match("adcb cc payment", rules))
    }

    @Test
    fun `a differently formatted spelling of the same brand matches by key`() {
        assertEquals(TransactionKind.SPEND, MerchantKindMatcher.match("POS PURCHASE CARREFOUR", rules))
    }

    @Test
    fun `unknown, blank and null merchants match nothing`() {
        assertNull(MerchantKindMatcher.match("Lulu", rules))
        assertNull(MerchantKindMatcher.match("  ", rules))
        assertNull(MerchantKindMatcher.match(null, rules))
        assertNull(MerchantKindMatcher.match("ADCB CC PAYMENT", emptyList()))
    }
}
