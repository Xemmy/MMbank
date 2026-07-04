package com.xemmy.mmbank.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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

    private companion object {
        const val KEY_BINANCE_API = "binance_api_key"
        const val KEY_BINANCE_SECRET = "binance_secret_key"
    }
}
