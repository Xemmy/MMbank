package com.xemmy.mmbank.domain.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes and checks the app PIN -- the lock's own secret, separate from the
 * phone's unlock PIN. Only the hash is ever stored (in
 * [com.xemmy.mmbank.data.prefs.SecureCredentialStore], itself encrypted).
 *
 * PBKDF2-HMAC-SHA256 with a random 16-byte salt, encoded as
 * `pbkdf2$<iterations>$<salt>$<hash>` (Base64), so the work factor can be
 * raised later without breaking stored PINs. Verification is constant-time.
 * A 4-8 digit PIN is a small keyspace, which is why [PinAttemptLimiter] slows
 * guessing on the device. Pure JVM (`javax.crypto`, `java.util.Base64`).
 */
object PinHasher {

    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 8
    const val DEFAULT_ITERATIONS = 120_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    private const val PREFIX = "pbkdf2"

    fun isValidPin(pin: String): Boolean = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** The stored form of [pin]. Throws [IllegalArgumentException] for an invalid PIN. */
    fun hash(pin: String, iterations: Int = DEFAULT_ITERATIONS, random: SecureRandom = SecureRandom()): String {
        require(isValidPin(pin)) { "A PIN is $MIN_LENGTH-$MAX_LENGTH digits" }
        require(iterations > 0)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(pin, salt, iterations)
        val b64 = Base64.getEncoder()
        return "$PREFIX$$iterations$${b64.encodeToString(salt)}$${b64.encodeToString(derived)}"
    }

    /** Whether [pin] matches [stored]. A malformed or tampered [stored] never matches. */
    fun verify(pin: String, stored: String): Boolean {
        if (!isValidPin(pin)) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it in 1..10_000_000 } ?: return false
        val decoder = Base64.getDecoder()
        val salt = runCatching { decoder.decode(parts[2]) }.getOrNull()?.takeIf { it.size == SALT_BYTES } ?: return false
        val expected = runCatching { decoder.decode(parts[3]) }.getOrNull()?.takeIf { it.size == KEY_BITS / 8 } ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), expected)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
