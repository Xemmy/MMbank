package com.xemmy.mmbank.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Abstraction over how the Room database's underlying SQLite connection is
 * opened. Room constructs its own open helper from a factory (so it can bind
 * migrations, callbacks and the schema), which is why this hands back a
 * [SupportSQLiteOpenHelper.Factory] rather than a helper instance.
 */
interface DatabaseEncryptor {
    fun encryptedFactory(context: Context, passphrase: String): SupportSQLiteOpenHelper.Factory
}
