package com.xemmy.mmbank.domain.model

/** App theme choice. DARK is the default (the app is dark-first); LIGHT is opt-in. */
enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
    ;

    /** Whether the dark palette applies, given the device's current dark-mode state. */
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        DARK -> true
        LIGHT -> false
    }

    companion object {
        /** Parses a stored pref value; anything missing or unknown falls back to [DARK]. */
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}
