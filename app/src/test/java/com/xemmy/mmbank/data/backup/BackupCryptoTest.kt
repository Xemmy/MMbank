package com.xemmy.mmbank.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val payload = """{"formatVersion":1,"accounts":[{"name":"ADIB Main"}]}"""
        .toByteArray(Charsets.UTF_8)

    @Test
    fun `round trip restores the exact payload`() {
        val blob = BackupCrypto.encrypt(payload, "correct horse".toCharArray())
        val back = BackupCrypto.decrypt(blob, "correct horse".toCharArray())
        assertArrayEquals(payload, back)
    }

    @Test
    fun `wrong password fails cleanly, not with garbage output`() {
        val blob = BackupCrypto.encrypt(payload, "rightpass".toCharArray())
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(blob, "wrongpass".toCharArray())
        }
        assertTrue(ex.message!!.contains("Wrong password"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = BackupCrypto.encrypt(payload, "rightpass".toCharArray())
        blob[blob.size - 3] = (blob[blob.size - 3] + 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(blob, "rightpass".toCharArray())
        }
    }

    @Test
    fun `non-backup file is rejected by magic check`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt("this is definitely not a backup".toByteArray(), "pw".toCharArray())
        }
    }

    @Test
    fun `two exports of the same data differ (fresh salt and iv)`() {
        val a = BackupCrypto.encrypt(payload, "pw123456".toCharArray())
        val b = BackupCrypto.encrypt(payload, "pw123456".toCharArray())
        assertTrue(!a.contentEquals(b))
    }
}
