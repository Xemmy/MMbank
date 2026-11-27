package com.xemmy.mmbank.domain.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {

    /** Few iterations: the format and checks are what's under test, not the work factor. */
    private fun hash(pin: String) = PinHasher.hash(pin, iterations = 1_000)

    @Test
    fun `the right PIN verifies, a wrong one doesn't`() {
        val stored = hash("2580")
        assertTrue(PinHasher.verify("2580", stored))
        assertFalse(PinHasher.verify("2581", stored))
        assertFalse(PinHasher.verify("25800", stored))
    }

    @Test
    fun `the same PIN hashes differently every time`() {
        val a = hash("123456")
        val b = hash("123456")
        assertNotEquals(a, b)
        assertTrue(PinHasher.verify("123456", a))
        assertTrue(PinHasher.verify("123456", b))
    }

    @Test
    fun `the work factor travels with the hash`() {
        val stored = PinHasher.hash("4321", iterations = 2_000)
        assertTrue(stored.startsWith("pbkdf2\$2000\$"))
        assertTrue(PinHasher.verify("4321", stored))
    }

    @Test
    fun `tampered or malformed stored values never match`() {
        val stored = hash("2580")
        val parts = stored.split('$')
        assertFalse(PinHasher.verify("2580", ""))
        assertFalse(PinHasher.verify("2580", "plain2580"))
        assertFalse(PinHasher.verify("2580", "md5$1000$${parts[2]}$${parts[3]}"))
        assertFalse(PinHasher.verify("2580", "pbkdf2$0$${parts[2]}$${parts[3]}"))
        assertFalse(PinHasher.verify("2580", "pbkdf2$1000$!!!$${parts[3]}"))
        assertFalse(PinHasher.verify("2580", "pbkdf2$1000$${parts[2]}$${parts[2]}"))
        assertFalse(PinHasher.verify("2580", "pbkdf2$1001$${parts[2]}$${parts[3]}"))
    }

    @Test
    fun `only 4 to 8 digits are a PIN`() {
        assertTrue(PinHasher.isValidPin("1234"))
        assertTrue(PinHasher.isValidPin("12345678"))
        assertFalse(PinHasher.isValidPin("123"))
        assertFalse(PinHasher.isValidPin("123456789"))
        assertFalse(PinHasher.isValidPin("12a4"))
        assertFalse(PinHasher.isValidPin("١٢٣٤"))
        assertFalse(PinHasher.verify("12a4", hash("1234")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `hashing an invalid PIN is refused`() {
        PinHasher.hash("12", iterations = 1_000)
    }
}
