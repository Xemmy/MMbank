package com.xemmy.mmbank.data.prefs

import android.content.Context

/** Non-sensitive app settings. */
class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("mmbank_prefs", Context.MODE_PRIVATE)

    /** Currency assumed when a bank message carries an amount but no currency token. */
    var defaultCurrency: String
        get() = prefs.getString(KEY_DEFAULT_CURRENCY, "USD") ?: "USD"
        set(value) = prefs.edit().putString(KEY_DEFAULT_CURRENCY, value.trim().uppercase()).apply()

    /** Monthly spend per cashback category, encoded as "key=amount;key=amount". */
    var cashbackSpendRaw: String
        get() = prefs.getString(KEY_CASHBACK_SPEND, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CASHBACK_SPEND, value).apply()

    private companion object {
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_CASHBACK_SPEND = "cashback_spend"
    }
}
