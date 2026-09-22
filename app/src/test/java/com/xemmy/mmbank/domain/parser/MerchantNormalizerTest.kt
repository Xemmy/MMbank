package com.xemmy.mmbank.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The normalizer exists so a rule taught for one spelling of a shop applies to
 * the next message from the same shop. Its whole design stance is that
 * **under-merging is acceptable and over-merging is not**: a missed merge costs
 * one extra correction, a wrong merge files one merchant's rules onto another.
 *
 * Roughly half the cases below are therefore negative — things it must refuse
 * to collapse.
 */
class MerchantNormalizerTest {

    // --- noise stripping ---

    @Test
    fun `processor prefixes are stripped from the key`() {
        assertEquals("carrefour", MerchantNormalizer.key("POS PURCHASE CARREFOUR"))
        assertEquals("carrefour", MerchantNormalizer.key("ECOM CARREFOUR"))
        assertEquals("golden fork", MerchantNormalizer.key("CARD GOLDEN FORK"))
    }

    @Test
    fun `a trailing city or country token is stripped`() {
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK DUBAI"))
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK DXB AE"))
    }

    @Test
    fun `ABU DHABI is stripped as two separate tokens`() {
        // Stripping one token at a time from the end means the two-word city
        // falls out without needing a rule that knows about it.
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK ABU DHABI"))
    }

    @Test
    fun `a leading city is kept because only the tail is stripped`() {
        // "Dubai Mall" is the merchant's actual name, not a location suffix.
        assertEquals("dubai mall parking", MerchantNormalizer.key("DUBAI MALL PARKING"))
    }

    @Test
    fun `store numbers and card masks are dropped`() {
        assertEquals("carrefour", MerchantNormalizer.key("CARREFOUR #1234"))
        assertEquals("carrefour", MerchantNormalizer.key("CARREFOUR *4543"))
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK 0421"))
    }

    @Test
    fun `reference codes are dropped`() {
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK TXN123456"))
        assertEquals("golden fork", MerchantNormalizer.key("GOLDEN FORK XXX810001"))
    }

    @Test
    fun `a merchant that is only a number still produces something rather than nothing`() {
        // Noise filtering must never delete the last surviving token.
        assertEquals("", MerchantNormalizer.key("77"))
        assertEquals("7788", MerchantNormalizer.key("7788"))
    }

    @Test
    fun `punctuation and case never change the key`() {
        assertEquals(
            MerchantNormalizer.key("Golden-Fork, Restaurant."),
            MerchantNormalizer.key("GOLDEN FORK RESTAURANT"),
        )
    }

    @Test
    fun `an ampersand survives so H and M stays one token`() {
        assertEquals("h&m", MerchantNormalizer.key("H&M DUBAI"))
    }

    @Test
    fun `arabic-indic digits normalize before keying`() {
        // TextNormalizer runs first, so ٤٥٤٣ is a digit run to be dropped, not
        // a word to be kept.
        assertEquals("carrefour", MerchantNormalizer.key("CARREFOUR ٤٥٤٣"))
    }

    // --- the headline case ---

    @Test
    fun `two spellings of the same Carrefour store share a key`() {
        assertEquals(
            MerchantNormalizer.key("POS CARREFOUR HYPERMARKET DXB *4543"),
            MerchantNormalizer.key("CARREFOUR MALL OF EMIRATES"),
        )
        assertTrue(
            MerchantNormalizer.sameMerchant(
                "POS PURCHASE CARREFOUR HYPERMARKET DUBAI",
                "Carrefour",
            )
        )
    }

    @Test
    fun `a known brand leading the name collapses the key to the brand`() {
        assertEquals("carrefour", MerchantNormalizer.key("CARREFOUR MARKET JUMEIRAH"))
        assertEquals("adnoc", MerchantNormalizer.key("ADNOC STATION 1042"))
        assertEquals("talabat", MerchantNormalizer.key("TALABAT ORDER 998877"))
    }

    // --- the over-merge guards ---

    @Test
    fun `a brand token inside a longer venue name does not collapse`() {
        // "Emirates" leads nothing here, and the name is longer than a short
        // one, so it stays whole rather than becoming an airline.
        val key = MerchantNormalizer.key("MALL OF THE EMIRATES PARKING LEVEL")
        assertNotEquals("emirates", key)
        assertTrue(key.contains("mall"))
    }

    @Test
    fun `two different brands in one name never collapse`() {
        // No way to tell which one owns the charge, so neither wins.
        val key = MerchantNormalizer.key("CARREFOUR ADNOC")
        assertEquals("carrefour adnoc", key)
    }

