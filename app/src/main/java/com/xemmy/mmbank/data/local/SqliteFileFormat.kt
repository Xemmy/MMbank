package com.xemmy.mmbank.data.local

/**
 * Detects whether a database file is plaintext SQLite or (assumed) SQLCipher.
 * Every plaintext SQLite file starts with the 16-byte magic header
 * "SQLite format 3" + NUL; an SQLCipher file's first page is ciphertext, so
 * the magic can never appear. Pure Kotlin for JVM tests; file I/O stays with
 * the caller.
 */
object SqliteFileFormat {

    const val HEADER_LENGTH = 16

    private val PLAINTEXT_MAGIC =
        byteArrayOf(
            0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66, // "SQLite f"
            0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00, // "ormat 3" + NUL
        )

    /** True iff [header] begins with the plaintext SQLite magic. */
    fun isPlaintextHeader(header: ByteArray): Boolean {
        if (header.size < HEADER_LENGTH) return false
        for (i in 0 until HEADER_LENGTH) {
            if (header[i] != PLAINTEXT_MAGIC[i]) return false
        }
        return true
    }

    /**
     * True when a database file exists but is not plaintext SQLite, i.e. it is
     * (assumed) SQLCipher. Opening such a file *without* its key makes SQLite
     * report "not a database" and Room's default corruption handler deletes
     * it, so callers must refuse to open it plain when the key is unavailable.
     */
    fun isEncryptedFile(exists: Boolean, header: ByteArray): Boolean =
        exists && !isPlaintextHeader(header)
}
