package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Stub SQLCipher-backed [DatabaseEncryptor]. Not wired into [AppDatabase] yet —
 * this is scaffolding for a future phase that swaps Room's default SQLite
 * driver for an encrypted one. Intentionally not implemented: real wiring
 * needs a passphrase source (Keystore-backed) that doesn't exist yet.
 */
class SecureDatabaseFactory : DatabaseEncryptor {
    override fun getEncryptedOpenHelper(context: Context, passphrase: String): SupportSQLiteOpenHelper =
        TODO("Requires device implementation: wrap net.zetetic SQLCipher SupportFactory here")
}
