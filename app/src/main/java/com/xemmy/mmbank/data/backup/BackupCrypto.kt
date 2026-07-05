package com.xemmy.mmbank.data.backup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for backup files.
 *
 * Layout: `"MMB1"(4) | salt(16) | iv(12) | AES-256-GCM ciphertext+tag`.
 * The key is derived with PBKDF2-HmacSHA256 (150k iterations), and GCM
 * authenticates the whole payload — a wrong password or a tampered file fails
 * cleanly instead of yielding garbage.
 */
object BackupCrypto {

    private const val MAGIC = "MMB1"
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256

    fun encrypt(plaintext: ByteArray, password: CharArray): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return MAGIC.toByteArray(Charsets.US_ASCII) + salt + iv + cipher.doFinal(plaintext)
    }

    fun decrypt(blob: ByteArray, password: CharArray): ByteArray {
        val headerLen = MAGIC.length + SALT_BYTES + IV_BYTES
        require(blob.size > headerLen) { "Not an MMbank backup file (too short)" }
        require(String(blob, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC) {
            "Not an MMbank backup file"
        }
        val salt = blob.copyOfRange(MAGIC.length, MAGIC.length + SALT_BYTES)
        val iv = blob.copyOfRange(MAGIC.length + SALT_BYTES, headerLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(blob.copyOfRange(headerLen, blob.size))
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password, or the file is corrupted")
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(PBEKeySpec(password, salt, ITERATIONS, KEY_BITS))
        return SecretKeySpec(key.encoded, "AES")
    }
}
