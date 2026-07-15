package com.xemmy.mmbank.data.local

import java.security.SecureRandom

/**
 * Generates the SQLCipher passphrase: 32 CSPRNG bytes rendered as lowercase
 * hex (64 chars), so it survives round-trips through preferences and SQL
 * string literals without any escaping concerns. Pure Kotlin for JVM tests.
 */
object DatabaseKeyMaterial {

    const val PASSPHRASE_BYTES = 32

    fun generate(random: SecureRandom = SecureRandom()): String =
        toHex(ByteArray(PASSPHRASE_BYTES).also(random::nextBytes))

    fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0F])
        }
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}
