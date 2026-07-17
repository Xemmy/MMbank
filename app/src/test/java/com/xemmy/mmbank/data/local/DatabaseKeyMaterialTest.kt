package com.xemmy.mmbank.data.local

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseKeyMaterialTest {

    @Test
    fun `passphrase is 64 lowercase hex chars for 32 bytes of entropy`() {
        val passphrase = DatabaseKeyMaterial.generate()
        assertEquals(DatabaseKeyMaterial.PASSPHRASE_BYTES * 2, passphrase.length)
        assertTrue(
            "unexpected characters in: $passphrase",
            passphrase.all { it in '0'..'9' || it in 'a'..'f' },
        )
    }

    @Test
    fun `two generations never collide`() {
        assertNotEquals(DatabaseKeyMaterial.generate(), DatabaseKeyMaterial.generate())
    }

    @Test
    fun `generation is deterministic for a seeded random source`() {
        // SHA1PRNG honors setSeed as the sole entropy source, so two identically
        // seeded instances must produce identical key material.
        fun seeded() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        assertEquals(
            DatabaseKeyMaterial.generate(seeded()),
            DatabaseKeyMaterial.generate(seeded()),
        )
    }

    @Test
    fun `toHex renders known bytes correctly`() {
        assertEquals("00ff10ab", DatabaseKeyMaterial.toHex(byteArrayOf(0x00, -1, 0x10, -85)))
        assertEquals("", DatabaseKeyMaterial.toHex(byteArrayOf()))
    }
}
