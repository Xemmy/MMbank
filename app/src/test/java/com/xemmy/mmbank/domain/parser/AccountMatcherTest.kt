package com.xemmy.mmbank.domain.parser

import com.xemmy.mmbank.domain.model.MerchantAccountMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A wrong account here silently moves money between balances -- a sharper
 * failure than a wrong category -- so the matcher is deliberately narrower than
 * [CategoryMatcher]: two tiers, no regex, and nothing matches on an empty key.
 */
class AccountMatcherTest {

    private fun mapping(pattern: String, accountId: Long) =
        MerchantAccountMapping(merchantPattern = pattern, accountId = accountId)

    @Test
    fun `an exact merchant match resolves to its account`() {
        assertEquals(7L, AccountMatcher.match("Golden Fork", listOf(mapping("Golden Fork", 7))))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertEquals(7L, AccountMatcher.match("GOLDEN FORK", listOf(mapping("golden fork", 7))))
    }

    @Test
    fun `a differently-formatted spelling resolves through the key tier`() {
        // The whole point: the rule was learned from one message's spelling and
        // has to apply to the next one's.
        val mappings = listOf(mapping("CARREFOUR MALL OF EMIRATES", 3))
        assertEquals(3L, AccountMatcher.match("POS CARREFOUR HYPERMARKET DXB *4543", mappings))
    }

    @Test
    fun `an unrelated merchant resolves to nothing`() {
        assertNull(AccountMatcher.match("Spinneys", listOf(mapping("Golden Fork", 7))))
    }

    @Test
    fun `a blank or null merchant never matches`() {
        val mappings = listOf(mapping("Golden Fork", 7))
        assertNull(AccountMatcher.match(null, mappings))
        assertNull(AccountMatcher.match("   ", mappings))
    }

    @Test
    fun `an empty mapping list returns null`() {
        assertNull(AccountMatcher.match("Golden Fork", emptyList()))
    }

    @Test
    fun `two unkeyable merchants never collide on the empty key`() {
        // Both key to "", which must mean "no key", not "the same key".
        assertNull(AccountMatcher.match("##", listOf(mapping("@@", 7))))
    }

    @Test
    fun `an exact match wins over a later key match pointing elsewhere`() {
        val mappings = listOf(
            mapping("Carrefour", 1),
            mapping("Carrefour Market Jumeirah", 99),
        )
        assertEquals(99L, AccountMatcher.match("Carrefour Market Jumeirah", mappings))
    }

    @Test
    fun `a regex-looking pattern is treated as a literal, not a pattern`() {
        // Account rules are always written from a concrete merchant, never
        // hand-authored, so there is no regex tier to be surprised by.
        assertNull(AccountMatcher.match("Golden Fork", listOf(mapping(".*", 7))))
    }
}
