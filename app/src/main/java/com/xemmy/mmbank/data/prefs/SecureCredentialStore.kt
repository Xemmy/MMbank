package com.xemmy.mmbank.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.xemmy.mmbank.data.local.DatabaseKeyMaterial

/**
 * Stores the Binance READ-ONLY API key pair in EncryptedSharedPreferences
 * (AES-256, key held in Android Keystore). Never grant this key trade or
 * withdrawal permissions — the app only ever calls read endpoints.
 */
class SecureCredentialStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "mmbank_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var binanceApiKey: String?
        get() = prefs.getString(KEY_BINANCE_API, null)
        set(value) = prefs.edit().putString(KEY_BINANCE_API, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    var binanceSecretKey: String?
        get() = prefs.getString(KEY_BINANCE_SECRET, null)
        set(value) = prefs.edit().putString(KEY_BINANCE_SECRET, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    val hasBinanceCredentials: Boolean
        get() = !binanceApiKey.isNullOrBlank() && !binanceSecretKey.isNullOrBlank()

    fun clearBinanceCredentials() {
        prefs.edit().remove(KEY_BINANCE_API).remove(KEY_BINANCE_SECRET).apply()
    }

    /**
     * API key for the Gate-2 online AI provider (sent as an Authorization
     * header to a cloud endpoint the user explicitly configured). Encrypted
     * at rest like the Binance keys above -- never stored in plain
     * SharedPreferences, unlike the endpoint URL/model, which carry no secret.
     */
    var aiApiKey: String?
        get() = prefs.getString(KEY_AI_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_AI_API_KEY, value?.trim()?.takeIf { it.isNotEmpty() }).apply()

    fun clearAiApiKey() {
        prefs.edit().remove(KEY_AI_API_KEY).apply()
    }

    /**
     * The app lock's own PIN, hashed (`pbkdf2$...`, see
     * [com.xemmy.mmbank.domain.auth.PinHasher]); never the PIN itself. Null
     * when no app PIN is set.
     */
    var appPinHash: String?
        get() = prefs.getString(KEY_APP_PIN_HASH, null)
        set(value) = prefs.edit().putString(KEY_APP_PIN_HASH, value?.takeIf { it.isNotBlank() }).apply()

    val hasAppPin: Boolean get() = !appPinHash.isNullOrBlank()

    /**
     * SQLCipher passphrase for the Room database: 32 CSPRNG bytes as hex,
     * generated exactly once per install and never rotated (rotating would
     * orphan the encrypted DB). Persisted with synchronous commit() — if the
     * process died after encrypting the DB but before this key hit disk, the
     * ledger would be permanently unreadable.
     *
     * Losing this entry (e.g. a wiped Keystore) makes the DB unrecoverable;
     * the JSON backup/export in Settings is the recovery path.
     */
    fun getOrCreateDatabasePassphrase(): String {
        prefs.getString(KEY_DB_PASSPHRASE, null)?.let { return it }
        val fresh = DatabaseKeyMaterial.generate()
        check(prefs.edit().putString(KEY_DB_PASSPHRASE, fresh).commit()) {
            "Failed to persist the database passphrase"
        }
        return fresh
    }

    private companion object {
        const val KEY_BINANCE_API = "binance_api_key"
        const val KEY_BINANCE_SECRET = "binance_secret_key"
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        const val KEY_AI_API_KEY = "ai_api_key"
        const val KEY_APP_PIN_HASH = "app_pin_hash"
    }
}
