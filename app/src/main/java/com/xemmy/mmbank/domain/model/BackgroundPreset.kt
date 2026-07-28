package com.xemmy.mmbank.domain.model

/**
 * Ambient background style drawn behind the Dashboard's content — see
 * [com.xemmy.mmbank.ui.theme.ambientBackground]. Persisted via
 * [com.xemmy.mmbank.data.prefs.AppPreferences.backgroundPreset].
 */
enum class BackgroundPreset {
    /** Cool teal/purple/pink blobs drifting over a deep indigo base. */
    AURORA,

    /** Near-black, almost static — battery-friendly on OLED screens. */
    MIDNIGHT_OLED,

    /** Dark base with pulsing magenta/cyan glow. */
    CYBER_NEON,

    /** Very subtle light frosted wash, minimal motion. */
    MINIMAL_FROST,
}
