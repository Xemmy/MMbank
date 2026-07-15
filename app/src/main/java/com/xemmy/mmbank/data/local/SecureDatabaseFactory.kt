package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

/**
 * SQLCipher-backed [DatabaseEncryptor]: every page of the Room database is
 * AES-encrypted at rest with the passphrase (itself generated once and held
 * in EncryptedSharedPreferences behind the Android Keystore — see
 * SecureCredentialStore.getOrCreateDatabasePassphrase).
 */
class SecureDatabaseFactory : DatabaseEncryptor {

    override fun encryptedFactory(context: Context, passphrase: String): SupportSQLiteOpenHelper.Factory {
        // Loads the native SQLCipher library; idempotent, must precede any
        // SQLCipher API use in the process.
        SQLiteDatabase.loadLibs(context)
        return SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))
    }
}
