package com.xemmy.mmbank.domain.paylater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayLaterProvidersTest {

    private val adibCharge = "Trx. of AED231.00 on your card ending *876 at Tabby, 8001234 UAE is Approved."
    private val receipt = "We received your first payment of AED 231.00. To pay ahead, open the Tabby app."
    private val checkout = "Your AED 924.00 purchase at Shory is confirmed. To pay as low as 77.00/month, extend your payment plan in the app"

    @Test fun `a merchant naming Tabby paid Tabby`() {
        assertEquals("Tabby", PayLaterProviders.providerOf("Tabby", null, "ADIB"))
        assertEquals("Tabby", PayLaterProviders.providerOf("TABBY FZ LLC", null, "ENBD"))
        assertNull(PayLaterProviders.providerOf("Carrefour", "paid with Tabby", "ENBD"))
    }

    @Test fun `a bank charge with no merchant is read from its text, whichever bank`() {
        assertEquals("Tabby", PayLaterProviders.providerOf(null, adibCharge, "ADIB"))
        assertEquals("Tabby", PayLaterProviders.providerOf(" ", "AED 231.00 was debited from card 4321 to TABBY", "ENBD"))
        assertNull(PayLaterProviders.providerOf(null, "Trx. of AED68.00 on your card ending *876 at MCDONALDS", "ADIB"))
    }

    @Test fun `Tabby's own messages and Tabby card spends are not payments to Tabby`() {
        assertNull(PayLaterProviders.providerOf(null, checkout + " Tabby", "Tabby"))
        assertNull(PayLaterProviders.providerOf(null, "AED27.60 with Tabby Cash Card ••4543", "ENBD"))
        assertNull(PayLaterProviders.providerOf(null, "Your Tabby Card limit is now AED 6813.02", "ENBD"))
        assertNull(PayLaterProviders.providerOf("Tabby+", null, "ENBD"))
    }

    @Test fun `a receipt must come from Tabby`() {
        assertTrue(PayLaterProviders.isReceipt(receipt, "Tabby"))
        assertTrue(PayLaterProviders.isReceipt("We have received your 3rd payment of AED 231.00", "com.tabby.customer"))
        assertFalse(PayLaterProviders.isReceipt(receipt, "ADIB"))
        assertFalse(PayLaterProviders.isReceipt(checkout, "Tabby"))
        assertFalse(PayLaterProviders.isReceipt(null, "Tabby"))
    }

    @Test fun `the checkout text gives the shop`() {
        assertTrue(PayLaterProviders.isSplitPurchase(checkout, "Tabby"))
        assertEquals("Shory", PayLaterProviders.splitPurchaseMerchant(checkout))
        assertEquals("Noon Minutes", PayLaterProviders.splitPurchaseMerchant("Your AED924.00 purchase at Noon Minutes is confirmed."))
        assertFalse(PayLaterProviders.isSplitPurchase(checkout, "ADIB"))
        assertFalse(PayLaterProviders.isSplitPurchase(receipt, "Tabby"))
        assertNull(PayLaterProviders.splitPurchaseMerchant(receipt))
    }
}