    @Test
    fun `ACE does not collapse inside PALACE`() {
        // Brands match whole tokens, never substrings. ("ace" is also below the
        // minimum collapse length, so this is belt and braces.)
        val key = MerchantNormalizer.key("PALACE HOTEL")
        assertEquals("palace hotel", key)
    }

    @Test
    fun `SMART DUBAI does not collapse to mart`() {
        // "mart" is in MerchantCategorizer's vocabulary as a GENERIC word, and
        // is deliberately not a curated brand -- collapsing on it would merge
        // every unrelated shop in the ledger.
        assertEquals("smart", MerchantNormalizer.key("SMART DUBAI"))
    }

    @Test
    fun `generic words are never brand tokens`() {
        for (generic in listOf("store", "mall", "shop", "coop", "hotel", "parking", "cafe")) {
            assertFalse(
                "'$generic' must not be collapsible",
                MerchantCategorizer.BRAND_TOKENS.contains(generic),
            )
        }
    }

    @Test
    fun `short brands like KFC are left alone rather than over-collapsed`() {
        // Real brands, but also common letter runs. They still categorize fine.
        for (short in listOf("kfc", "nol", "rta", "osn", "vox", "ace")) {
            assertFalse(
                "'$short' is too short to collapse onto",
                MerchantCategorizer.BRAND_TOKENS.contains(short),
            )
        }
        assertEquals("kfc drive thru", MerchantNormalizer.key("KFC DRIVE THRU"))
    }

    @Test
    fun `noon food does not collapse to noon`() {
        // Longest-first ordering, and "noon" is blocklisted outright because it
        // is both a retailer and a time of day.
        assertEquals("noon food", MerchantNormalizer.key("NOON FOOD DELIVERY DUBAI"))
    }

    @Test
    fun `blocklisted brands never collapse even when they lead`() {
        assertEquals("emirates airline booking", MerchantNormalizer.key("EMIRATES AIRLINE BOOKING"))
    }

    // --- empties and stability ---

    @Test
    fun `blank null and punctuation-only input produce an empty key`() {
        assertEquals("", MerchantNormalizer.key(null))
        assertEquals("", MerchantNormalizer.key(""))
        assertEquals("", MerchantNormalizer.key("   "))
        assertEquals("", MerchantNormalizer.key("##"))
    }

    @Test
    fun `sameMerchant is false when both keys are empty`() {
        // Two unkeyable merchants are not the same merchant.
        assertFalse(MerchantNormalizer.sameMerchant("##", "@@"))
        assertFalse(MerchantNormalizer.sameMerchant(null, null))
    }

    @Test
    fun `key is idempotent - key of key equals key`() {
        for (raw in listOf(
            "POS CARREFOUR HYPERMARKET DXB *4543",
            "GOLDEN FORK RESTAURANT ABU DHABI",
            "MALL OF THE EMIRATES PARKING LEVEL",
            "H&M DUBAI",
        )) {
            val once = MerchantNormalizer.key(raw)
            assertEquals("not idempotent for '$raw'", once, MerchantNormalizer.key(once))
        }
    }

    @Test
    fun `genuinely different merchants keep different keys`() {
        assertNotEquals(
            MerchantNormalizer.key("SPINNEYS MARKET DUBAI"),
            MerchantNormalizer.key("CHOITHRAM MARKET DUBAI"),
        )
        assertFalse(MerchantNormalizer.sameMerchant("GOLDEN FORK", "SILVER SPOON"))
    }

    // --- displayName ---

    @Test
    fun `displayName keeps the venue and does not collapse to the brand`() {
        // The key may collapse to "carrefour"; the label the user reads must
        // still say which branch it was.
        assertEquals("Carrefour Market Jumeirah", MerchantNormalizer.displayName("CARREFOUR MARKET JUMEIRAH"))
        assertEquals("carrefour", MerchantNormalizer.key("CARREFOUR MARKET JUMEIRAH"))
    }

    @Test
    fun `displayName title-cases shouted names but leaves short ones alone`() {
        assertEquals("Golden Fork", MerchantNormalizer.displayName("GOLDEN FORK DUBAI"))
        assertEquals("KFC", MerchantNormalizer.displayName("KFC"))
        assertEquals("H&M", MerchantNormalizer.displayName("H&M DXB"))
    }

    @Test
    fun `displayName returns null for unusable input`() {
        assertNull(MerchantNormalizer.displayName(null))
        assertNull(MerchantNormalizer.displayName("   "))
        assertNull(MerchantNormalizer.displayName("##"))
    }
}
