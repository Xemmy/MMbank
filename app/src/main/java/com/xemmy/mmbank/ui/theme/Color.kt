package com.xemmy.mmbank.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * High-contrast Material 3 palette (spec: Zero-Conflict Color System). Every
 * value here is a leaf token — [Theme.kt] assembles them into the actual
 * `darkColorScheme()`/`lightColorScheme()`; nothing outside this file and
 * Theme.kt should reference a raw hex for a role covered here.
 *
 * Contrast ratios (WCAG relative luminance) for every pair are checked by
 * ColorContrastTest. Two pairs are pinned to exact brand hex values rather
 * than tuned for AAA — see that test for the measured ratios and rationale.
 */

// -- Dark theme surfaces -----------------------------------------------------
val DarkSurface = Color(0xFF0B0F17)
val DarkSurfaceContainerLowest = Color(0xFF060911)
val DarkSurfaceContainerLow = Color(0xFF0F1420)
val DarkSurfaceContainer = Color(0xFF161E2E)
val DarkSurfaceContainerHigh = Color(0xFF1E293B)
val DarkSurfaceContainerHighest = Color(0xFF283349)
val DarkOnSurface = Color(0xFFF1F5F9)
val DarkOnSurfaceVariant = Color(0xFF94A3B8)
val DarkOutline = Color(0xFF475569)
val DarkOutlineVariant = Color(0xFF1E293B)

// -- Light theme surfaces -----------------------------------------------------
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF8FAFC)
val LightSurfaceContainer = Color(0xFFF1F5F9)
val LightSurfaceContainerHigh = Color(0xFFE2E8F0)
val LightSurfaceContainerHighest = Color(0xFFCBD5E1)
val LightOnSurface = Color(0xFF0B0F17)
val LightOnSurfaceVariant = Color(0xFF475569)
val LightOutline = Color(0xFF64748B)
val LightOutlineVariant = Color(0xFFCBD5E1)

// -- Financial accents: income / expense -------------------------------------
val IncomeContainerDark = Color(0xFF064E3B)
val OnIncomeContainerDark = Color(0xFF6EE7B7)
val ExpenseContainerDark = Color(0xFF7F1D1D)
val OnExpenseContainerDark = Color(0xFFFCA5A5)

val IncomeContainerLight = Color(0xFFD1FAE5)
val OnIncomeContainerLight = Color(0xFF054D3A)
val ExpenseContainerLight = Color(0xFFFEE2E2)
val OnExpenseContainerLight = Color(0xFF7F1717)

// -- Primary accent (shared brand hue across themes) --------------------------
val PrimaryElectric = Color(0xFF6366F1)
val OnPrimaryElectric = Color(0xFFFFFFFF)

// -- Secondary / tertiary / error, dark theme ---------------------------------
val SecondaryDark = Color(0xFF2DD4BF)
val OnSecondaryDark = Color(0xFF00332E)
val SecondaryContainerDark = Color(0xFF115E59)
val OnSecondaryContainerDark = Color(0xFF99F6E4)

val TertiaryDark = Color(0xFFF0BE5A)
val OnTertiaryDark = Color(0xFF3F2E00)
val TertiaryContainerDark = Color(0xFF7C5800)
val OnTertiaryContainerDark = Color(0xFFFFE7A8)

val ErrorDark = Color(0xFFFCA5A5)
val OnErrorDark = Color(0xFF450A0A)
val ErrorContainerDark = ExpenseContainerDark
val OnErrorContainerDark = OnExpenseContainerDark

val PrimaryContainerDark = Color(0xFF312E81)
val OnPrimaryContainerDark = Color(0xFFC7D2FE)

// -- Secondary / tertiary / error, light theme ---------------------------------
val SecondaryLight = Color(0xFF0F766E)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFCCFBF1)
val OnSecondaryContainerLight = Color(0xFF042F2E)

val TertiaryLight = Color(0xFF8A5A00)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFFFE7A8)
val OnTertiaryContainerLight = Color(0xFF2B1E00)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = ExpenseContainerLight
val OnErrorContainerLight = OnExpenseContainerLight

val PrimaryContainerLight = Color(0xFFE0E1FF)
val OnPrimaryContainerLight = Color(0xFF181655)
