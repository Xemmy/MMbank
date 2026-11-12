package com.xemmy.mmbank.ui.screens.search

import com.xemmy.mmbank.ui.navigation.Routes
import com.xemmy.mmbank.ui.screens.plan.PlanEntry
import com.xemmy.mmbank.ui.screens.settings.SettingsEntry

/**
 * Every screen and setting the search can jump to. The Plan tools come
 * straight from [PlanEntry] and the Settings rows from [SettingsEntry], so a
 * new one is searchable the day it is added; the keyword text is what lets
 * "pin" find App lock and "forex" find Currency & preferences.
 */
object SearchDestinations {

    private val settingsText: Map<SettingsEntry, Pair<String, String>> = mapOf(
        SettingsEntry.CAPTURE to ("Capture & permissions" to "sms notifications listener permission battery"),
        SettingsEntry.IMPORT to ("Import & ignore messages" to "paste backlog ignore mute"),
        SettingsEntry.RULES to ("Parsing rules" to "regex bank message format sender"),
        SettingsEntry.AI to ("Local AI parsing" to "ai model online provider privacy gemma"),
        SettingsEntry.PREFERENCES to ("Currency & preferences" to "default currency forex rates exchange default person retention"),
        SettingsEntry.CATEGORIES_PEOPLE to ("Categories & People" to "category people person manage"),
        SettingsEntry.APPEARANCE to ("Appearance & Backgrounds" to "theme dark light background style"),
        SettingsEntry.SECURITY to ("App lock & PIN" to "pin biometric fingerprint password lock security screenshot"),
        SettingsEntry.BACKUP to ("Backup & restore" to "export import encrypted file mmb"),
        SettingsEntry.INTEGRITY to ("Integrity check" to "reconcile bank balance compare books"),
        SettingsEntry.BINANCE to ("Binance" to "crypto exchange api keys"),
        SettingsEntry.DIAGNOSTICS to ("Diagnostics" to "crash log errors capture log"),
        SettingsEntry.DEMO to ("Demo mode" to "sample data"),
    )

    private val tabs = listOf(
        SearchDestination("Home", "dashboard overview net worth", Routes.HOME),
        SearchDestination("Activity", "transactions list history", Routes.ACTIVITY),
        SearchDestination("Insights", "analytics charts spending trends", Routes.INSIGHTS),
        SearchDestination("Plan", "budgets bills goals", Routes.PLAN),
        SearchDestination("Accounts", "balances cards banks", Routes.ACCOUNTS),
    )

    private val tools = listOf(
        SearchDestination("Monthly report", "statement month export", Routes.MONTHLY_REPORT),
        SearchDestination("Copilot", "ask question chat assistant", Routes.COPILOT),
        SearchDestination("To review", "review uncategorized queue inbox", Routes.REVIEW_TRANSACTIONS),
        SearchDestination("Uncategorized", "categorize queue", Routes.UNCATEGORIZED_QUEUE),
        SearchDestination("Compare with a bank statement", "reconcile csv statement missing transactions", Routes.STATEMENT_RECONCILE),
    )

    val all: List<SearchDestination> by lazy {
        tabs +
            PlanEntry.entries.map { SearchDestination(it.title, it.subtitle, it.route) } +
            tools +
            settingsText.map { (entry, text) -> SearchDestination(text.first, text.second, entry.route) }
    }

    /** Titles for every [SettingsEntry], so a test can prove none was forgotten. */
    internal val settingsEntriesCovered: Set<SettingsEntry> get() = settingsText.keys
}
