package com.xemmy.mmbank.domain.model

/**
 * Colour theme, independent of [ThemeMode] (dark/light) and of the ambient
 * [BackgroundStyle]. OBSIDIAN is today's blue-black look and stays the default;
 * GRAPHITE is a neutral black with a green accent.
 */
enum class ColorPalette(val displayName: String, val description: String) {
    OBSIDIAN("Obsidian", "Blue-black with an indigo accent"),
    GRAPHITE("Graphite", "Neutral black with a green accent"),
    ;

    companion object {
        /** Parses a stored pref value; anything missing or unknown falls back to [OBSIDIAN]. */
        fun fromStored(value: String?): ColorPalette =
            entries.firstOrNull { it.name == value } ?: OBSIDIAN
    }
}
