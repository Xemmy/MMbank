package com.xemmy.mmbank.domain.model

/**
 * Ambient background style drawn behind the whole app — see
 * [com.xemmy.mmbank.ui.theme.ambientBackground]. Persisted via
 * [com.xemmy.mmbank.data.prefs.AppPreferences.backgroundPreset].
 */
enum class BackgroundStyle {
    /** Deep violet glow top-right over a pure-black surface — battery-friendly on OLED. */
    OLED_OBSIDIAN,

    /** Muted indigo and teal glows with a soft, wide falloff. */
    AURORA_MUTED,

    /** Faint amber and espresso glow over a warm neutral surface. */
    WARM_CASHMERE,

    /** Flat surface color, zero color bleed. */
    MINIMAL_SLATE,
}
