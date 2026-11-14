package com.xemmy.mmbank.ui.screens.settings

/** One row on the Settings root. [route] is the NavHost destination it opens. */
enum class SettingsEntry(val route: String) {
    CAPTURE("settings_capture"),
    IMPORT("settings_import"),
    RULES("rules"),
    AI("settings_ai"),
    PREFERENCES("settings_prefs"),
    NOTIFICATIONS("settings_notifications"),
    CATEGORIES_PEOPLE("settings_categories_people"),
    APPEARANCE("settings_appearance"),
    SECURITY("settings_security"),
    BACKUP("settings_backup"),
    INTEGRITY("integrity_check"),
    BINANCE("settings_binance"),
    DIAGNOSTICS("settings_diagnostics"),
    DEMO("settings_demo"),
}

/** Named group on the Settings root, in display order. */
enum class SettingsGroup(val title: String, val entries: List<SettingsEntry>) {
    CAPTURE(
        "Capture",
        listOf(SettingsEntry.CAPTURE, SettingsEntry.IMPORT, SettingsEntry.RULES, SettingsEntry.AI),
    ),
    MONEY("Money", listOf(SettingsEntry.PREFERENCES, SettingsEntry.CATEGORIES_PEOPLE)),
    NOTIFICATIONS("Notifications", listOf(SettingsEntry.NOTIFICATIONS)),
    APPEARANCE("Appearance", listOf(SettingsEntry.APPEARANCE)),
    SECURITY("Security & privacy", listOf(SettingsEntry.SECURITY)),
    DATA("Data", listOf(SettingsEntry.BACKUP, SettingsEntry.INTEGRITY)),
    CONNECTIONS("Connections", listOf(SettingsEntry.BINANCE)),
    ABOUT("About", listOf(SettingsEntry.DIAGNOSTICS, SettingsEntry.DEMO)),
}
