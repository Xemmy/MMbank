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

    /** Masks every amount on the dashboard until toggled back. */
    var hideBalances: Boolean
        get() = prefs.getBoolean(KEY_HIDE_BALANCES, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_BALANCES, value).apply()

    /** Person newly captured transactions are assigned to; null = unassigned. */
    var defaultPerson: String?
        get() = prefs.getString(KEY_DEFAULT_PERSON, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_DEFAULT_PERSON, value?.trim().orEmpty()).apply()

    /** Saved people, newline-separated. Editable in Settings, included in backups. */
    var personsRaw: String
        get() = prefs.getString(KEY_PERSONS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONS, value).apply()

    /** The saved people as a clean list, in saved order. */
    var persons: List<String>
        get() = personsRaw.split('\n').map(String::trim).filter(String::isNotEmpty).distinct()
        set(value) {
            personsRaw = value.map(String::trim).filter(String::isNotEmpty).distinct().joinToString("\n")
        }

    /** Adds [name] to the saved people if it isn't there yet (case-insensitive). */
    fun addPerson(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val current = persons
        if (current.none { it.equals(clean, ignoreCase = true) }) persons = current + clean
    }

    /** Savings goal target, in the default currency. <= 0 means no goal set. */
    var goalTargetAmount: Double
        get() = prefs.getFloat(KEY_GOAL_TARGET, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_GOAL_TARGET, value.toFloat()).apply()

    /**
     * Planned saving per month for the goal. <= 0 means "estimate from the
     * ledger" (average net flow of recent months).
     */
    var goalMonthlySaving: Double
        get() = prefs.getFloat(KEY_GOAL_MONTHLY, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_GOAL_MONTHLY, value.toFloat()).apply()

    private companion object {
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_CASHBACK_SPEND = "cashback_spend"
        const val KEY_HIDE_BALANCES = "hide_balances"
        const val KEY_DEFAULT_PERSON = "default_person"
        const val KEY_PERSONS = "persons"
        const val KEY_GOAL_TARGET = "goal_target"
        const val KEY_GOAL_MONTHLY = "goal_monthly"
    }
}
