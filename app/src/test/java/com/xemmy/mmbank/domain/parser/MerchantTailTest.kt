package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantTailTest {
    @Test fun `english tail is cut`() {
        assertEquals("AMAZON", MerchantTail.trim("AMAZON on card 1234"))
        assertEquals("CARREFOUR", MerchantTail.trim("CARREFOUR with balance AED 10"))
    }

    @Test fun `arabic account tail is cut`() {
        assertEquals("نون", MerchantTail.trim("نون حساب رقم XXX810"))
    }

    @Test fun `plain names are untouched`() {
        assertEquals("Noon Minutes", MerchantTail.trim("Noon Minutes"))
    }

    @Test fun `name is never trimmed to nothing`() {
        assertEquals("Card Mart", MerchantTail.trim("Card Mart"))
    }
}
