package com.xemmy.mmbank.domain.account

import com.xemmy.mmbank.domain.account.AccountNumberMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountNumberMatcherTest {

    private val current = Candidate(1, "0123456810001", cardEndings = "4321", currencyCode = "AED")
    private val card = Candidate(2, "876", cardEndings = "5555, 6666", currencyCode = "AED")
    private val usd = Candidate(3, "9963", cardEndings = null, currencyCode = "USD")

    @Test
    fun `matches the main account number by shared suffix`() {
        assertEquals(1L, AccountNumberMatcher.match("XXX810001", "AED", listOf(current, card, usd)))
    }

    @Test
    fun `matches an extra card number on the account`() {
        assertEquals(1L, AccountNumberMatcher.match("*4321", "AED", listOf(current, card)))
        assertEquals(2L, AccountNumberMatcher.match("card ending 6666", "AED", listOf(current, card)))
    }

    @Test
    fun `fewer than three digits never matches`() {
        assertNull(AccountNumberMatcher.match("*76", "AED", listOf(card)))
        assertNull(AccountNumberMatcher.match("", "AED", listOf(card)))
    }

    @Test
    fun `currency breaks a tie between accounts sharing digits`() {
        val aed = Candidate(4, "963", null, "AED")
        assertEquals(3L, AccountNumberMatcher.match("*963", "USD", listOf(aed, usd)))
        assertEquals(4L, AccountNumberMatcher.match("*963", "AED", listOf(aed, usd)))
    }

    @Test
    fun `the longer shared suffix wins`() {
        val short = Candidate(5, "001", null, "AED")
        assertEquals(1L, AccountNumberMatcher.match("810001", "AED", listOf(short, current)))
    }

    @Test
    fun `a true tie is ambiguous and returns null instead of guessing`() {
        val a = Candidate(6, "1234", null, "AED")
        val b = Candidate(7, "1234", null, "AED")
        assertNull(AccountNumberMatcher.match("*1234", "AED", listOf(a, b)))
    }

    @Test
    fun `numbersOf splits card endings and drops short fragments`() {
        assertEquals(
            listOf("876", "5555", "6666"),
            AccountNumberMatcher.numbersOf(Candidate(1, "876", "5555; 6666, 12", "AED")),
        )
    }
}
