package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.SpendingCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantCategorizerTest {

    @Test
    fun `groceries merchants map to GROCERIES`() {
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("CARREFOUR-MAF YAS"))
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("Lulu Hypermarket"))
    }

    @Test
    fun `fuel wins over generic station wording`() {
        assertEquals(SpendingCategory.FUEL, MerchantCategorizer.categorize("ADNOC Station 123"))
        assertEquals(SpendingCategory.FUEL, MerchantCategorizer.categorize("ENOC"))
    }

    @Test
    fun `ride hailing and transit map to TRANSPORT`() {
        assertEquals(SpendingCategory.TRANSPORT, MerchantCategorizer.categorize("CAREEM RIDE"))
        assertEquals(SpendingCategory.TRANSPORT, MerchantCategorizer.categorize("Salik toll"))
    }

    @Test
    fun `food delivery and restaurants map to DINING`() {
        assertEquals(SpendingCategory.DINING, MerchantCategorizer.categorize("TALABAT"))
        assertEquals(SpendingCategory.DINING, MerchantCategorizer.categorize("Starbucks Coffee"))
    }

    @Test
    fun `subscriptions map to ENTERTAINMENT`() {
        assertEquals(SpendingCategory.ENTERTAINMENT, MerchantCategorizer.categorize("NETFLIX.COM"))
        assertEquals(SpendingCategory.ENTERTAINMENT, MerchantCategorizer.categorize("APPLE.COM/BILL"))
    }

    @Test
    fun `utilities map to UTILITIES`() {
        assertEquals(SpendingCategory.UTILITIES, MerchantCategorizer.categorize("DEWA bill"))
    }

    @Test
    fun `it can fall back to the raw message text`() {
        assertEquals(
            SpendingCategory.GROCERIES,
            MerchantCategorizer.categorize(merchant = null, rawText = "You spent AED 32 at SPINNEYS DUBAI"),
        )
    }

    @Test
    fun `unknown merchant stays UNCATEGORIZED`() {
        assertEquals(SpendingCategory.UNCATEGORIZED, MerchantCategorizer.categorize("Zxqwer Ltd"))
        assertEquals(SpendingCategory.UNCATEGORIZED, MerchantCategorizer.categorize(null, null))
        assertEquals(SpendingCategory.UNCATEGORIZED, MerchantCategorizer.categorize("   "))
    }
}
