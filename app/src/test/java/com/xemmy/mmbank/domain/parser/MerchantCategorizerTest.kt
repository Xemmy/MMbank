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

    // -- short keywords must stand as a whole word ------------------------------

    @Test
    fun `short keywords no longer fire inside unrelated words`() {
        val none = SpendingCategory.UNCATEGORIZED
        assertEquals(none, MerchantCategorizer.categorize("Grand Palace"))
        assertEquals(none, MerchantCategorizer.categorize("Space Odyssey"))
        assertEquals(none, MerchantCategorizer.categorize("Marketplace XYZ"))
        assertEquals(none, MerchantCategorizer.categorize("Acer Dubai"))
        assertEquals(none, MerchantCategorizer.categorize("Smart Gadgets"))
        assertEquals(none, MerchantCategorizer.categorize("Martini Bar"))
        assertEquals(none, MerchantCategorizer.categorize("Halal Kitchen"))
        assertEquals(none, MerchantCategorizer.categorize("Private Label"))
    }

    @Test
    fun `short keywords still match as whole words`() {
        assertEquals(SpendingCategory.SHOPPING, MerchantCategorizer.categorize("ACE Hardware"))
        assertEquals(SpendingCategory.SHOPPING, MerchantCategorizer.categorize("ACE"))
        assertEquals(SpendingCategory.TRANSPORT, MerchantCategorizer.categorize("Hala Taxi"))
        assertEquals(SpendingCategory.TRANSPORT, MerchantCategorizer.categorize("NOL top-up"))
        assertEquals(SpendingCategory.FEES, MerchantCategorizer.categorize("VAT 5%"))
        assertEquals(SpendingCategory.FEES, MerchantCategorizer.categorize("Annual fees"))
        assertEquals(SpendingCategory.DINING, MerchantCategorizer.categorize("KFC-DUBAI"))
    }

    @Test
    fun `mart still matches as a word or a known compound`() {
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("Lulu Mart"))
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("D-Mart"))
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("DMART"))
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("Supermart Al Barsha"))
    }

    @Test
    fun `longer keywords keep matching inside larger strings`() {
        assertEquals(SpendingCategory.SHOPPING, MerchantCategorizer.categorize("Bookshop Downtown"))
        assertEquals(SpendingCategory.GROCERIES, MerchantCategorizer.categorize("WWW.CARREFOURUAE.COM"))
    }

    @Test
    fun `the message text is searched with the same word rules`() {
        assertEquals(
            SpendingCategory.UNCATEGORIZED,
            MerchantCategorizer.categorize(merchant = null, rawText = "You spent AED 120 at Palace Holdings Group"),
        )
        assertEquals(
            SpendingCategory.TRANSPORT,
            MerchantCategorizer.categorize(merchant = null, rawText = "You spent AED 12 on NOL card top-up"),
        )
    }
}
