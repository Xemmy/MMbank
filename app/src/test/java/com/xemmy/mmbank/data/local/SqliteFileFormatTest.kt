package com.xemmy.mmbank.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plaintext-vs-encrypted decision guards the one-time SQLCipher
 * migration: a false positive would re-run the export against ciphertext, a
 * false negative would leave a plaintext ledger unencrypted forever.
 */
class SqliteFileFormatTest {

    // The genuine 16-byte magic: "SQLite format 3" terminated by a NUL byte.
    private val plaintextHeader =
        "SQLite format 3".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00)

    @Test
    fun `real sqlite header is plaintext`() {
        assertTrue(SqliteFileFormat.isPlaintextHeader(plaintextHeader))
    }

    @Test
    fun `header followed by page content is still plaintext`() {
        assertTrue(SqliteFileFormat.isPlaintextHeader(plaintextHeader + byteArrayOf(0x10, 0x00, 0x01)))
    }

    @Test
    fun `ciphertext-looking bytes are not plaintext`() {
        val encrypted = ByteArray(16) { i -> (i * 37 + 91).toByte() }
        assertFalse(SqliteFileFormat.isPlaintextHeader(encrypted))
    }

    @Test
    fun `short read is never plaintext`() {
        assertFalse(SqliteFileFormat.isPlaintextHeader(ByteArray(0)))
        assertFalse(SqliteFileFormat.isPlaintextHeader(plaintextHeader.copyOf(15)))
    }

    @Test
    fun `single flipped byte in the magic is not plaintext`() {
        val corrupted = plaintextHeader.copyOf().also { it[0] = 'X'.code.toByte() }
        assertFalse(SqliteFileFormat.isPlaintextHeader(corrupted))
    }

    @Test
    fun `magic with trailing space instead of NUL is not plaintext`() {
        val wrongTerminator = "SQLite format 3 ".toByteArray(Charsets.ISO_8859_1)
        assertFalse(SqliteFileFormat.isPlaintextHeader(wrongTerminator))
    }

    @Test
    fun encryptedFileNeedsToExistAndLackThePlaintextMagic() {
        val encrypted = ByteArray(16) { 0x7F }
        assertTrue(SqliteFileFormat.isEncryptedFile(true, encrypted))
        assertFalse(SqliteFileFormat.isEncryptedFile(true, plaintextHeader))
        assertFalse(SqliteFileFormat.isEncryptedFile(false, encrypted))
    }
}
