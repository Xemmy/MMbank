package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Abstraction over how the Room database's underlying SQLite connection is
 * opened. Lets a future encrypted implementation (SQLCipher) swap in without
 * AppDatabase/AppContainer knowing the difference.
 */
interface DatabaseEncryptor {
    fun getEncryptedOpenHelper(context: Context, passphrase: String): SupportSQLiteOpenHelper
}
