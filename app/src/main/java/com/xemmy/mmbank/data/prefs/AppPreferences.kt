package com.xemmy.mmbank.data.prefs

import android.content.Context
import com.xemmy.mmbank.domain.ai.AiProviderMode
import com.xemmy.mmbank.domain.model.BackgroundStyle
import com.xemmy.mmbank.domain.model.DesignMode
import com.xemmy.mmbank.domain.model.FilterBarLayout

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

    /** Hides dust/zero-USD crypto holdings and wallets on the Dashboard. Default matches prior behavior. */
    var hideZeroBalances: Boolean
        get() = prefs.getBoolean(KEY_HIDE_ZERO_BALANCES, true)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_ZERO_BALANCES, value).apply()

    /** Hides the whole Crypto (Binance) section from the Dashboard. */
    var hideCryptoBalance: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CRYPTO_BALANCE, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_CRYPTO_BALANCE, value).apply()

    /**
     * Leaves crypto's dollar value out of the net-worth total, independent of
     * [hideCryptoBalance] — the section can stay visible while its value is
     * excluded from totals, or vice versa.
     */
    var excludeCryptoFromTotal: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_CRYPTO_FROM_TOTAL, false)
        set(value) = prefs.edit().putBoolean(KEY_EXCLUDE_CRYPTO_FROM_TOTAL, value).apply()

    /** Hides CREDIT_CARD accounts from the Dashboard's account list entirely. */
    var hideCreditCardBalance: Boolean
        get() = prefs.getBoolean(KEY_HIDE_CREDIT_CARD_BALANCE, false)
        set(value) = prefs.edit().putBoolean(KEY_HIDE_CREDIT_CARD_BALANCE, value).apply()

    /**
     * Leaves CREDIT_CARD accounts' balance out of the net-worth total,
     * independent of [hideCreditCardBalance] — the accounts can stay visible
     * while their value is excluded from totals, or vice versa.
     */
    var excludeCreditCardFromTotal: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_CREDIT_CARD_FROM_TOTAL, false)
        set(value) = prefs.edit().putBoolean(KEY_EXCLUDE_CREDIT_CARD_FROM_TOTAL, value).apply()

    /** Person newly captured transactions are assigned to; null = unassigned. */
    var defaultPerson: String?
        get() = prefs.getString(KEY_DEFAULT_PERSON, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_DEFAULT_PERSON, value?.trim().orEmpty()).apply()

    /** One-shot flag: the starter Tabby Card/Cash rules were inserted once. */
    var tabbyRulesSeeded: Boolean
        get() = prefs.getBoolean(KEY_TABBY_RULES_SEEDED, false)
        set(value) = prefs.edit().putBoolean(KEY_TABBY_RULES_SEEDED, value).apply()

    /**
     * "Demo mode" is on: real accounts/transactions are hidden and disposable
     * sample data is shown instead. Persisted so the state survives a restart;
     * the demo rows themselves live in the DB tagged isDemo=1.
     */
    var demoModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEMO_MODE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO_MODE_ENABLED, value).apply()

    /**
     * While Demo mode is on, the real saved-people list is stashed here and
     * [personsRaw] shows demo people instead. Restored (and cleared) when demo
     * mode is switched off, so the real list is preserved, never lost.
     */
    var demoPersonsBackup: String
        get() = prefs.getString(KEY_DEMO_PERSONS_BACKUP, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DEMO_PERSONS_BACKUP, value).apply()

    /** Opt-in biometric app lock (lock on background + idle timeout). */
    var appLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_APP_LOCK_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, value).apply()

    /** Minutes of inactivity before an enabled app lock re-engages. */
    var appLockTimeoutMinutes: Int
        get() = prefs.getInt(KEY_APP_LOCK_TIMEOUT_MIN, 5)
        set(value) = prefs.edit().putInt(KEY_APP_LOCK_TIMEOUT_MIN, value.coerceAtLeast(1)).apply()

    /**
     * Wall-clock millis when the app was last active (backgrounded or unlocked).
     * The app lock uses it to require auth only after the timeout has actually
     * elapsed, so a quick switch away and back doesn't re-prompt. 0 = never.
     */
    var appLockLastActiveAt: Long
        get() = prefs.getLong(KEY_APP_LOCK_LAST_ACTIVE, 0L)
        set(value) = prefs.edit().putLong(KEY_APP_LOCK_LAST_ACTIVE, value).apply()

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

    /**
     * Gate-2 local AI parsing: when on, messages the regex/signature parser
     * misses are forwarded to a local (loopback-only) OpenAI-compatible server
     * (e.g. the Off Grid app). Off by default; opt-in in Settings.
     */
    var aiParsingEnabled: Boolean
        get() = prefs.getBoolean(KEY_AI_PARSING_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AI_PARSING_ENABLED, value).apply()

    /**
     * Full /v1/chat/completions URL of the local AI server. MUST be a loopback
     * address ([com.xemmy.mmbank.domain.ai.LoopbackGuard] rejects anything
     * else); the default targets the common on-device server port.
     */
    var aiEndpointUrl: String
        get() = prefs.getString(KEY_AI_ENDPOINT_URL, DEFAULT_AI_ENDPOINT) ?: DEFAULT_AI_ENDPOINT
        set(value) = prefs.edit().putString(KEY_AI_ENDPOINT_URL, value.trim()).apply()

    /** Model name sent in the chat request. Blank = let the server use its loaded model. */
    var aiModelName: String
        get() = prefs.getString(KEY_AI_MODEL_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AI_MODEL_NAME, value.trim()).apply()

    /**
     * LOCAL (default): loopback-only, enforced in code and by the network
     * security config -- the original, hard privacy boundary. ONLINE is an
     * explicit opt-in to a cloud OpenAI-compatible API; the API key itself is
     * NOT stored here (see [com.xemmy.mmbank.data.prefs.SecureCredentialStore.aiApiKey]),
     * only this non-secret mode flag.
     */
    var aiProviderMode: AiProviderMode
        get() = runCatching {
            AiProviderMode.valueOf(prefs.getString(KEY_AI_PROVIDER_MODE, null) ?: AiProviderMode.LOCAL.name)
        }.getOrDefault(AiProviderMode.LOCAL)
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER_MODE, value.name).apply()

    /**
     * Caps automatic Gate-2 escalations (see [com.xemmy.mmbank.domain.ai.AiDailyBudget]) to
     * this many per local day. Only the unattended background path is capped; the manual
     * "Parse with AI" action and the Settings test buttons are unaffected.
     */
    var aiDailyRequestLimit: Int
        get() = prefs.getInt(KEY_AI_DAILY_LIMIT, DEFAULT_AI_DAILY_LIMIT)
        set(value) = prefs.edit().putInt(KEY_AI_DAILY_LIMIT, value.coerceAtLeast(1)).apply()

    /** Local-day key (e.g. "2026-07-21") the automatic-parsing budget below was last consumed for. */
    var aiBudgetDayKey: String
        get() = prefs.getString(KEY_AI_BUDGET_DAY_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AI_BUDGET_DAY_KEY, value).apply()

    /** Automatic Gate-2 requests consumed so far on [aiBudgetDayKey]. Stale once the day rolls over. */
    var aiBudgetDayCount: Int
        get() = prefs.getInt(KEY_AI_BUDGET_DAY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_AI_BUDGET_DAY_COUNT, value).apply()

    /**
     * Classic vs Premium (AGSL AI-parsing indicator + springier motion on top
     * of the same Material 3 baseline theme -- see [com.xemmy.mmbank.ui.theme.MMbankTheme]).
     * Classic by default.
     */
    var designMode: DesignMode
        get() = runCatching {
            DesignMode.valueOf(prefs.getString(KEY_DESIGN_MODE, null) ?: DesignMode.CLASSIC.name)
        }.getOrDefault(DesignMode.CLASSIC)
        set(value) = prefs.edit().putString(KEY_DESIGN_MODE, value.name).apply()

    /**
     * Layout of the Activity tab's filter row (see [FilterBarLayout]).
     * Single-row by default; Classic keeps every chip visible across wrapped
     * lines, the original behavior. Only the Transactions screen reads this,
     * so it's a plain re-read rather than a reactive AppContainer flow.
     */
    var filterBarLayout: FilterBarLayout
        get() = runCatching {
            FilterBarLayout.valueOf(prefs.getString(KEY_FILTER_BAR_LAYOUT, null) ?: FilterBarLayout.SINGLE_ROW.name)
        }.getOrDefault(FilterBarLayout.SINGLE_ROW)
        set(value) = prefs.edit().putString(KEY_FILTER_BAR_LAYOUT, value.name).apply()

    /** Ambient background style drawn behind the whole app. Minimal Slate by default. */
    var backgroundPreset: BackgroundStyle
        get() = runCatching {
            BackgroundStyle.valueOf(prefs.getString(KEY_BACKGROUND_PRESET, null) ?: BackgroundStyle.MINIMAL_SLATE.name)
        }.getOrDefault(BackgroundStyle.MINIMAL_SLATE)
        set(value) = prefs.edit().putString(KEY_BACKGROUND_PRESET, value.name).apply()

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

    /**
     * Monthly spend threshold, in [defaultCurrency], that the Activity tab's
     * Premium hero card measures the currently filtered total against.
     * <= 0 means no budget is set — the hero card then shows the total
     * without a consumption bar.
     */
    var monthlySpendBudget: Double
        get() = prefs.getFloat(KEY_MONTHLY_SPEND_BUDGET, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_MONTHLY_SPEND_BUDGET, value.toFloat()).apply()

    /**
     * Committed monthly spend (rent, installments, subscriptions, …) subtracted
     * from [monthlySpendBudget] before splitting the rest across the days left
     * in the month -- see [com.xemmy.mmbank.domain.budget.DailyBudgetPacing].
     * 0 (default) means the whole budget is treated as discretionary.
     */
    var fixedMonthlyExpenses: Double
        get() = prefs.getFloat(KEY_FIXED_MONTHLY_EXPENSES, 0f).toDouble()
        set(value) = prefs.edit().putFloat(KEY_FIXED_MONTHLY_EXPENSES, value.toFloat()).apply()

    /**
     * Manual payday override (day of month, 1-31) for
     * [com.xemmy.mmbank.domain.intelligence.CashFlowForecaster] — wins over
     * the auto-detected recurring-income pattern when set. 0 = no override.
     */
    var paycheckDayOfMonth: Int
        get() = prefs.getInt(KEY_PAYCHECK_DAY_OF_MONTH, 0)
        set(value) = prefs.edit().putInt(KEY_PAYCHECK_DAY_OF_MONTH, value.coerceIn(0, 31)).apply()

    /**
     * Category-cap alerts already announced, as
     * `"{categoryId}:{yyyy-MM}:{NEAR|OVER}"` keys (see
     * [com.xemmy.mmbank.domain.budget.CategoryBudgetCalculator.alertKey]) --
     * so each threshold notifies once per category per month instead of on
     * every transaction that keeps it over the line.
     *
     * Keys carry their own month, so a new month alerts again with no reset
     * job. Old months are pruned on write to stop the set growing forever.
     */
    var categoryBudgetAlerts: Set<String>
        get() = prefs.getStringSet(KEY_CATEGORY_BUDGET_ALERTS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_CATEGORY_BUDGET_ALERTS, value).apply()

    /**
     * Opt-in daily reminder shortly before a detected subscription renews.
     *
     * **Off by default, deliberately.** Turning it on schedules the app's only
     * periodic work (see
     * [com.xemmy.mmbank.data.subscription.SubscriptionReminderWorker]); the
     * README sells "no background polling" as a battery property, so nothing is
     * scheduled until the user asks for it.
     *
     * A plain boolean rather than a reactive [com.xemmy.mmbank.di.AppContainer]
     * flow: only the Settings screen and the worker read it, so no other
     * ViewModel needs to reflect the change live.
     */
    var subscriptionRemindersEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBSCRIPTION_REMINDERS, false)
        set(value) = prefs.edit().putBoolean(KEY_SUBSCRIPTION_REMINDERS, value).apply()

    /**
     * Renewals already announced, as `"{subscriptionId}:{nextDueDateMillis}"`
     * keys (see [com.xemmy.mmbank.domain.intelligence.SubscriptionReminders.reminderKey]),
     * so a daily tick doesn't re-announce the same renewal every day. Pruned to
     * live subscriptions on each run.
     */
    var subscriptionRemindersSent: Set<String>
        get() = prefs.getStringSet(KEY_SUBSCRIPTION_REMINDERS_SENT, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_SUBSCRIPTION_REMINDERS_SENT, value).apply()

    private companion object {
        const val DEFAULT_AI_ENDPOINT = "http://127.0.0.1:11434/v1/chat/completions"
        const val KEY_AI_PARSING_ENABLED = "ai_parsing_enabled"
        const val KEY_AI_ENDPOINT_URL = "ai_endpoint_url"
        const val KEY_AI_MODEL_NAME = "ai_model_name"
        const val KEY_AI_PROVIDER_MODE = "ai_provider_mode"
        const val KEY_AI_DAILY_LIMIT = "ai_daily_limit"
        const val KEY_AI_BUDGET_DAY_KEY = "ai_budget_day_key"
        const val KEY_AI_BUDGET_DAY_COUNT = "ai_budget_day_count"
        const val DEFAULT_AI_DAILY_LIMIT = 50
        const val KEY_DEFAULT_CURRENCY = "default_currency"
        const val KEY_CASHBACK_SPEND = "cashback_spend"
        const val KEY_HIDE_BALANCES = "hide_balances"
        const val KEY_HIDE_ZERO_BALANCES = "hide_zero_balances"
        const val KEY_HIDE_CRYPTO_BALANCE = "hide_crypto_balance"
        const val KEY_EXCLUDE_CRYPTO_FROM_TOTAL = "exclude_crypto_from_total"
        const val KEY_HIDE_CREDIT_CARD_BALANCE = "hide_credit_card_balance"
        const val KEY_EXCLUDE_CREDIT_CARD_FROM_TOTAL = "exclude_credit_card_from_total"
        const val KEY_DEFAULT_PERSON = "default_person"
        const val KEY_PERSONS = "persons"
        const val KEY_TABBY_RULES_SEEDED = "tabby_rules_seeded"
        const val KEY_DEMO_MODE_ENABLED = "demo_mode_enabled"
        const val KEY_DEMO_PERSONS_BACKUP = "demo_persons_backup"
        const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        const val KEY_APP_LOCK_TIMEOUT_MIN = "app_lock_timeout_min"
        const val KEY_APP_LOCK_LAST_ACTIVE = "app_lock_last_active_at"
        const val KEY_GOAL_TARGET = "goal_target"
        const val KEY_GOAL_MONTHLY = "goal_monthly"
        const val KEY_MONTHLY_SPEND_BUDGET = "monthly_spend_budget"
        const val KEY_FIXED_MONTHLY_EXPENSES = "fixed_monthly_expenses"
        const val KEY_PAYCHECK_DAY_OF_MONTH = "paycheck_day_of_month"
        const val KEY_DESIGN_MODE = "design_mode"
        const val KEY_FILTER_BAR_LAYOUT = "filter_bar_layout"
        const val KEY_BACKGROUND_PRESET = "background_preset"
        const val KEY_CATEGORY_BUDGET_ALERTS = "category_budget_alerts"
        const val KEY_SUBSCRIPTION_REMINDERS = "subscription_reminders_enabled"
        const val KEY_SUBSCRIPTION_REMINDERS_SENT = "subscription_reminders_sent"
    }
}
