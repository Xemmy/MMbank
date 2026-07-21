package com.xemmy.mmbank.domain.model

/**
 * Classic is the original Material 3 baseline UI. Premium opts into Material 3
 * Expressive theming (springier motion, expanded shapes), a GPU-shader (AGSL)
 * AI-parsing indicator on devices that support it, and a spring pop-in on the
 * transaction detail dialog. Every premium surface falls back to its Classic
 * behavior when the device or API level can't support the effect, so Premium
 * is never a hard requirement -- only a progressive enhancement.
 */
enum class DesignMode {
    CLASSIC,
    PREMIUM,
}
